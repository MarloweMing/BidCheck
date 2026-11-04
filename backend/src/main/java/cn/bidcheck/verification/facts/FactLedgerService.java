package cn.bidcheck.verification.facts;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.FactRepository;
import cn.bidcheck.persistence.Stored;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;
import static cn.bidcheck.verification.facts.LedgerValues.*;

/** Immutable fact occurrences plus run/checkpoint dependencies. Caller owns the fenced project transaction. */
@Service
public final class FactLedgerService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(FactLedgerService.class);
    private static final String VERSION="fact-ledger-4-field-observations";
    private static final Set<String> KINDS=cn.bidcheck.compat.Java8Api.setOf("MATERIAL", "DECLARATION", "SECTION", "PERSON_ROLE", "CERTIFICATE", "DATE_RANGE", "MONTHS", "RELATION", "NUMBER", "CONTRACT", "QUALITY", "FIELD", "PERSON", "COMPANY");
    private final Store store;
    private final FactRepository core;
    @Autowired public FactLedgerService(Store store,FactRepository core){this.store=Objects.requireNonNull(store);this.core=Objects.requireNonNull(core);}
    /** Compatibility for isolated MemoryStore tests only; a real application must inject SQL storage. */
    public FactLedgerService(Store store){this(store,testRepository(store));}
    private static FactRepository testRepository(Store store){if(store.mapper()!=null)throw new IllegalArgumentException("RELATIONAL_FACT_REPOSITORY_REQUIRED");return FactRepository.memoryForTests();}
    public String identity(){return VERSION;}
    /** The caller holds the document/project fence and transaction, just as for publish(). */
    public Map<String,Object> persistPageFacts(Document document,Page page,String parserIdentity){
        return new PageFactLedger(store,core).persistPageFacts(document,page,parserIdentity);
    }
    /** Source-grounded fields supplied by a structured parser use the same page ledger. */
    public Map<String,Object> persistSuppliedPageFacts(Document document,Page page,List<Fact> facts,String parserIdentity){
        return new PageFactLedger(store,core).persistSuppliedPageFacts(document,page,facts,parserIdentity);
    }
    public Map<String,Object> persistContextFacts(Document document,List<Page> window,String parserIdentity,Map<String,Object> batch){
        return new PageFactLedger(store,core).persistContextFacts(document,window,parserIdentity,batch);
    }
    public Map<String,Object> documentView(Document document,String parserIdentity){
        return new PageFactLedger(store,core).documentView(document,parserIdentity);
    }
    /**
     * Reopens immutable occurrences selected by another source-grounded index (for example the
     * project evidence graph) against the caller's current document manifest.  This is deliberately
     * a fact read, not a conclusion cache: every source is matched to the same project, document
     * version/hash, parser fingerprint and original quote before the occurrence is returned.
     */
    public List<Map<String,Object>> revalidateOccurrences(String projectId,Collection<String> occurrenceIds,
                                                           List<Document> manifestDocuments) {
        checkName(projectId);
        if(occurrenceIds==null||occurrenceIds.isEmpty())return cn.bidcheck.compat.Java8Api.listOf();
        List<String> ids=cn.bidcheck.compat.Java8Api.toList(occurrenceIds.stream().filter(Objects::nonNull).map(cn.bidcheck.compat.Java8Api::strip)
                .filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).distinct().sorted());
        if(ids.size()>2000)throw new IllegalArgumentException("FACT_REVALIDATION_LIMIT");
        Scope scope=new Scope(projectId,"evidence-graph-reuse-v1",manifestDocuments);
        List<Map<String,Object>> accepted=new ArrayList<>();
        for(String id:ids){
            Map<String,Object> stored=core.get(id);
            if(stored.isEmpty()||!projectId.equals(text(stored,"projectId"))
                    ||!id.equals(text(stored,"occurrenceId"))||!id.equals(text(stored,"id")))continue;
            List<Map<String,Object>> originalSources=strictMaps(stored.get("sources"));
            if(originalSources.isEmpty()||originalSources.size()>16)continue;
            Map<String,Map<String,Object>> sources=new LinkedHashMap<>();boolean invalid=false;
            for(Map<String,Object> original:originalSources){
                String sourceId=text(original,"id");
                if(cn.bidcheck.compat.Java8Api.isBlank(sourceId)||sources.containsKey(sourceId)||!validSource(scope,original)){invalid=true;break;}
                sources.put(sourceId,source(scope,original));
            }
            if(invalid)continue;
            Map<String,Object> verified;
            try{verified=fact(map(stored.get("fact")),sources);}
            catch(IllegalArgumentException rejected){continue;}
            if(!canonical(verified).equals(canonical(stored.get("fact"))))continue;
            Map<String,Object> entity=EntityIdentity.resolve(verified,new ArrayList<>(sources.values()),cn.bidcheck.compat.Java8Api.listOf());
            Map<String,Object> occurrence=new LinkedHashMap<>(stored);
            occurrence.put("projectId",projectId);occurrence.put("occurrenceId",id);occurrence.put("fact",verified);
            occurrence.put("sources",new ArrayList<>(sources.values()));occurrence.put("entity",entity);
            occurrence.put("revalidatedForCurrentManifest",true);occurrence.put("revalidationPolicy","evidence-graph-reuse-v1");
            accepted.add(copy(occurrence));
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(accepted);
    }
    /** Revalidated page/context facts that the deterministic checker can safely consume. */
    public List<Fact> documentFacts(Document document,String parserIdentity){
        return new PageFactLedger(store,core).documentFacts(document,parserIdentity);
    }
    /** Caller must hold the project lock and fenced transaction. No history, usage or snapshot is mutated. */
    public Map<String,Object> migrateLegacyOccurrences(String project,int max){return migrateLegacyOccurrences(project,max,"");}
    public Map<String,Object> migrateLegacyOccurrences(String project,int max,String cursor){
        checkName(project);if(project.length()>64||max<1||max>2000)throw new IllegalArgumentException("LEGACY_MIGRATION_LIMIT");
        long after=-1;String afterId="";if(cursor!=null&&!cn.bidcheck.compat.Java8Api.isBlank(cursor)){
            try{if(cursor.length()>2048)throw new IllegalArgumentException();Map<String,Object> c=store.map(new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8));
                if(integer(c.get("version"),-1)!=1||!hash(project).equals(text(c,"projectHash"))||!(c.get("createdAt") instanceof Number)||((Number) (c.get("createdAt"))).doubleValue()!=((Number) (c.get("createdAt"))).longValue()||((Number) (c.get("createdAt"))).longValue()<0||cn.bidcheck.compat.Java8Api.isBlank(text(c,"id"))||text(c,"id").length()>64)throw new IllegalArgumentException();after=((Number) (c.get("createdAt"))).longValue();afterId=text(c,"id");
            }catch(RuntimeException badCursor){throw new IllegalArgumentException("LEGACY_CURSOR_INVALID");}
        }
        int scanned=0,migrated=0,already=0,rejected=0;boolean more=false;Map<String,Integer> errors=new TreeMap<>();
        Map<String,Document> documents=new LinkedHashMap<String,Document>(8,0.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<String,Document> e){return size()>4;}};
        while(scanned<max){interrupted();int size=Math.min(25,max-scanned);List<Stored> rows=core.legacyRows(project,after,afterId,size+1);more=rows.size()>size;
            for(Stored row:rows.subList(0,Math.min(rows.size(),size))){interrupted();scanned++;after=row.createdAt;afterId=row.id;
                Map<String,Object> verified;
                try{verified=validateLegacy(project,row,documents);}catch(IllegalArgumentException invalid){rejected++;String code=Objects.toString(invalid.getMessage(),"");if(!code.matches("[A-Z][A-Z0-9_]{1,80}"))code="LEGACY_RECORD_INVALID";errors.merge(code,1,Integer::sum);continue;}
                // Database errors must escape and roll back the caller's transaction, never masquerade as a rejected source.
                Map<String,Object> present=core.get(row.id);if(!present.isEmpty()){
                    if(!project.equals(text(present,"projectId"))||!text(verified,"engineIdentity").equals(text(present,"engineIdentity")))throw new IllegalStateException("LEGACY_CORE_SCOPE_MISMATCH");already++;
                }else{core.insert(verified);migrated++;}
            }
            if(!more)break;
        }
        String next=after<0?"":Base64.getUrlEncoder().withoutPadding().encodeToString(store.encode(of("version",1,"projectHash",hash(project),"createdAt",after,"id",afterId)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return of("scanned",scanned,"migrated",migrated,"alreadyPresent",already,"rejected",rejected,"errors",errors,"nextCursor",next,"hasMore",more,"adoptedForRun",false);
    }
    private Map<String,Object> validateLegacy(String project,Stored row,Map<String,Document> documents){
        if(!"fact_occurrence".equals(row.kind)||!project.equals(row.projectId)||row.body==null||row.body.length()>2_000_000)throw new IllegalArgumentException("LEGACY_RECORD_INVALID");
        Map<String,Object> value;try{value=store.map(row.body);}catch(IllegalStateException invalid){throw new IllegalArgumentException("LEGACY_JSON_INVALID");}
        if(!row.id.equals(text(value,"id"))||!row.id.equals(text(value,"occurrenceId"))||!project.equals(text(value,"projectId")))throw new IllegalArgumentException("LEGACY_SCOPE_MISMATCH");
        String engine=text(value,"engineIdentity");checkName(engine);List<Map<String,Object>> originalSources=strictMaps(value.get("sources"));if(originalSources.isEmpty()||originalSources.size()>16)throw new IllegalArgumentException("LEGACY_SOURCE_LIMIT");
        Map<String,Document> manifest=new LinkedHashMap<>();for(Map<String,Object> source:originalSources){String id=text(evidence(source.get("evidence")),"documentId");Document d=documents.get(id);
            if(d==null){try{d=store.get("document",id,Document.class);}catch(ResponseStatusException missing){if(missing.getStatus().value()==404)throw new IllegalArgumentException("LEGACY_DOCUMENT_MISSING");throw missing;}catch(IllegalStateException malformed){throw new IllegalArgumentException("LEGACY_DOCUMENT_INVALID");}documents.put(id,d);}manifest.put(id,d);
        }
        Scope scope=new Scope(project,engine,new ArrayList<>(manifest.values()));Map<String,Map<String,Object>> refs=new LinkedHashMap<>();
        for(Map<String,Object> old:originalSources){String id=text(old,"id");checkName(id);if(!validSource(scope,old)||refs.putIfAbsent(id,source(scope,old))!=null)throw new IllegalArgumentException("LEGACY_SOURCE_UNVERIFIED");}
        Map<String,Object> validated=fact(map(value.get("fact")),refs);if(!canonical(validated).equals(canonical(value.get("fact")))||strings(validated.get("sourceIds")).size()!=refs.size())throw new IllegalArgumentException("LEGACY_FACT_SHAPE_INVALID");
        List<Map<String,Object>> sources=cn.bidcheck.compat.Java8Api.toList(strings(validated.get("sourceIds")).stream().map(refs::get));
        Map<String,Object> semantic=new LinkedHashMap<>(validated);semantic.remove("id");semantic.put("sourceIds",cn.bidcheck.compat.Java8Api.toList(sources.stream().map(s->text(s,"sourceFingerprint")).sorted()));
        String legacyId=dbKey("occurrence",canonical(cn.bidcheck.compat.Java8Api.listOf(project, engine, semantic)));
        String semanticId=dbKey("occurrence",canonical(cn.bidcheck.compat.Java8Api.listOf(project, engine, SemanticFactIdentity.value(semantic))));
        if(!row.id.equals(legacyId)&&!row.id.equals(semanticId))throw new IllegalArgumentException("LEGACY_OCCURRENCE_ID_INVALID");
        if(!maps(map(map(validated.get("attributes")).get("structureProof")).get("records")).isEmpty())throw new IllegalArgumentException("LEGACY_STRUCTURE_REVALIDATION_REQUIRED");
        Map<String,Object> derived=EntityIdentity.resolve(validated,sources,cn.bidcheck.compat.Java8Api.listOf());
        if(!canonical(derived).equals(canonical(value.get("entity"))))throw new IllegalArgumentException("LEGACY_ENTITY_UNVERIFIED");
        if(text(value,"createdAt").length()>40||text(value,"ledgerVersion").length()>100)throw new IllegalArgumentException("LEGACY_METADATA_INVALID");
        Map<String,Object> verified=new LinkedHashMap<>(value);verified.put("fact",validated);verified.put("sources",sources);verified.put("entity",derived);return verified;
    }
    public static String parserFingerprint(Page p){return hash(Objects.toString(p.parser(),"")+"\0"+Objects.toString(p.locationType(),"")+"\0"+p.number()+"\0"+Objects.toString(p.text(),"")+"\0"+canonical(p.layout()==null?cn.bidcheck.compat.Java8Api.mapOf():p.layout().toMap()));}

    /** Prepared values have no public constructor and never expose their mutable internal records. */
    public static final class PreparedManifest {
        private final Scope scope;
        // Run-scoped, bounded immutable payload caches. Index membership is always queried again:
        // a miss is never retained and newly committed observations remain visible after single-flight.
        private final Map<String,Map<String,Object>> batches=payloadCache(512,16_000_000),occurrences=payloadCache(2048,8_000_000);
        private final Map<String,Set<String>> sourceHints=boundedCache(20000);
        private final java.util.concurrent.locks.ReentrantLock sourceHintsLock=new java.util.concurrent.locks.ReentrantLock();
        private PreparedManifest(Scope scope){this.scope=scope;}
        public String identity(){return scope.manifestIdentity;}
    }
    private static <V> Map<String,V> boundedCache(int size){return Collections.synchronizedMap(new LinkedHashMap<String,V>(32,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<String,V> entry){return size()>size;}});}
    private static Map<String,Map<String,Object>> payloadCache(int capacity,int maxChars){
        return Collections.synchronizedMap(new LinkedHashMap<String,Map<String,Object>>(32,.75f,true){
            private final Map<String,Integer> weights=new HashMap<>();private long chars;
            @Override public Map<String,Object> put(String key,Map<String,Object> value){
                int weight=canonical(value).length();if(weight>maxChars)return null;
                Map<String,Object> previous=super.put(key,value);chars+=weight-weights.getOrDefault(key,0);weights.put(key,weight);
                Iterator<Map.Entry<String,Map<String,Object>>> rows=entrySet().iterator();while((size()>capacity||chars>maxChars)&&rows.hasNext()){String removed=rows.next().getKey();chars-=weights.remove(removed);rows.remove();}return previous;
            }
        });
    }
    public static final class PreparedBatch {
        private final FactLedgerService owner;private final Scope scope;
        private final String runId,checkpointId,operation,engineVersion,digest;
        private final Map<String,Object> batch,coverage;private final List<Map<String,Object>> entries,rejected;
        private final Map<String,Map<String,Object>> sources;
        private PreparedBatch(FactLedgerService owner,Scope scope,String runId,String checkpointId,String operation,String engineVersion,
                              Map<String,Object> batch,Map<String,Object> coverage,List<Map<String,Object>> entries,List<Map<String,Object>> rejected,Map<String,Map<String,Object>> sources){
            this.owner=owner;this.scope=scope;this.runId=runId;this.checkpointId=checkpointId;this.operation=operation;this.engineVersion=engineVersion;
            this.batch=batch;this.coverage=coverage;this.entries=entries;this.rejected=rejected;this.sources=sources;
            this.digest=hash(canonical(cn.bidcheck.compat.Java8Api.listOf(scope.project, scope.engine, scope.manifestIdentity, runId, checkpointId, batch)));
        }
        public String digest(){return digest;}
        public int inputCount(){return maps(batch.get("facts")).size();}
        public int acceptedCount(){return entries.size();}
        public boolean rejected(){return !rejected.isEmpty();}
    }
    public PreparedManifest prepareManifest(String projectId,String engineIdentity,List<Document> documents){return new PreparedManifest(new Scope(projectId,engineIdentity,documents));}
    public PreparedBatch prepareBatch(PreparedManifest manifest,String runId,String checkpointId,Map<String,Object> inputBatch){
        Scope scope=Objects.requireNonNull(manifest).scope;checkName(runId);checkName(checkpointId);if(runId.length()>64)throw new IllegalArgumentException("FACT_SCOPE_ID_INVALID");
        Objects.requireNonNull(inputBatch);interrupted();if(canonical(inputBatch).length()>2_000_000)throw new IllegalArgumentException("FACT_BATCH_LIMIT");
        Map<String,Object> batch=copy(inputBatch);
        String operation=text(batch,"operationIdentity"),engineVersion=text(batch,"engineVersion");if(operation.length()>500||engineVersion.length()>200)throw new IllegalArgumentException("FACT_OPERATION_INVALID");
        List<Map<String,Object>> rejected=new ArrayList<>();Map<String,Map<String,Object>> sources=new LinkedHashMap<>();Set<String> invalidSources=new HashSet<>();
        List<Map<String,Object>> inputSources=strictMaps(batch.get("sources"));if(inputSources.size()>128)throw new IllegalArgumentException("FACT_SOURCE_LIMIT");
        for(Map<String,Object> input:inputSources){interrupted();String id=text(input,"id");try{checkName(id);if(sources.containsKey(id)||invalidSources.contains(id))throw new IllegalArgumentException("SOURCE_ID_DUPLICATE");sources.put(id,source(scope,input));}catch(IllegalArgumentException failure){sources.remove(id);invalidSources.add(id);rejected.add(of("sourceId",id,"code",failure.getMessage()));}}
        List<Map<String,Object>> layoutCells=maps(batch.get("layoutCells"));if(layoutCells.size()>300)throw new IllegalArgumentException("FACT_LAYOUT_LIMIT");
        List<Map<String,Object>> inputFacts=strictMaps(batch.get("facts"));if(inputFacts.size()>120)throw new IllegalArgumentException("FACT_COUNT_LIMIT");
        List<Map<String,Object>> entries=new ArrayList<>();Set<String> originalFactIds=new HashSet<>();int index=0;
        for(Map<String,Object> input:inputFacts){interrupted();index++;try{
            Map<String,Object> fact=fact(input,sources);List<Map<String,Object>> refs=cn.bidcheck.compat.Java8Api.toList(strings(fact.get("sourceIds")).stream().map(sources::get));
            Map<String,Object> attrs=map(fact.get("attributes"));attrs.remove("structureProof");
            Map<String,Object> entity=EntityIdentity.resolve(fact,refs,layoutCells);Map<String,Object> structure=map(entity.get("structureProof"));
            if(!maps(structure.get("records")).isEmpty())attrs.put("structureProof",structure);fact.put("attributes",attrs);
            String originalId=text(input,"id");if(!cn.bidcheck.compat.Java8Api.isBlank(originalId))originalFactIds.add(originalId);
            Map<String,Object> semantic=new LinkedHashMap<>(fact);semantic.remove("id");semantic.put("sourceIds",cn.bidcheck.compat.Java8Api.toList(refs.stream().map(ref->text(ref,"sourceFingerprint")).sorted()));
            String occurrenceId=dbKey("occurrence",canonical(cn.bidcheck.compat.Java8Api.listOf(scope.project, scope.engine, SemanticFactIdentity.value(semantic))));
            entries.add(of("occurrenceId",occurrenceId,"fact",fact,"sources",refs,"entity",entity,"originalFactId",originalId));
        }catch(IllegalArgumentException failure){rejected.add(of("index",index,"code",failure.getMessage()));}}
        Map<String,Object> coverage=coverage(batch.get("extractionCoverage"),sources.keySet(),originalFactIds,rejected,entries.size());
        return new PreparedBatch(this,scope,runId,checkpointId,operation,engineVersion,batch,coverage,entries,rejected,sources);
    }
    public Map<String,Object> publish(String projectId,String runId,String checkpointId,String engineIdentity,List<Document> manifestBidDocs,Map<String,Object> batch){
        return publishPrepared(prepareBatch(prepareManifest(projectId,engineIdentity,manifestBidDocs),runId,checkpointId,batch));
    }
    /** Only bounded relational persistence remains inside the caller's fenced transaction. */
    public Map<String,Object> publishPrepared(PreparedBatch prepared){
        if(prepared==null||prepared.owner!=this)throw new IllegalArgumentException("FACT_PREPARATION_OWNER_INVALID");
        long started=System.nanoTime();
        interrupted();Scope scope=prepared.scope;String runId=prepared.runId,checkpointId=prepared.checkpointId,operation=prepared.operation,engineVersion=prepared.engineVersion;
        if(!read("fact_snapshot",snapshotId(scope,runId)).isEmpty())throw new IllegalStateException("FACT_RUN_SNAPSHOT_FROZEN");
        Map<String,Object> batch=prepared.batch,coverage=prepared.coverage;Map<String,Map<String,Object>> sources=prepared.sources;
        List<Map<String,Object>> rejected=new ArrayList<>(prepared.rejected),accepted=new ArrayList<>();int reused=0,index=0;
        List<String> occurrenceIds=cn.bidcheck.compat.Java8Api.toList(prepared.entries.stream().map(entry->text(entry,"occurrenceId")).distinct());
        Map<String,Map<String,Object>> existingValues=core.getAll(occurrenceIds);Set<String> existing=existingValues.keySet();
        Map<String,Map<String,Object>> legacy=store.mapsByIds("fact_occurrence",scope.project,cn.bidcheck.compat.Java8Api.toList(occurrenceIds.stream().filter(id->!existing.contains(id))));
        Map<String,Map<String,Object>> pending=new LinkedHashMap<>();
        for(Map<String,Object> entry:prepared.entries){interrupted();index++;try{
            String occurrenceId=text(entry,"occurrenceId");Map<String,Object> fact=map(entry.get("fact"));List<Map<String,Object>> refs=maps(entry.get("sources"));
            Map<String,Object> old=pending.containsKey(occurrenceId)?pending.get(occurrenceId):existingValues.getOrDefault(occurrenceId,legacy.getOrDefault(occurrenceId,cn.bidcheck.compat.Java8Api.mapOf()));
            if(old.isEmpty())pending.put(occurrenceId,of("id",occurrenceId,"occurrenceId",occurrenceId,"projectId",scope.project,"engineIdentity",scope.engine,"fact",fact,"sources",refs,"entity",entry.get("entity"),"layoutIdentity",text(batch,"layoutIdentity"),"createdAt",Instant.now().toString(),"ledgerVersion",VERSION));
            else {
                if(!scope.project.equals(text(old,"projectId"))||!scope.engine.equals(text(old,"engineIdentity"))||maps(old.get("sources")).isEmpty()||maps(old.get("sources")).stream().anyMatch(ref->!validSource(scope,ref)))throw new IllegalArgumentException("FACT_EXISTING_SCOPE_INVALID");
                if(!existing.contains(occurrenceId))pending.putIfAbsent(occurrenceId,old);reused++;
            }
            accepted.add(of("occurrenceId",occurrenceId,"fact",fact,"sources",refs,"originalFactId",entry.get("originalFactId")));
        }catch(IllegalArgumentException failure){rejected.add(of("index",index,"code",failure.getMessage()));}}
        core.insertBatch(new ArrayList<>(pending.values()));
        coverage=coverage(batch.get("extractionCoverage"),sources.keySet(),prepared.entries.stream().map(e->text(e,"originalFactId")).filter(id->!cn.bidcheck.compat.Java8Api.isBlank(id)).collect(java.util.stream.Collectors.toSet()),rejected,accepted.size());
        List<Map<String,Object>> fieldCoverage=FieldCoverage.validate(batch.get("fieldCoverage"),sources,accepted);
        boolean persisted=!accepted.isEmpty()||!fieldCoverage.isEmpty();
        String batchId=dbKey("fact-batch",canonical(cn.bidcheck.compat.Java8Api.listOf(scope.project, scope.engine, operation, engineVersion, accepted, sources, coverage, fieldCoverage)));
        boolean adoption=cn.bidcheck.compat.Java8Api.setOf("REUSED", "REUSED_OBSERVATION").contains(text(batch,"origin"));
        Map<String,Object> storedBatch=of("id",batchId,"projectId",scope.project,"engineIdentity",scope.engine,"engineVersion",engineVersion,"observationPolicy",text(batch,"observationPolicy"),"operationIdentity",operation,"entries",accepted,"sources",new ArrayList<>(sources.values()),"extractionCoverage",coverage,"fieldCoverage",fieldCoverage,"layoutCells",maps(batch.get("layoutCells")),"layoutIdentity",text(batch,"layoutIdentity"),"origin",text(batch,"origin"));
        if(persisted&&read("fact_batch",batchId).isEmpty()){
            Map<String,Object> stored=storedBatch;
            store.save("fact_batch",batchId,scope.project,engineParent(scope),stored);
            if(!adoption&&(!accepted.isEmpty()||fieldCoverage.stream().anyMatch(FieldCoverage::completeRead)))for(String docId:cn.bidcheck.compat.Java8Api.toList(sources.values().stream().map(ref->text(map(ref.get("evidence")),"documentId")).distinct())){String parent=documentParent(scope,scope.documents.get(docId));String key=dbKey("fact-index",parent+batchId);store.save("fact_index",key,scope.project,parent,of("id",key,"batchId",batchId,"sourceIds",new ArrayList<>(sources.keySet()),"sourceIndexVersion",1));}
        }
        CanonicalFactLookup.Publication lookupPublication=persisted&&!adoption?new CanonicalFactLookup(store).index(scope,storedBatch,accepted,fieldCoverage):new CanonicalFactLookup.Publication(0,cn.bidcheck.compat.Java8Api.listOf());
        int lookupWrites=lookupPublication.writes();
        // Migration is bounded and shares the publisher's transaction/fence. BUILDING/BLOCKED
        // readers retain the legacy path; no half-migrated index can claim evidence absence.
        Map<String,Object> migration=persisted?migrateLookupIndex(scope,50):cn.bidcheck.compat.Java8Api.mapOf();
        List<String> usages=new ArrayList<>();List<Stored> usageRows=new ArrayList<>();
        List<String> usageKeys=cn.bidcheck.compat.Java8Api.toList(accepted.stream().map(entry->dbKey("fact-use",canonical(cn.bidcheck.compat.Java8Api.listOf(scope.project, scope.engine, runId, checkpointId, batchId, text(entry,"occurrenceId"))))));
        Set<String> existingUses=store.mapsByIds("fact_usage",scope.project,usageKeys).keySet();Set<String> newUses=new HashSet<>();
        for(Map<String,Object> entry:accepted){String occurrence=text(entry,"occurrenceId"),usageId=dbKey("fact-use",canonical(cn.bidcheck.compat.Java8Api.listOf(scope.project, scope.engine, runId, checkpointId, batchId, occurrence)));
            if(!existingUses.contains(usageId)&&newUses.add(usageId)){Map<String,Object> use=of("id",usageId,"usageId",usageId,"projectId",scope.project,"runId",runId,"checkpointId",checkpointId,"engineIdentity",scope.engine,"batchId",batchId,"occurrenceId",occurrence,"operationIdentity",operation,"manifestIdentity",scope.manifestIdentity);usageRows.add(store.newRow("fact_usage",usageId,scope.project,runId,use));usageRows.add(store.newRow("fact_origin",dbKey("fact-origin",usageId),scope.project,occurrence,use));}usages.add(usageId);}
        store.insertRows(usageRows);
        List<String> coverageUsageIds=new ArrayList<>();if(!fieldCoverage.isEmpty()){
            String coverageUse=dbKey("field-use",canonical(cn.bidcheck.compat.Java8Api.listOf(scope.project, scope.engine, runId, checkpointId, batchId)));
            if(read("fact_coverage_usage",coverageUse).isEmpty())store.save("fact_coverage_usage",coverageUse,scope.project,runId,
                of("id",coverageUse,"projectId",scope.project,"runId",runId,"checkpointId",checkpointId,"engineIdentity",scope.engine,"manifestIdentity",scope.manifestIdentity,"batchId",batchId));
            coverageUsageIds.add(coverageUse);
        }
        LOG.info("bidcheck.fact_publish runId={} checkpointId={} batchId={} durationMs={} accepted={} reused={} inserted={} usageRows={} fieldObservations={} adoptionAuditOnly={} canonicalLookupWrites={} migrationProcessed={}",runId,checkpointId,batchId,(System.nanoTime()-started)/1_000_000,accepted.size(),reused,pending.size(),usageRows.size(),fieldCoverage.size(),adoption,lookupWrites,migration.getOrDefault("processed",0));
        return of("accepted",accepted.size(),"reused",reused,"rejected",rejected,"batchId",persisted?batchId:"","occurrenceIds",cn.bidcheck.compat.Java8Api.toList(accepted.stream().map(e->e.get("occurrenceId")).distinct()),"usageIds",usages,"coverageUsageIds",coverageUsageIds,"coveragePublished",!coverage.isEmpty()||!fieldCoverage.isEmpty(),"fieldCoverage",fieldCoverage,"changedEvidence",lookupPublication.changedEvidence());
    }
    public static final class PreparedSnapshot {
        private final FactLedgerService owner;private final Scope scope;private final String runId,dependenciesDigest,coverageDigest,previousBody;
        private final Store.PreparedRecord payload;private final Map<String,Object> metadata;private final Set<String> usageIds,occurrenceIds;
        private PreparedSnapshot(FactLedgerService owner,Scope scope,String runId,Map<String,Object> value,Set<String> usageIds,Set<String> occurrenceIds,String previousBody){
            this.owner=owner;this.scope=scope;this.runId=runId;this.usageIds=cn.bidcheck.compat.Java8Api.setCopyOf(usageIds);this.occurrenceIds=cn.bidcheck.compat.Java8Api.setCopyOf(occurrenceIds);this.previousBody=previousBody;
            dependenciesDigest=dependenciesDigest(maps(value.get("dependencies")));
            coverageDigest=dependenciesDigest(maps(value.get("coverageDependencies")));
            metadata=of("id",value.get("id"),"summary",copy(map(value.get("summary"))));
            payload=owner.store.prepareRecord("fact_snapshot",text(value,"id"),scope.project,runId,value);
        }
        public Map<String,Object> metadata(){return copy(metadata);}
    }
    /** All source revalidation and N+1 fact hydration happen without task/project row locks. */
    public PreparedSnapshot prepareSnapshot(PreparedManifest manifest,String runId,List<Map<String,Object>> receipts){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("FACT_SNAPSHOT_PREPARATION_REQUIRES_NO_TRANSACTION");
        Scope scope=manifest.scope;Set<String> usageIds=new TreeSet<>(),occurrenceIds=new TreeSet<>();
        for(Map<String,Object> receipt:receipts){if(!maps(receipt.get("rejected")).isEmpty())throw new IllegalStateException("FACT_PUBLICATION_REJECTED");usageIds.addAll(strings(receipt.get("usageIds")));occurrenceIds.addAll(strings(receipt.get("occurrenceIds")));}
        Map<String,Object> prepared=assemble(scope,runId,true);
        Set<String> actualUses=maps(prepared.get("dependencies")).stream().map(u->text(u,"id")).collect(java.util.stream.Collectors.toSet());
        Set<String> actualOccurrences=maps(prepared.get("occurrences")).stream().map(o->text(o,"occurrenceId")).collect(java.util.stream.Collectors.toSet());
        if(!usageIds.equals(actualUses)||!occurrenceIds.equals(actualOccurrences))throw new IllegalStateException("FACT_PUBLICATION_INCOMPLETE");
        Stored previous=store.mapper().get(snapshotId(scope,runId));
        if(previous!=null&&(!"fact_snapshot".equals(previous.kind)||!scope.project.equals(previous.projectId)||!runId.equals(previous.parentId)
                ||!canonical(store.map(previous.body)).equals(canonical(prepared))))throw new IllegalStateException("FACT_RUN_SNAPSHOT_FROZEN");
        return new PreparedSnapshot(this,scope,runId,prepared,usageIds,occurrenceIds,previous==null?null:previous.body);
    }
    /** Caller holds the current lease and project fence. It cannot inject an arbitrary snapshot map. */
    public Map<String,Object> freezePreparedSnapshot(PreparedSnapshot prepared){
        if(prepared==null||prepared.owner!=this)throw new IllegalArgumentException("FACT_SNAPSHOT_PREPARATION_OWNER_INVALID");
        interrupted();List<Map<String,Object>> current=runUses(prepared.scope,prepared.runId);
        Set<String> uses=current.stream().map(u->text(u,"id")).collect(java.util.stream.Collectors.toSet());
        Set<String> occurrences=current.stream().map(u->text(u,"occurrenceId")).collect(java.util.stream.Collectors.toSet());
        if(!uses.equals(prepared.usageIds)||!occurrences.equals(prepared.occurrenceIds)||!dependenciesDigest(current).equals(prepared.dependenciesDigest)
            ||!dependenciesDigest(coverageUses(prepared.scope,prepared.runId)).equals(prepared.coverageDigest))throw new IllegalStateException("FACT_PUBLICATION_CHANGED");
        Stored previous=store.mapper().get(snapshotId(prepared.scope,prepared.runId));
        if(previous!=null){if(!"fact_snapshot".equals(previous.kind)||!prepared.scope.project.equals(previous.projectId)||!prepared.runId.equals(previous.parentId)
                ||!Objects.equals(previous.body,prepared.previousBody))throw new IllegalStateException("FACT_RUN_SNAPSHOT_FROZEN");return prepared.metadata();}
        if(prepared.previousBody!=null)throw new IllegalStateException("FACT_PUBLICATION_CHANGED");
        store.savePrepared(prepared.payload);return prepared.metadata();
    }
    private static String dependenciesDigest(List<Map<String,Object>> uses){return hash(canonical(cn.bidcheck.compat.Java8Api.toList(uses.stream().sorted(Comparator.comparing(u->text(u,"id"))))));}
    private List<Map<String,Object>> runUses(Scope scope,String runId){return cn.bidcheck.compat.Java8Api.toList(store.maps("fact_usage",scope.project,runId).stream().filter(u->scope.engine.equals(text(u,"engineIdentity"))&&scope.manifestIdentity.equals(text(u,"manifestIdentity"))));}
    private List<Map<String,Object>> coverageUses(Scope scope,String runId){return cn.bidcheck.compat.Java8Api.toList(store.maps("fact_coverage_usage",scope.project,runId).stream().filter(u->scope.engine.equals(text(u,"engineIdentity"))&&scope.manifestIdentity.equals(text(u,"manifestIdentity"))));}

    public List<Map<String,Object>> lookup(String projectId,String engineIdentity,List<Document> manifestBidDocs,Map<String,Object> request){
        return lookup(prepareManifest(projectId,engineIdentity,manifestBidDocs),request);
    }
    /** Caller owns one immutable document manifest for a run; all per-source checks below remain. */
    public List<Map<String,Object>> lookup(PreparedManifest manifest,Map<String,Object> request){
        long started=System.nanoTime();Scope scope=Objects.requireNonNull(manifest).scope;Map<String,Object> req=request==null?cn.bidcheck.compat.Java8Api.mapOf():request;int limit=integer(req.get("limit"),100);if(limit<1||limit>2000)throw new IllegalArgumentException("FACT_LOOKUP_LIMIT");
        Set<String> requestedDocs=new HashSet<>(strings(req.get("documentIds"))),requestedSources=new HashSet<>(strings(req.get("sourceIds"))),kinds=new HashSet<>(strings(req.get("factKinds"))),observationKeys=new HashSet<>(strings(req.get("observationKeys")));
        if(!scope.documents.keySet().containsAll(requestedDocs))throw new IllegalArgumentException("FACT_LOOKUP_OUTSIDE_MANIFEST");
        // Invalid explicit contracts are caller errors, not an old-index compatibility case.
        // In particular, a compile-only repair has no field target and must not enumerate every
        // historical extraction batch looking for a substitute identity.
        if("CANONICAL_SOURCE".equals(text(req,"lookupMode"))&&Boolean.TRUE.equals(req.get("includeFieldCoverage"))
            &&(observationKeys.isEmpty()||cn.bidcheck.compat.Java8Api.isBlank(text(req,"modelIdentity")))){
            LOG.warn("bidcheck.fact_lookup_rejected runId={} checkpointId={} phase={} reason=FIELD_LOOKUP_IDENTITY_REQUIRED legacyFallback=false",safeLogId(req.get("runId")),safeLogId(req.get("checkpointId")),safeLogId(req.get("lookupPhase")));
            throw new IllegalArgumentException("FIELD_LOOKUP_IDENTITY_REQUIRED");
        }
        boolean bySubject="CANONICAL_SUBJECT".equals(text(req,"lookupMode"));
        if(bySubject&&(strings(req.get("subjects")).isEmpty()||strings(req.get("subjects")).size()>64||Boolean.TRUE.equals(req.get("includeFieldCoverage"))))throw new IllegalArgumentException("SUBJECT_LOOKUP_CONTRACT_INVALID");
        if(("CANONICAL_SOURCE".equals(text(req,"lookupMode"))||bySubject)&&!requestedSources.isEmpty()&&!kinds.isEmpty()){
            CanonicalFactLookup canonicalIndex=new CanonicalFactLookup(store);
            if(canonicalIndex.ready(scope,requestedDocs.isEmpty()?scope.documents.keySet():requestedDocs)){
                long readinessNanos=System.nanoTime()-started;
                try{return lookupCanonical(manifest,req,limit,readinessNanos);}catch(CanonicalLookupInvalid invalid){
                    LOG.warn("bidcheck.fact_lookup_fallback runId={} checkpointId={} reason={}",safeLogId(req.get("runId")),safeLogId(req.get("checkpointId")),invalid.getMessage());
                }
            }else LOG.info("bidcheck.fact_lookup_fallback runId={} checkpointId={} reason=CANONICAL_INDEX_NOT_READY",safeLogId(req.get("runId")),safeLogId(req.get("checkpointId")));
        }
        Map<String,Map<String,Object>> index=new LinkedHashMap<>();for(Document doc:scope.documents.values()){if(!requestedDocs.isEmpty()&&!requestedDocs.contains(doc.id()))continue;for(Map<String,Object> row:store.maps("fact_index",scope.project,documentParent(scope,doc)))index.putIfAbsent(text(row,"batchId"),row);}
        SourceSelection selection=selectBatches(manifest,index,requestedSources);int loaded=selection.loaded();List<String> batchIds=selection.ids();
        Map<String,Map<String,Object>> batches=new LinkedHashMap<>();List<String> missing=new ArrayList<>();int cacheHits=0;
        for(String id:batchIds){Map<String,Object> cached=manifest.batches.get(id);if(cached==null)missing.add(id);else{batches.put(id,cached);cacheHits++;}}
        Map<String,Map<String,Object>> fetched=store.mapsByIds("fact_batch",scope.project,missing);loaded+=fetched.size();fetched.forEach((id,batch)->{rememberBatch(manifest,id,batch);batches.put(id,batch);});
        Set<String> occurrenceIds=new LinkedHashSet<>();for(Map<String,Object> batch:batches.values())for(Map<String,Object> entry:maps(batch.get("entries"))){List<String> aliases=cn.bidcheck.compat.Java8Api.toList(maps(entry.get("sources")).stream().map(s->text(s,"id")));if(!aliases.isEmpty()&&(requestedSources.isEmpty()||requestedSources.containsAll(aliases)))occurrenceIds.add(text(entry,"occurrenceId"));}
        Map<String,Map<String,Object>> occurrences=new HashMap<>();List<String> missingOccurrences=new ArrayList<>();
        for(String id:occurrenceIds){Map<String,Object> cached=manifest.occurrences.get(id);if(cached==null)missingOccurrences.add(id);else occurrences.put(id,cached);}
        Map<String,Map<String,Object>> loadedOccurrences=core.getAll(missingOccurrences);
        List<String> legacyIds=cn.bidcheck.compat.Java8Api.toList(missingOccurrences.stream().filter(id->!loadedOccurrences.containsKey(id)));loadedOccurrences.putAll(store.mapsByIds("fact_occurrence",scope.project,legacyIds));
        loadedOccurrences.forEach((id,value)->{manifest.occurrences.put(id,value);occurrences.put(id,value);});
        // Origins are mutable append-only usage records. Always refresh them, including after a
        // durable lease wait; immutable occurrence caching must not hide another checkpoint's use.
        Map<String,List<Map<String,Object>>> eligibleByBatch=new HashMap<>();
        for(java.util.Map.Entry<java.lang.String, java.util.Map<java.lang.String, java.lang.Object>> batchValue:batches.entrySet()){Map<String,Object> batch=batchValue.getValue();if(!scope.engine.equals(text(batch,"engineIdentity"))||!scope.project.equals(text(batch,"projectId")))continue;List<Map<String,Object>> eligible=new ArrayList<>();
            for(Map<String,Object> batchEntry:maps(batch.get("entries"))){List<String> aliases=cn.bidcheck.compat.Java8Api.toList(maps(batchEntry.get("sources")).stream().map(s->text(s,"id")));if(aliases.isEmpty()||!requestedSources.isEmpty()&&!requestedSources.containsAll(aliases))continue;
                Map<String,Object> entry=materializeEntry(scope,batchEntry,occurrences.getOrDefault(text(batchEntry,"occurrenceId"),cn.bidcheck.compat.Java8Api.mapOf()));if(entry.isEmpty())continue;
                Map<String,Object> fact=map(entry.get("fact"));List<Map<String,Object>> refs=maps(entry.get("sources"));if(refs.isEmpty()||refs.stream().anyMatch(s->!validSource(scope,s))||!kinds.isEmpty()&&!kinds.contains(text(fact,"kind")))continue;
                if(!requestedSources.isEmpty()&&!requestedSources.containsAll(strings(fact.get("sourceIds"))))continue;
                if(!requestedDocs.isEmpty()&&refs.stream().anyMatch(s->!requestedDocs.contains(text(map(s.get("evidence")),"documentId"))))continue;
                if(bySubject&&!CanonicalFactLookup.subjectMatches(fact,strings(req.get("subjects"))))continue;
                eligible.add(entry);
            }eligibleByBatch.put(batchValue.getKey(),eligible);
        }
        // The cap counts immutable occurrences, not the number of extraction/adoption batches
        // which mention them. Keep one source-validated representative for each occurrence.
        Map<String,Map<String,Object>> selectedEntries=new LinkedHashMap<>();
        for(String batchId:batchIds)for(Map<String,Object> entry:eligibleByBatch.getOrDefault(batchId,cn.bidcheck.compat.Java8Api.listOf())){
            String id=text(entry,"occurrenceId");if(selectedEntries.size()<limit)selectedEntries.putIfAbsent(id,entry);
        }
        Map<String,List<Map<String,Object>>> origins=store.mapsByParents("fact_origin",scope.project,selectedEntries.keySet());
        int fieldLimit=integer(req.get("fieldCoverageLimit"),limit);if(fieldLimit<1||fieldLimit>2000)throw new IllegalArgumentException("FIELD_COVERAGE_LOOKUP_LIMIT");
        List<Map<String,Object>> out=new ArrayList<>();Map<String,Map<String,Object>> completedFields=new LinkedHashMap<>(),pendingFields=new LinkedHashMap<>();Set<String> allCompleteFieldKeys=new HashSet<>();
        for(String batchId:batchIds){interrupted();Map<String,Object> batch=batches.getOrDefault(batchId,cn.bidcheck.compat.Java8Api.mapOf());if(!scope.engine.equals(text(batch,"engineIdentity"))||!scope.project.equals(text(batch,"projectId")))continue;
            List<Map<String,Object>> allSources=maps(batch.get("sources"));boolean allValid=allSources.stream().allMatch(s->validSource(scope,s));List<Map<String,Object>> entries=maps(batch.get("entries"));List<Map<String,Object>> eligible=eligibleByBatch.getOrDefault(batchId,cn.bidcheck.compat.Java8Api.listOf());
            List<Map<String,Object>> represented=cn.bidcheck.compat.Java8Api.toList(eligible.stream().filter(entry->selectedEntries.get(text(entry,"occurrenceId"))==entry));
            boolean complete=allValid&&represented.size()==entries.size()&&(!requestedSources.isEmpty()?requestedSources.containsAll(cn.bidcheck.compat.Java8Api.toList(allSources.stream().map(s->text(s,"id")))):true);
            if(Boolean.TRUE.equals(req.get("includeFieldCoverage"))){Map<String,String> occurrencesByFact=new HashMap<>();for(Map<String,Object> entry:eligible)occurrencesByFact.put(text(map(entry.get("fact")),"id"),text(entry,"occurrenceId"));
                for(Map<String,Object> row:maps(batch.get("fieldCoverage"))){List<String> refs=strings(row.get("sourceIds")),factIds=strings(row.get("factIds"));
                    if((!observationKeys.isEmpty()&&!observationKeys.contains(text(row,"observationKey")))||(!requestedSources.isEmpty()&&!requestedSources.containsAll(refs))
                        ||allSources.stream().filter(s->refs.contains(text(s,"id"))).count()!=refs.size()||allSources.stream().filter(s->refs.contains(text(s,"id"))).anyMatch(s->!validSource(scope,s)))continue;
                    List<String> dependencies=cn.bidcheck.compat.Java8Api.toList(factIds.stream().map(occurrencesByFact::get));
                    // A receipt may refer to another batch's model IDs. Resolve only through
                    // validated occurrence identities, with exact current source aliases.
                    if(dependencies.stream().anyMatch(id->id==null||!selectedEntries.containsKey(id)||!refs.containsAll(strings(map(selectedEntries.get(id).get("fact")).get("sourceIds")))))continue;
                    Map<String,Object> receipt=copy(row);receipt.put("factIds",dependencies);
                    Map<String,Object> record=of("recordType","FIELD_COVERAGE","engineVersion",batch.get("engineVersion"),"observationPolicy",text(batch,"observationPolicy"),"operationIdentity",batch.get("operationIdentity"),"batchId",batchId,"fieldCoverage",cn.bidcheck.compat.Java8Api.listOf(receipt),"fieldCoverageSources",allSources);
                    if(!dependencies.isEmpty())record.put("factReferenceType","OCCURRENCE_ID");
                    Map<String,Object> key=new LinkedHashMap<>(receipt);for(String provenance:cn.bidcheck.compat.Java8Api.listOf("reusedFromBatchId", "reusedObservation", "observedInCurrentRun"))key.remove(provenance);
                    Map<String,Map<String,Object>> bucket=FieldCoverage.completeRead(receipt)?completedFields:pendingFields;
                    if(FieldCoverage.completeRead(receipt))allCompleteFieldKeys.add(canonical(key));
                    if(bucket.size()<fieldLimit)bucket.putIfAbsent(canonical(key),copy(record));
                }
            }
            for(Map<String,Object> entry:represented){String occurrence=text(entry,"occurrenceId");Map<String,Object> value=of("occurrenceId",occurrence,"fact",entry.get("fact"),"sources",entry.get("sources"),"engineVersion",batch.get("engineVersion"),"operationIdentity",batch.get("operationIdentity"),"batchId",batchId,"batchFactCount",entries.size(),"coverageComplete",complete,
                    "origins",cn.bidcheck.compat.Java8Api.toList(origins.getOrDefault(occurrence,cn.bidcheck.compat.Java8Api.listOf()).stream().filter(o->scope.engine.equals(text(o,"engineIdentity"))).map(o->of("runId",o.get("runId"),"checkpointId",o.get("checkpointId"),"usageId",o.get("usageId")))));
                putIdentity(value,EntityIdentity.resolve(map(entry.get("fact")),maps(entry.get("sources")),maps(batch.get("layoutCells"))));
                value.put("observationPolicy",text(batch,"observationPolicy"));
                if(complete&&!map(batch.get("extractionCoverage")).isEmpty()){value.put("extractionCoverage",batch.get("extractionCoverage"));value.put("coverageSources",allSources);}out.add(copy(value));}
        }
        int uniqueFacts=out.size();out.addAll(completedFields.values());out.addAll(cn.bidcheck.compat.Java8Api.toList(pendingFields.values().stream().limit(fieldLimit-completedFields.size())));
        boolean factTruncated=eligibleByBatch.values().stream().flatMap(Collection::stream).map(entry->text(entry,"occurrenceId")).distinct().count()>selectedEntries.size();
        annotateLookupLimits(out,req,factTruncated,allCompleteFieldKeys.size()>fieldLimit);
        LOG.info("bidcheck.fact_lookup runId={} checkpointId={} phase={} durationMs={} indexRows={} selectedBatches={} batchLoads={} batchCacheHits={} occurrenceLoads={} originParents={} returned={} uniqueFacts={} fieldReceipts={} sourceHintWaitMs={} sourceHintLoads={}",safeLogId(req.get("runId")),safeLogId(req.get("checkpointId")),safeLogId(req.get("lookupPhase")),(System.nanoTime()-started)/1_000_000,index.size(),batchIds.size(),loaded,cacheHits,loadedOccurrences.size(),selectedEntries.size(),out.size(),uniqueFacts,out.size()-uniqueFacts,selection.waitMs(),selection.loaded());
        return out;
    }
    /** Maintenance entry point; caller must own the same fenced project transaction as publication. */
    public Map<String,Object> migrateLookupIndex(PreparedManifest manifest,int maximum){return migrateLookupIndex(manifest.scope,maximum);}
    /** Empty means that no trustworthy complete revision is available yet. Never cache this read. */
    public String lookupRevision(PreparedManifest manifest){
        Scope scope=manifest.scope;CanonicalFactLookup index=new CanonicalFactLookup(store);
        if(!index.ready(scope,scope.documents.keySet()))return "";
        List<String> ids=cn.bidcheck.compat.Java8Api.toList(scope.documentParents.values().stream().map(CanonicalFactLookup::revisionId).sorted());
        Map<String,Map<String,Object>> rows=store.mapsByIds("fact_lookup_revision",scope.project,ids);
        List<String> revisions=new ArrayList<>();for(String id:ids){Map<String,Object> row=rows.getOrDefault(id,cn.bidcheck.compat.Java8Api.mapOf());
            if(!row.isEmpty()&&(!scope.project.equals(text(row,"projectId"))||!CanonicalFactLookup.VERSION.equals(text(row,"version"))))return "";
            revisions.add(id+":"+text(row,"digest"));
        }
        return hash(canonical(cn.bidcheck.compat.Java8Api.listOf(CanonicalFactLookup.VERSION, scope.manifestIdentity, revisions)));
    }
    private Map<String,Object> migrateLookupIndex(Scope scope,int maximum){
        Map<String,Object> result=new CanonicalFactLookup(store).migrate(scope,maximum,batch->{
            if(!scope.project.equals(text(batch,"projectId"))||!scope.engine.equals(text(batch,"engineIdentity")))throw new IllegalArgumentException("LOOKUP_LEGACY_SCOPE_INVALID");
            List<Map<String,Object>> sources=maps(batch.get("sources"));
            if(sources.isEmpty()||sources.stream().anyMatch(s->!validSource(scope,s)))throw new IllegalArgumentException("LOOKUP_LEGACY_SOURCE_INVALID");
            List<Map<String,Object>> verified=new ArrayList<>();List<String> requested=cn.bidcheck.compat.Java8Api.toList(maps(batch.get("entries")).stream().map(e->text(e,"occurrenceId")).distinct());
            Map<String,Map<String,Object>> occurrences=core.getAll(requested);occurrences.putAll(store.mapsByIds("fact_occurrence",scope.project,cn.bidcheck.compat.Java8Api.toList(requested.stream().filter(id->!occurrences.containsKey(id)))));
            for(Map<String,Object> entry:maps(batch.get("entries"))){Map<String,Object> value=materializeEntry(scope,entry,occurrences.getOrDefault(text(entry,"occurrenceId"),cn.bidcheck.compat.Java8Api.mapOf()));
                if(value.isEmpty())throw new IllegalArgumentException("LOOKUP_LEGACY_OCCURRENCE_INVALID");
                Map<String,Map<String,Object>> refs=new LinkedHashMap<>();for(Map<String,Object> source:maps(value.get("sources")))refs.put(text(source,"id"),source);
                Map<String,Object> proof=fact(map(value.get("fact")),refs);
                if(!canonical(proof).equals(canonical(value.get("fact"))))throw new IllegalArgumentException("LOOKUP_LEGACY_FACT_INVALID");
                value.put("originalFactId",text(entry,"originalFactId"));verified.add(value);
            }
            return verified;
        });
        if(integer(result.get("processed"),0)>0||maps(result.get("documents")).stream().anyMatch(row->"BLOCKED".equals(row.get("status"))))
            LOG.info("bidcheck.fact_lookup_migration projectId={} processed={} indexWrites={} documents={}",safeLogId(scope.project),result.get("processed"),result.get("writes"),result.get("documents"));
        return result;
    }
    private static final class CanonicalLookupInvalid extends RuntimeException {CanonicalLookupInvalid(String message){super(message);}}
    /** Queries only current source/type/observation buckets, then hydrates canonical occurrences.
     * There is deliberately no document-wide batch enumeration and no cached negative lookup. */
    private List<Map<String,Object>> lookupCanonical(PreparedManifest manifest,Map<String,Object> request,int limit,long readinessNanos){
        long started=System.nanoTime();Scope scope=manifest.scope;int fieldLimit=integer(request.get("fieldCoverageLimit"),limit);
        if(fieldLimit<1||fieldLimit>2000)throw new IllegalArgumentException("FIELD_COVERAGE_LOOKUP_LIMIT");
        Set<String> selected=new LinkedHashSet<>(strings(request.get("sourceIds"))),docs=new LinkedHashSet<>(strings(request.get("documentIds")));
        if(docs.isEmpty())docs.addAll(scope.documents.keySet());Set<String> kinds=new LinkedHashSet<>(strings(request.get("factKinds")));
        List<String> observations=strings(request.get("observationKeys"));String model=text(request,"modelIdentity");
        boolean includeFields=Boolean.TRUE.equals(request.get("includeFieldCoverage"));
        if(includeFields&&(observations.isEmpty()||cn.bidcheck.compat.Java8Api.isBlank(model)))throw new IllegalArgumentException("FIELD_LOOKUP_IDENTITY_REQUIRED");
        Map<String,Map<String,Object>> current=new LinkedHashMap<>();
        for(Map<String,Object> row:maps(request.get("sources"))){Map<String,Object> source;
            try{source=source(scope,row);}catch(IllegalArgumentException invalid){throw new CanonicalLookupInvalid("REQUEST_SOURCE_INVALID");}
            String id=text(source,"id");if(!selected.contains(id)||current.putIfAbsent(id,source)!=null)throw new CanonicalLookupInvalid("REQUEST_SOURCE_ALIAS_INVALID");
        }
        if(!current.keySet().equals(selected))throw new CanonicalLookupInvalid("REQUEST_SOURCE_SET_INCOMPLETE");
        Set<String> factBuckets=new LinkedHashSet<>(),fieldBuckets=new LinkedHashSet<>();
        boolean bySubject="CANONICAL_SUBJECT".equals(text(request,"lookupMode"));
        if(bySubject){for(String doc:docs)for(String subject:strings(request.get("subjects")))for(String kind:kinds)
            factBuckets.add(CanonicalFactLookup.bucket(scope.documentParents.get(doc),"S:"+CanonicalFactLookup.subjectKey(subject),kind,"",""));}
        else for(String doc:docs)for(String id:selected){Map<String,Object> source=current.get(id);
            if(source!=null&&!doc.equals(text(evidence(source.get("evidence")),"documentId")))continue;
            String token=source==null?"A:"+id:"F:"+text(source,"sourceFingerprint"),parent=scope.documentParents.get(doc);
            for(String kind:kinds)factBuckets.add(CanonicalFactLookup.bucket(parent,token,kind,"",""));
            if(includeFields)for(String observation:observations)fieldBuckets.add(CanonicalFactLookup.bucket(parent,token,"FIELD",observation,model));
        }
        Map<String,Map<String,Object>> refs=new LinkedHashMap<>();
        long sourceValidationDone=System.nanoTime();
        readCanonicalRefs(scope,"fact_lookup_ref",factBuckets,refs);readCanonicalRefs(scope,"field_lookup_ref",fieldBuckets,refs);
        long bucketLookupDone=System.nanoTime();
        Map<String,Map<String,Object>> items=store.mapsByIds("fact_lookup_item",scope.project,refs.keySet());
        for(java.util.Map.Entry<java.lang.String, java.util.Map<java.lang.String, java.lang.Object>> ref:refs.entrySet()){Map<String,Object> item=items.getOrDefault(ref.getKey(),cn.bidcheck.compat.Java8Api.mapOf());
            if(!CanonicalFactLookup.validItem(item,scope)||!text(ref.getValue(),"itemDigest").equals(text(item,"digest")))throw new CanonicalLookupInvalid("LOOKUP_REFERENCE_CORRUPT");}
        Set<String> dependencies=new LinkedHashSet<>();for(Map<String,Object> item:items.values())dependencies.addAll(strings(item.get("dependencyItemIds")));
        dependencies.removeAll(items.keySet());Map<String,Map<String,Object>> dependencyItems=store.mapsByIds("fact_lookup_item",scope.project,dependencies);
        for(String id:dependencies)if(!CanonicalFactLookup.validItem(dependencyItems.getOrDefault(id,cn.bidcheck.compat.Java8Api.mapOf()),scope))throw new CanonicalLookupInvalid("LOOKUP_DEPENDENCY_CORRUPT");
        items.putAll(dependencyItems);
        long itemLookupDone=System.nanoTime();
        Set<String> ids=new LinkedHashSet<>();for(Map<String,Object> item:items.values())if(!cn.bidcheck.compat.Java8Api.isBlank(text(item,"occurrenceId")))ids.add(text(item,"occurrenceId"));
        Map<String,Map<String,Object>> occurrences=new LinkedHashMap<>();List<String> missing=new ArrayList<>();
        for(String id:ids){Map<String,Object> cached=manifest.occurrences.get(id);if(cached==null)missing.add(id);else occurrences.put(id,cached);}
        Map<String,Map<String,Object>> fetched=core.getAll(missing);fetched.putAll(store.mapsByIds("fact_occurrence",scope.project,cn.bidcheck.compat.Java8Api.toList(missing.stream().filter(id->!fetched.containsKey(id)))));
        for(java.util.Map.Entry<java.lang.String, java.util.Map<java.lang.String, java.lang.Object>> entry:fetched.entrySet()){manifest.occurrences.put(entry.getKey(),entry.getValue());occurrences.put(entry.getKey(),entry.getValue());}
        long occurrenceLookupDone=System.nanoTime();
        Map<String,Map<String,Object>> accepted=new LinkedHashMap<>();Map<String,String> itemOccurrences=new HashMap<>();
        for(Map<String,Object> item:items.values()){
            String occurrence=text(item,"occurrenceId");if(cn.bidcheck.compat.Java8Api.isBlank(occurrence)||!kinds.contains(text(item,"factKind")))continue;
            if(!occurrences.containsKey(occurrence))throw new CanonicalLookupInvalid("LOOKUP_OCCURRENCE_MISSING");
            List<Map<String,Object>> sources=currentAliases(scope,maps(item.get("sources")),current,selected,docs);if(sources.isEmpty())continue;
            Map<String,Object> entry=materializeEntry(scope,of("occurrenceId",occurrence,"sources",sources,"fact",of("id",text(item,"originalFactId"))),occurrences.get(occurrence));
            if(entry.isEmpty())throw new CanonicalLookupInvalid("LOOKUP_OCCURRENCE_INVALID");
            if(bySubject&&!CanonicalFactLookup.subjectMatches(map(entry.get("fact")),strings(request.get("subjects"))))continue;
            Map<String,Map<String,Object>> byAlias=new LinkedHashMap<>();sources.forEach(s->byAlias.put(text(s,"id"),s));
            try{fact(map(entry.get("fact")),byAlias);}catch(IllegalArgumentException invalid){throw new CanonicalLookupInvalid("LOOKUP_FACT_UNGROUNDED");}
            itemOccurrences.put(text(item,"id"),occurrence);if(!Boolean.TRUE.equals(request.get("paged"))&&accepted.size()>=limit&&!accepted.containsKey(occurrence))continue;
            Map<String,Object> value=of("occurrenceId",occurrence,"fact",entry.get("fact"),"sources",sources,"engineVersion",item.get("engineVersion"),"observationPolicy",item.get("observationPolicy"),"operationIdentity",item.get("operationIdentity"),"batchId",item.get("batchId"),"batchFactCount",1,"coverageComplete",false,"lookupItemId",item.get("id"));
            putIdentity(value,EntityIdentity.resolve(map(entry.get("fact")),sources,maps(item.get("layoutCells"))));accepted.putIfAbsent(occurrence,value);
        }
        boolean includeOrigins=Boolean.TRUE.equals(request.get("includeOrigins"));
        Map<String,List<Map<String,Object>>> origins=includeOrigins?store.mapsByParents("fact_origin",scope.project,accepted.keySet()):cn.bidcheck.compat.Java8Api.mapOf();
        for(java.util.Map.Entry<java.lang.String, java.util.Map<java.lang.String, java.lang.Object>> row:accepted.entrySet()){row.getValue().put("origins",cn.bidcheck.compat.Java8Api.toList(origins.getOrDefault(row.getKey(),cn.bidcheck.compat.Java8Api.listOf()).stream().filter(o->scope.engine.equals(text(o,"engineIdentity"))).map(o->of("runId",o.get("runId"),"checkpointId",o.get("checkpointId"),"usageId",o.get("usageId")))));row.getValue().put("originsIncluded",includeOrigins);}
        Map<String,Map<String,Object>> receipts=new LinkedHashMap<>();int candidateReceipts=0;
        for(Map<String,Object> item:items.values()){
            Map<String,Object> receipt=map(item.get("fieldCoverage"));if(receipt.isEmpty())continue;
            if(!FieldCoverage.completeRead(receipt)||!model.equals(text(receipt,"modelIdentity"))||!observations.contains(text(receipt,"observationKey")))throw new CanonicalLookupInvalid("LOOKUP_FIELD_CONTRACT_INVALID");
            List<Map<String,Object>> sources=currentAliases(scope,maps(item.get("sources")),current,selected,docs);if(sources.isEmpty())continue;
            List<String> facts=cn.bidcheck.compat.Java8Api.toList(strings(item.get("dependencyItemIds")).stream().map(itemOccurrences::get));
            if(facts.stream().anyMatch(id->id==null||!accepted.containsKey(id)))continue;
            Map<String,Map<String,Object>> byAlias=new LinkedHashMap<>();sources.forEach(s->byAlias.put(text(s,"id"),s));
            Map<String,Object> checked=copy(receipt);checked.put("sourceIds",cn.bidcheck.compat.Java8Api.toList(sources.stream().map(s->text(s,"id"))));checked.put("factIds",facts);
            List<Map<String,Object>> validatedFacts=cn.bidcheck.compat.Java8Api.toList(facts.stream().map(id->of("originalFactId",id,"fact",accepted.get(id).get("fact"))));
            if(FieldCoverage.validate(cn.bidcheck.compat.Java8Api.listOf(checked),byAlias,validatedFacts).size()!=1)throw new CanonicalLookupInvalid("LOOKUP_FIELD_DEPENDENCY_INVALID");
            candidateReceipts++;if(!Boolean.TRUE.equals(request.get("paged"))&&receipts.size()>=fieldLimit)continue;
            Map<String,Object> row=of("recordType","FIELD_COVERAGE","engineVersion",item.get("engineVersion"),"observationPolicy",item.get("observationPolicy"),"operationIdentity",item.get("operationIdentity"),"batchId",item.get("batchId"),"fieldCoverage",cn.bidcheck.compat.Java8Api.listOf(checked),"fieldCoverageSources",sources,"factReferenceType","OCCURRENCE_ID","lookupItemId",item.get("id"));
            receipts.putIfAbsent(canonical(checked),row);
        }
        List<Map<String,Object>> result=new ArrayList<>(accepted.values());result.addAll(receipts.values());boolean factTruncated=itemOccurrences.values().stream().distinct().count()>accepted.size(),fieldTruncated=candidateReceipts>fieldLimit;
        annotateLookupLimits(result,request,factTruncated,fieldTruncated);
        LOG.info("bidcheck.fact_lookup runId={} checkpointId={} phase={} mode={} durationMs={} readinessMs={} sourceValidationMs={} bucketLookupMs={} itemLookupMs={} occurrenceLookupMs={} validationAndAssemblyMs={} sourceBuckets={} candidateRefs={} uniqueItems={} occurrenceLoads={} occurrenceCacheHits={} batchLoads=0 uniqueFacts={} fieldReceipts={} factTruncated={} fieldTruncated={}",safeLogId(request.get("runId")),safeLogId(request.get("checkpointId")),safeLogId(request.get("lookupPhase")),bySubject?"CANONICAL_SUBJECT":"CANONICAL_SOURCE",(System.nanoTime()-started+readinessNanos)/1_000_000,readinessNanos/1_000_000,(sourceValidationDone-started)/1_000_000,(bucketLookupDone-sourceValidationDone)/1_000_000,(itemLookupDone-bucketLookupDone)/1_000_000,(occurrenceLookupDone-itemLookupDone)/1_000_000,(System.nanoTime()-occurrenceLookupDone)/1_000_000,factBuckets.size()+fieldBuckets.size(),refs.size(),items.size(),fetched.size(),ids.size()-missing.size(),accepted.size(),receipts.size(),factTruncated,fieldTruncated);
        return Boolean.TRUE.equals(request.get("paged"))?canonicalPage(request,result,limit,current,bySubject):result;
    }
    private static List<Map<String,Object>> canonicalPage(Map<String,Object> request,List<Map<String,Object>> rows,int limit,
            Map<String,Map<String,Object>> current,boolean bySubject){
        // Bind the cursor to both the verified query and the immutable item set. Concurrent
        // publications invalidate a continuation instead of silently skipping counterevidence.
        rows.sort(Comparator.comparing(row->text(row,"lookupItemId")));
        List<String> itemIds=new ArrayList<>();
        for(Map<String,Object> row:rows)itemIds.add(text(row,"lookupItemId"));
        String signature=hash(canonical(Arrays.asList(current.keySet(),request.get("observationKeys"),request.get("subjects"),request.get("factKinds"),itemIds)));
        int offset=0;
        String cursor=text(request,"cursor");
        if(!cursor.isEmpty()){
            String[] parts=cursor.split(":",-1);
            if(parts.length==2&&signature.equals(parts[0]))try{offset=Integer.parseInt(parts[1]);}catch(NumberFormatException invalid){offset=-1;}
            else offset=-1;
            if(offset<=0||offset>=rows.size()){
                List<Map<String,Object>> stale=new ArrayList<>();annotateLookupLimits(stale,request,true,true);return stale;
            }
        }
        int end=Math.min(rows.size(),offset+limit);
        List<Map<String,Object>> page=new ArrayList<>(rows.subList(offset,end));
        if(page.isEmpty())page.add(of("recordType","LOOKUP_METADATA"));
        for(Map<String,Object> row:page){
            row.remove("lookupTruncated");row.remove("factLookupTruncated");row.remove("fieldLookupTruncated");row.remove("lookupTruncation");
        }
        Map<String,Object> receipt=page.get(0);
        if(end<rows.size())receipt.put("nextCursor",signature+":"+end);
        else {receipt.put("lookupPagesComplete",true);if(!bySubject)receipt.put("coveredSourceIds",new ArrayList<>(current.keySet()));}
        return page;
    }
    private static void annotateLookupLimits(List<Map<String,Object>> rows,Map<String,Object> request,boolean factTruncated,boolean fieldTruncated){
        if(!factTruncated&&!fieldTruncated)return;
        Map<String,Object> scope=of("phase",text(request,"lookupPhase"),"sourceIds",strings(request.get("sourceIds")),"factKinds",strings(request.get("factKinds")),"observationKeys",strings(request.get("observationKeys")),"reason","LOOKUP_RESULT_CAPACITY");
        if(rows.isEmpty())rows.add(of("recordType","LOOKUP_METADATA"));
        for(Map<String,Object> row:rows){row.put("lookupTruncated",true);row.put("factLookupTruncated",factTruncated);row.put("fieldLookupTruncated",fieldTruncated);row.put("lookupTruncation",scope);}
    }
    private void readCanonicalRefs(Scope scope,String kind,Collection<String> buckets,Map<String,Map<String,Object>> refs){
        for(java.util.Map.Entry<java.lang.String, java.util.List<java.util.Map<java.lang.String, java.lang.Object>>> bucket:store.mapsByParents(kind,scope.project,buckets).entrySet())for(Map<String,Object> row:bucket.getValue()){
            if(!CanonicalFactLookup.VERSION.equals(text(row,"version"))||!scope.project.equals(text(row,"projectId"))||!bucket.getKey().equals(text(row,"bucket"))||cn.bidcheck.compat.Java8Api.isBlank(text(row,"itemId")))throw new CanonicalLookupInvalid("LOOKUP_BUCKET_CORRUPT");
            Map<String,Object> previous=refs.putIfAbsent(text(row,"itemId"),row);if(previous!=null&&!text(previous,"itemDigest").equals(text(row,"itemDigest")))throw new CanonicalLookupInvalid("LOOKUP_REFERENCE_CONFLICT");
        }
    }
    private List<Map<String,Object>> currentAliases(Scope scope,List<Map<String,Object>> old,Map<String,Map<String,Object>> current,Set<String> selected,Set<String> docs){
        if(old.isEmpty()||old.stream().anyMatch(s->!validSource(scope,s)))throw new CanonicalLookupInvalid("LOOKUP_SOURCE_INVALID");
        List<Map<String,Object>> remapped=new ArrayList<>();Set<String> aliases=new HashSet<>();
        for(Map<String,Object> source:old){if(!docs.contains(text(evidence(source.get("evidence")),"documentId")))return cn.bidcheck.compat.Java8Api.listOf();Map<String,Object> now=source;
            if(!current.isEmpty()){now=current.values().stream().filter(s->text(s,"sourceFingerprint").equals(text(source,"sourceFingerprint"))).sorted(Comparator.comparing(s->text(s,"id").equals(text(source,"id"))?"":text(s,"id"))).findFirst().orElse(null);}
            if(now==null||!selected.contains(text(now,"id"))||!aliases.add(text(now,"id")))return cn.bidcheck.compat.Java8Api.listOf();remapped.add(now);
        }
        return remapped;
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class SourceSelection {
        private final List<String> ids;
        private final int loaded;
        private final long waitMs;
        @com.fasterxml.jackson.annotation.JsonCreator
        private SourceSelection(@com.fasterxml.jackson.annotation.JsonProperty("ids") List<String> ids, @com.fasterxml.jackson.annotation.JsonProperty("loaded") int loaded, @com.fasterxml.jackson.annotation.JsonProperty("waitMs") long waitMs) { this.ids = ids; this.loaded = loaded; this.waitMs = waitMs; }
        public List<String> ids() { return ids; }
        public int loaded() { return loaded; }
        public long waitMs() { return waitMs; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; SourceSelection that = (SourceSelection) other; return java.util.Objects.equals(this.ids, that.ids) && java.util.Objects.equals(this.loaded, that.loaded) && java.util.Objects.equals(this.waitMs, that.waitMs); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.ids); hash = 31 * hash + java.util.Objects.hashCode(this.loaded); hash = 31 * hash + java.util.Objects.hashCode(this.waitMs); return hash; }
        @Override public String toString() { return "SourceSelection[" + "ids=" + ids + ", loaded=" + loaded + ", waitMs=" + waitMs + "]"; }
}
    private SourceSelection selectBatches(PreparedManifest manifest,Map<String,Map<String,Object>> index,Set<String> requestedSources){
        long started=System.nanoTime();
        // Only legacy source-hint initialization is serialized. Recheck after acquiring so cold
        // checkpoint threads share the work; index membership above is refreshed on every lookup.
        try{manifest.sourceHintsLock.lockInterruptibly();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("事实来源索引初始化已中断");}
        try{long waitMs=(System.nanoTime()-started)/1_000_000;interrupted();List<String> selected=new ArrayList<>(),ids=new ArrayList<>(index.keySet());int loaded=0;
            for(int at=0;at<ids.size();at+=100){List<String> part=ids.subList(at,Math.min(ids.size(),at+100)),missing=new ArrayList<>();Map<String,Set<String>> hints=new HashMap<>();
                for(String id:part){Set<String> hint=manifest.sourceHints.get(id);if(hint==null){Map<String,Object> row=index.get(id);if(integer(row.get("sourceIndexVersion"),0)==1&&row.get("sourceIds") instanceof List<?>){hint=new HashSet<>(strings(row.get("sourceIds")));manifest.sourceHints.put(id,hint);}else missing.add(id);}if(hint!=null)hints.put(id,hint);}
                Map<String,Map<String,Object>> values=store.mapsByIds("fact_batch",manifest.scope.project,missing);loaded+=values.size();values.forEach((id,batch)->{rememberBatch(manifest,id,batch);hints.put(id,manifest.sourceHints.get(id));});
                // Use this chunk's snapshot, rather than rereading the bounded cache after it
                // may have evicted an earlier hint from an unusually large manifest.
                for(String id:part)if(requestedSources.isEmpty()||!Collections.disjoint(requestedSources,hints.getOrDefault(id,cn.bidcheck.compat.Java8Api.setOf())))selected.add(id);
                interrupted();
            }return new SourceSelection(cn.bidcheck.compat.Java8Api.listCopyOf(selected),loaded,waitMs);
        }finally{manifest.sourceHintsLock.unlock();}
    }
    private static String safeLogId(Object value){String text=Objects.toString(value,"");return text.matches("[A-Za-z0-9_.:-]{1,100}")?text:"";}
    private static void rememberBatch(PreparedManifest manifest,String id,Map<String,Object> batch){
        Set<String> sources=new HashSet<>();maps(batch.get("sources")).forEach(s->sources.add(text(s,"id")));for(Map<String,Object> entry:maps(batch.get("entries")))maps(entry.get("sources")).forEach(s->sources.add(text(s,"id")));
        manifest.sourceHints.put(id,sources);manifest.batches.put(id,batch);
    }

    public Map<String,Object> view(String projectId,String runId,String engineIdentity,List<Document> manifestBidDocs){Scope scope=new Scope(projectId,engineIdentity,manifestBidDocs);checkName(runId);Map<String,Object> frozen=read("fact_snapshot",snapshotId(scope,runId));return frozen.isEmpty()?assemble(scope,runId,false):copy(frozen);}
    public Map<String,Object> snapshot(String projectId,String runId,String engineIdentity,List<Document> manifestBidDocs){Scope scope=new Scope(projectId,engineIdentity,manifestBidDocs);checkName(runId);String id=snapshotId(scope,runId);Map<String,Object> previous=read("fact_snapshot",id);if(!previous.isEmpty())return copy(previous);Map<String,Object> snapshot=assemble(scope,runId,true);store.save("fact_snapshot",id,scope.project,runId,snapshot);return copy(snapshot);}
    private Map<String,Object> assemble(Scope scope,String runId,boolean frozen){
        List<Map<String,Object>> uses=runUses(scope,runId);
        Map<String,List<Map<String,Object>>> byOccurrence=new LinkedHashMap<>();uses.forEach(u->byOccurrence.computeIfAbsent(text(u,"occurrenceId"),k->new ArrayList<>()).add(u));
        List<Map<String,Object>> occurrences=new ArrayList<>(),relations=new ArrayList<>();Map<String,Map<String,Object>> entities=new LinkedHashMap<>();Map<String,List<Map<String,Object>>> conflicts=new LinkedHashMap<>();
        for(java.util.Map.Entry<java.lang.String, java.util.List<java.util.Map<java.lang.String, java.lang.Object>>> group:byOccurrence.entrySet()){interrupted();Map<String,Object> occurrence=read("fact_occurrence",group.getKey());List<Map<String,Object>> sources=maps(occurrence.get("sources"));if(!scope.project.equals(text(occurrence,"projectId"))||!scope.engine.equals(text(occurrence,"engineIdentity"))||sources.isEmpty()||sources.stream().anyMatch(s->!validSource(scope,s)))continue;
            Map<String,Object> fact=map(occurrence.get("fact")),entity=map(occurrence.get("entity"));List<Map<String,Object>> refs=cn.bidcheck.compat.Java8Api.toList(sources.stream().map(s->map(s.get("evidence"))));
            Map<String,Object> item=new LinkedHashMap<>(occurrence);for(String key:cn.bidcheck.compat.Java8Api.listOf("kind", "subject", "field", "rawValue", "value", "unit", "basis", "scope", "attributes"))item.put(key,fact.get(key));putIdentity(item,entity);item.put("sourceRefs",refs);item.put("usedByCheckpoints",cn.bidcheck.compat.Java8Api.toList(group.getValue().stream().map(u->text(u,"checkpointId")).distinct().map(id->of("id",id))));item.put("origins",group.getValue());occurrences.add(item);
            Map<String,Object> aggregate=entities.computeIfAbsent(text(entity,"id"),k->{Map<String,Object> e=new LinkedHashMap<>(entity);e.put("occurrenceIds",new ArrayList<String>());e.put("sourceRefs",new ArrayList<Map<String,Object>>());return e;});append(aggregate,"occurrenceIds",group.getKey());for(Map<String,Object> ref:refs)append(aggregate,"sourceRefs",ref);
            if(cn.bidcheck.compat.Java8Api.setOf("RELATION", "PERSON_ROLE").contains(text(fact,"kind"))){Map<String,Object> relation=map(occurrence.get("relation"));if(relation.isEmpty())relation=of("id",group.getKey(),"entityId",entity.get("id"),"kind",fact.get("kind"),"subject",fact.get("subject"),"field",fact.get("field"),"predicate",fact.get("field"),"object","RELATION".equals(text(fact,"kind"))?map(fact.get("attributes")).get("object"):fact.get("value"),"resolution","SOURCE_BOUND","value",fact.get("value"),"attributes",fact.get("attributes"),"scope",fact.get("scope"));relation.put("sourceRefs",refs);relation.put("usedByCheckpoints",item.get("usedByCheckpoints"));relations.add(relation);}
            String key=EntityIdentity.conflictKey(fact,entity,sources);if(!cn.bidcheck.compat.Java8Api.isBlank(key))conflicts.computeIfAbsent(key,k->new ArrayList<>()).add(item);
        }
        List<Map<String,Object>> conflictRows=new ArrayList<>();for(java.util.Map.Entry<java.lang.String, java.util.List<java.util.Map<java.lang.String, java.lang.Object>>> group:conflicts.entrySet()){List<Map<String,Object>> variants=group.getValue();if(variants.stream().map(v->comparisonValue(map(v.get("fact")))).distinct().count()<2)continue;conflictRows.add(of("id",dbKey("conflict",group.getKey()),"status","REVIEW_REQUIRED","entityId",map(variants.get(0).get("entity")).get("id"),"field",map(variants.get(0).get("fact")).get("field"),"reason","相同强身份、字段、单位、口径、时点及范围出现不同值，保留各自原文，未自动裁决","occurrenceIds",cn.bidcheck.compat.Java8Api.toList(variants.stream().map(v->v.get("occurrenceId"))),"sourceRefs",cn.bidcheck.compat.Java8Api.toList(variants.stream().flatMap(v->maps(v.get("sourceRefs")).stream()).distinct())));}
        List<Map<String,Object>> coverageDependencies=coverageUses(scope,runId),fieldCoverage=new ArrayList<>();
        for(Map<String,Object> usage:coverageDependencies){Map<String,Object> batch=read("fact_batch",text(usage,"batchId"));Map<String,Map<String,Object>> validSources=new LinkedHashMap<>();
            for(Map<String,Object> source:maps(batch.get("sources")))if(validSource(scope,source))validSources.put(text(source,"id"),source);
            for(Map<String,Object> row:FieldCoverage.validate(batch.get("fieldCoverage"),validSources,maps(batch.get("entries")))){Map<String,Object> item=copy(row);item.put("checkpointId",usage.get("checkpointId"));item.put("batchId",usage.get("batchId"));item.put("sources",cn.bidcheck.compat.Java8Api.toList(strings(row.get("sourceIds")).stream().map(validSources::get)));fieldCoverage.add(item);}
        }
        long shared=occurrences.stream().filter(o->maps(o.get("usedByCheckpoints")).size()>1).count();Map<String,Object> summary=of("occurrences",occurrences.size(),"entities",entities.size(),"relations",relations.size(),"conflicts",conflictRows.size(),"sharedOccurrences",shared,"fieldObservations",fieldCoverage.size());
        return of("id",snapshotId(scope,runId),"projectId",scope.project,"runId",runId,"engineIdentity",scope.engine,"ledgerVersion",VERSION,"manifestIdentity",scope.manifestIdentity,"frozen",frozen,"summary",summary,"stats",summary,"occurrences",occurrences,"entities",new ArrayList<>(entities.values()),"relations",relations,"conflicts",conflictRows,"dependencies",uses,"coverageDependencies",coverageDependencies,"fieldCoverage",fieldCoverage,"limitations",cn.bidcheck.compat.Java8Api.listOf("同名只作为候选，不据此跨来源确认同一实体；未能绑定强标识的事实保留来源内身份。", "冲突汇总只覆盖本运行已使用事实；不把未检出冲突解释为全项目无冲突。", "字段读取状态只覆盖列明来源；已读未见不证明整份材料缺失，提取失败不影响其他已验证字段。"));
    }
    @SuppressWarnings("unchecked") private static void append(Map<String,Object> map,String key,Object value){List<Object> values=(List<Object>)map.get(key);if(!values.contains(value))values.add(value);}
    private static void putIdentity(Map<String,Object> target,Map<String,Object> entity){target.put("entity",entity);target.put("entityId",entity.get("id"));target.put("identityStrength",entity.get("identityStrength"));target.put("entityType",entity.get("type"));target.put("displayName",entity.get("displayName"));target.put("identityClaims",entity.getOrDefault("identityClaims",cn.bidcheck.compat.Java8Api.listOf()));}
    static Map<String,Object> source(Scope scope,Map<String,Object> input){Map<String,Object> e=evidence(input.get("evidence"));String id=text(e,"documentId");Document doc=scope.documents.get(id);if(doc==null||!scope.project.equals(text(input,"projectId"))||!doc.sha256().equals(text(input,"documentHash"))||doc.version()!=integer(input.get("documentVersion"),-1))throw new IllegalArgumentException("SOURCE_OUTSIDE_MANIFEST");
        int page=integer(e.get("page"),-1);String pageKey=Scope.pageKey(doc.id(),text(e,"locationType"),page);Page original=scope.pages.get(pageKey);if(original==null)throw new IllegalArgumentException("SOURCE_LOCATION_INVALID");String fingerprint=scope.pageFingerprints.get(pageKey);String quote=text(e,"quote");if(cn.bidcheck.compat.Java8Api.isBlank(quote)||quote.length()>10000||!Objects.toString(original.text(),"").contains(quote))throw new IllegalArgumentException("SOURCE_NOT_IN_MANIFEST");
        if(!fingerprint.equals(text(input,"parserFingerprint")))throw new IllegalArgumentException("SOURCE_PARSER_MISMATCH");
        double confidence=e.get("confidence") instanceof Number?((Number) (e.get("confidence"))).doubleValue():0;if(!Double.isFinite(confidence)||confidence<0||confidence>1)throw new IllegalArgumentException("SOURCE_CONFIDENCE_INVALID");
        Map<String,Object> canonicalEvidence=of("documentId",doc.id(),"documentName",doc.name(),"page",page,"locationType",original.locationType(),"quote",quote,"anchor",text(e,"anchor"),"confidence",confidence);
        return of("id",text(input,"id"),"projectId",scope.project,"evidence",canonicalEvidence,"documentHash",doc.sha256(),"documentVersion",doc.version(),"parserFingerprint",fingerprint,"sourceFingerprint",hash(canonical(cn.bidcheck.compat.Java8Api.listOf(doc.id(), doc.sha256(), doc.version(), fingerprint, canonicalEvidence))));
    }
    static boolean validSource(Scope scope,Map<String,Object> source){try{return text(source(scope,source),"sourceFingerprint").equals(text(source,"sourceFingerprint"));}catch(IllegalArgumentException e){return false;}}
    static Map<String,Object> fact(Map<String,Object> input,Map<String,Map<String,Object>> sources){String kind=text(input,"kind"),raw=text(input,"rawValue");List<String> ids=strings(input.get("sourceIds"));if(!KINDS.contains(kind)||cn.bidcheck.compat.Java8Api.isBlank(raw)||raw.length()>8000||ids.isEmpty()||ids.size()>16||ids.stream().distinct().count()!=ids.size()||!sources.keySet().containsAll(ids))throw new IllegalArgumentException("FACT_SOURCE_OR_TYPE_INVALID");
        if(text(input,"id").length()>200)throw new IllegalArgumentException("FACT_ID_LIMIT");
        if(ids.stream().map(sources::get).map(s->text(map(s.get("evidence")),"quote")).noneMatch(q->clean(q).contains(clean(raw))))throw new IllegalArgumentException("FACT_RAW_VALUE_UNGROUNDED");
        if(!cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "HISTORICAL_PROJECT", "UNRESOLVED").contains(text(input,"scope")))throw new IllegalArgumentException("FACT_SCOPE_INVALID");
        Map<String,Object> attrs=map(input.get("attributes"));if(canonical(attrs).length()>32000)throw new IllegalArgumentException("FACT_ATTRIBUTES_LIMIT");
        if(kind.equals("RELATION")){String object=text(attrs,"object");if(ids.stream().map(sources::get).map(s->text(map(s.get("evidence")),"quote")).noneMatch(q->EntityIdentity.local(q,text(input,"subject"),object)&&q.contains(text(input,"field"))&&!cn.bidcheck.compat.Java8Api.isBlank(text(input,"field"))))throw new IllegalArgumentException("FACT_RELATION_UNGROUNDED");}
        Map<String,Object> fact=new LinkedHashMap<>();for(String key:cn.bidcheck.compat.Java8Api.listOf("kind", "subject", "field", "rawValue", "value", "unit", "basis", "scope")){String value=text(input,key);if(value.length()>8000)throw new IllegalArgumentException("FACT_VALUE_LIMIT");fact.put(key,value);}fact.put("sourceIds",cn.bidcheck.compat.Java8Api.listCopyOf(ids));fact.put("attributes",copy(attrs));if(!cn.bidcheck.compat.Java8Api.isBlank(text(input,"id")))fact.put("id",text(input,"id"));return fact;
    }
    private static Map<String,Object> coverage(Object raw,Set<String> sources,Set<String> facts,List<?> rejected,int accepted){Map<String,Object> c=map(raw);if(accepted==0||!rejected.isEmpty()||!"VALIDATED".equals(c.get("status"))||cn.bidcheck.compat.Java8Api.isBlank(text(c,"modelIdentity"))||strings(c.get("ruleSignatures")).isEmpty()||!sources.equals(new HashSet<>(strings(c.get("sourceIds"))))||!facts.equals(new HashSet<>(strings(c.get("factIds"))))||facts.size()!=accepted)return cn.bidcheck.compat.Java8Api.mapOf();return copy(c);}
    private Map<String,Object> read(String kind,String id){if(kind.equals("fact_occurrence")){Map<String,Object> value=core.get(id);if(!value.isEmpty())return value;}return readRecord(kind,id);}
    private Map<String,Object> readRecord(String kind,String id){try{return store.getMap(kind,id);}catch(ResponseStatusException e){if(e.getStatus().value()==404)return cn.bidcheck.compat.Java8Api.mapOf();throw e;}}
    private Map<String,Object> materializeEntry(Scope scope,Map<String,Object> entry){
        return materializeEntry(scope,entry,read("fact_occurrence",text(entry,"occurrenceId")));
    }
    private Map<String,Object> materializeEntry(Scope scope,Map<String,Object> entry,Map<String,Object> occurrence){
        if(occurrence.isEmpty()||!scope.project.equals(text(occurrence,"projectId"))||!scope.engine.equals(text(occurrence,"engineIdentity")))return cn.bidcheck.compat.Java8Api.mapOf();
        List<Map<String,Object>> aliases=maps(entry.get("sources")),originals=maps(occurrence.get("sources"));
        if(!cn.bidcheck.compat.Java8Api.toList(aliases.stream().map(s->text(s,"sourceFingerprint")).sorted()).equals(cn.bidcheck.compat.Java8Api.toList(originals.stream().map(s->text(s,"sourceFingerprint")).sorted()))||aliases.stream().map(s->text(s,"id")).distinct().count()!=aliases.size())return cn.bidcheck.compat.Java8Api.mapOf();
        Map<String,Map<String,Object>> stored=new HashMap<>();for(Map<String,Object> s:maps(occurrence.get("sources")))stored.put(text(s,"sourceFingerprint"),s);
        List<Map<String,Object>> refs=new ArrayList<>();for(Map<String,Object> alias:aliases){Map<String,Object> actual=stored.get(text(alias,"sourceFingerprint"));if(actual==null)return cn.bidcheck.compat.Java8Api.mapOf();actual=copy(actual);actual.put("id",alias.get("id"));refs.add(actual);}
        if(refs.size()!=maps(occurrence.get("sources")).size())return cn.bidcheck.compat.Java8Api.mapOf();Map<String,Object> fact=copy(map(occurrence.get("fact")));fact.put("sourceIds",cn.bidcheck.compat.Java8Api.toList(refs.stream().map(s->s.get("id"))));
        String factId=text(map(entry.get("fact")),"id");if(!cn.bidcheck.compat.Java8Api.isBlank(factId))fact.put("id",factId);
        return of("occurrenceId",entry.get("occurrenceId"),"fact",fact,"sources",refs);
    }

    /** Original deterministic extractor integration. Caller holds the document/project transaction lock. */
    public void persistNativeFacts(Document doc,List<Fact> facts,String engineIdentity){
        Scope scope=new Scope(doc.projectId(),engineIdentity,cn.bidcheck.compat.Java8Api.listOf(doc));if(facts==null||facts.size()>10000)throw new IllegalArgumentException("NATIVE_FACT_LIMIT");int order=0;
        for(Fact original:facts){interrupted();if(original==null||original.evidence()==null)throw new IllegalArgumentException("NATIVE_FACT_SOURCE_MISSING");Evidence e=original.evidence();Page p=scope.pages.get(Scope.pageKey(doc.id(),e.locationType(),e.page()));if(p==null)throw new IllegalArgumentException("SOURCE_LOCATION_INVALID");
            e=nativeEvidence(e,p);
            Map<String,Object> src=source(scope,of("id","N1","projectId",doc.projectId(),"evidence",e,"documentHash",doc.sha256(),"documentVersion",doc.version(),"parserFingerprint",parserFingerprint(p)));
            Map<String,Object> attrs=of("nativeEntityType",Objects.toString(original.entityType(),""),"nativeEntityKey",Objects.toString(original.entityKey(),""),"nativeOrder",order++);if(cn.bidcheck.compat.Java8Api.setOf("PERSON", "COMPANY").contains(Objects.toString(original.entityType(),"")))attrs.put("entityType",original.entityType());
            Map<String,Object> value=fact(of("kind","FIELD","subject",original.entityKey(),"field",original.field(),"rawValue",original.rawValue(),"value",original.normalizedValue(),"unit","","basis","","scope","UNRESOLVED","sourceIds",cn.bidcheck.compat.Java8Api.listOf("N1"),"attributes",attrs),cn.bidcheck.compat.Java8Api.mapOf("N1", src));
            String id=dbKey("native-fact",canonical(cn.bidcheck.compat.Java8Api.listOf(scope.project, scope.engine, scope.manifestIdentity, value, src.get("sourceFingerprint"))));if(!core.get(id).isEmpty())continue;
            core.insert(of("id",id,"occurrenceId",id,"projectId",scope.project,"engineIdentity",scope.engine,"fact",value,"sources",cn.bidcheck.compat.Java8Api.listOf(src),"entity",EntityIdentity.resolve(value,cn.bidcheck.compat.Java8Api.listOf(src)),"layoutIdentity","","createdAt",Instant.now().toString(),"ledgerVersion",VERSION,"sourceDomain","NATIVE_FACT","extractionMethod","NATIVE_EXTRACTOR"));
        }
    }
    public List<Fact> nativeFacts(Document doc,String engineIdentity){
        Scope scope=new Scope(doc.projectId(),engineIdentity,cn.bidcheck.compat.Java8Api.listOf(doc));List<Map<String,Object>> values=new ArrayList<>();for(Map<String,Object> occurrence:core.nativeRecords(scope.project,scope.engine,doc.id(),doc.version(),doc.sha256())){List<Map<String,Object>> refs=maps(occurrence.get("sources"));if(!refs.isEmpty()&&refs.stream().allMatch(s->validSource(scope,s)))values.add(occurrence);}
        values.sort(Comparator.comparingInt(v->integer(map(map(v.get("fact")).get("attributes")).get("nativeOrder"),0)));
        return cn.bidcheck.compat.Java8Api.toList(values.stream().map(v->{Map<String,Object> f=map(v.get("fact")),a=map(f.get("attributes")),e=map(maps(v.get("sources")).get(0).get("evidence"));return new Fact(text(a,"nativeEntityType"),text(a,"nativeEntityKey"),text(f,"field"),text(f,"rawValue"),text(f,"value"),new Evidence(text(e,"documentId"),text(e,"documentName"),integer(e.get("page"),0),text(e,"locationType"),text(e,"quote"),text(e,"anchor"),((Number)e.get("confidence")).doubleValue()));}));
    }
    /** Legacy native contexts join trimmed lines. Recover only their exact contiguous original text. */
    static Evidence nativeEvidence(Evidence evidence,Page page){
        String text=Objects.toString(page.text(),""),quote=Objects.toString(evidence.quote(),"");
        if(cn.bidcheck.compat.Java8Api.isBlank(quote)||quote.length()>10000||text.contains(quote))return evidence;
        List<String> lines=cn.bidcheck.compat.Java8Api.toList(Arrays.stream(quote.split("\\R")).map(String::trim).filter(s->!s.isEmpty()));if(lines.size()<2)return evidence;
        String pattern=lines.stream().map(java.util.regex.Pattern::quote).collect(java.util.stream.Collectors.joining("\\s+"));
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("(?U)"+pattern).matcher(text);if(!match.find())return evidence;String original=match.group();if(match.find())throw new IllegalArgumentException("NATIVE_SOURCE_ALIGNMENT_AMBIGUOUS");
        return new Evidence(evidence.documentId(),evidence.documentName(),evidence.page(),evidence.locationType(),original,evidence.anchor(),evidence.confidence());
    }
    private static String engineParent(Scope s){return dbKey("fact-engine",canonical(cn.bidcheck.compat.Java8Api.listOf(s.project, s.engine)));}
    private static String documentParent(Scope s,Document d){return s.documentParents.get(d.id());}
    private static String snapshotId(Scope s,String run){return dbKey("fact-snapshot",canonical(cn.bidcheck.compat.Java8Api.listOf(s.project, s.engine, run, s.manifestIdentity)));}
    private static void checkName(String name){if(name==null||cn.bidcheck.compat.Java8Api.isBlank(name)||name.length()>200)throw new IllegalArgumentException("FACT_SCOPE_ID_INVALID");}
    private static List<Map<String,Object>> strictMaps(Object raw){if(!(raw instanceof List<?>)||((List<?>) (raw)).stream().anyMatch(v->!(v instanceof Map)))throw new IllegalArgumentException("FACT_BATCH_SHAPE_INVALID");return maps(raw);}
    private static String comparisonValue(Map<String,Object> fact){String value=text(fact,"value");if("NUMBER".equals(text(fact,"kind")))try{return new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();}catch(NumberFormatException ignored){}return canonical(value);}
    static final class Scope{
        final String project,engine,manifestIdentity;
        final Map<String,Document> documents=new LinkedHashMap<>();
        final Map<String,String> documentParents=new HashMap<>(),pageFingerprints=new HashMap<>();
        final Map<String,Page> pages=new HashMap<>();
        Scope(String project,String engine,List<Document> docs){
            checkName(project);checkName(engine);if(project.length()>64)throw new IllegalArgumentException("FACT_SCOPE_ID_INVALID");this.project=project;this.engine=engine;
            if(docs==null||docs.size()>24)throw new IllegalArgumentException("FACT_MANIFEST_INVALID");
            for(Document d:docs){if(d==null||!project.equals(d.projectId())||d.id()==null||d.sha256()==null||!d.sha256().matches("[a-fA-F0-9]{64}")||d.version()<1||d.pages()==null||documents.putIfAbsent(d.id(),d)!=null)throw new IllegalArgumentException("FACT_MANIFEST_INVALID");
                List<String> fingerprints=new ArrayList<>();for(Page p:d.pages()){String key=pageKey(d.id(),p.locationType(),p.number());if(pages.putIfAbsent(key,p)!=null)throw new IllegalArgumentException("FACT_MANIFEST_DUPLICATE_LOCATION");String fingerprint=parserFingerprint(p);pageFingerprints.put(key,fingerprint);fingerprints.add(fingerprint);}
                documentParents.put(d.id(),dbKey("fact-doc",canonical(cn.bidcheck.compat.Java8Api.listOf(project, engine, d.id(), d.sha256(), d.version(), fingerprints))));
            }manifestIdentity=hash(canonical(cn.bidcheck.compat.Java8Api.toList(documentParents.values().stream().sorted())));
        }
        static String pageKey(String documentId,String location,int page){return documentId+"\0"+location+"\0"+page;}
    }
}
