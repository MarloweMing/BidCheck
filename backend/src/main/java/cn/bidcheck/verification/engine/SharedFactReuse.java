package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.verification.facts.FieldCoverage;
import cn.bidcheck.verification.evidence.EvidenceOperationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A source-checked occurrence cache, never a cache of business decisions or document completeness. */
final class SharedFactReuse {
    private static final int LIMIT=240;
    private static final Set<String> EXCLUDED=Set.of("SECTION","QUALITY","TEXT_ANOMALY","QUALITY_ADVISORY");
    // Only active/waiting leases own entries. Unrelated source/target keys never share a lock.
    private static final ConcurrentHashMap<String,Flight> FLIGHTS=new ConcurrentHashMap<>();
    private static final class Flight {final ReentrantLock lock=new ReentrantLock(true);int references;}
    private record RegisteredFlight(String key,Flight flight) {}
    private final EvidenceCorpus corpus;
    private final Map<String,Object> context;
    private final String modelIdentity,engineIdentity,processFlightNamespace;
    private final ObjectMapper json=new ObjectMapper();
    private final Map<String,Map<String,Object>> metadata=new HashMap<>();
    private final Map<String,Map<String,Object>> receipts=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>> fieldReceipts=new LinkedHashMap<>(),observations=new LinkedHashMap<>();
    private final Set<String> currentObservationKeys=new HashSet<>();
    private final Set<String> reused=new HashSet<>(),published=new HashSet<>();
    int sharedPublishes,extractionSkipped,rejectedFacts;

