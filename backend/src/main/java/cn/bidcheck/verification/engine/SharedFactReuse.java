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
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SharedFactReuse.class);
    private static final int LIMIT=240;
    private static final Set<String> EXCLUDED=Set.of("SECTION","QUALITY","TEXT_ANOMALY","QUALITY_ADVISORY");
    // Only active/waiting leases own entries. Unrelated source/target keys never share a lock.
    private static final ConcurrentHashMap<String,Flight> FLIGHTS=new ConcurrentHashMap<>();
    private static final class Flight {final ReentrantLock lock=new ReentrantLock(true);int references;}
    private record RegisteredFlight(String key,Flight flight) {}
    private record FieldCandidate(String batch,Map<String,Object> receipt,boolean occurrenceReferences) {}
    private final EvidenceCorpus corpus;
    private final Map<String,Object> context;
    private final String modelIdentity,engineIdentity,processFlightNamespace;
    private final ObjectMapper json=new ObjectMapper();
    private final Map<String,Map<String,Object>> metadata=new HashMap<>();
    private final Map<String,Map<String,Object>> receipts=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>> fieldReceipts=new LinkedHashMap<>(),observations=new LinkedHashMap<>();
    private final Set<String> currentObservationKeys=new HashSet<>();
    private final Map<String,Long> currentObservationOrder=new HashMap<>();private long observationOrder;
    private final Set<String> reused=new HashSet<>(),published=new HashSet<>();
    // A usage is scoped to this evaluator/checkpoint. Re-reading the same occurrence after a
    // flight wait must not append another adoption; a different checkpoint still records its use.
    private final Set<String> adoptedFacts=new HashSet<>(),adoptedReads=new HashSet<>();
    private final Set<String> nativePublished=new HashSet<>();
    int sharedPublishes,extractionSkipped,rejectedFacts;
    private boolean factLookupTruncated,fieldLookupTruncated;
    private final Map<String,Map<String,Object>> lookupTruncations=new LinkedHashMap<>();
    boolean factLookupTruncated(){return factLookupTruncated;}
    boolean fieldLookupTruncated(){return fieldLookupTruncated;}
    List<Map<String,Object>> lookupTruncations(){return List.copyOf(lookupTruncations.values());}
    private void markLookupTruncation(boolean facts,boolean fields,Map<String,Object> detail){
        if(!facts&&!fields)return;factLookupTruncated|=facts;fieldLookupTruncated|=fields;
        Map<String,Object> value=new LinkedHashMap<>(detail);value.put("factLookupTruncated",facts);value.put("fieldLookupTruncated",fields);
        lookupTruncations.put(identity(json.valueToTree(ordered(value)).toString()),value);
    }

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
    static Fact canonical(Fact f){String id="F"+identity(new ObjectMapper().valueToTree(ordered(cn.bidcheck.verification.facts.SemanticFactIdentity.value(value(f)))).toString()).substring(0,24);return new Fact(id,f.kind(),f.subject(),f.field(),f.rawValue(),f.value(),f.unit(),f.basis(),f.scope(),f.sourceIds(),f.attributes());}
    static String signature(Rule r){return identity(new ObjectMapper().valueToTree(ordered(linked("predicate",r.predicate(),"description",r.description(),"subject",r.subject(),"field",r.field(),"operator",r.operator(),"expected",r.expected(),"unit",r.unit(),"parameters",r.parameters(),"sourceQuote",r.sourceQuote(),"logic",r.logic(),"children",r.children().stream().map(SharedFactReuse::signature).toList()))).toString());}
    static Map<String,Object> observationTarget(Rule r){
        // Only numeric comparison thresholds are independent of the value being observed.
        // Material names, certificate classes, conditions and object selectors remain in the key.
        Map<String,Object> qualifiers=new LinkedHashMap<>(r.parameters());
        // These server-owned pointers identify the requirement, not the bid-side observation.
        // Resolved subject/lot/currency/tax and other business qualifiers remain explicit above/in qualifiers.
        for(String metadata:List.of("obligationIdentity","requirementSourceContext","requirementBindings","priceConstraintSources","scoringParentRuleId","readPurpose"))qualifiers.remove(metadata);
        return linked("predicate",r.predicate(),"subject",r.subject(),"field",r.field(),"unit",r.unit(),"parameters",qualifiers,
            "targetExpected",r.predicate().equals("NUMBER")?"":r.expected(),"children",r.children().stream().map(SharedFactReuse::signature).toList());
    }
    static Set<String> kinds(List<Rule> rules){Set<String> kinds=new LinkedHashSet<>(List.of("PERSON_ROLE","RELATION"));for(Rule r:rules){
        if(SourceScoringRules.programOnly(r))continue;
        if(SourceScoringRules.applies(r)&&Set.of("TITLE_TIER",SourcePerformanceScore.MODE,SourceExtraPersonnelScore.MODE).contains(str(r.parameters(),"sourceScoringMode"))){kinds.addAll(kinds(SourceScoringRules.evidenceRules(r)));continue;}
        switch(r.predicate()){
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
        String observationContract="EXTRACTED".equals(origin)?ExtractionContracts.VERSION:engineIdentity;
        return EvidenceOperationRepository.descriptor(project,operationType,List.of(),sources,targets,artifact,modelIdentity,"FACTS_ONLY",observationContract+":"+FieldCoverage.POLICY);
    }
    @SuppressWarnings("unchecked") List<Fact> lookup(List<Rule> rules,List<Source> candidates,List<Map<String,Object>> layout){
        return lookup(rules,candidates,layout,"SELECTED_SOURCES");
    }
    @SuppressWarnings("unchecked") List<Fact> lookup(List<Rule> rules,List<Source> candidates,List<Map<String,Object>> layout,String phase){
        if(rules.isEmpty()||rules.stream().allMatch(r->"UNSUPPORTED".equals(r.predicate()))){
            LOG.info("bidcheck.fact_lookup_skip runId={} checkpointId={} phase={} reason=NO_EXECUTABLE_EVIDENCE_TARGET sources={}",trace("sourceRunId"),trace("checkpointId"),phase,candidates.size());
            return List.of();
        }
        // Visual recovery uses the same corpus but has its own verified evidence channel.
        // Send only complete text source manifests to the canonical fact index; a V alias
        // without text metadata would otherwise force a needless legacy-history fallback.
        candidates=candidates.stream().filter(source->metadata.containsKey(source.id())).toList();
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
        if(context.get("sharedFactLookup") instanceof Function<?,?> callback){Object result=((Function<Map<String,Object>,?>)callback).apply(linked("lookupMode","CANONICAL_SOURCE","sources",selected.stream().map(metadata::get).filter(Objects::nonNull).toList(),"modelIdentity",modelIdentity,"sourceIds",new ArrayList<>(selected),"documentIds",corpus.documents.stream().map(Document::id).toList(),"factKinds",new ArrayList<>(requestedKinds),"limit",LIMIT,"fieldCoverageLimit",LIMIT,"engineVersion",VERSION,"engineIdentity",engineIdentity,"observationPolicy",FieldCoverage.POLICY,"includeFieldCoverage",true,"observationKeys",rules.stream().map(r->FieldCoverage.key(observationTarget(r))).distinct().toList(),"runId",context.get("sourceRunId"),"checkpointId",context.get("checkpointId"),"lookupPhase",phase));entries.addAll(RuleEvaluator.maps(result));}
        long suppliedFacts=entries.stream().filter(row->row.get("fact") instanceof Map<?,?>).map(row->json.valueToTree(ordered(row.get("fact"))).toString()).distinct().count();
        entries.addAll(processingEntries(requestedKinds,candidates,layout,(int)Math.max(0,120-suppliedFacts)));
        Map<String,Object> lookupScope=linked("phase",phase,"sourceIds",new ArrayList<>(selected),"factKinds",new ArrayList<>(requestedKinds),"observationKeys",rules.stream().map(r->FieldCoverage.key(observationTarget(r))).distinct().toList());
        for(Map<String,Object> entry:entries){Map<String,Object> detail=new LinkedHashMap<>(lookupScope);if(entry.get("lookupTruncation") instanceof Map<?,?> raw)raw.forEach((k,v)->detail.put(String.valueOf(k),v));
            markLookupTruncation(Boolean.TRUE.equals(entry.get("factLookupTruncated")),Boolean.TRUE.equals(entry.get("fieldLookupTruncated")),detail);}
        if(entries.size()>LIMIT*8){List<Map<String,Object>> omitted=entries.subList(LIMIT*8,entries.size());Map<String,Object> detail=new LinkedHashMap<>(lookupScope);detail.put("reason","SHARED_ENTRY_SCAN_CAPACITY");
            markLookupTruncation(omitted.stream().anyMatch(row->row.get("fact") instanceof Map<?,?>),omitted.stream().anyMatch(row->!RuleEvaluator.maps(row.get("fieldCoverage")).isEmpty()),detail);}
        Map<String,Fact> accepted=new LinkedHashMap<>(),acceptedByOccurrence=new HashMap<>();Map<String,Set<String>> acceptedByBatch=new HashMap<>();Map<String,Map<String,Fact>> acceptedFactsByBatch=new HashMap<>();Map<String,Map<String,Object>> batchReceipts=new HashMap<>();Map<String,FieldCandidate> completeFields=new LinkedHashMap<>(),pendingFields=new LinkedHashMap<>();
        // Facts and field reads have independent capacities. A full fact result must not discard
        // READ_NOT_FOUND receipts or receipt dependencies returned after those facts.
        for(Map<String,Object> entry:entries.stream().limit(LIMIT*8L).toList()){
            if("LOOKUP_METADATA".equals(str(entry,"recordType")))continue;
            if(!VERSION.equals(str(entry,"engineVersion"))&&!FieldCoverage.POLICY.equals(str(entry,"observationPolicy"))){rejectedFacts++;continue;}
            String receiptBatch=str(entry,"batchId");if(receiptBatch.isBlank())receiptBatch=str(entry,"operationIdentity");
            Map<String,Map<String,Object>> fieldSources=new HashMap<>();for(Map<String,Object> source:RuleEvaluator.maps(entry.get("fieldCoverageSources")))fieldSources.put(str(source,"id"),source);
            List<Map<String,Object>> scopedFields=RuleEvaluator.maps(entry.get("fieldCoverage")).stream().filter(row->{List<String> ids=ProofVerifier.strings(row.get("sourceIds"));return validFieldReceipt(row)&&!ids.isEmpty()&&selected.containsAll(ids)&&ids.stream().allMatch(id->sourceMatches(id,fieldSources.get(id)));}).toList();
            if(!receiptBatch.isBlank())for(Map<String,Object> receipt:scopedFields){boolean occurrenceReferences="OCCURRENCE_ID".equals(str(entry,"factReferenceType"));Map<String,Object> semantic=new LinkedHashMap<>(receipt);for(String provenance:List.of("reusedFromBatchId","reusedObservation","observedInCurrentRun"))semantic.remove(provenance);
                String key=(occurrenceReferences?"occurrence:":receiptBatch+":")+json.valueToTree(ordered(semantic));Map<String,FieldCandidate> bucket=FieldCoverage.completeRead(receipt)?completeFields:pendingFields;
                if(bucket.size()<LIMIT)bucket.putIfAbsent(key,new FieldCandidate(receiptBatch,receipt,occurrenceReferences));
                else if(!bucket.containsKey(key)&&FieldCoverage.completeRead(receipt)){Map<String,Object> detail=new LinkedHashMap<>(lookupScope);detail.put("reason","SHARED_FIELD_RECEIPT_CAPACITY");markLookupTruncation(false,true,detail);}
            }
            if("FIELD_COVERAGE".equals(str(entry,"recordType")))continue;
            if(!(entry.get("fact") instanceof Map<?,?> factRow)){rejectedFacts++;continue;}
            JsonNode node=json.valueToTree(factRow);String kind=node.path("kind").asText();List<String> refs=ProofVerifier.strings(factRow.get("sourceIds"));
            Map<String,Map<String,Object>> provided=new HashMap<>();for(Map<String,Object> source:RuleEvaluator.maps(entry.get("sources")))provided.put(str(source,"id"),source);
            if(EXCLUDED.contains(kind)||!requestedKinds.contains(kind)||refs.isEmpty()||refs.stream().anyMatch(id->!selected.contains(id)||!sourceMatches(id,provided.get(id)))){rejectedFacts++;continue;}
            // Re-read current original text and current table bindings. Persisted PASS/verified flags have no authority.
            ProofVerifier.Verified verified=new ProofVerifier(corpus.byId).verify(json.valueToTree(Map.of("facts",List.of(factRow))),candidates,layout);
            if(verified.facts().size()!=1||!verified.rejected().isEmpty()){rejectedFacts++;continue;}
            Fact fact=canonical(verified.facts().get(0));if(!shareable(fact)){rejectedFacts++;continue;}
            if(accepted.size()>=LIMIT&&!accepted.containsKey(fact.id())){rejectedFacts++;Map<String,Object> detail=new LinkedHashMap<>(lookupScope);detail.put("reason","SHARED_FACT_CAPACITY");markLookupTruncation(true,false,detail);continue;}accepted.put(fact.id(),fact);reused.add(fact.id());
            if(!str(entry,"occurrenceId").isBlank())acceptedByOccurrence.putIfAbsent(str(entry,"occurrenceId"),fact);
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
        List<FieldCandidate> fieldCandidates=new ArrayList<>(completeFields.values());fieldCandidates.addAll(pendingFields.values().stream().limit(LIMIT-completeFields.size()).toList());
        for(FieldCandidate candidate:fieldCandidates){String batch=candidate.batch();Map<String,Object> receipt=candidate.receipt();List<String> ids=ProofVerifier.strings(receipt.get("factIds"));Map<String,Fact> dependencies=candidate.occurrenceReferences()?acceptedByOccurrence:acceptedFactsByBatch.getOrDefault(batch,Map.of());
            if(ids.size()>120||ProofVerifier.strings(receipt.get("sourceIds")).size()>128||!dependencies.keySet().containsAll(ids)||"EXTRACTED".equals(str(receipt,"status"))&&ids.isEmpty())continue;
            Map<String,Object> target=(Map<String,Object>)receipt.get("target");List<String> sourceIds=ProofVerifier.strings(receipt.get("sourceIds"));
            if(ids.stream().anyMatch(id->!sourceIds.containsAll(dependencies.get(id).sourceIds())||!FieldCoverage.matches(target,value(dependencies.get(id)))))continue;
            fieldReceipts.put(batch+":"+observationScope(receipt),receipt);observeCached(receipt);
            String localScope=observationScope(receipt);boolean alreadyObservedHere=currentObservationKeys.contains(localScope)&&FieldCoverage.completeRead(observations.getOrDefault(localScope,Map.of()));
            if(FieldCoverage.completeRead(receipt)&&!alreadyObservedHere){
                Map<String,Object> adopted=new LinkedHashMap<>(receipt);adopted.put("factIds",ids.stream().map(id->dependencies.get(id).id()).distinct().toList());
                adopted.put("reusedObservation",true);adopted.put("observedInCurrentRun",false);
                adopted.put("reusedFromBatchId",str(receipt,"reusedFromBatchId").isBlank()?batch:str(receipt,"reusedFromBatchId"));
                adoptedFields.put(identity(json.valueToTree(ordered(adopted)).toString()),adopted);
            }
        }
        List<Fact> facts=List.copyOf(accepted.values());
        // Register use of the already validated receipt under the caller's current run manifest.
        // This is a dependency on the original read, never a new extraction or completeness claim.
        publishAdoption(facts,List.copyOf(adoptedFields.values()),candidates,layout);return facts;
    }
    @SuppressWarnings("unchecked") private void publishAdoption(List<Fact> facts,List<Map<String,Object>> fields,List<Source> candidates,List<Map<String,Object>> layout){
        if(!(context.get("sharedFactPublisher") instanceof Consumer<?> callback))return;
        int suppliedFacts=facts.size(),suppliedReads=fields.size();
        fields=fields.stream().filter(field->!adoptedReads.contains(adoptionReadKey(field))).toList();
        Map<String,Fact> byId=new LinkedHashMap<>();facts.forEach(f->byId.put(f.id(),f));Set<String> included=new HashSet<>();List<AdoptionPart> parts=new ArrayList<>();AdoptionPart current=new AdoptionPart();
        // Lookup has separate fact/receipt limits. Persist each receipt with its entire dependency
        // set, instead of attaching all 240 lookup facts to every 120-fact ledger batch.
        for(Map<String,Object> field:fields){List<Fact> required=ProofVerifier.strings(field.get("factIds")).stream().map(byId::get).toList();Set<String> refs=new LinkedHashSet<>(ProofVerifier.strings(field.get("sourceIds")));required.forEach(f->refs.addAll(f.sourceIds()));
            if(!current.fits(required,refs,true)){parts.add(current);current=new AdoptionPart();}
            current.add(required,refs);current.fields.add(field);required.forEach(f->included.add(f.id()));
        }
        for(Fact fact:facts)if(!included.contains(fact.id())&&!adoptedFacts.contains(fact.id())){if(!current.fits(List.of(fact),new HashSet<>(fact.sourceIds()),false)){parts.add(current);current=new AdoptionPart();}current.add(List.of(fact),fact.sourceIds());}
        if(!current.facts.isEmpty()||!current.fields.isEmpty())parts.add(current);
        for(AdoptionPart partition:parts){List<Map<String,Object>> part=List.copyOf(partition.fields);List<Map<String,Object>> rows=partition.facts.values().stream().map(f->{Map<String,Object> row=new LinkedHashMap<>(value(f));row.put("id",f.id());return row;}).toList();
            String origin=part.isEmpty()?"REUSED":"REUSED_OBSERVATION";Map<String,Object> descriptor=operationDescriptor(origin,candidates.stream().filter(s->partition.sources.contains(s.id())).toList(),List.of());String requestKey=EvidenceOperationRepository.key(descriptor);String operation=identity(requestKey+json.valueToTree(ordered(rows))+json.valueToTree(ordered(part)));if(published.contains(operation))continue;
            ((Consumer<Map<String,Object>>)callback).accept(linked("facts",rows,"sources",partition.sources.stream().map(metadata::get).filter(Objects::nonNull).toList(),"fieldCoverage",part,
                "operationIdentity",operation,"evidenceOperationDescriptor",descriptor,"engineVersion",VERSION,"observationPolicy",FieldCoverage.POLICY,"modelIdentity",modelIdentity,"origin",origin,
                "evidenceOperationKey",requestKey,"newModelObservation",false,"layoutIdentity",context.getOrDefault("layoutIdentity",""),"layoutCells",layout));published.add(operation);sharedPublishes++;
            adoptedFacts.addAll(partition.facts.keySet());part.forEach(field->adoptedReads.add(adoptionReadKey(field)));
        }
        LOG.info("bidcheck.fact_adoption runId={} checkpointId={} suppliedFacts={} suppliedReads={} writes={} newReads={} alreadyUsedFacts={}",trace("sourceRunId"),trace("checkpointId"),suppliedFacts,suppliedReads,parts.size(),fields.size(),suppliedFacts-parts.stream().flatMap(p->p.facts.keySet().stream()).distinct().count());
    }
    private String adoptionReadKey(Map<String,Object> field){Map<String,Object> semantic=new LinkedHashMap<>(field);for(String provenance:List.of("reusedFromBatchId","reusedObservation","observedInCurrentRun"))semantic.remove(provenance);return identity(json.valueToTree(ordered(semantic)).toString());}
    private static final class AdoptionPart {
        final Map<String,Fact> facts=new LinkedHashMap<>();final Set<String> sources=new LinkedHashSet<>();final List<Map<String,Object>> fields=new ArrayList<>();
        boolean fits(List<Fact> required,Set<String> refs,boolean field){Set<String> ids=new HashSet<>(facts.keySet()),allSources=new HashSet<>(sources);required.forEach(f->ids.add(f.id()));allSources.addAll(refs);return ids.size()<=120&&allSources.size()<=128&&(!field||fields.size()<120);}
        void add(List<Fact> required,Collection<String> refs){required.forEach(f->facts.putIfAbsent(f.id(),f));sources.addAll(refs);}
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
            // matchingProcessingSource has already checked the document, revision, parser and exact
            // substring. These persisted source IDs are aliases, not entries in the current corpus.
            // Validate only the narrow quotations here: granting the full-page index would either
            // discard the aliases or let surrounding text repair an ungrounded persisted claim.
            // lookup revalidates the remapped fact with corpus.byId before accepting or publishing it.
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
        int reads=0,programProofs=0;
        for(Rule rule:rules){
            if(!candidates.isEmpty()&&pendingSources(rule,candidates).isEmpty()){extractionSkipped++;reads++;continue;}
            // A PASS alone is insufficient: these readers revalidate the current original and
            // explicit counterevidence. Aggregate, comparison and partial certificate proofs do
            // not authorize skipping unreviewed sources or manufacture model read receipts.
            Decision current=evaluator.evaluate(rule);
            if("PASS".equals(current.status())&&SourceLocalExistenceClosure.proven(rule,current,evaluator)){extractionSkipped++;programProofs++;continue;}
            remaining.add(rule);
        }
        if(reads+programProofs>0)LOG.info("bidcheck.extraction_pruning runId={} checkpointId={} rules={} reusedReads={} sourceRevalidatedProofs={} remaining={}",trace("sourceRunId"),trace("checkpointId"),rules.size(),reads,programProofs,remaining.size());
        return remaining;
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
        long started=System.nanoTime(),localDone=started;
        Set<String> keys=new TreeSet<>();for(Rule rule:rules)for(Source source:candidates){Map<String,Object> target=observationTarget(rule);keys.add(EvidenceOperationRepository.textKey(
            corpus.documents.isEmpty()?"":corpus.documents.get(0).projectId(),modelIdentity,ExtractionContracts.VERSION+":"+FieldCoverage.POLICY,List.of(metadata.getOrDefault(source.id(),Map.of("id",source.id()))),List.of(target),FieldCoverage.key(target)));}
        List<RegisteredFlight> registered=new ArrayList<>();List<ReentrantLock> held=new ArrayList<>();List<EvidenceOperationRepository.Lease> durable=new ArrayList<>();
        try{
            // Register every reference before locking: a releaser cannot remove an entry that
            // another lease is about to acquire. Global key order prevents overlapping-set deadlock.
            for(String durableKey:keys){String key=processFlightNamespace+durableKey;Flight flight=FLIGHTS.compute(key,(ignored,current)->{Flight value=current==null?new Flight():current;value.references++;return value;});registered.add(new RegisteredFlight(key,flight));}
            for(RegisteredFlight entry:registered){entry.flight().lock.lockInterruptibly();held.add(entry.flight().lock);}
            localDone=System.nanoTime();
            // Use the same globally sorted source/target keys for durable leases. This retains the
            // partial-overlap exclusion across instances as well as inside this JVM. VerificationEngine
            // re-runs both the immutable operation lookup and the fact ledger lookup after acquisition.
            if(context.get("evidenceOperationRepository") instanceof EvidenceOperationRepository repository){
                String project=corpus.documents.isEmpty()?"":corpus.documents.get(0).projectId();
                for(String key:keys)durable.add(repository.coordinate(project,key));
            }
            LOG.info("bidcheck.fact_flight runId={} checkpointId={} keys={} sources={} rules={} localWaitMs={} durableWaitMs={} durationMs={}",trace("sourceRunId"),trace("checkpointId"),keys.size(),candidates.size(),rules.size(),(localDone-started)/1_000_000,(System.nanoTime()-localDone)/1_000_000,(System.nanoTime()-started)/1_000_000);
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
    void publish(List<Fact> facts,List<Source> candidates,List<Rule> rules,List<Map<String,Object>> layout,boolean completeExtraction,String origin){
        // Native record readers explicitly expand retrieved chunks to original record spans.
        // Preserve that complete server-owned source chain when persisting the extracted fact.
        Map<String,Source> scope=new LinkedHashMap<>();candidates.forEach(s->scope.put(s.id(),s));
        if("NATIVE".equals(origin))for(Fact fact:facts)for(String id:fact.sourceIds()){Source source=corpus.byId.get(id);if(source!=null)scope.put(id,source);}
        publish(facts,new ArrayList<>(scope.values()),rules,layout,completeExtraction,origin,List.of());
    }
    @SuppressWarnings("unchecked") void publish(List<Fact> facts,List<Source> candidates,List<Rule> rules,List<Map<String,Object>> layout,boolean completeExtraction,String origin,List<Map<String,Object>> rejections){
        List<Fact> valid=new ArrayList<>();for(Fact f:facts){if(!shareable(f))continue;ProofVerifier.Verified check=new ProofVerifier(corpus.byId).verify(json.valueToTree(Map.of("facts",List.of(value(f)))),candidates,layout);if(check.facts().size()==1&&check.rejected().isEmpty())valid.add(canonical(check.facts().get(0)));}
        // Native facts carry no model read-completion receipt. Once this checkpoint has durably
        // recorded the same immutable fact, changing retrieval targets does not require republishing it.
        if("NATIVE".equals(origin))valid.removeIf(f->nativePublished.contains(f.id()));
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
        if("NATIVE".equals(origin))valid.forEach(f->nativePublished.add(f.id()));
        // Expose complete local observations only after every durable part succeeded.
        // Earlier committed parts remain recoverable receipts, not a whole-batch completion claim.
        fields.forEach(this::observe);
    }
    /** Persist a single network observation as bounded ledger batches. Each part keeps the complete
     * fact/source chain: splitting receipts must never turn a multi-source fact into partial evidence. */
    @SuppressWarnings("unchecked") private void publishFieldParts(Map<String,Object> batch,List<Map<String,Object>> fields){
        if(!(context.get("sharedFactPublisher") instanceof Consumer<?> callback))return;
        long started=System.nanoTime();int writes=0;
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
            published.add(operation);sharedPublishes++;writes++;
        }
        LOG.info("bidcheck.fact_publication runId={} checkpointId={} origin={} durationMs={} parts={} writes={} facts={} fields={}",trace("sourceRunId"),trace("checkpointId"),str(batch,"origin"),(System.nanoTime()-started)/1_000_000,parts,writes,RuleEvaluator.maps(batch.get("facts")).size(),fields.size());
    }
    private String trace(String key){String value=str(context,key);return value.matches("[A-Za-z0-9_.:-]{1,100}")?value:"";}
    private static boolean kindCouldMatch(Rule rule,String kind){return rule.predicate().equals(kind)||rule.predicate().equals("PERSON_COUNT")&&kind.equals("PERSON_ROLE")||rule.predicate().equals("VALIDITY")&&Set.of("CERTIFICATE","DATE_RANGE").contains(kind)||rule.predicate().equals("SAME_FIELD")&&Set.of("FIELD","NUMBER","CERTIFICATE").contains(kind)||rule.predicate().equals("PRICE_RECONCILE")&&Set.of("NUMBER","FIELD").contains(kind)||rule.predicate().equals("DATE_RELATION")&&kind.equals("FIELD");}
    static boolean rejectedFieldCouldMatch(Rule rule,Map<String,Object> rejected){String kind=str(rejected,"kind"),field=str(rejected,"field");Set<String> fields=new LinkedHashSet<>();fields.add(rule.field());if(rule.predicate().equals("PRICE_RECONCILE"))fields.addAll(ProofVerifier.strings(rule.parameters().get("componentFields")));if(rule.predicate().equals("DATE_RELATION")){fields.add(str(rule.parameters(),"leftRole"));fields.add(str(rule.parameters(),"rightRole"));}fields.remove("");return kindCouldMatch(rule,kind)&&(rule.predicate().equals("PERSON_COUNT")&&kind.equals("PERSON_ROLE")||fields.isEmpty()||fields.contains(field));}
    private Map<String,Object> fieldReceipt(Map<String,Object> target,List<String> refs,List<String> facts,String state,boolean complete,String reason){return linked("observationKey",FieldCoverage.key(target),"target",target,"status",state,"sourceIds",refs,"factIds",facts,"sourceScopeComplete",complete,"observationPolicy",FieldCoverage.POLICY,"modelIdentity",modelIdentity,"reason",reason,"scope","SELECTED_SOURCES_ONLY","absenceProven",false);}
    private String observationScope(Map<String,Object> field){return str(field,"observationKey")+":"+new TreeSet<>(ProofVerifier.strings(field.get("sourceIds")));}
    private void observe(Map<String,Object> field){String key=observationScope(field);currentObservationKeys.add(key);currentObservationOrder.put(key,++observationOrder);observations.put(key,field);if(FieldCoverage.completeRead(field))fieldReceipts.put("local:"+key,field);}
    private void observeCached(Map<String,Object> field){String key=observationScope(field);if(currentObservationKeys.contains(key))return;Map<String,Object> previous=observations.get(key);if(previous==null||observationRank(field)>observationRank(previous))observations.put(key,field);}
    private static int observationRank(Map<String,Object> field){return switch(str(field,"status")){case "EXTRACTED"->Boolean.TRUE.equals(field.get("sourceScopeComplete"))?5:4;case "READ_NOT_FOUND"->3;case "FAILED"->2;default->1;};}
    @SuppressWarnings("unchecked") void publishState(List<Rule> rules,List<Source> candidates,String state,String reason){
        List<String> refs=candidates.stream().map(Source::id).filter(metadata::containsKey).toList();if(refs.isEmpty()||!Set.of("UNREAD","FAILED").contains(state))return;
        List<Map<String,Object>> fields=rules.stream().filter(r->!EXCLUDED.contains(r.predicate())&&!r.predicate().equals("SCORE_COUNT")).map(r->fieldReceipt(observationTarget(r),refs,List.of(),state,false,reason)).toList();fields.forEach(this::observe);
        if(fields.isEmpty()||!(context.get("sharedFactPublisher") instanceof Consumer<?> callback))return;
        Map<String,Object> descriptor=operationDescriptor(state,candidates,rules);String requestKey=EvidenceOperationRepository.key(descriptor);String operation=identity(requestKey+json.valueToTree(ordered(fields)));
        publishFieldParts(linked("facts",List.of(),"sources",refs.stream().map(metadata::get).toList(),"fieldCoverage",fields,"operationIdentity",operation,"evidenceOperationKey",requestKey,"evidenceOperationDescriptor",descriptor,"engineVersion",VERSION,"observationPolicy",FieldCoverage.POLICY,"modelIdentity",modelIdentity,"origin",state),fields);
    }
    List<Map<String,Object>> coverage(){
        List<Map<String,Object>> visible=new ArrayList<>();
        for(var entry:observations.entrySet()){
            Map<String,Object> parent=entry.getValue();List<String> parentSources=ProofVerifier.strings(parent.get("sourceIds"));boolean superseded=false;
            if(parentSources.size()>1&&Set.of("UNREAD","FAILED").contains(str(parent,"status"))){Set<String> covered=new HashSet<>();
                for(var childEntry:observations.entrySet()){Map<String,Object> child=childEntry.getValue();List<String> childSources=ProofVerifier.strings(child.get("sourceIds"));
                    if(childSources.isEmpty()||childSources.size()>=parentSources.size()||!parentSources.containsAll(childSources)
                            ||!str(parent,"observationKey").equals(str(child,"observationKey"))||!validFieldReceipt(child)||!FieldCoverage.completeRead(child))continue;
                    // Only later successful child reads can close a current failed/pending parent.
                    // Cached reads may close historical parent receipts after normal source/fact validation.
                    if(currentObservationKeys.contains(entry.getKey())&&currentObservationOrder.getOrDefault(childEntry.getKey(),0L)<=currentObservationOrder.getOrDefault(entry.getKey(),0L))continue;
                    covered.addAll(childSources);
                }superseded=covered.containsAll(parentSources);
            }
            if(!superseded)visible.add(parent);
        }return List.copyOf(visible);
    }
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
