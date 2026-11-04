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
    private static final Set<String> EXCLUDED=cn.bidcheck.compat.Java8Api.setOf("SECTION", "QUALITY", "TEXT_ANOMALY", "QUALITY_ADVISORY");
    // Only active/waiting leases own entries. Unrelated source/target keys never share a lock.
    private static final ConcurrentHashMap<String,Flight> FLIGHTS=new ConcurrentHashMap<>();
    private static final class Flight {final ReentrantLock lock=new ReentrantLock(true);int references;}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class RegisteredFlight {
        private final String key;
        private final Flight flight;
        @com.fasterxml.jackson.annotation.JsonCreator
        private RegisteredFlight(@com.fasterxml.jackson.annotation.JsonProperty("key") String key, @com.fasterxml.jackson.annotation.JsonProperty("flight") Flight flight) { this.key = key; this.flight = flight; }
        public String key() { return key; }
        public Flight flight() { return flight; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; RegisteredFlight that = (RegisteredFlight) other; return java.util.Objects.equals(this.key, that.key) && java.util.Objects.equals(this.flight, that.flight); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.key); hash = 31 * hash + java.util.Objects.hashCode(this.flight); return hash; }
        @Override public String toString() { return "RegisteredFlight[" + "key=" + key + ", flight=" + flight + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class FieldCandidate {
        private final String batch;
        private final Map<String,Object> receipt;
        private final boolean occurrenceReferences;
        @com.fasterxml.jackson.annotation.JsonCreator
        private FieldCandidate(@com.fasterxml.jackson.annotation.JsonProperty("batch") String batch, @com.fasterxml.jackson.annotation.JsonProperty("receipt") Map<String,Object> receipt, @com.fasterxml.jackson.annotation.JsonProperty("occurrenceReferences") boolean occurrenceReferences) { this.batch = batch; this.receipt = receipt; this.occurrenceReferences = occurrenceReferences; }
        public String batch() { return batch; }
        public Map<String,Object> receipt() { return receipt; }
        public boolean occurrenceReferences() { return occurrenceReferences; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; FieldCandidate that = (FieldCandidate) other; return java.util.Objects.equals(this.batch, that.batch) && java.util.Objects.equals(this.receipt, that.receipt) && java.util.Objects.equals(this.occurrenceReferences, that.occurrenceReferences); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.batch); hash = 31 * hash + java.util.Objects.hashCode(this.receipt); hash = 31 * hash + java.util.Objects.hashCode(this.occurrenceReferences); return hash; }
        @Override public String toString() { return "FieldCandidate[" + "batch=" + batch + ", receipt=" + receipt + ", occurrenceReferences=" + occurrenceReferences + "]"; }
}
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
    private final Set<String> emittedLookupAudits=new HashSet<>();
    private final Set<String> nativePublished=new HashSet<>();
    int sharedPublishes,extractionSkipped,rejectedFacts;
    private boolean fieldLookupTruncated;
    private final Map<String,Set<String>> unreadByKey=new LinkedHashMap<>(), coveredByKey=new LinkedHashMap<>();
    boolean truncatedFor(String key){return unreadByKey.containsKey(key)||unreadByKey.containsKey("*");}
    Set<String> coveredSources(String key){return Collections.unmodifiableSet(coveredByKey.getOrDefault(key,Collections.emptySet()));}
    boolean truncatedForRules(List<Rule> rules){return rules.stream().anyMatch(r->truncatedFor(FieldCoverage.key(observationTarget(r))));}
    private void completeSourceRead(Collection<String> keys,Collection<String> sources){
        for(String key:keys){
            coveredByKey.computeIfAbsent(key,k->new LinkedHashSet<>()).addAll(sources);
            Set<String> missing=unreadByKey.get(key);
            if(missing!=null&&!missing.isEmpty()){missing.removeAll(sources);if(missing.isEmpty())unreadByKey.remove(key);}
        }
    }
    private Map<String,Object> subjectPreloadTrace=cn.bidcheck.compat.Java8Api.mapOf();
    private List<Map<String,Object>> personEvidenceProfiles=cn.bidcheck.compat.Java8Api.listOf();
    Map<String,Object> subjectPreloadTrace(){return subjectPreloadTrace;}
    List<Map<String,Object>> personEvidenceProfiles(){return personEvidenceProfiles;}
    private final Map<String,Map<String,Object>> lookupTruncations=new LinkedHashMap<>();
    boolean factLookupTruncated(){return !unreadByKey.isEmpty();}
    boolean fieldLookupTruncated(){return fieldLookupTruncated;}
    List<Map<String,Object>> lookupTruncations(){return cn.bidcheck.compat.Java8Api.listCopyOf(lookupTruncations.values());}
    private void markLookupTruncation(boolean facts,boolean fields,Map<String,Object> detail){
        if(!facts&&!fields)return;fieldLookupTruncated|=fields;
        if(facts){
            List<String> keys=ProofVerifier.strings(detail.get("observationKeys")), sources=ProofVerifier.strings(detail.get("sourceIds"));
            for(String key:keys.isEmpty()?Collections.singletonList("*"):keys){
                unreadByKey.computeIfAbsent(key,k->new LinkedHashSet<>()).addAll(sources);
                if(coveredByKey.containsKey(key))coveredByKey.get(key).removeAll(sources);
            }
        }
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
            Document doc=corpus.documents.stream().filter(d->d.id().equals(source.evidence().documentId())).findFirst().orElseThrow(java.util.NoSuchElementException::new);
            Page page=corpus.pages.get(EvidenceCorpus.pageKey(doc.id(),source.evidence().page(),source.evidence().locationType()));
            metadata.put(source.id(),linked("id",source.id(),"evidence",source.evidence(),"projectId",doc.projectId(),"documentHash",doc.sha256(),"documentVersion",doc.version(),"parserFingerprint",parserFingerprint(page)));
        }
    }
    Map<String,Map<String,Object>> sourceMetadata() {
        return Collections.unmodifiableMap(metadata);
    }
    static String parserFingerprint(Page page){return cn.bidcheck.verification.facts.FactLedgerService.parserFingerprint(page);}
    static Map<String,Object> value(Fact f){return linked("kind",f.kind(),"subject",f.subject(),"field",f.field(),"rawValue",f.rawValue(),"value",f.value(),"unit",f.unit(),"basis",f.basis(),"scope",f.scope(),"sourceIds",f.sourceIds(),"attributes",f.attributes());}
    private static Object ordered(Object value){if(value instanceof Map<?,?>){Map<String,Object> out=new TreeMap<>();((Map<?,?>) (value)).forEach((k,v)->out.put(k.toString(),ordered(v)));return out;}if(value instanceof List<?>)return cn.bidcheck.compat.Java8Api.toList(((List<?>) (value)).stream().map(SharedFactReuse::ordered));return value;}
    static Fact canonical(Fact f){String id="F"+identity(new ObjectMapper().valueToTree(ordered(cn.bidcheck.verification.facts.SemanticFactIdentity.value(value(f)))).toString()).substring(0,24);return new Fact(id,f.kind(),f.subject(),f.field(),f.rawValue(),f.value(),f.unit(),f.basis(),f.scope(),f.sourceIds(),f.attributes());}
    static String signature(Rule r){return identity(new ObjectMapper().valueToTree(ordered(linked("predicate",r.predicate(),"description",r.description(),"subject",r.subject(),"field",r.field(),"operator",r.operator(),"expected",r.expected(),"unit",r.unit(),"parameters",r.parameters(),"sourceQuote",r.sourceQuote(),"logic",r.logic(),"children",cn.bidcheck.compat.Java8Api.toList(r.children().stream().map(SharedFactReuse::signature))))).toString());}
    static Map<String,Object> observationTarget(Rule r){
        // Only numeric comparison thresholds are independent of the value being observed.
        // Material names, certificate classes, conditions and object selectors remain in the key.
        Map<String,Object> qualifiers=new LinkedHashMap<>(r.parameters());
        // These server-owned pointers identify the requirement, not the bid-side observation.
        // Resolved subject/lot/currency/tax and other business qualifiers remain explicit above/in qualifiers.
        for(String metadata:cn.bidcheck.compat.Java8Api.listOf("obligationIdentity", "requirementSourceContext", "requirementBindings", "priceConstraintSources", "scoringParentRuleId", "readPurpose"))qualifiers.remove(metadata);
        return linked("predicate",r.predicate(),"subject",r.subject(),"field",r.field(),"unit",r.unit(),"parameters",qualifiers,
            "targetExpected",r.predicate().equals("NUMBER")?"":r.expected(),"children",cn.bidcheck.compat.Java8Api.toList(r.children().stream().map(SharedFactReuse::signature)));
    }
    static Set<String> kinds(List<Rule> rules){Set<String> kinds=new LinkedHashSet<>(cn.bidcheck.compat.Java8Api.listOf("PERSON_ROLE", "RELATION"));for(Rule r:rules){
        if(SourceScoringRules.programOnly(r))continue;
        if(SourceScoringRules.applies(r)&&cn.bidcheck.compat.Java8Api.setOf("TITLE_TIER", SourcePerformanceScore.MODE, SourceExtraPersonnelScore.MODE).contains(str(r.parameters(),"sourceScoringMode"))){kinds.addAll(kinds(SourceScoringRules.evidenceRules(r)));continue;}
        switch (r.predicate()) {case "VALIDITY":kinds.addAll(cn.bidcheck.compat.Java8Api.listOf("DATE_RANGE", "CERTIFICATE")); break;case "SAME_FIELD":kinds.addAll(cn.bidcheck.compat.Java8Api.listOf("FIELD", "NUMBER", "CERTIFICATE")); break;case "PRICE_RECONCILE":kinds.addAll(cn.bidcheck.compat.Java8Api.listOf("NUMBER", "FIELD")); break;case "DATE_RELATION":kinds.add("FIELD"); break;case "SCORE_COUNT":kinds.add("CONTRACT"); break;default:{if(ProofVerifier.KINDS.contains(r.predicate())&&!EXCLUDED.contains(r.predicate()))kinds.add(r.predicate());} break;}}return kinds;}
    private boolean shareable(Fact f){return !EXCLUDED.contains(f.kind())&&!f.sourceIds().isEmpty()&&f.sourceIds().stream().allMatch(metadata::containsKey);}
    private Map<String,Object> operationDescriptor(String origin,List<Source> candidates,List<Rule> rules){
        String project=corpus.documents.isEmpty()?"":corpus.documents.get(0).projectId();
        List<Map<String,Object>> sources=cn.bidcheck.compat.Java8Api.toList(candidates.stream().map(s->metadata.getOrDefault(s.id(),cn.bidcheck.compat.Java8Api.mapOf("id", s.id()))));
        List<Map<String,Object>> targets=cn.bidcheck.compat.Java8Api.toList(rules.stream().map(SharedFactReuse::observationTarget));
        // Extraction observes fields; it does not decide a checkpoint's threshold or outcome.
        // Key the durable operation by canonical observation targets so equivalent checks can share
        // the same source read even when their wording, id or comparison value differs.
        String artifact=identity(cn.bidcheck.compat.Java8Api.toList(targets.stream().map(target->json.valueToTree(ordered(target)).toString()).sorted()).toString());
        String operationType="EXTRACTED".equals(origin)?"TEXT_FACT_EXTRACTION":"TEXT_FACT_"+origin;
        String observationContract="EXTRACTED".equals(origin)?ExtractionContracts.VERSION:engineIdentity;
        return EvidenceOperationRepository.descriptor(project,operationType,cn.bidcheck.compat.Java8Api.listOf(),sources,targets,artifact,modelIdentity,"FACTS_ONLY",observationContract+":"+FieldCoverage.POLICY);
    }
    @SuppressWarnings("unchecked") List<Fact> lookup(List<Rule> rules,List<Source> candidates,List<Map<String,Object>> layout){
        return lookup(rules,candidates,layout,"SELECTED_SOURCES");
    }
    /** Subject lookup expands discovery beyond the last six displayed source chunks. The full
     * immutable source manifest is still required to revalidate every fact dependency. */
    List<Fact> preloadSubjects(List<Rule> rules,List<Map<String,Object>> layout){
        Set<String> subjects=new LinkedHashSet<>();
        for(Rule rule:rules)for(String selector:AgentRuleSubjects.selectors(rule)){
            subjects.addAll(PersonnelRoles.queryTerms(selector));
            if(selector.matches(".*(?:上述人员|拟派人员|项目组人员).*"))for(String role:cn.bidcheck.compat.Java8Api.listOf("项目负责人", "施工企业主要负责人", "施工部分负责人", "设计部分负责人", "专职安全员"))subjects.addAll(PersonnelRoles.queryTerms(role));
        }
        if(rules.stream().anyMatch(r->cn.bidcheck.compat.Java8Api.setOf("CONTRACT", "SCORE_COUNT", "MONTHS", "PERSON_COUNT", "PERSON_ROLE", "CERTIFICATE").contains(r.predicate())))
            for(String role:cn.bidcheck.compat.Java8Api.listOf("投标人", "牵头人", "施工单位", "设计单位", "施工成员", "设计成员"))subjects.addAll(PersonnelRoles.queryTerms(role));
        subjects.removeIf(cn.bidcheck.compat.Java8Api::isBlank);if(subjects.isEmpty())return cn.bidcheck.compat.Java8Api.listOf();
        Map<String,Fact> discovered=new LinkedHashMap<>();
        List<Fact> anchors=lookupScoped(rules,corpus.all,layout,"SUBJECT_PREREQUISITES",cn.bidcheck.compat.Java8Api.toList(subjects.stream().limit(64)));
        anchors.forEach(f->discovered.put(f.id(),f));
        Set<String> entities=new LinkedHashSet<>();
        for(Fact fact:anchors){
            if("PERSON_ROLE".equals(fact.kind())&&"CURRENT_PROJECT".equals(fact.scope())){
                addEntitySelector(entities,fact.value());addEntitySelector(entities,str(fact.attributes(),"employer"));
            }
            if("RELATION".equals(fact.kind())){
                addEntitySelector(entities,fact.subject());addEntitySelector(entities,fact.value());
                for(String field:cn.bidcheck.compat.Java8Api.listOf("object", "employer", "member", "organization"))addEntitySelector(entities,str(fact.attributes(),field));
            }
        }
        if(!entities.isEmpty())lookupScoped(rules,corpus.all,layout,"SUBJECT_ENTITY_DETAILS",cn.bidcheck.compat.Java8Api.toList(entities.stream().limit(64))).forEach(f->discovered.putIfAbsent(f.id(),f));
        Set<String> employers=new LinkedHashSet<>();
        for(Fact fact:discovered.values())for(String field:cn.bidcheck.compat.Java8Api.listOf("employer", "organization", "member"))addEntitySelector(employers,str(fact.attributes(),field));
        employers.removeAll(entities);employers.removeAll(subjects);
        if(!employers.isEmpty())lookupScoped(rules,corpus.all,layout,"SUBJECT_EMPLOYER_RELATIONS",cn.bidcheck.compat.Java8Api.toList(employers.stream().limit(64))).forEach(f->discovered.putIfAbsent(f.id(),f));
        subjectPreloadTrace=linked("policy","ROLE_TO_ENTITY_TO_EMPLOYER_SOURCE_BOUND","roleSelectors",new ArrayList<>(subjects),"entitySelectors",new ArrayList<>(entities),"employerSelectors",new ArrayList<>(employers),"anchorFacts",anchors.size(),"discoveredFacts",discovered.size(),"sourceFactIds",new ArrayList<>(discovered.keySet()),"rawOccurrencesPreserved",true,"automaticIdentityMerge",false);
        personEvidenceProfiles=PersonEvidenceProfiles.audit(cn.bidcheck.compat.Java8Api.listCopyOf(discovered.values()),corpus,layout);
        LOG.info("bidcheck.subject_profile_preload runId={} checkpointId={} roleSelectors={} entitySelectors={} employerSelectors={} anchorFacts={} discoveredFacts={}",trace("sourceRunId"),trace("checkpointId"),subjects.size(),entities.size(),employers.size(),anchors.size(),discovered.size());
        return cn.bidcheck.compat.Java8Api.listCopyOf(discovered.values());
    }
    private static void addEntitySelector(Set<String> selectors,String value){String normalized=clean(value);if(!cn.bidcheck.compat.Java8Api.isBlank(normalized)&&normalized.length()<=200&&!DomainBindings.role(normalized)&&!normalized.matches(".*[*＊×].*"))selectors.add(normalized);}
    @SuppressWarnings("unchecked") List<Fact> lookup(List<Rule> rules,List<Source> candidates,List<Map<String,Object>> layout,String phase){
        return lookupScoped(rules,candidates,layout,phase,cn.bidcheck.compat.Java8Api.listOf());
    }
    @SuppressWarnings("unchecked") private List<Fact> lookupScoped(List<Rule> rules,List<Source> candidates,List<Map<String,Object>> layout,String phase,List<String> subjects){

        if(rules.isEmpty()||rules.stream().allMatch(r->"UNSUPPORTED".equals(r.predicate()))){
            LOG.info("bidcheck.fact_lookup_skip runId={} checkpointId={} phase={} reason=NO_EXECUTABLE_EVIDENCE_TARGET sources={}",trace("sourceRunId"),trace("checkpointId"),phase,candidates.size());
            return cn.bidcheck.compat.Java8Api.listOf();
        }
        // Visual recovery uses the same corpus but has its own verified evidence channel.
        // Send only complete text source manifests to the canonical fact index; a V alias
        // without text metadata would otherwise force a needless legacy-history fallback.
        candidates=cn.bidcheck.compat.Java8Api.toList(candidates.stream().filter(source->metadata.containsKey(source.id())));
        if(candidates.isEmpty())return cn.bidcheck.compat.Java8Api.listOf();
        boolean subjectLookup=!subjects.isEmpty();
        Set<String> requestedKinds=subjectLookup?new LinkedHashSet<>("SUBJECT_ENTITY_DETAILS".equals(phase)?cn.bidcheck.compat.Java8Api.listOf("PERSON_ROLE", "RELATION", "CERTIFICATE", "MONTHS", "FIELD", "NUMBER", "CONTRACT"):cn.bidcheck.compat.Java8Api.listOf("PERSON_ROLE", "RELATION")):kinds(rules),selected=new LinkedHashSet<>(cn.bidcheck.compat.Java8Api.toList(candidates.stream().map(Source::id)));
        List<Map<String,Object>> entries=new ArrayList<>();
        if(!subjectLookup&&context.get("evidenceOperationLookup") instanceof Function<?,?>){
            Map<String,Object> descriptor=operationDescriptor("EXTRACTED",candidates,rules);String operationKey=EvidenceOperationRepository.key(descriptor);
            Object raw=((Function<Map<String,Object>,?>)((Function<?,?>) (context.get("evidenceOperationLookup")))).apply(linked("operationKey",operationKey,"descriptor",descriptor));
            if(raw instanceof Map<?,?>){Map<String,Object> cached=json.convertValue(((Map<?,?>) (raw)),Map.class);List<Map<String,Object>> cachedSources=RuleEvaluator.maps(cached.get("sources")),cachedFields=RuleEvaluator.maps(cached.get("fieldCoverage"));
                for(Map<String,Object> fact:RuleEvaluator.maps(cached.get("facts")))entries.add(linked("fact",fact,"sources",cachedSources,"engineVersion",VERSION,"operationIdentity",operationKey,
                    "fieldCoverage",cachedFields,"fieldCoverageSources",cachedSources,"coverageSources",cachedSources,"coverageComplete",false,"recordType","FACT"));
                if(RuleEvaluator.maps(cached.get("facts")).isEmpty()&&!cachedFields.isEmpty())entries.add(linked("sources",cachedSources,"engineVersion",VERSION,"operationIdentity",operationKey,
                    "fieldCoverage",cachedFields,"fieldCoverageSources",cachedSources,"coverageSources",cachedSources,"coverageComplete",false,"recordType","FIELD_COVERAGE"));
            }
        }
        boolean completePageSet=false;
        List<String> observationKeys=cn.bidcheck.compat.Java8Api.toList(rules.stream().map(r->FieldCoverage.key(observationTarget(r))).distinct());
        if(context.get("sharedFactLookup") instanceof Function<?,?>){
            Function<Map<String,Object>,?> query=(Function<Map<String,Object>,?>)context.get("sharedFactLookup");
            Map<String,Object> request=linked("lookupMode",subjectLookup?"CANONICAL_SUBJECT":"CANONICAL_SOURCE","subjects",subjects,"sources",cn.bidcheck.compat.Java8Api.toList(selected.stream().map(metadata::get).filter(Objects::nonNull)),"modelIdentity",modelIdentity,"sourceIds",new ArrayList<>(selected),"documentIds",cn.bidcheck.compat.Java8Api.toList(corpus.documents.stream().map(Document::id)),"factKinds",new ArrayList<>(requestedKinds),"limit",LIMIT,"fieldCoverageLimit",LIMIT,"engineVersion",VERSION,"engineIdentity",engineIdentity,"observationPolicy",FieldCoverage.POLICY,"includeFieldCoverage",!subjectLookup,"observationKeys",cn.bidcheck.compat.Java8Api.toList(rules.stream().map(r->FieldCoverage.key(observationTarget(r))).distinct()),"runId",context.get("sourceRunId"),"checkpointId",context.get("checkpointId"),"lookupPhase",phase);
            request.put("paged",true);
            Set<String> visited=new HashSet<>();
            while(true){
                List<Map<String,Object>> page=RuleEvaluator.maps(query.apply(request));
                entries.addAll(page);
                if(page.isEmpty()){
                    if(!visited.isEmpty())markLookupTruncation(true,true,linked("sourceIds",new ArrayList<>(selected),"observationKeys",observationKeys,"reason","MISSING_CONTINUATION_RECEIPT"));
                    break;
                }
                Map<String,Object> receipt=page.get(0);
                String cursor=str(receipt,"nextCursor");
                if(cursor.isEmpty()){
                    completePageSet=Boolean.TRUE.equals(receipt.get("lookupPagesComplete"));
                    if(!visited.isEmpty()&&!completePageSet)markLookupTruncation(true,true,linked("sourceIds",new ArrayList<>(selected),"observationKeys",observationKeys,"reason","MISSING_CONTINUATION_RECEIPT"));
                    if(completePageSet&&!subjectLookup&&selected.equals(new HashSet<>(ProofVerifier.strings(receipt.get("coveredSourceIds")))))
                        completeSourceRead(observationKeys,selected);
                    break;
                }
                if(!visited.add(cursor)){
                    markLookupTruncation(true,true,linked("sourceIds",new ArrayList<>(selected),"observationKeys",observationKeys,"reason","REPEATED_LOOKUP_CURSOR"));
                    break;
                }
                request.put("cursor",cursor);
            }
        }
        long suppliedFacts=entries.stream().filter(row->row.get("fact") instanceof Map<?,?>).map(row->json.valueToTree(ordered(row.get("fact"))).toString()).distinct().count();
        List<Map<String,Object>> processingAudit=new ArrayList<>();
        entries.addAll(processingEntries(requestedKinds,candidates,layout,(int)Math.max(0,120-suppliedFacts),processingAudit));
        Map<String,Object> lookupScope=linked("phase",phase,"sourceIds",new ArrayList<>(selected),"factKinds",new ArrayList<>(requestedKinds),"subjects",subjects,"observationKeys",cn.bidcheck.compat.Java8Api.toList(rules.stream().map(r->FieldCoverage.key(observationTarget(r))).distinct()));
        for(Map<String,Object> entry:entries){Map<String,Object> detail=new LinkedHashMap<>(lookupScope);if(entry.get("lookupTruncation") instanceof Map<?,?>)((Map<?,?>) (entry.get("lookupTruncation"))).forEach((k,v)->detail.put(String.valueOf(k),v));
            markLookupTruncation(Boolean.TRUE.equals(entry.get("factLookupTruncated")),Boolean.TRUE.equals(entry.get("fieldLookupTruncated")),detail);}
        if(!completePageSet&&entries.size()>LIMIT*8){List<Map<String,Object>> omitted=entries.subList(LIMIT*8,entries.size());Map<String,Object> detail=new LinkedHashMap<>(lookupScope);detail.put("reason","SHARED_ENTRY_SCAN_CAPACITY");
            markLookupTruncation(omitted.stream().anyMatch(row->row.get("fact") instanceof Map<?,?>),omitted.stream().anyMatch(row->!RuleEvaluator.maps(row.get("fieldCoverage")).isEmpty()),detail);}
        Map<String,Fact> accepted=new LinkedHashMap<>(),acceptedByOccurrence=new HashMap<>();Map<String,Set<String>> acceptedByBatch=new HashMap<>();Map<String,Map<String,Fact>> acceptedFactsByBatch=new HashMap<>();Map<String,Map<String,Object>> batchReceipts=new HashMap<>();Map<String,FieldCandidate> completeFields=new LinkedHashMap<>(),pendingFields=new LinkedHashMap<>();
        List<Map<String,Object>> candidateAudit=new ArrayList<>();int metadataEntries=0,fieldOnlyEntries=0;
        ProofVerifier batchVerifier = ContractRecordBindings.verifier(corpus, rules, candidates);
        // Facts and field reads have independent capacities. A full fact result must not discard
        // READ_NOT_FOUND receipts or receipt dependencies returned after those facts.
        for(Map<String,Object> entry:entries.subList(0,completePageSet?entries.size():Math.min(entries.size(),LIMIT*8))){
            if("LOOKUP_METADATA".equals(str(entry,"recordType"))){metadataEntries++;continue;}
            if(!VERSION.equals(str(entry,"engineVersion"))&&!FieldCoverage.POLICY.equals(str(entry,"observationPolicy"))){auditCandidate(candidateAudit,entry,"SKIPPED","ENGINE_OR_OBSERVATION_POLICY_MISMATCH",cn.bidcheck.compat.Java8Api.listOf(),"");continue;}
            String receiptBatch=str(entry,"batchId");if(cn.bidcheck.compat.Java8Api.isBlank(receiptBatch))receiptBatch=str(entry,"operationIdentity");
            Map<String,Map<String,Object>> fieldSources=new HashMap<>();for(Map<String,Object> source:RuleEvaluator.maps(entry.get("fieldCoverageSources")))fieldSources.put(str(source,"id"),source);
            List<Map<String,Object>> scopedFields=cn.bidcheck.compat.Java8Api.toList(RuleEvaluator.maps(entry.get("fieldCoverage")).stream().filter(row->{List<String> ids=ProofVerifier.strings(row.get("sourceIds"));return validFieldReceipt(row)&&!ids.isEmpty()&&selected.containsAll(ids)&&ids.stream().allMatch(id->sourceMatches(id,fieldSources.get(id)));}));
            if(!cn.bidcheck.compat.Java8Api.isBlank(receiptBatch))for(Map<String,Object> receipt:scopedFields){boolean occurrenceReferences="OCCURRENCE_ID".equals(str(entry,"factReferenceType"));Map<String,Object> semantic=new LinkedHashMap<>(receipt);for(String provenance:cn.bidcheck.compat.Java8Api.listOf("reusedFromBatchId", "reusedObservation", "observedInCurrentRun"))semantic.remove(provenance);
                String key=(occurrenceReferences?"occurrence:":receiptBatch+":")+json.valueToTree(ordered(semantic));Map<String,FieldCandidate> bucket=FieldCoverage.completeRead(receipt)?completeFields:pendingFields;
                if(completePageSet||bucket.size()<LIMIT)bucket.putIfAbsent(key,new FieldCandidate(receiptBatch,receipt,occurrenceReferences));
                else if(!bucket.containsKey(key)&&FieldCoverage.completeRead(receipt)){Map<String,Object> detail=new LinkedHashMap<>(lookupScope);detail.put("reason","SHARED_FIELD_RECEIPT_CAPACITY");markLookupTruncation(false,true,detail);}
            }
            if("FIELD_COVERAGE".equals(str(entry,"recordType"))){fieldOnlyEntries++;continue;}
            if(!(entry.get("fact") instanceof Map<?,?>)){rejectedFacts++;auditCandidate(candidateAudit,entry,"REJECTED","FACT_ROW_MISSING",cn.bidcheck.compat.Java8Api.listOf(),"");continue;}
            JsonNode node=json.valueToTree(((Map<?,?>) (entry.get("fact"))));String kind=node.path("kind").asText();List<String> refs=ProofVerifier.strings(((Map<?,?>) (entry.get("fact"))).get("sourceIds"));
            Map<String,Map<String,Object>> provided=new HashMap<>();for(Map<String,Object> source:RuleEvaluator.maps(entry.get("sources")))provided.put(str(source,"id"),source);
            if(EXCLUDED.contains(kind)||!requestedKinds.contains(kind)){auditCandidate(candidateAudit,entry,"SKIPPED",EXCLUDED.contains(kind)?"FACT_KIND_NOT_SHAREABLE":"FACT_KIND_NOT_REQUESTED",cn.bidcheck.compat.Java8Api.listOf(),"");continue;}
            if(refs.isEmpty()){rejectedFacts++;auditCandidate(candidateAudit,entry,"REJECTED","SOURCE_REFERENCE_EMPTY",cn.bidcheck.compat.Java8Api.listOf(),"");continue;}
            if(!selected.containsAll(refs)){auditCandidate(candidateAudit,entry,"SKIPPED","SOURCE_NOT_SELECTED",cn.bidcheck.compat.Java8Api.listOf(),"");continue;}
            List<Map<String,Object>> sourceFailures=new ArrayList<>();for(String ref:refs){String reason=sourceMismatchReason(ref,provided.get(ref));if(!cn.bidcheck.compat.Java8Api.isBlank(reason))sourceFailures.add(linked("sourceId",ref,"code",reason));}
            if(!sourceFailures.isEmpty()){rejectedFacts++;auditCandidate(candidateAudit,entry,"REJECTED","SOURCE_MANIFEST_MISMATCH",sourceFailures,"");continue;}
            // Re-read current original text and current table bindings. Persisted PASS/verified flags have no authority.
            ProofVerifier.Verified verified=batchVerifier.verify(json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(((Map<?,?>) (entry.get("fact")))))),candidates,layout);
            if(verified.facts().size()!=1||!verified.rejected().isEmpty()){rejectedFacts++;auditCandidate(candidateAudit,entry,"REJECTED","PROOF_VERIFICATION_FAILED",verified.rejected(),"");continue;}
            Fact fact=canonical(verified.facts().get(0));if(!shareable(fact)){rejectedFacts++;auditCandidate(candidateAudit,entry,"REJECTED","CANONICAL_FACT_NOT_SHAREABLE",cn.bidcheck.compat.Java8Api.listOf(),fact.id());continue;}
            if("MATERIAL".equals(kind)&&rules.stream().filter(r->"MATERIAL".equals(r.predicate())).noneMatch(r->FieldCoverage.matches(observationTarget(r),value(fact)))){
                auditCandidate(candidateAudit,entry,"SKIPPED","DIFFERENT_MATERIAL_TARGET",cn.bidcheck.compat.Java8Api.listOf(),fact.id());continue;
            }
            if(subjectLookup&&!subjectRelevant(fact,subjects)){auditCandidate(candidateAudit,entry,"SKIPPED","SUBJECT_NOT_REQUESTED",cn.bidcheck.compat.Java8Api.listOf(),fact.id());continue;}
            if(!completePageSet&&accepted.size()>=LIMIT&&!accepted.containsKey(fact.id())){Map<String,Object> detail=new LinkedHashMap<>(lookupScope);detail.put("reason","SHARED_FACT_CAPACITY");markLookupTruncation(true,false,detail);auditCandidate(candidateAudit,entry,"SKIPPED","SHARED_FACT_CAPACITY",cn.bidcheck.compat.Java8Api.listOf(),fact.id());continue;}
            auditCandidate(candidateAudit,entry,accepted.containsKey(fact.id())?"DUPLICATE":"ACCEPTED",accepted.containsKey(fact.id())?"CANONICAL_FACT_ALREADY_ACCEPTED":"SOURCE_AND_TYPED_PROOF_VERIFIED",cn.bidcheck.compat.Java8Api.listOf(),fact.id());accepted.put(fact.id(),fact);reused.add(fact.id());
            if(!cn.bidcheck.compat.Java8Api.isBlank(str(entry,"occurrenceId")))acceptedByOccurrence.putIfAbsent(str(entry,"occurrenceId"),fact);
            String batch=str(entry,"batchId");if(cn.bidcheck.compat.Java8Api.isBlank(batch))batch=str(entry,"operationIdentity");
            acceptedByBatch.computeIfAbsent(batch,x->new HashSet<>()).add(node.path("id").asText());
            acceptedFactsByBatch.computeIfAbsent(batch,x->new HashMap<>()).put(node.path("id").asText(),fact);
            if(!Boolean.FALSE.equals(entry.get("coverageComplete"))&&entry.get("extractionCoverage") instanceof Map<?,?>){Map<String,Object> receipt=json.convertValue(((Map<?,?>) (entry.get("extractionCoverage"))),Map.class);List<String> allSources=ProofVerifier.strings(receipt.get("sourceIds"));
                for(Map<String,Object> source:RuleEvaluator.maps(entry.get("coverageSources")))provided.putIfAbsent(str(source,"id"),source);
                if(!cn.bidcheck.compat.Java8Api.isBlank(batch)&&!allSources.isEmpty()&&selected.containsAll(allSources)&&allSources.stream().allMatch(id->sourceMatches(id,provided.get(id))))batchReceipts.put(batch,receipt);
            }
        }
        batchReceipts.forEach((batch,receipt)->{List<String> ids=ProofVerifier.strings(receipt.get("factIds"));if(!ids.isEmpty()&&acceptedByBatch.getOrDefault(batch,cn.bidcheck.compat.Java8Api.setOf()).containsAll(ids)&&validReceipt(receipt))receipts.put(batch,receipt);});
        Map<String,Map<String,Object>> adoptedFields=new LinkedHashMap<>();
        List<FieldCandidate> fieldCandidates=new ArrayList<>(completeFields.values());fieldCandidates.addAll(cn.bidcheck.compat.Java8Api.toList(pendingFields.values().stream().limit(completePageSet?Long.MAX_VALUE:Math.max(0,LIMIT-completeFields.size()))));
        for(FieldCandidate candidate:fieldCandidates){String batch=candidate.batch();Map<String,Object> receipt=candidate.receipt();List<String> ids=ProofVerifier.strings(receipt.get("factIds"));Map<String,Fact> dependencies=candidate.occurrenceReferences()?acceptedByOccurrence:acceptedFactsByBatch.getOrDefault(batch,cn.bidcheck.compat.Java8Api.mapOf());
            if(ids.size()>120||ProofVerifier.strings(receipt.get("sourceIds")).size()>128||!dependencies.keySet().containsAll(ids)||"EXTRACTED".equals(str(receipt,"status"))&&ids.isEmpty())continue;
            Map<String,Object> target=(Map<String,Object>)receipt.get("target");List<String> sourceIds=ProofVerifier.strings(receipt.get("sourceIds"));
            if(ids.stream().anyMatch(id->!sourceIds.containsAll(dependencies.get(id).sourceIds())||!FieldCoverage.matches(target,value(dependencies.get(id)))))continue;
            fieldReceipts.put(batch+":"+observationScope(receipt),receipt);observeCached(receipt);
            String localScope=observationScope(receipt);boolean alreadyObservedHere=currentObservationKeys.contains(localScope)&&FieldCoverage.completeRead(observations.getOrDefault(localScope,cn.bidcheck.compat.Java8Api.mapOf()));
            if(FieldCoverage.completeRead(receipt)&&!alreadyObservedHere){
                Map<String,Object> adopted=new LinkedHashMap<>(receipt);adopted.put("factIds",cn.bidcheck.compat.Java8Api.toList(ids.stream().map(id->dependencies.get(id).id()).distinct()));
                adopted.put("reusedObservation",true);adopted.put("observedInCurrentRun",false);
                adopted.put("reusedFromBatchId",cn.bidcheck.compat.Java8Api.isBlank(str(receipt,"reusedFromBatchId"))?batch:str(receipt,"reusedFromBatchId"));
                adoptedFields.put(identity(json.valueToTree(ordered(adopted)).toString()),adopted);
            }
        }
        List<Fact> facts=cn.bidcheck.compat.Java8Api.listCopyOf(accepted.values());
        // Register use of the already validated receipt under the caller's current run manifest.
        // This is a dependency on the original read, never a new extraction or completeness claim.
        publishLookupAudit(phase,subjectLookup?"CANONICAL_SUBJECT":"CANONICAL_SOURCE",subjects,requestedKinds,selected.size(),entries.size(),metadataEntries,fieldOnlyEntries,facts,candidateAudit,processingAudit);
        publishAdoption(facts,cn.bidcheck.compat.Java8Api.listCopyOf(adoptedFields.values()),candidates,layout);return facts;
    }
    private void auditCandidate(List<Map<String,Object>> audit,Map<String,Object> entry,String outcome,String reason,List<Map<String,Object>> failures,String canonicalId){
        Map<String,Object> fact=entry.get("fact") instanceof Map<?,?>?json.convertValue(((Map<?,?>) (entry.get("fact"))),Map.class):cn.bidcheck.compat.Java8Api.mapOf();
        audit.add(linked("candidateId",str(fact,"id"),"canonicalFactId",canonicalId,"occurrenceId",str(entry,"occurrenceId"),"batchId",str(entry,"batchId"),"operationIdentity",str(entry,"operationIdentity"),"recordType",str(entry,"recordType"),"engineVersion",str(entry,"engineVersion"),"observationPolicy",str(entry,"observationPolicy"),"kind",str(fact,"kind"),"field",str(fact,"field"),"sourceIds",ProofVerifier.strings(fact.get("sourceIds")),"outcome",outcome,"reason",reason,"failures",failures));
    }
    @SuppressWarnings("unchecked") private void publishLookupAudit(String phase,String mode,List<String> subjects,Set<String> kinds,int sources,int entries,int metadataCount,int fieldOnlyCount,List<Fact> facts,List<Map<String,Object>> candidates,List<Map<String,Object>> processingCandidates){
        if(!(context.get("sharedFactAdoptionAudit") instanceof Consumer<?>))return;
        Map<String,Long> counts=new LinkedHashMap<>();for(String outcome:cn.bidcheck.compat.Java8Api.listOf("ACCEPTED", "DUPLICATE", "SKIPPED", "REJECTED"))counts.put(outcome,candidates.stream().filter(c->outcome.equals(c.get("outcome"))).count());
        Map<String,Object> event=linked("policy","SHARED_FACT_ADOPTION_AUDIT_V1","lookupPhase",phase,"lookupMode",mode,"subjects",cn.bidcheck.compat.Java8Api.listCopyOf(subjects),"requestedKinds",cn.bidcheck.compat.Java8Api.listCopyOf(kinds),"selectedSourceCount",sources,"suppliedEntryCount",entries,"metadataEntryCount",metadataCount,"fieldOnlyEntryCount",fieldOnlyCount,"unexaminedEntryCount",Math.max(0,entries-metadataCount-fieldOnlyCount-candidates.size()),"candidateCount",candidates.size(),"outcomeCounts",counts,"acceptedFactIds",cn.bidcheck.compat.Java8Api.toList(facts.stream().map(Fact::id)),"candidates",candidates,"processingRejectedCount",processingCandidates.size(),"processingCandidates",processingCandidates,"rejectedInThisLookup",counts.get("REJECTED")+processingCandidates.size(),"rawEvidenceStoredInLedger",true);
        String auditId=identity(json.valueToTree(ordered(event)).toString());event.put("auditId",auditId);boolean repeated=!emittedLookupAudits.add(auditId);event.put("repeated",repeated);if(repeated){event.put("sameAsAuditId",auditId);event.put("candidates",cn.bidcheck.compat.Java8Api.listOf());event.put("processingCandidates",cn.bidcheck.compat.Java8Api.listOf());}
        ((Consumer<Map<String,Object>>)((Consumer<?>) (context.get("sharedFactAdoptionAudit")))).accept(event);
        LOG.info("bidcheck.fact_adoption_audit runId={} checkpointId={} phase={} mode={} auditId={} candidates={} accepted={} duplicate={} skipped={} rejected={} repeated={}",trace("sourceRunId"),trace("checkpointId"),phase,mode,auditId,candidates.size(),counts.get("ACCEPTED"),counts.get("DUPLICATE"),counts.get("SKIPPED"),counts.get("REJECTED"),repeated);
    }
    private static boolean subjectRelevant(Fact fact,List<String> subjects){
        Set<String> values=new HashSet<>(cn.bidcheck.compat.Java8Api.listOf(clean(fact.subject()), clean(fact.value())));
        if("RELATION".equals(fact.kind()))for(String key:cn.bidcheck.compat.Java8Api.listOf("object", "subject", "employer", "member", "organization", "leftRole", "rightRole"))values.add(clean(str(fact.attributes(),key)));
        return subjects.stream().map(VerificationTypes::clean).anyMatch(values::contains);
    }
    @SuppressWarnings("unchecked") private void publishAdoption(List<Fact> facts,List<Map<String,Object>> fields,List<Source> candidates,List<Map<String,Object>> layout){
        if(!(context.get("sharedFactPublisher") instanceof Consumer<?>))return;
        int suppliedFacts=facts.size(),suppliedReads=fields.size();
        fields=cn.bidcheck.compat.Java8Api.toList(fields.stream().filter(field->!adoptedReads.contains(adoptionReadKey(field))));
        Map<String,Fact> byId=new LinkedHashMap<>();facts.forEach(f->byId.put(f.id(),f));Set<String> included=new HashSet<>();List<AdoptionPart> parts=new ArrayList<>();AdoptionPart current=new AdoptionPart();
        // Lookup has separate fact/receipt limits. Persist each receipt with its entire dependency
        // set, instead of attaching all 240 lookup facts to every 120-fact ledger batch.
        for(Map<String,Object> field:fields){List<Fact> required=cn.bidcheck.compat.Java8Api.toList(ProofVerifier.strings(field.get("factIds")).stream().map(byId::get));Set<String> refs=new LinkedHashSet<>(ProofVerifier.strings(field.get("sourceIds")));required.forEach(f->refs.addAll(f.sourceIds()));
            if(!current.fits(required,refs,true)){parts.add(current);current=new AdoptionPart();}
            current.add(required,refs);current.fields.add(field);required.forEach(f->included.add(f.id()));
        }
        for(Fact fact:facts)if(!included.contains(fact.id())&&!adoptedFacts.contains(fact.id())){if(!current.fits(cn.bidcheck.compat.Java8Api.listOf(fact),new HashSet<>(fact.sourceIds()),false)){parts.add(current);current=new AdoptionPart();}current.add(cn.bidcheck.compat.Java8Api.listOf(fact),fact.sourceIds());}
        if(!current.facts.isEmpty()||!current.fields.isEmpty())parts.add(current);
        for(AdoptionPart partition:parts){List<Map<String,Object>> part=cn.bidcheck.compat.Java8Api.listCopyOf(partition.fields);List<Map<String,Object>> rows=cn.bidcheck.compat.Java8Api.toList(partition.facts.values().stream().map(f->{Map<String,Object> row=new LinkedHashMap<>(value(f));row.put("id",f.id());return row;}));
            String origin=part.isEmpty()?"REUSED":"REUSED_OBSERVATION";Map<String,Object> descriptor=operationDescriptor(origin,cn.bidcheck.compat.Java8Api.toList(candidates.stream().filter(s->partition.sources.contains(s.id()))),cn.bidcheck.compat.Java8Api.listOf());String requestKey=EvidenceOperationRepository.key(descriptor);String operation=identity(requestKey+json.valueToTree(ordered(rows))+json.valueToTree(ordered(part)));if(published.contains(operation))continue;
            ((Consumer<Map<String,Object>>)((Consumer<?>) (context.get("sharedFactPublisher")))).accept(linked("facts",rows,"sources",cn.bidcheck.compat.Java8Api.toList(partition.sources.stream().map(metadata::get).filter(Objects::nonNull)),"fieldCoverage",part,
                "operationIdentity",operation,"evidenceOperationDescriptor",descriptor,"engineVersion",VERSION,"observationPolicy",FieldCoverage.POLICY,"modelIdentity",modelIdentity,"origin",origin,
                "evidenceOperationKey",requestKey,"newModelObservation",false,"layoutIdentity",context.getOrDefault("layoutIdentity",""),"layoutCells",layout));published.add(operation);sharedPublishes++;
            adoptedFacts.addAll(partition.facts.keySet());part.forEach(field->adoptedReads.add(adoptionReadKey(field)));
        }
        LOG.info("bidcheck.fact_adoption runId={} checkpointId={} suppliedFacts={} suppliedReads={} writes={} newReads={} alreadyUsedFacts={}",trace("sourceRunId"),trace("checkpointId"),suppliedFacts,suppliedReads,parts.size(),fields.size(),suppliedFacts-parts.stream().flatMap(p->p.facts.keySet().stream()).distinct().count());
    }
    private String adoptionReadKey(Map<String,Object> field){Map<String,Object> semantic=new LinkedHashMap<>(field);for(String provenance:cn.bidcheck.compat.Java8Api.listOf("reusedFromBatchId", "reusedObservation", "observedInCurrentRun"))semantic.remove(provenance);return identity(json.valueToTree(ordered(semantic)).toString());}
    private static final class AdoptionPart {
        final Map<String,Fact> facts=new LinkedHashMap<>();final Set<String> sources=new LinkedHashSet<>();final List<Map<String,Object>> fields=new ArrayList<>();
        boolean fits(List<Fact> required,Set<String> refs,boolean field){Set<String> ids=new HashSet<>(facts.keySet()),allSources=new HashSet<>(sources);required.forEach(f->ids.add(f.id()));allSources.addAll(refs);return ids.size()<=120&&allSources.size()<=128&&(!field||fields.size()<120);}
        void add(List<Fact> required,Collection<String> refs){required.forEach(f->facts.putIfAbsent(f.id(),f));sources.addAll(refs);}
    }
    /** Page/window facts have a separate parser identity. They supply candidates, never extraction coverage. */
    private List<Map<String,Object>> processingEntries(Set<String> kinds,List<Source> candidates,List<Map<String,Object>> layout,int limit,List<Map<String,Object>> audit){
        if(limit==0)return cn.bidcheck.compat.Java8Api.listOf();List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> occurrence:RuleEvaluator.maps(context.get("processingOccurrences"))){
            if(out.size()>=limit)break;
            if(!(occurrence.get("fact") instanceof Map<?,?>))continue;
            Map<String,Object> fact=json.convertValue(((Map<?,?>) (occurrence.get("fact"))),Map.class);String kind=str(fact,"kind");
            if(EXCLUDED.contains(kind)||!kinds.contains(kind))continue;
            List<String> refs=ProofVerifier.strings(fact.get("sourceIds"));
            if(refs.isEmpty()||refs.size()>12||refs.stream().distinct().count()!=refs.size()){rejectedFacts++;auditCandidate(audit,occurrence,"REJECTED","PROCESSING_SOURCE_REFERENCE_INVALID",cn.bidcheck.compat.Java8Api.listOf(),"");continue;}
            Map<String,Map<String,Object>> stored=new HashMap<>();boolean duplicate=false;
            for(Map<String,Object> source:RuleEvaluator.maps(occurrence.get("sources")))if(stored.putIfAbsent(str(source,"id"),source)!=null)duplicate=true;
            Map<String,String> remap=new LinkedHashMap<>();List<Source> narrow=new ArrayList<>();List<Map<String,Object>> failures=new ArrayList<>();
            if(!duplicate)for(String ref:refs){
                Map<String,Object> original=stored.get(ref);Source current=matchingProcessingSource(original,candidates,str(occurrence,"projectId"));
                if(current==null){failures.add(linked("sourceId",ref,"code",processingMismatchReason(original,candidates,str(occurrence,"projectId"))));break;}
                Evidence evidence=json.convertValue(original.get("evidence"),Evidence.class);
                remap.put(ref,current.id());narrow.add(new Source(ref,evidence,current.opening(),current.index(),current.historical(),current.documentHash(),current.documentVersion(),current.scopeContext()));
            }
            if(remap.size()!=refs.size()){rejectedFacts++;auditCandidate(audit,occurrence,"REJECTED",duplicate?"PROCESSING_SOURCE_MANIFEST_DUPLICATED":"PROCESSING_SOURCE_REMAP_FAILED",failures,"");continue;}
            // matchingProcessingSource has already checked the document, revision, parser and exact
            // substring. These persisted source IDs are aliases, not entries in the current corpus.
            // Validate only the narrow quotations here: granting the full-page index would either
            // discard the aliases or let surrounding text repair an ungrounded persisted claim.
            // lookup revalidates the remapped fact with corpus.byId before accepting or publishing it.
            ProofVerifier.Verified verified=new ProofVerifier().verify(json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(fact))),narrow,layout);
            if(verified.facts().size()!=1||!verified.rejected().isEmpty()){rejectedFacts++;auditCandidate(audit,occurrence,"REJECTED","PROCESSING_NARROW_PROOF_REJECTED",verified.rejected(),"");continue;}
            Map<String,Object> mapped=new LinkedHashMap<>(value(verified.facts().get(0)));mapped.put("sourceIds",cn.bidcheck.compat.Java8Api.toList(refs.stream().map(remap::get).distinct()));
            mapped.put("id","PF"+identity(str(occurrence,"occurrenceId")+json.valueToTree(ordered(mapped))).substring(0,24));
            List<String> mappedIds=ProofVerifier.strings(mapped.get("sourceIds"));
            out.add(linked("fact",mapped,"sources",cn.bidcheck.compat.Java8Api.toList(mappedIds.stream().map(metadata::get)),"engineVersion",VERSION,
                "operationIdentity","processing:"+str(occurrence,"occurrenceId"),"coverageComplete",false));
        }return out;
    }
    /** Diagnostic mirror of the alias remap; never changes which source may be accepted. */
    private String processingMismatchReason(Map<String,Object> provided,List<Source> candidates,String project){
        if(provided==null)return "PROCESSING_SOURCE_MANIFEST_MISSING";
        if(cn.bidcheck.compat.Java8Api.isBlank(project)||!project.equals(str(provided,"projectId")))return "PROCESSING_SOURCE_PROJECT_MISMATCH";
        try{
            JsonNode node=json.valueToTree(provided.get("evidence"));if(!node.isObject()||!node.path("page").isIntegralNumber())return "PROCESSING_SOURCE_EVIDENCE_MALFORMED";
            Evidence prior=json.convertValue(provided.get("evidence"),Evidence.class);
            if(prior==null||prior.quote()==null||cn.bidcheck.compat.Java8Api.isBlank(prior.quote())||prior.quote().length()>10000)return "PROCESSING_SOURCE_QUOTE_INVALID";
            Object version=provided.get("documentVersion");if(!(version instanceof Number)||((Number) (version)).doubleValue()!=((Number) (version)).intValue())return "PROCESSING_SOURCE_VERSION_INVALID";
            List<Source> samePage=cn.bidcheck.compat.Java8Api.toList(candidates.stream().filter(s->Objects.equals(s.evidence().documentId(),prior.documentId())&&Objects.equals(s.evidence().documentName(),prior.documentName())&&s.evidence().page()==prior.page()&&Objects.equals(s.evidence().locationType(),prior.locationType())));
            if(samePage.isEmpty())return "PROCESSING_SOURCE_PAGE_NOT_SELECTED";
            for(String key:cn.bidcheck.compat.Java8Api.listOf("projectId", "documentHash", "documentVersion", "parserFingerprint")){
                Object expected=key.equals("projectId")?project:key.equals("documentVersion")?((Number) (version)).intValue():str(provided,key);
                samePage=cn.bidcheck.compat.Java8Api.toList(samePage.stream().filter(s->{java.util.Map<java.lang.String, java.lang.Object> current=metadata.get(s.id());return current!=null&&(key.equals("documentVersion")?RunSession.number(current.get(key),-1)==((Number) (version)).intValue():Objects.equals(str(current,key),expected));}));
                if(samePage.isEmpty())switch (key) {case "projectId":return "PROCESSING_SOURCE_PROJECT_MISMATCH";case "documentHash":return "PROCESSING_SOURCE_HASH_MISMATCH";case "documentVersion":return "PROCESSING_SOURCE_VERSION_MISMATCH";default:return "PROCESSING_SOURCE_PARSER_MISMATCH";}
            }
            return "PROCESSING_SOURCE_QUOTE_NOT_CONTAINED";
        }catch(IllegalArgumentException malformed){return "PROCESSING_SOURCE_EVIDENCE_MALFORMED";}
    }
    private Source matchingProcessingSource(Map<String,Object> provided,List<Source> candidates,String project){
        if(provided==null||cn.bidcheck.compat.Java8Api.isBlank(project)||!project.equals(str(provided,"projectId")))return null;
        try{
            JsonNode evidenceNode=json.valueToTree(provided.get("evidence"));
            if(!evidenceNode.isObject()||!evidenceNode.path("page").isIntegralNumber())return null;
            Evidence prior=json.convertValue(provided.get("evidence"),Evidence.class);
            if(prior==null||prior.quote()==null||cn.bidcheck.compat.Java8Api.isBlank(prior.quote())||prior.quote().length()>10000)return null;
            Object version=provided.get("documentVersion");if(!(version instanceof Number)||((Number) (version)).doubleValue()!=((Number) (version)).intValue())return null;
            return candidates.stream().filter(source->{Map<String,Object> current=metadata.get(source.id());if(current==null)return false;
                Evidence now=source.evidence();return project.equals(str(current,"projectId"))&&str(current,"documentHash").equals(str(provided,"documentHash"))
                    &&str(current,"parserFingerprint").equals(str(provided,"parserFingerprint"))&&RunSession.number(current.get("documentVersion"),-1)==((Number) (version)).intValue()
                    &&Objects.equals(prior.documentId(),now.documentId())&&Objects.equals(prior.documentName(),now.documentName())&&prior.page()==now.page()
                    &&Objects.equals(prior.locationType(),now.locationType())&&now.quote().contains(prior.quote());
            }).min(Comparator.comparingInt(source->source.evidence().quote().length())).orElse(null);
        }catch(IllegalArgumentException malformed){return null;}
    }
    private boolean sourceMatches(String id,Map<String,Object> provided){
        return cn.bidcheck.compat.Java8Api.isBlank(sourceMismatchReason(id,provided));
    }
    private String sourceMismatchReason(String id,Map<String,Object> provided){
        Map<String,Object> current=metadata.get(id);if(current==null)return "CURRENT_SOURCE_NOT_FOUND";if(provided==null)return "SOURCE_MANIFEST_NOT_PROVIDED";
        for(String key:cn.bidcheck.compat.Java8Api.listOf("id", "projectId", "documentHash", "parserFingerprint"))if(!str(current,key).equals(str(provided,key)))switch (key) {case "id":return "SOURCE_ID_MISMATCH";case "projectId":return "SOURCE_PROJECT_MISMATCH";case "documentHash":return "SOURCE_DOCUMENT_HASH_MISMATCH";default:return "SOURCE_PARSER_FINGERPRINT_MISMATCH";}
        if(RunSession.number(current.get("documentVersion"),-1)!=RunSession.number(provided.get("documentVersion"),-2))return "SOURCE_DOCUMENT_VERSION_MISMATCH";
        try{Evidence prior=json.convertValue(provided.get("evidence"),Evidence.class),now=(Evidence)current.get("evidence");if(prior==null)return "SOURCE_EVIDENCE_MISSING";
            if(!Objects.equals(prior.documentId(),now.documentId())||!Objects.equals(prior.documentName(),now.documentName()))return "SOURCE_DOCUMENT_IDENTITY_MISMATCH";
            if(prior.page()!=now.page()||!Objects.equals(prior.locationType(),now.locationType())||!Objects.equals(prior.anchor(),now.anchor()))return "SOURCE_LOCATION_MISMATCH";
            return Objects.equals(prior.quote(),now.quote())?"":"SOURCE_QUOTE_MISMATCH";
        }catch(IllegalArgumentException ex){return "SOURCE_EVIDENCE_MALFORMED";}
    }
    private boolean validReceipt(Map<String,Object> receipt){return "VALIDATED".equals(str(receipt,"status"))&&modelIdentity.equals(str(receipt,"modelIdentity"))&&!ProofVerifier.strings(receipt.get("ruleSignatures")).isEmpty();}
    @SuppressWarnings("unchecked") private boolean validFieldReceipt(Map<String,Object> receipt){Map<String,Object> target=receipt.get("target") instanceof Map<?,?>?json.convertValue(((Map<?,?>) (receipt.get("target"))),Map.class):cn.bidcheck.compat.Java8Api.mapOf();String status=str(receipt,"status");List<String> ids=ProofVerifier.strings(receipt.get("factIds"));boolean validState=status.equals("EXTRACTED")?!ids.isEmpty():ids.isEmpty()&&(!status.equals("READ_NOT_FOUND")||Boolean.TRUE.equals(receipt.get("sourceScopeComplete")));return validState&&FieldCoverage.POLICY.equals(str(receipt,"observationPolicy"))&&modelIdentity.equals(str(receipt,"modelIdentity"))&&FieldCoverage.STATES.contains(status)&&!target.isEmpty()&&FieldCoverage.key(target).equals(str(receipt,"observationKey"));}
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
    Set<String> pendingSources(Rule rule,List<Source> candidates){Set<String> pending=new LinkedHashSet<>(cn.bidcheck.compat.Java8Api.toList(candidates.stream().map(Source::id)));pending.removeAll(coveredSources(rule));return pending;}
    Map<String,Set<String>> pendingSources(List<Rule> rules,List<Source> candidates){Map<String,Set<String>> pending=new LinkedHashMap<>();for(Rule rule:rules)pending.put(rule.id(),pendingSources(rule,candidates));return pending;}
    /** In-process singleflight, bounded by active/waiting source-window leases, not past keys.
     * Callers must re-read the durable ledger after acquiring. */
    Lease coordinate(List<Rule> rules,List<Source> candidates)throws java.io.InterruptedIOException{
        long started=System.nanoTime(),localDone=started;
        Set<String> keys=new TreeSet<>();for(Rule rule:rules)for(Source source:candidates){Map<String,Object> target=observationTarget(rule);keys.add(EvidenceOperationRepository.textKey(
            corpus.documents.isEmpty()?"":corpus.documents.get(0).projectId(),modelIdentity,ExtractionContracts.VERSION+":"+FieldCoverage.POLICY,cn.bidcheck.compat.Java8Api.listOf(metadata.getOrDefault(source.id(),cn.bidcheck.compat.Java8Api.mapOf("id", source.id()))),cn.bidcheck.compat.Java8Api.listOf(target),FieldCoverage.key(target)));}
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
            if(context.get("evidenceOperationRepository") instanceof EvidenceOperationRepository){
                String project=corpus.documents.isEmpty()?"":corpus.documents.get(0).projectId();
                for(String key:keys)durable.add(((EvidenceOperationRepository) (context.get("evidenceOperationRepository"))).coordinate(project,key));
            }
            LOG.info("bidcheck.fact_flight runId={} checkpointId={} keys={} sources={} rules={} localWaitMs={} durableWaitMs={} durationMs={}",trace("sourceRunId"),trace("checkpointId"),keys.size(),candidates.size(),rules.size(),(localDone-started)/1_000_000,(System.nanoTime()-localDone)/1_000_000,(System.nanoTime()-started)/1_000_000);
            return new Lease(registered,held,durable);
        }catch(InterruptedException e){closeDurable(durable);release(registered,held);Thread.currentThread().interrupt();java.io.InterruptedIOException interrupted=new java.io.InterruptedIOException("核验已中断");interrupted.initCause(e);throw interrupted;
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
        publish(facts,new ArrayList<>(scope.values()),rules,layout,completeExtraction,origin,cn.bidcheck.compat.Java8Api.listOf());
    }
    @SuppressWarnings("unchecked") void publish(List<Fact> facts,List<Source> candidates,List<Rule> rules,List<Map<String,Object>> layout,boolean completeExtraction,String origin,List<Map<String,Object>> rejections){
        ProofVerifier batchVerifier = ContractRecordBindings.verifier(corpus, rules, candidates);
        List<Fact> valid=new ArrayList<>();for(Fact f:facts){if(!shareable(f))continue;ProofVerifier.Verified check=batchVerifier.verify(json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(value(f)))),candidates,layout);if(check.facts().size()==1&&check.rejected().isEmpty())valid.add(canonical(check.facts().get(0)));}
        // Native facts carry no model read-completion receipt. Once this checkpoint has durably
        // recorded the same immutable fact, changing retrieval targets does not require republishing it.
        if("NATIVE".equals(origin))valid.removeIf(f->nativePublished.contains(f.id()));
        List<String> refs="EXTRACTED".equals(origin)?cn.bidcheck.compat.Java8Api.toList(candidates.stream().map(Source::id).filter(metadata::containsKey)):cn.bidcheck.compat.Java8Api.toList(valid.stream().flatMap(f->f.sourceIds().stream()).distinct());
        List<Map<String,Object>> fields=new ArrayList<>();if("EXTRACTED".equals(origin)&&!refs.isEmpty())for(Rule rule:rules){
            if(EXCLUDED.contains(rule.predicate())||rule.predicate().equals("SCORE_COUNT"))continue;Map<String,Object> target=observationTarget(rule);
            List<Fact> matched=cn.bidcheck.compat.Java8Api.toList(valid.stream().filter(f->FieldCoverage.matches(target,value(f))));
            boolean failed=rejections.stream().anyMatch(row->cn.bidcheck.compat.Java8Api.isBlank(str(row,"kind"))||cn.bidcheck.compat.Java8Api.isBlank(str(row,"field"))||rejectedFieldCouldMatch(rule,row));
            boolean done=(completeExtraction||!rejections.isEmpty()&&!failed)&&valid.size()==facts.size()&&candidates.stream().allMatch(s->metadata.containsKey(s.id()));
            // Single-window receipts let overlapping later retrievals reuse only what was actually read.
            // A multi-window fact retains all its sources; its identity chain is never split into false facts.
            Set<String> multiSource=new HashSet<>();for(Fact fact:matched)if(fact.sourceIds().size()>1)multiSource.addAll(fact.sourceIds());
            if(!multiSource.isEmpty()){Map<String,Object> field=fieldReceipt(target,refs,cn.bidcheck.compat.Java8Api.toList(matched.stream().map(Fact::id)),"EXTRACTED",done,failed?"FIELD_VALIDATION_FAILED":"");fields.add(field);}
            for(String source:refs){if(multiSource.contains(source))continue;List<Fact> local=cn.bidcheck.compat.Java8Api.toList(matched.stream().filter(f->f.sourceIds().equals(cn.bidcheck.compat.Java8Api.listOf(source))));
                boolean localFailed=rejections.stream().anyMatch(row->(ProofVerifier.strings(row.get("sourceIds")).isEmpty()||ProofVerifier.strings(row.get("sourceIds")).contains(source))&&(cn.bidcheck.compat.Java8Api.isBlank(str(row,"kind"))||cn.bidcheck.compat.Java8Api.isBlank(str(row,"field"))||rejectedFieldCouldMatch(rule,row)));
                boolean read=(completeExtraction||!rejections.isEmpty()&&!localFailed)&&valid.size()==facts.size()&&metadata.containsKey(source);
                String status=!local.isEmpty()?"EXTRACTED":read?"READ_NOT_FOUND":"FAILED";Map<String,Object> field=fieldReceipt(target,cn.bidcheck.compat.Java8Api.listOf(source),cn.bidcheck.compat.Java8Api.toList(local.stream().map(Fact::id)),status,read,localFailed?"FIELD_VALIDATION_FAILED":"");fields.add(field);
            }
        }
        if(valid.isEmpty()&&fields.isEmpty())return;
        boolean coverage=completeExtraction&&refs.stream().allMatch(metadata::containsKey)&&valid.size()==facts.size()&&!rules.isEmpty()&&rules.stream().allMatch(r->!EXCLUDED.contains(r.predicate())&&!r.predicate().equals("SCORE_COUNT"));
        List<Map<String,Object>> rows=cn.bidcheck.compat.Java8Api.toList(valid.stream().map(f->{Map<String,Object> row=new LinkedHashMap<>(value(f));row.put("id",f.id());return row;}));
        Map<String,Object> receipt=coverage?linked("status","VALIDATED","modelIdentity",modelIdentity,"ruleSignatures",cn.bidcheck.compat.Java8Api.toList(rules.stream().map(SharedFactReuse::signature).distinct()),"sourceIds",refs,"factIds",cn.bidcheck.compat.Java8Api.toList(valid.stream().map(Fact::id))):cn.bidcheck.compat.Java8Api.mapOf();
        Map<String,Object> descriptor=operationDescriptor(origin,candidates,rules);String requestKey=EvidenceOperationRepository.key(descriptor);String operation=identity(requestKey+json.valueToTree(ordered(rows))+json.valueToTree(ordered(receipt))+json.valueToTree(ordered(fields)));
        if(!(context.get("sharedFactPublisher") instanceof Consumer<?>)){fields.forEach(this::observe);return;}
        Map<String,Object> batch=linked("facts",rows,"sources",cn.bidcheck.compat.Java8Api.toList(refs.stream().map(metadata::get).filter(Objects::nonNull)),"operationIdentity",operation,"evidenceOperationKey",requestKey,"evidenceOperationDescriptor",descriptor,"engineVersion",VERSION,"observationPolicy",FieldCoverage.POLICY,"modelIdentity",modelIdentity,"origin",origin,"layoutIdentity",context.getOrDefault("layoutIdentity",""),"layoutCells",layout,"fieldCoverage",fields);
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
        if(!(context.get("sharedFactPublisher") instanceof Consumer<?>))return;
        long started=System.nanoTime();int writes=0;
        int parts=Math.max(1,(fields.size()+119)/120);
        for(int index=0;index<parts;index++){
            List<Map<String,Object>> part=cn.bidcheck.compat.Java8Api.listCopyOf(fields.subList(index*120,Math.min(fields.size(),(index+1)*120)));
            String operation=str(batch,"operationIdentity");if(parts>1)operation=identity(operation+":field-part:"+index+":"+json.valueToTree(ordered(part)));
            if(published.contains(operation))continue;
            Map<String,Object> value=new LinkedHashMap<>(batch);value.put("operationIdentity",operation);value.put("fieldCoverage",part);
            // A first committed part must not authorize the legacy whole-request shortcut if a later
            // part fails. Durable field receipts alone describe the completed windows of multipart reads.
            if(parts>1)value.remove("extractionCoverage");
            value.put("observationPartition",linked("part",index+1,"parts",parts,"fieldCount",part.size(),"completeObservationFieldCount",fields.size(),"factsAndSourcesIntact",true));
            ((Consumer<Map<String,Object>>)((Consumer<?>) (context.get("sharedFactPublisher")))).accept(value); // Lease/storage failure propagates; no silent partial completion.
            published.add(operation);sharedPublishes++;writes++;
        }
        LOG.info("bidcheck.fact_publication runId={} checkpointId={} origin={} durationMs={} parts={} writes={} facts={} fields={}",trace("sourceRunId"),trace("checkpointId"),str(batch,"origin"),(System.nanoTime()-started)/1_000_000,parts,writes,RuleEvaluator.maps(batch.get("facts")).size(),fields.size());
    }
    private String trace(String key){String value=str(context,key);return value.matches("[A-Za-z0-9_.:-]{1,100}")?value:"";}
    private static boolean kindCouldMatch(Rule rule,String kind){return rule.predicate().equals(kind)||rule.predicate().equals("PERSON_COUNT")&&kind.equals("PERSON_ROLE")||rule.predicate().equals("VALIDITY")&&cn.bidcheck.compat.Java8Api.setOf("CERTIFICATE", "DATE_RANGE").contains(kind)||rule.predicate().equals("SAME_FIELD")&&cn.bidcheck.compat.Java8Api.setOf("FIELD", "NUMBER", "CERTIFICATE").contains(kind)||rule.predicate().equals("PRICE_RECONCILE")&&cn.bidcheck.compat.Java8Api.setOf("NUMBER", "FIELD").contains(kind)||rule.predicate().equals("DATE_RELATION")&&kind.equals("FIELD");}
    static boolean rejectedFieldCouldMatch(Rule rule,Map<String,Object> rejected){String kind=str(rejected,"kind"),field=str(rejected,"field");Set<String> fields=new LinkedHashSet<>();fields.add(rule.field());if(rule.predicate().equals("PRICE_RECONCILE"))fields.addAll(ProofVerifier.strings(rule.parameters().get("componentFields")));if(rule.predicate().equals("DATE_RELATION")){fields.add(str(rule.parameters(),"leftRole"));fields.add(str(rule.parameters(),"rightRole"));}fields.remove("");return kindCouldMatch(rule,kind)&&(rule.predicate().equals("PERSON_COUNT")&&kind.equals("PERSON_ROLE")||fields.isEmpty()||fields.contains(field));}
    private Map<String,Object> fieldReceipt(Map<String,Object> target,List<String> refs,List<String> facts,String state,boolean complete,String reason){return linked("observationKey",FieldCoverage.key(target),"target",target,"status",state,"sourceIds",refs,"factIds",facts,"sourceScopeComplete",complete,"observationPolicy",FieldCoverage.POLICY,"modelIdentity",modelIdentity,"reason",reason,"scope","SELECTED_SOURCES_ONLY","absenceProven",false);}
    private String observationScope(Map<String,Object> field){return str(field,"observationKey")+":"+new TreeSet<>(ProofVerifier.strings(field.get("sourceIds")));}
    private void observe(Map<String,Object> field){String key=observationScope(field);currentObservationKeys.add(key);currentObservationOrder.put(key,++observationOrder);observations.put(key,field);if(FieldCoverage.completeRead(field))fieldReceipts.put("local:"+key,field);}
    private void observeCached(Map<String,Object> field){String key=observationScope(field);if(currentObservationKeys.contains(key))return;Map<String,Object> previous=observations.get(key);if(previous==null||observationRank(field)>observationRank(previous))observations.put(key,field);}
    private static int observationRank(Map<String,Object> field){switch (str(field,"status")) {case "EXTRACTED":return Boolean.TRUE.equals(field.get("sourceScopeComplete"))?5:4;case "READ_NOT_FOUND":return 3;case "FAILED":return 2;default:return 1;}}
    @SuppressWarnings("unchecked") void publishState(List<Rule> rules,List<Source> candidates,String state,String reason){
        List<String> refs=cn.bidcheck.compat.Java8Api.toList(candidates.stream().map(Source::id).filter(metadata::containsKey));if(refs.isEmpty()||!cn.bidcheck.compat.Java8Api.setOf("UNREAD", "FAILED").contains(state))return;
        List<Map<String,Object>> fields=cn.bidcheck.compat.Java8Api.toList(rules.stream().filter(r->!EXCLUDED.contains(r.predicate())&&!r.predicate().equals("SCORE_COUNT")).map(r->fieldReceipt(observationTarget(r),refs,cn.bidcheck.compat.Java8Api.listOf(),state,false,reason)));fields.forEach(this::observe);
        if(fields.isEmpty()||!(context.get("sharedFactPublisher") instanceof Consumer<?>))return;
        Map<String,Object> descriptor=operationDescriptor(state,candidates,rules);String requestKey=EvidenceOperationRepository.key(descriptor);String operation=identity(requestKey+json.valueToTree(ordered(fields)));
        publishFieldParts(linked("facts",cn.bidcheck.compat.Java8Api.listOf(),"sources",cn.bidcheck.compat.Java8Api.toList(refs.stream().map(metadata::get)),"fieldCoverage",fields,"operationIdentity",operation,"evidenceOperationKey",requestKey,"evidenceOperationDescriptor",descriptor,"engineVersion",VERSION,"observationPolicy",FieldCoverage.POLICY,"modelIdentity",modelIdentity,"origin",state),fields);
    }
    List<Map<String,Object>> coverage(){
        List<Map<String,Object>> visible=new ArrayList<>();
        for(java.util.Map.Entry<java.lang.String, java.util.Map<java.lang.String, java.lang.Object>> entry:observations.entrySet()){
            Map<String,Object> parent=entry.getValue();List<String> parentSources=ProofVerifier.strings(parent.get("sourceIds"));boolean superseded=false;
            if(parentSources.size()>1&&cn.bidcheck.compat.Java8Api.setOf("UNREAD", "FAILED").contains(str(parent,"status"))){Set<String> covered=new HashSet<>();
                for(java.util.Map.Entry<java.lang.String, java.util.Map<java.lang.String, java.lang.Object>> childEntry:observations.entrySet()){Map<String,Object> child=childEntry.getValue();List<String> childSources=ProofVerifier.strings(child.get("sourceIds"));
                    if(childSources.isEmpty()||childSources.size()>=parentSources.size()||!parentSources.containsAll(childSources)
                            ||!str(parent,"observationKey").equals(str(child,"observationKey"))||!validFieldReceipt(child)||!FieldCoverage.completeRead(child))continue;
                    // Only later successful child reads can close a current failed/pending parent.
                    // Cached reads may close historical parent receipts after normal source/fact validation.
                    if(currentObservationKeys.contains(entry.getKey())&&currentObservationOrder.getOrDefault(childEntry.getKey(),0L)<=currentObservationOrder.getOrDefault(entry.getKey(),0L))continue;
                    covered.addAll(childSources);
                }superseded=covered.containsAll(parentSources);
            }
            if(!superseded)visible.add(parent);
        }return cn.bidcheck.compat.Java8Api.listCopyOf(visible);
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