    SharedFactReuse(EvidenceCorpus corpus,Map<String,Object> context,String modelIdentity,String engineIdentity){
        this(corpus,context,modelIdentity,engineIdentity,"");
    }
    /** The namespace is package-private so integration tests can represent two JVM registries while
     * production callers retain the single shared process registry. */
    SharedFactReuse(EvidenceCorpus corpus,Map<String,Object> context,String modelIdentity,String engineIdentity,String processFlightNamespace){
        this.corpus=corpus;this.context=context;this.modelIdentity=modelIdentity;this.engineIdentity=engineIdentity;
        this.processFlightNamespace=Objects.toString(processFlightNamespace,"");
        for(Source source:corpus.all){if(source.id().startsWith("V"))continue;
            Document doc=corpus.documents.stream().filter(d->d.id().equals(source.evidence().documentId())).findFirst().orElseThrow();
            Page page=corpus.pages.get(EvidenceCorpus.pageKey(doc.id(),source.evidence().page(),source.evidence().locationType()));
            metadata.put(source.id(),linked("id",source.id(),"evidence",source.evidence(),"projectId",doc.projectId(),"documentHash",doc.sha256(),"documentVersion",doc.version(),"parserFingerprint",parserFingerprint(page)));
        }
    }
    static String parserFingerprint(Page page){return cn.bidcheck.verification.facts.FactLedgerService.parserFingerprint(page);}
    static Map<String,Object> value(Fact f){return linked("kind",f.kind(),"subject",f.subject(),"field",f.field(),"rawValue",f.rawValue(),"value",f.value(),"unit",f.unit(),"basis",f.basis(),"scope",f.scope(),"sourceIds",f.sourceIds(),"attributes",f.attributes());}
    private static Object ordered(Object value){if(value instanceof Map<?,?> map){Map<String,Object> out=new TreeMap<>();map.forEach((k,v)->out.put(k.toString(),ordered(v)));return out;}if(value instanceof List<?> list)return list.stream().map(SharedFactReuse::ordered).toList();return value;}
    static Fact canonical(Fact f){String id="F"+identity(new ObjectMapper().valueToTree(ordered(value(f))).toString()).substring(0,24);return new Fact(id,f.kind(),f.subject(),f.field(),f.rawValue(),f.value(),f.unit(),f.basis(),f.scope(),f.sourceIds(),f.attributes());}
    static String signature(Rule r){return identity(new ObjectMapper().valueToTree(ordered(linked("predicate",r.predicate(),"description",r.description(),"subject",r.subject(),"field",r.field(),"operator",r.operator(),"expected",r.expected(),"unit",r.unit(),"parameters",r.parameters(),"sourceQuote",r.sourceQuote(),"logic",r.logic(),"children",r.children().stream().map(SharedFactReuse::signature).toList()))).toString());}
    static Map<String,Object> observationTarget(Rule r){
        // Only numeric comparison thresholds are independent of the value being observed.
        // Material names, certificate classes, conditions and object selectors remain in the key.
        Map<String,Object> qualifiers=new LinkedHashMap<>(r.parameters());
        // These server-owned pointers identify the requirement, not the bid-side observation.
        // Resolved subject/lot/currency/tax and other business qualifiers remain explicit above/in qualifiers.
        for(String metadata:List.of("obligationIdentity","requirementSourceContext","requirementBindings","priceConstraintSources"))qualifiers.remove(metadata);
        return linked("predicate",r.predicate(),"subject",r.subject(),"field",r.field(),"unit",r.unit(),"parameters",qualifiers,
            "targetExpected",r.predicate().equals("NUMBER")?"":r.expected(),"children",r.children().stream().map(SharedFactReuse::signature).toList());
    }
    static Set<String> kinds(List<Rule> rules){Set<String> kinds=new LinkedHashSet<>(List.of("PERSON_ROLE","RELATION"));for(Rule r:rules){switch(r.predicate()){
        case "VALIDITY"->kinds.addAll(List.of("DATE_RANGE","CERTIFICATE"));case "SAME_FIELD"->kinds.addAll(List.of("FIELD","NUMBER","CERTIFICATE"));case "PRICE_RECONCILE"->kinds.addAll(List.of("NUMBER","FIELD"));case "DATE_RELATION"->kinds.add("FIELD");case "SCORE_COUNT"->kinds.add("CONTRACT");default->{if(ProofVerifier.KINDS.contains(r.predicate())&&!EXCLUDED.contains(r.predicate()))kinds.add(r.predicate());}
    }}return kinds;}
    private boolean shareable(Fact f){return !EXCLUDED.contains(f.kind())&&!f.sourceIds().isEmpty()&&f.sourceIds().stream().allMatch(metadata::containsKey);}
    private Map<String,Object> operationDescriptor(String origin,List<Source> candidates,List<Rule> rules){
        String project=corpus.documents.isEmpty()?"":corpus.documents.get(0).projectId();
        List<Map<String,Object>> sources=candidates.stream().map(s->metadata.getOrDefault(s.id(),Map.of("id",s.id()))).toList();
        List<Map<String,Object>> targets=rules.stream().map(SharedFactReuse::observationTarget).toList();
        // Extraction observes fields; it does not decide a checkpoint's threshold or outcome.
        // Key the durable operation by canonical observation targets so equivalent checks can share
        // the same source read even when their wording, id or comparison value differs.
        String artifact=identity(targets.stream().map(target->json.valueToTree(ordered(target)).toString()).sorted().toList().toString());
        String operationType="EXTRACTED".equals(origin)?"TEXT_FACT_EXTRACTION":"TEXT_FACT_"+origin;
        return EvidenceOperationRepository.descriptor(project,operationType,List.of(),sources,targets,artifact,modelIdentity,"FACTS_ONLY",engineIdentity+":"+FieldCoverage.POLICY);
    }
    @SuppressWarnings("unchecked") List<Fact> lookup(List<Rule> rules,List<Source> candidates,List<Map<String,Object>> layout){
        if(candidates.isEmpty())return List.of();
        Set<String> requestedKinds=kinds(rules),selected=new LinkedHashSet<>(candidates.stream().map(Source::id).toList());
        List<Map<String,Object>> entries=new ArrayList<>();
        if(context.get("evidenceOperationLookup") instanceof Function<?,?> operationLookup){
            Map<String,Object> descriptor=operationDescriptor("EXTRACTED",candidates,rules);String operationKey=EvidenceOperationRepository.key(descriptor);
            Object raw=((Function<Map<String,Object>,?>)operationLookup).apply(linked("operationKey",operationKey,"descriptor",descriptor));
            if(raw instanceof Map<?,?> cachedRaw){Map<String,Object> cached=json.convertValue(cachedRaw,Map.class);List<Map<String,Object>> cachedSources=RuleEvaluator.maps(cached.get("sources")),cachedFields=RuleEvaluator.maps(cached.get("fieldCoverage"));
                for(Map<String,Object> fact:RuleEvaluator.maps(cached.get("facts")))entries.add(linked("fact",fact,"sources",cachedSources,"engineVersion",VERSION,"operationIdentity",operationKey,
                    "fieldCoverage",cachedFields,"fieldCoverageSources",cachedSources,"coverageSources",cachedSources,"coverageComplete",false,"recordType","FACT"));
                if(RuleEvaluator.maps(cached.get("facts")).isEmpty()&&!cachedFields.isEmpty())entries.add(linked("sources",cachedSources,"engineVersion",VERSION,"operationIdentity",operationKey,
                    "fieldCoverage",cachedFields,"fieldCoverageSources",cachedSources,"coverageSources",cachedSources,"coverageComplete",false,"recordType","FIELD_COVERAGE"));
            }
        }
        if(context.get("sharedFactLookup") instanceof Function<?,?> callback){Object result=((Function<Map<String,Object>,?>)callback).apply(linked("sourceIds",new ArrayList<>(selected),"documentIds",corpus.documents.stream().map(Document::id).toList(),"factKinds",new ArrayList<>(requestedKinds),"limit",LIMIT,"engineVersion",VERSION,"engineIdentity",engineIdentity,"observationPolicy",FieldCoverage.POLICY,"includeFieldCoverage",true,"observationKeys",rules.stream().map(r->FieldCoverage.key(observationTarget(r))).distinct().toList()));entries.addAll(RuleEvaluator.maps(result));}
        entries.addAll(processingEntries(requestedKinds,candidates,layout,Math.max(0,120-entries.size())));
        Map<String,Fact> accepted=new LinkedHashMap<>();Map<String,Set<String>> acceptedByBatch=new HashMap<>();Map<String,Map<String,Fact>> acceptedFactsByBatch=new HashMap<>();Map<String,Map<String,Object>> batchReceipts=new HashMap<>();Map<String,List<Map<String,Object>>> batchFields=new HashMap<>();
        for(Map<String,Object> entry:entries.stream().limit(LIMIT).toList()){
            if(!VERSION.equals(str(entry,"engineVersion"))&&!FieldCoverage.POLICY.equals(str(entry,"observationPolicy"))){rejectedFacts++;continue;}
            String receiptBatch=str(entry,"batchId");if(receiptBatch.isBlank())receiptBatch=str(entry,"operationIdentity");
            Map<String,Map<String,Object>> fieldSources=new HashMap<>();for(Map<String,Object> source:RuleEvaluator.maps(entry.get("fieldCoverageSources")))fieldSources.put(str(source,"id"),source);
            List<Map<String,Object>> scopedFields=RuleEvaluator.maps(entry.get("fieldCoverage")).stream().filter(row->{List<String> ids=ProofVerifier.strings(row.get("sourceIds"));return validFieldReceipt(row)&&!ids.isEmpty()&&selected.containsAll(ids)&&ids.stream().allMatch(id->sourceMatches(id,fieldSources.get(id)));}).toList();
            if(!receiptBatch.isBlank()&&!scopedFields.isEmpty())batchFields.put(receiptBatch,scopedFields);
            if("FIELD_COVERAGE".equals(str(entry,"recordType")))continue;
            if(!(entry.get("fact") instanceof Map<?,?> factRow)){rejectedFacts++;continue;}
            JsonNode node=json.valueToTree(factRow);String kind=node.path("kind").asText();List<String> refs=ProofVerifier.strings(factRow.get("sourceIds"));
            Map<String,Map<String,Object>> provided=new HashMap<>();for(Map<String,Object> source:RuleEvaluator.maps(entry.get("sources")))provided.put(str(source,"id"),source);
            if(EXCLUDED.contains(kind)||!requestedKinds.contains(kind)||refs.isEmpty()||refs.stream().anyMatch(id->!selected.contains(id)||!sourceMatches(id,provided.get(id)))){rejectedFacts++;continue;}
            // Re-read current original text and current table bindings. Persisted PASS/verified flags have no authority.
            ProofVerifier.Verified verified=new ProofVerifier().verify(json.valueToTree(Map.of("facts",List.of(factRow))),candidates,layout);
            if(verified.facts().size()!=1||!verified.rejected().isEmpty()){rejectedFacts++;continue;}
            Fact fact=canonical(verified.facts().get(0));if(!shareable(fact)){rejectedFacts++;continue;}accepted.put(fact.id(),fact);reused.add(fact.id());
            String batch=str(entry,"batchId");if(batch.isBlank())batch=str(entry,"operationIdentity");
            acceptedByBatch.computeIfAbsent(batch,x->new HashSet<>()).add(node.path("id").asText());
            acceptedFactsByBatch.computeIfAbsent(batch,x->new HashMap<>()).put(node.path("id").asText(),fact);
            if(!Boolean.FALSE.equals(entry.get("coverageComplete"))&&entry.get("extractionCoverage") instanceof Map<?,?> raw){Map<String,Object> receipt=json.convertValue(raw,Map.class);List<String> allSources=ProofVerifier.strings(receipt.get("sourceIds"));
                for(Map<String,Object> source:RuleEvaluator.maps(entry.get("coverageSources")))provided.putIfAbsent(str(source,"id"),source);
                if(!batch.isBlank()&&!allSources.isEmpty()&&selected.containsAll(allSources)&&allSources.stream().allMatch(id->sourceMatches(id,provided.get(id))))batchReceipts.put(batch,receipt);
            }
        }
        batchReceipts.forEach((batch,receipt)->{List<String> ids=ProofVerifier.strings(receipt.get("factIds"));if(!ids.isEmpty()&&acceptedByBatch.getOrDefault(batch,Set.of()).containsAll(ids)&&validReceipt(receipt))receipts.put(batch,receipt);});
        Map<String,Map<String,Object>> adoptedFields=new LinkedHashMap<>();
        batchFields.forEach((batch,rows)->{for(Map<String,Object> receipt:rows){List<String> ids=ProofVerifier.strings(receipt.get("factIds"));
            if(!acceptedByBatch.getOrDefault(batch,Set.of()).containsAll(ids)||"EXTRACTED".equals(str(receipt,"status"))&&ids.isEmpty())continue;
            Map<String,Object> target=(Map<String,Object>)receipt.get("target");if(ids.stream().anyMatch(id->!FieldCoverage.matches(target,value(acceptedFactsByBatch.get(batch).get(id)))))continue;
            fieldReceipts.put(batch+":"+observationScope(receipt),receipt);observeCached(receipt);
            String localScope=observationScope(receipt);boolean alreadyObservedHere=currentObservationKeys.contains(localScope)&&FieldCoverage.completeRead(observations.getOrDefault(localScope,Map.of()));
            if(FieldCoverage.completeRead(receipt)&&!alreadyObservedHere){
                Map<String,Object> adopted=new LinkedHashMap<>(receipt);adopted.put("factIds",ids.stream().map(id->acceptedFactsByBatch.get(batch).get(id).id()).toList());
                adopted.put("reusedObservation",true);adopted.put("observedInCurrentRun",false);
                adopted.put("reusedFromBatchId",str(receipt,"reusedFromBatchId").isBlank()?batch:str(receipt,"reusedFromBatchId"));
                adoptedFields.put(identity(json.valueToTree(ordered(adopted)).toString()),adopted);
            }
        }});
        List<Fact> facts=List.copyOf(accepted.values());
        // Register use of the already validated receipt under the caller's current run manifest.
        // This is a dependency on the original read, never a new extraction or completeness claim.
        publishAdoption(facts,List.copyOf(adoptedFields.values()),candidates,layout);return facts;
    }
    @SuppressWarnings("unchecked") private void publishAdoption(List<Fact> facts,List<Map<String,Object>> fields,List<Source> candidates,List<Map<String,Object>> layout){
        if(fields.isEmpty()){publish(facts,candidates,List.of(),layout,false,"REUSED");return;}
        if(!(context.get("sharedFactPublisher") instanceof Consumer<?> callback))return;
        List<Map<String,Object>> rows=facts.stream().map(f->{Map<String,Object> row=new LinkedHashMap<>(value(f));row.put("id",f.id());return row;}).toList();
        // The persistent validator accepts at most 120 field receipts per batch. All parts retain
        // the exact facts/sources needed to verify their own receipt; no observation is dropped.
        for(int start=0;start<fields.size();start+=120){List<Map<String,Object>> part=List.copyOf(fields.subList(start,Math.min(fields.size(),start+120)));Set<String> ids=new LinkedHashSet<>();facts.forEach(f->ids.addAll(f.sourceIds()));part.forEach(f->ids.addAll(ProofVerifier.strings(f.get("sourceIds"))));
            Map<String,Object> descriptor=operationDescriptor("REUSED_OBSERVATION",candidates,List.of());String requestKey=EvidenceOperationRepository.key(descriptor);String operation=identity(requestKey+json.valueToTree(ordered(rows))+json.valueToTree(ordered(part)));if(published.contains(operation))continue;
            ((Consumer<Map<String,Object>>)callback).accept(linked("facts",rows,"sources",ids.stream().map(metadata::get).filter(Objects::nonNull).toList(),"fieldCoverage",part,
                "operationIdentity",operation,"evidenceOperationDescriptor",descriptor,"engineVersion",VERSION,"observationPolicy",FieldCoverage.POLICY,"modelIdentity",modelIdentity,"origin","REUSED_OBSERVATION",
                "evidenceOperationKey",requestKey,"newModelObservation",false,"layoutIdentity",context.getOrDefault("layoutIdentity",""),"layoutCells",layout));published.add(operation);sharedPublishes++;
        }
    }
    /** Page/window facts have a separate parser identity. They supply candidates, never extraction coverage. */
    private List<Map<String,Object>> processingEntries(Set<String> kinds,List<Source> candidates,List<Map<String,Object>> layout,int limit){
        if(limit==0)return List.of();List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> occurrence:RuleEvaluator.maps(context.get("processingOccurrences"))){
            if(out.size()>=limit)break;
            if(!(occurrence.get("fact") instanceof Map<?,?> raw))continue;
            Map<String,Object> fact=json.convertValue(raw,Map.class);String kind=str(fact,"kind");
            if(EXCLUDED.contains(kind)||!kinds.contains(kind))continue;
            List<String> refs=ProofVerifier.strings(fact.get("sourceIds"));
            if(refs.isEmpty()||refs.size()>12||refs.stream().distinct().count()!=refs.size()){rejectedFacts++;continue;}
            Map<String,Map<String,Object>> stored=new HashMap<>();boolean duplicate=false;
            for(Map<String,Object> source:RuleEvaluator.maps(occurrence.get("sources")))if(stored.putIfAbsent(str(source,"id"),source)!=null)duplicate=true;
            Map<String,String> remap=new LinkedHashMap<>();List<Source> narrow=new ArrayList<>();
            if(!duplicate)for(String ref:refs){
                Map<String,Object> original=stored.get(ref);Source current=matchingProcessingSource(original,candidates,str(occurrence,"projectId"));
                if(current==null)break;
                Evidence evidence=json.convertValue(original.get("evidence"),Evidence.class);
                remap.put(ref,current.id());narrow.add(new Source(ref,evidence,current.opening(),current.index(),current.historical(),current.documentHash(),current.documentVersion(),current.scopeContext()));
            }
            if(remap.size()!=refs.size()){rejectedFacts++;continue;}
            // Validate the old narrow quote before widening to the engine's current source chunk. A nearby
            // unrelated name/amount in the larger chunk must not repair an ungrounded persisted claim.
            ProofVerifier.Verified verified=new ProofVerifier().verify(json.valueToTree(Map.of("facts",List.of(fact))),narrow,layout);
            if(verified.facts().size()!=1||!verified.rejected().isEmpty()){rejectedFacts++;continue;}
            Map<String,Object> mapped=new LinkedHashMap<>(value(verified.facts().get(0)));mapped.put("sourceIds",refs.stream().map(remap::get).distinct().toList());
            mapped.put("id","PF"+identity(str(occurrence,"occurrenceId")+json.valueToTree(ordered(mapped))).substring(0,24));
            List<String> mappedIds=ProofVerifier.strings(mapped.get("sourceIds"));
            out.add(linked("fact",mapped,"sources",mappedIds.stream().map(metadata::get).toList(),"engineVersion",VERSION,
                "operationIdentity","processing:"+str(occurrence,"occurrenceId"),"coverageComplete",false));
        }return out;
    }
    private Source matchingProcessingSource(Map<String,Object> provided,List<Source> candidates,String project){
        if(provided==null||project.isBlank()||!project.equals(str(provided,"projectId")))return null;
        try{
            JsonNode evidenceNode=json.valueToTree(provided.get("evidence"));
            if(!evidenceNode.isObject()||!evidenceNode.path("page").isIntegralNumber())return null;
            Evidence prior=json.convertValue(provided.get("evidence"),Evidence.class);
            if(prior==null||prior.quote()==null||prior.quote().isBlank()||prior.quote().length()>10000)return null;
            Object version=provided.get("documentVersion");if(!(version instanceof Number n)||n.doubleValue()!=n.intValue())return null;
            return candidates.stream().filter(source->{Map<String,Object> current=metadata.get(source.id());if(current==null)return false;
                Evidence now=source.evidence();return project.equals(str(current,"projectId"))&&str(current,"documentHash").equals(str(provided,"documentHash"))
                    &&str(current,"parserFingerprint").equals(str(provided,"parserFingerprint"))&&RunSession.number(current.get("documentVersion"),-1)==n.intValue()
                    &&Objects.equals(prior.documentId(),now.documentId())&&Objects.equals(prior.documentName(),now.documentName())&&prior.page()==now.page()
                    &&Objects.equals(prior.locationType(),now.locationType())&&now.quote().contains(prior.quote());
            }).min(Comparator.comparingInt(source->source.evidence().quote().length())).orElse(null);
        }catch(IllegalArgumentException malformed){return null;}
    }
    private boolean sourceMatches(String id,Map<String,Object> provided){
        Map<String,Object> current=metadata.get(id);if(current==null||provided==null)return false;
        for(String key:List.of("id","projectId","documentHash","parserFingerprint"))if(!str(current,key).equals(str(provided,key)))return false;
        if(RunSession.number(current.get("documentVersion"),-1)!=RunSession.number(provided.get("documentVersion"),-2))return false;
        try{Evidence prior=json.convertValue(provided.get("evidence"),Evidence.class),now=(Evidence)current.get("evidence");return prior!=null&&Objects.equals(prior.documentId(),now.documentId())&&Objects.equals(prior.documentName(),now.documentName())&&prior.page()==now.page()&&Objects.equals(prior.locationType(),now.locationType())&&Objects.equals(prior.quote(),now.quote())&&Objects.equals(prior.anchor(),now.anchor());}catch(IllegalArgumentException ex){return false;}
    }
    private boolean validReceipt(Map<String,Object> receipt){return "VALIDATED".equals(str(receipt,"status"))&&modelIdentity.equals(str(receipt,"modelIdentity"))&&!ProofVerifier.strings(receipt.get("ruleSignatures")).isEmpty();}
    @SuppressWarnings("unchecked") private boolean validFieldReceipt(Map<String,Object> receipt){Map<String,Object> target=receipt.get("target") instanceof Map<?,?> raw?json.convertValue(raw,Map.class):Map.of();String status=str(receipt,"status");List<String> ids=ProofVerifier.strings(receipt.get("factIds"));boolean validState=status.equals("EXTRACTED")?!ids.isEmpty():ids.isEmpty()&&(!status.equals("READ_NOT_FOUND")||Boolean.TRUE.equals(receipt.get("sourceScopeComplete")));return validState&&FieldCoverage.POLICY.equals(str(receipt,"observationPolicy"))&&modelIdentity.equals(str(receipt,"modelIdentity"))&&FieldCoverage.STATES.contains(status)&&!target.isEmpty()&&FieldCoverage.key(target).equals(str(receipt,"observationKey"));}
    /** Field reads are reusable before object relations or business comparisons can be completed. */
    List<Rule> extractionRules(List<Rule> rules,List<Source> candidates,RuleEvaluator evaluator){List<Rule> remaining=new ArrayList<>();
        for(Rule rule:rules){if(!candidates.isEmpty()&&pendingSources(rule,candidates).isEmpty())extractionSkipped++;else remaining.add(rule);}return remaining;
    }
    Set<String> coveredSources(Rule rule){Set<String> covered=new HashSet<>();if(EXCLUDED.contains(rule.predicate())||rule.predicate().equals("SCORE_COUNT"))return covered;
        String key=FieldCoverage.key(observationTarget(rule));for(Map<String,Object> receipt:fieldReceipts.values())if(validFieldReceipt(receipt)&&FieldCoverage.completeRead(receipt)&&key.equals(str(receipt,"observationKey")))covered.addAll(ProofVerifier.strings(receipt.get("sourceIds")));
        // Legacy full extraction receipts still require every cited fact to have passed current validation.
        for(Map<String,Object> receipt:receipts.values())if(validReceipt(receipt)&&ProofVerifier.strings(receipt.get("ruleSignatures")).contains(signature(rule)))covered.addAll(ProofVerifier.strings(receipt.get("sourceIds")));
        return covered;
    }
    Set<String> pendingSources(Rule rule,List<Source> candidates){Set<String> pending=new LinkedHashSet<>(candidates.stream().map(Source::id).toList());pending.removeAll(coveredSources(rule));return pending;}
    Map<String,Set<String>> pendingSources(List<Rule> rules,List<Source> candidates){Map<String,Set<String>> pending=new LinkedHashMap<>();for(Rule rule:rules)pending.put(rule.id(),pendingSources(rule,candidates));return pending;}
    /** In-process singleflight, bounded by active/waiting source-window leases, not past keys.
     * Callers must re-read the durable ledger after acquiring. */
    Lease coordinate(List<Rule> rules,List<Source> candidates)throws java.io.InterruptedIOException{
        Set<String> keys=new TreeSet<>();for(Rule rule:rules)for(Source source:candidates){Map<String,Object> target=observationTarget(rule);keys.add(EvidenceOperationRepository.textKey(
            corpus.documents.isEmpty()?"":corpus.documents.get(0).projectId(),modelIdentity,engineIdentity,List.of(metadata.getOrDefault(source.id(),Map.of("id",source.id()))),List.of(target),FieldCoverage.key(target)));}
        List<RegisteredFlight> registered=new ArrayList<>();List<ReentrantLock> held=new ArrayList<>();List<EvidenceOperationRepository.Lease> durable=new ArrayList<>();
        try{
            // Register every reference before locking: a releaser cannot remove an entry that
            // another lease is about to acquire. Global key order prevents overlapping-set deadlock.
            for(String durableKey:keys){String key=processFlightNamespace+durableKey;Flight flight=FLIGHTS.compute(key,(ignored,current)->{Flight value=current==null?new Flight():current;value.references++;return value;});registered.add(new RegisteredFlight(key,flight));}
            for(RegisteredFlight entry:registered){entry.flight().lock.lockInterruptibly();held.add(entry.flight().lock);}
            // Use the same globally sorted source/target keys for durable leases. This retains the
            // partial-overlap exclusion across instances as well as inside this JVM. VerificationEngine
            // re-runs both the immutable operation lookup and the fact ledger lookup after acquisition.
            if(context.get("evidenceOperationRepository") instanceof EvidenceOperationRepository repository){
                String project=corpus.documents.isEmpty()?"":corpus.documents.get(0).projectId();
                for(String key:keys)durable.add(repository.coordinate(project,key));
            }
            return new Lease(registered,held,durable);
        }catch(InterruptedException e){closeDurable(durable);release(registered,held);Thread.currentThread().interrupt();var interrupted=new java.io.InterruptedIOException("核验已中断");interrupted.initCause(e);throw interrupted;
        }catch(java.io.InterruptedIOException e){closeDurable(durable);release(registered,held);throw e;
        }catch(RuntimeException|Error e){closeDurable(durable);release(registered,held);throw e;}
    }
    private static void release(List<RegisteredFlight> registered,List<ReentrantLock> held){
        for(int i=held.size()-1;i>=0;i--)held.get(i).unlock();
        for(int i=registered.size()-1;i>=0;i--){RegisteredFlight entry=registered.get(i);FLIGHTS.compute(entry.key(),(ignored,current)->{
            if(current!=entry.flight()||current.references<=0)throw new IllegalStateException("Extraction lease reference mismatch");
            return --current.references==0?null:current;
        });}
    }
    private static void closeDurable(List<EvidenceOperationRepository.Lease> durable){for(int i=durable.size()-1;i>=0;i--)durable.get(i).close();}
    static int activeFlightKeys(){return FLIGHTS.size();}
    static final class Lease implements AutoCloseable {
        private final List<RegisteredFlight> registered;private final List<ReentrantLock> held;private final List<EvidenceOperationRepository.Lease> durable;private final Thread owner=Thread.currentThread();private boolean closed;
        Lease(List<RegisteredFlight> registered,List<ReentrantLock> held,List<EvidenceOperationRepository.Lease> durable){this.registered=registered;this.held=held;this.durable=durable;}
        public void close(){if(Thread.currentThread()!=owner)throw new IllegalMonitorStateException("Extraction lease must close on its acquiring thread");if(closed)return;try{closeDurable(durable);}finally{release(registered,held);closed=true;}}
    }
    void publish(List<Fact> facts,List<Source> candidates,List<Rule> rules,List<Map<String,Object>> layout,boolean completeExtraction,String origin){publish(facts,candidates,rules,layout,completeExtraction,origin,List.of());}
    @SuppressWarnings("unchecked") void publish(List<Fact> facts,List<Source> candidates,List<Rule> rules,List<Map<String,Object>> layout,boolean completeExtraction,String origin,List<Map<String,Object>> rejections){
        List<Fact> valid=new ArrayList<>();for(Fact f:facts){if(!shareable(f))continue;ProofVerifier.Verified check=new ProofVerifier().verify(json.valueToTree(Map.of("facts",List.of(value(f)))),candidates,layout);if(check.facts().size()==1&&check.rejected().isEmpty())valid.add(canonical(check.facts().get(0)));}
        List<String> refs="EXTRACTED".equals(origin)?candidates.stream().map(Source::id).filter(metadata::containsKey).toList():valid.stream().flatMap(f->f.sourceIds().stream()).distinct().toList();
        List<Map<String,Object>> fields=new ArrayList<>();if("EXTRACTED".equals(origin)&&!refs.isEmpty())for(Rule rule:rules){
            if(EXCLUDED.contains(rule.predicate())||rule.predicate().equals("SCORE_COUNT"))continue;Map<String,Object> target=observationTarget(rule);
            List<Fact> matched=valid.stream().filter(f->FieldCoverage.matches(target,value(f))).toList();
            boolean failed=rejections.stream().anyMatch(row->str(row,"kind").isBlank()||str(row,"field").isBlank()||rejectedFieldCouldMatch(rule,row));
            boolean done=(completeExtraction||!rejections.isEmpty()&&!failed)&&valid.size()==facts.size()&&candidates.stream().allMatch(s->metadata.containsKey(s.id()));
            // Single-window receipts let overlapping later retrievals reuse only what was actually read.
            // A multi-window fact retains all its sources; its identity chain is never split into false facts.
            Set<String> multiSource=new HashSet<>();for(Fact fact:matched)if(fact.sourceIds().size()>1)multiSource.addAll(fact.sourceIds());
            if(!multiSource.isEmpty()){Map<String,Object> field=fieldReceipt(target,refs,matched.stream().map(Fact::id).toList(),"EXTRACTED",done,failed?"FIELD_VALIDATION_FAILED":"");fields.add(field);}
            for(String source:refs){if(multiSource.contains(source))continue;List<Fact> local=matched.stream().filter(f->f.sourceIds().equals(List.of(source))).toList();
                boolean localFailed=rejections.stream().anyMatch(row->(ProofVerifier.strings(row.get("sourceIds")).isEmpty()||ProofVerifier.strings(row.get("sourceIds")).contains(source))&&(str(row,"kind").isBlank()||str(row,"field").isBlank()||rejectedFieldCouldMatch(rule,row)));
                boolean read=(completeExtraction||!rejections.isEmpty()&&!localFailed)&&valid.size()==facts.size()&&metadata.containsKey(source);
                String status=!local.isEmpty()?"EXTRACTED":read?"READ_NOT_FOUND":"FAILED";Map<String,Object> field=fieldReceipt(target,List.of(source),local.stream().map(Fact::id).toList(),status,read,localFailed?"FIELD_VALIDATION_FAILED":"");fields.add(field);
            }
        }
        if(valid.isEmpty()&&fields.isEmpty())return;
        boolean coverage=completeExtraction&&refs.stream().allMatch(metadata::containsKey)&&valid.size()==facts.size()&&!rules.isEmpty()&&rules.stream().allMatch(r->!EXCLUDED.contains(r.predicate())&&!r.predicate().equals("SCORE_COUNT"));
        List<Map<String,Object>> rows=valid.stream().map(f->{Map<String,Object> row=new LinkedHashMap<>(value(f));row.put("id",f.id());return row;}).toList();
        Map<String,Object> receipt=coverage?linked("status","VALIDATED","modelIdentity",modelIdentity,"ruleSignatures",rules.stream().map(SharedFactReuse::signature).distinct().toList(),"sourceIds",refs,"factIds",valid.stream().map(Fact::id).toList()):Map.of();
        Map<String,Object> descriptor=operationDescriptor(origin,candidates,rules);String requestKey=EvidenceOperationRepository.key(descriptor);String operation=identity(requestKey+json.valueToTree(ordered(rows))+json.valueToTree(ordered(receipt))+json.valueToTree(ordered(fields)));
        if(!(context.get("sharedFactPublisher") instanceof Consumer<?>)){fields.forEach(this::observe);return;}
        Map<String,Object> batch=linked("facts",rows,"sources",refs.stream().map(metadata::get).filter(Objects::nonNull).toList(),"operationIdentity",operation,"evidenceOperationKey",requestKey,"evidenceOperationDescriptor",descriptor,"engineVersion",VERSION,"observationPolicy",FieldCoverage.POLICY,"modelIdentity",modelIdentity,"origin",origin,"layoutIdentity",context.getOrDefault("layoutIdentity",""),"layoutCells",layout,"fieldCoverage",fields);
        if(coverage)batch.put("extractionCoverage",receipt);
        publishFieldParts(batch,fields);
        // Expose complete local observations only after every durable part succeeded.
        // Earlier committed parts remain recoverable receipts, not a whole-batch completion claim.
        fields.forEach(this::observe);
    }
    /** Persist a single network observation as bounded ledger batches. Each part keeps the complete
     * fact/source chain: splitting receipts must never turn a multi-source fact into partial evidence. */
    @SuppressWarnings("unchecked") private void publishFieldParts(Map<String,Object> batch,List<Map<String,Object>> fields){
        if(!(context.get("sharedFactPublisher") instanceof Consumer<?> callback))return;
        int parts=Math.max(1,(fields.size()+119)/120);
        for(int index=0;index<parts;index++){
            List<Map<String,Object>> part=List.copyOf(fields.subList(index*120,Math.min(fields.size(),(index+1)*120)));
            String operation=str(batch,"operationIdentity");if(parts>1)operation=identity(operation+":field-part:"+index+":"+json.valueToTree(ordered(part)));
            if(published.contains(operation))continue;
            Map<String,Object> value=new LinkedHashMap<>(batch);value.put("operationIdentity",operation);value.put("fieldCoverage",part);
            // A first committed part must not authorize the legacy whole-request shortcut if a later
            // part fails. Durable field receipts alone describe the completed windows of multipart reads.
            if(parts>1)value.remove("extractionCoverage");
            value.put("observationPartition",linked("part",index+1,"parts",parts,"fieldCount",part.size(),"completeObservationFieldCount",fields.size(),"factsAndSourcesIntact",true));
            ((Consumer<Map<String,Object>>)callback).accept(value); // Lease/storage failure propagates; no silent partial completion.
            published.add(operation);sharedPublishes++;
        }
    }
    private static boolean kindCouldMatch(Rule rule,String kind){return rule.predicate().equals(kind)||rule.predicate().equals("PERSON_COUNT")&&kind.equals("PERSON_ROLE")||rule.predicate().equals("VALIDITY")&&Set.of("CERTIFICATE","DATE_RANGE").contains(kind)||rule.predicate().equals("SAME_FIELD")&&Set.of("FIELD","NUMBER","CERTIFICATE").contains(kind)||rule.predicate().equals("PRICE_RECONCILE")&&Set.of("NUMBER","FIELD").contains(kind)||rule.predicate().equals("DATE_RELATION")&&kind.equals("FIELD");}
    static boolean rejectedFieldCouldMatch(Rule rule,Map<String,Object> rejected){String kind=str(rejected,"kind"),field=str(rejected,"field");Set<String> fields=new LinkedHashSet<>();fields.add(rule.field());if(rule.predicate().equals("PRICE_RECONCILE"))fields.addAll(ProofVerifier.strings(rule.parameters().get("componentFields")));if(rule.predicate().equals("DATE_RELATION")){fields.add(str(rule.parameters(),"leftRole"));fields.add(str(rule.parameters(),"rightRole"));}fields.remove("");return kindCouldMatch(rule,kind)&&(rule.predicate().equals("PERSON_COUNT")&&kind.equals("PERSON_ROLE")||fields.isEmpty()||fields.contains(field));}
    private Map<String,Object> fieldReceipt(Map<String,Object> target,List<String> refs,List<String> facts,String state,boolean complete,String reason){return linked("observationKey",FieldCoverage.key(target),"target",target,"status",state,"sourceIds",refs,"factIds",facts,"sourceScopeComplete",complete,"observationPolicy",FieldCoverage.POLICY,"modelIdentity",modelIdentity,"reason",reason,"scope","SELECTED_SOURCES_ONLY","absenceProven",false);}
    private String observationScope(Map<String,Object> field){return str(field,"observationKey")+":"+new TreeSet<>(ProofVerifier.strings(field.get("sourceIds")));}
    private void observe(Map<String,Object> field){String key=observationScope(field);currentObservationKeys.add(key);observations.put(key,field);if(FieldCoverage.completeRead(field))fieldReceipts.put("local:"+key,field);}
    private void observeCached(Map<String,Object> field){String key=observationScope(field);if(currentObservationKeys.contains(key))return;Map<String,Object> previous=observations.get(key);if(previous==null||observationRank(field)>observationRank(previous))observations.put(key,field);}
    private static int observationRank(Map<String,Object> field){return switch(str(field,"status")){case "EXTRACTED"->Boolean.TRUE.equals(field.get("sourceScopeComplete"))?5:4;case "READ_NOT_FOUND"->3;case "FAILED"->2;default->1;};}
    @SuppressWarnings("unchecked") void publishState(List<Rule> rules,List<Source> candidates,String state,String reason){
        List<String> refs=candidates.stream().map(Source::id).filter(metadata::containsKey).toList();if(refs.isEmpty()||!Set.of("UNREAD","FAILED").contains(state))return;
        List<Map<String,Object>> fields=rules.stream().filter(r->!EXCLUDED.contains(r.predicate())&&!r.predicate().equals("SCORE_COUNT")).map(r->fieldReceipt(observationTarget(r),refs,List.of(),state,false,reason)).toList();fields.forEach(this::observe);
        if(fields.isEmpty()||!(context.get("sharedFactPublisher") instanceof Consumer<?> callback))return;
        Map<String,Object> descriptor=operationDescriptor(state,candidates,rules);String requestKey=EvidenceOperationRepository.key(descriptor);String operation=identity(requestKey+json.valueToTree(ordered(fields)));
        publishFieldParts(linked("facts",List.of(),"sources",refs.stream().map(metadata::get).toList(),"fieldCoverage",fields,"operationIdentity",operation,"evidenceOperationKey",requestKey,"evidenceOperationDescriptor",descriptor,"engineVersion",VERSION,"observationPolicy",FieldCoverage.POLICY,"modelIdentity",modelIdentity,"origin",state),fields);
    }
    List<Map<String,Object>> coverage(){return List.copyOf(observations.values());}
    /** Same-run recovery may outlive or exceed a bounded ledger lookup. Only the recovery
     * verifier can supply these exact source/rule/fact-checked observations. No new read is claimed. */
    void restoreRecoveryCoverage(RecoveryFieldCoverage.Replayed replay){
        for(Map<String,Object> receipt:replay.receipts())if(validFieldReceipt(receipt)&&FieldCoverage.completeRead(receipt)){
            fieldReceipts.put("recovery:"+observationScope(receipt),receipt);observeCached(receipt);
        }
    }
    Set<String> completedReads(){Set<String> reads=new TreeSet<>();for(Map<String,Object> field:fieldReceipts.values())if(validFieldReceipt(field)&&FieldCoverage.completeRead(field))for(String source:ProofVerifier.strings(field.get("sourceIds")))reads.add(str(field,"observationKey")+":"+source);return reads;}
    int reusedFacts(){return reused.size();}
}
