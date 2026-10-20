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

/** Frozen lookup baseline before source-prefilter; isolated H2 tests only. Source SHA-256: 02e1a6842159c9f5b2c185fde8ee65c270c89d8dbcffada10bccc2fd60c3909f. */
final class LegacyFactLedgerLookupFixture {
    private static final String VERSION="fact-ledger-4-field-observations";
    private static final Set<String> KINDS=Set.of("MATERIAL","DECLARATION","SECTION","PERSON_ROLE","CERTIFICATE","DATE_RANGE","MONTHS","RELATION","NUMBER","CONTRACT","QUALITY","FIELD","PERSON","COMPANY");
    private final Store store;
    private final FactRepository core;
    public LegacyFactLedgerLookupFixture(Store store,FactRepository core){this.store=Objects.requireNonNull(store);this.core=Objects.requireNonNull(core);}
    /** Compatibility for isolated MemoryStore tests only; a real application must inject SQL storage. */
    public LegacyFactLedgerLookupFixture(Store store){this(store,testRepository(store));}
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
    /** Revalidated page/context facts that the deterministic checker can safely consume. */
    public List<Fact> documentFacts(Document document,String parserIdentity){
        return new PageFactLedger(store,core).documentFacts(document,parserIdentity);
    }
    /** Caller must hold the project lock and fenced transaction. No history, usage or snapshot is mutated. */
    public Map<String,Object> migrateLegacyOccurrences(String project,int max){return migrateLegacyOccurrences(project,max,"");}
    public Map<String,Object> migrateLegacyOccurrences(String project,int max,String cursor){
        checkName(project);if(project.length()>64||max<1||max>2000)throw new IllegalArgumentException("LEGACY_MIGRATION_LIMIT");
        long after=-1;String afterId="";if(cursor!=null&&!cursor.isBlank()){
            try{if(cursor.length()>2048)throw new IllegalArgumentException();Map<String,Object> c=store.map(new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8));
                if(integer(c.get("version"),-1)!=1||!hash(project).equals(text(c,"projectHash"))||!(c.get("createdAt") instanceof Number n)||n.doubleValue()!=n.longValue()||n.longValue()<0||text(c,"id").isBlank()||text(c,"id").length()>64)throw new IllegalArgumentException();after=n.longValue();afterId=text(c,"id");
            }catch(RuntimeException badCursor){throw new IllegalArgumentException("LEGACY_CURSOR_INVALID");}
        }
        int scanned=0,migrated=0,already=0,rejected=0;boolean more=false;Map<String,Integer> errors=new TreeMap<>();
        Map<String,Document> documents=new LinkedHashMap<>(8,0.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<String,Document> e){return size()>4;}};
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
            if(d==null){try{d=store.get("document",id,Document.class);}catch(ResponseStatusException missing){if(missing.getStatusCode().value()==404)throw new IllegalArgumentException("LEGACY_DOCUMENT_MISSING");throw missing;}catch(IllegalStateException malformed){throw new IllegalArgumentException("LEGACY_DOCUMENT_INVALID");}documents.put(id,d);}manifest.put(id,d);
        }
        Scope scope=new Scope(project,engine,new ArrayList<>(manifest.values()));Map<String,Map<String,Object>> refs=new LinkedHashMap<>();
        for(Map<String,Object> old:originalSources){String id=text(old,"id");checkName(id);if(!validSource(scope,old)||refs.putIfAbsent(id,source(scope,old))!=null)throw new IllegalArgumentException("LEGACY_SOURCE_UNVERIFIED");}
        Map<String,Object> validated=fact(map(value.get("fact")),refs);if(!canonical(validated).equals(canonical(value.get("fact")))||strings(validated.get("sourceIds")).size()!=refs.size())throw new IllegalArgumentException("LEGACY_FACT_SHAPE_INVALID");
        List<Map<String,Object>> sources=strings(validated.get("sourceIds")).stream().map(refs::get).toList();
        Map<String,Object> semantic=new LinkedHashMap<>(validated);semantic.remove("id");semantic.put("sourceIds",sources.stream().map(s->text(s,"sourceFingerprint")).sorted().toList());
        if(!row.id.equals(dbKey("occurrence",canonical(List.of(project,engine,semantic)))))throw new IllegalArgumentException("LEGACY_OCCURRENCE_ID_INVALID");
        if(!maps(map(map(validated.get("attributes")).get("structureProof")).get("records")).isEmpty())throw new IllegalArgumentException("LEGACY_STRUCTURE_REVALIDATION_REQUIRED");
        Map<String,Object> derived=EntityIdentity.resolve(validated,sources,List.of());
        if(!canonical(derived).equals(canonical(value.get("entity"))))throw new IllegalArgumentException("LEGACY_ENTITY_UNVERIFIED");
        if(text(value,"createdAt").length()>40||text(value,"ledgerVersion").length()>100)throw new IllegalArgumentException("LEGACY_METADATA_INVALID");
        Map<String,Object> verified=new LinkedHashMap<>(value);verified.put("fact",validated);verified.put("sources",sources);verified.put("entity",derived);return verified;
    }
    public static String parserFingerprint(Page p){return hash(Objects.toString(p.parser(),"")+"\0"+Objects.toString(p.locationType(),"")+"\0"+p.number()+"\0"+Objects.toString(p.text(),"")+"\0"+canonical(p.layout()==null?Map.of():p.layout().toMap()));}

    /** Prepared values have no public constructor and never expose their mutable internal records. */
    public static final class PreparedManifest {
        private final Scope scope;
        private PreparedManifest(Scope scope){this.scope=scope;}
        public String identity(){return scope.manifestIdentity;}
    }
    public static final class PreparedBatch {
        private final LegacyFactLedgerLookupFixture owner;private final Scope scope;
        private final String runId,checkpointId,operation,engineVersion,digest;
        private final Map<String,Object> batch,coverage;private final List<Map<String,Object>> entries,rejected;
        private final Map<String,Map<String,Object>> sources;
        private PreparedBatch(LegacyFactLedgerLookupFixture owner,Scope scope,String runId,String checkpointId,String operation,String engineVersion,
                              Map<String,Object> batch,Map<String,Object> coverage,List<Map<String,Object>> entries,List<Map<String,Object>> rejected,Map<String,Map<String,Object>> sources){
            this.owner=owner;this.scope=scope;this.runId=runId;this.checkpointId=checkpointId;this.operation=operation;this.engineVersion=engineVersion;
            this.batch=batch;this.coverage=coverage;this.entries=entries;this.rejected=rejected;this.sources=sources;
            this.digest=hash(canonical(List.of(scope.project,scope.engine,scope.manifestIdentity,runId,checkpointId,batch)));
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
            Map<String,Object> fact=fact(input,sources);List<Map<String,Object>> refs=strings(fact.get("sourceIds")).stream().map(sources::get).toList();
            Map<String,Object> attrs=map(fact.get("attributes"));attrs.remove("structureProof");
            Map<String,Object> entity=EntityIdentity.resolve(fact,refs,layoutCells);Map<String,Object> structure=map(entity.get("structureProof"));
            if(!maps(structure.get("records")).isEmpty())attrs.put("structureProof",structure);fact.put("attributes",attrs);
            String originalId=text(input,"id");if(!originalId.isBlank())originalFactIds.add(originalId);
            Map<String,Object> semantic=new LinkedHashMap<>(fact);semantic.remove("id");semantic.put("sourceIds",refs.stream().map(ref->text(ref,"sourceFingerprint")).sorted().toList());
            String occurrenceId=dbKey("occurrence",canonical(List.of(scope.project,scope.engine,semantic)));
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
        interrupted();Scope scope=prepared.scope;String runId=prepared.runId,checkpointId=prepared.checkpointId,operation=prepared.operation,engineVersion=prepared.engineVersion;
        if(!read("fact_snapshot",snapshotId(scope,runId)).isEmpty())throw new IllegalStateException("FACT_RUN_SNAPSHOT_FROZEN");
        Map<String,Object> batch=prepared.batch,coverage=prepared.coverage;Map<String,Map<String,Object>> sources=prepared.sources;
        List<Map<String,Object>> rejected=new ArrayList<>(prepared.rejected),accepted=new ArrayList<>();int reused=0,index=0;
        Set<String> existing=core.existingIds(prepared.entries.stream().map(entry->text(entry,"occurrenceId")).toList());
        Map<String,Map<String,Object>> pending=new LinkedHashMap<>();
        for(Map<String,Object> entry:prepared.entries){interrupted();index++;try{
            String occurrenceId=text(entry,"occurrenceId");Map<String,Object> fact=map(entry.get("fact"));List<Map<String,Object>> refs=maps(entry.get("sources"));
            Map<String,Object> old=pending.containsKey(occurrenceId)?pending.get(occurrenceId):existing.contains(occurrenceId)?read("fact_occurrence",occurrenceId):readRecord("fact_occurrence",occurrenceId);
            if(old.isEmpty())pending.put(occurrenceId,of("id",occurrenceId,"occurrenceId",occurrenceId,"projectId",scope.project,"engineIdentity",scope.engine,"fact",fact,"sources",refs,"entity",entry.get("entity"),"layoutIdentity",text(batch,"layoutIdentity"),"createdAt",Instant.now().toString(),"ledgerVersion",VERSION));
            else {
                if(!scope.project.equals(text(old,"projectId"))||!scope.engine.equals(text(old,"engineIdentity"))||maps(old.get("sources")).isEmpty()||maps(old.get("sources")).stream().anyMatch(ref->!validSource(scope,ref)))throw new IllegalArgumentException("FACT_EXISTING_SCOPE_INVALID");
                if(!existing.contains(occurrenceId))pending.putIfAbsent(occurrenceId,old);reused++;
            }
            accepted.add(of("occurrenceId",occurrenceId,"fact",fact,"sources",refs,"originalFactId",entry.get("originalFactId")));
        }catch(IllegalArgumentException failure){rejected.add(of("index",index,"code",failure.getMessage()));}}
        core.insertBatch(new ArrayList<>(pending.values()));
        coverage=coverage(batch.get("extractionCoverage"),sources.keySet(),prepared.entries.stream().map(e->text(e,"originalFactId")).filter(id->!id.isBlank()).collect(java.util.stream.Collectors.toSet()),rejected,accepted.size());
        List<Map<String,Object>> fieldCoverage=FieldCoverage.validate(batch.get("fieldCoverage"),sources,accepted);
        boolean persisted=!accepted.isEmpty()||!fieldCoverage.isEmpty();
        String batchId=dbKey("fact-batch",canonical(List.of(scope.project,scope.engine,operation,engineVersion,accepted,sources,coverage,fieldCoverage)));
        if(persisted&&read("fact_batch",batchId).isEmpty()){
            Map<String,Object> stored=of("id",batchId,"projectId",scope.project,"engineIdentity",scope.engine,"engineVersion",engineVersion,"observationPolicy",text(batch,"observationPolicy"),"operationIdentity",operation,"entries",accepted,"sources",new ArrayList<>(sources.values()),"extractionCoverage",coverage,"fieldCoverage",fieldCoverage,"layoutCells",maps(batch.get("layoutCells")),"layoutIdentity",text(batch,"layoutIdentity"));
            store.save("fact_batch",batchId,scope.project,engineParent(scope),stored);
            for(String docId:sources.values().stream().map(ref->text(map(ref.get("evidence")),"documentId")).distinct().toList()){String parent=documentParent(scope,scope.documents.get(docId));String key=dbKey("fact-index",parent+batchId);store.save("fact_index",key,scope.project,parent,of("id",key,"batchId",batchId));}
        }
        List<String> usages=new ArrayList<>();
        for(Map<String,Object> entry:accepted){String occurrence=text(entry,"occurrenceId"),usageId=dbKey("fact-use",canonical(List.of(scope.project,scope.engine,runId,checkpointId,batchId,occurrence)));
            if(read("fact_usage",usageId).isEmpty()){Map<String,Object> use=of("id",usageId,"usageId",usageId,"projectId",scope.project,"runId",runId,"checkpointId",checkpointId,"engineIdentity",scope.engine,"batchId",batchId,"occurrenceId",occurrence,"operationIdentity",operation,"manifestIdentity",scope.manifestIdentity);store.save("fact_usage",usageId,scope.project,runId,use);store.save("fact_origin",dbKey("fact-origin",usageId),scope.project,occurrence,use);}usages.add(usageId);}
        List<String> coverageUsageIds=new ArrayList<>();if(!fieldCoverage.isEmpty()){
            String coverageUse=dbKey("field-use",canonical(List.of(scope.project,scope.engine,runId,checkpointId,batchId)));
            if(read("fact_coverage_usage",coverageUse).isEmpty())store.save("fact_coverage_usage",coverageUse,scope.project,runId,
                of("id",coverageUse,"projectId",scope.project,"runId",runId,"checkpointId",checkpointId,"engineIdentity",scope.engine,"manifestIdentity",scope.manifestIdentity,"batchId",batchId));
            coverageUsageIds.add(coverageUse);
        }
        return of("accepted",accepted.size(),"reused",reused,"rejected",rejected,"batchId",persisted?batchId:"","occurrenceIds",accepted.stream().map(e->e.get("occurrenceId")).distinct().toList(),"usageIds",usages,"coverageUsageIds",coverageUsageIds,"coveragePublished",!coverage.isEmpty()||!fieldCoverage.isEmpty(),"fieldCoverage",fieldCoverage);
    }
    public static final class PreparedSnapshot {
        private final LegacyFactLedgerLookupFixture owner;private final Scope scope;private final String runId,dependenciesDigest,coverageDigest,previousBody;
        private final Store.PreparedRecord payload;private final Map<String,Object> metadata;private final Set<String> usageIds,occurrenceIds;
        private PreparedSnapshot(LegacyFactLedgerLookupFixture owner,Scope scope,String runId,Map<String,Object> value,Set<String> usageIds,Set<String> occurrenceIds,String previousBody){
            this.owner=owner;this.scope=scope;this.runId=runId;this.usageIds=Set.copyOf(usageIds);this.occurrenceIds=Set.copyOf(occurrenceIds);this.previousBody=previousBody;
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
    private static String dependenciesDigest(List<Map<String,Object>> uses){return hash(canonical(uses.stream().sorted(Comparator.comparing(u->text(u,"id"))).toList()));}
    private List<Map<String,Object>> runUses(Scope scope,String runId){return store.maps("fact_usage",scope.project,runId).stream().filter(u->scope.engine.equals(text(u,"engineIdentity"))&&scope.manifestIdentity.equals(text(u,"manifestIdentity"))).toList();}
    private List<Map<String,Object>> coverageUses(Scope scope,String runId){return store.maps("fact_coverage_usage",scope.project,runId).stream().filter(u->scope.engine.equals(text(u,"engineIdentity"))&&scope.manifestIdentity.equals(text(u,"manifestIdentity"))).toList();}

    public List<Map<String,Object>> lookup(String projectId,String engineIdentity,List<Document> manifestBidDocs,Map<String,Object> request){
        Scope scope=new Scope(projectId,engineIdentity,manifestBidDocs);Map<String,Object> req=request==null?Map.of():request;int limit=integer(req.get("limit"),100);if(limit<1||limit>2000)throw new IllegalArgumentException("FACT_LOOKUP_LIMIT");
        Set<String> requestedDocs=new HashSet<>(strings(req.get("documentIds"))),requestedSources=new HashSet<>(strings(req.get("sourceIds"))),kinds=new HashSet<>(strings(req.get("factKinds"))),observationKeys=new HashSet<>(strings(req.get("observationKeys")));
        if(!scope.documents.keySet().containsAll(requestedDocs))throw new IllegalArgumentException("FACT_LOOKUP_OUTSIDE_MANIFEST");
        Set<String> batchIds=new LinkedHashSet<>();for(Document doc:scope.documents.values()){if(!requestedDocs.isEmpty()&&!requestedDocs.contains(doc.id()))continue;for(Map<String,Object> row:store.maps("fact_index",scope.project,documentParent(scope,doc)))batchIds.add(text(row,"batchId"));}
        List<Map<String,Object>> out=new ArrayList<>(),coverageOnly=new ArrayList<>();
        for(String batchId:batchIds){interrupted();Map<String,Object> batch=read("fact_batch",batchId);if(!scope.engine.equals(text(batch,"engineIdentity"))||!scope.project.equals(text(batch,"projectId")))continue;
            List<Map<String,Object>> allSources=maps(batch.get("sources"));boolean allValid=allSources.stream().allMatch(s->validSource(scope,s));List<Map<String,Object>> entries=maps(batch.get("entries"));List<Map<String,Object>> eligible=new ArrayList<>();
            for(Map<String,Object> batchEntry:entries){Map<String,Object> entry=materializeEntry(scope,batchEntry);if(entry.isEmpty())continue;Map<String,Object> f=map(entry.get("fact"));List<Map<String,Object>> refs=maps(entry.get("sources"));if(refs.isEmpty()||refs.stream().anyMatch(s->!validSource(scope,s)))continue;if(!kinds.isEmpty()&&!kinds.contains(text(f,"kind")))continue;
                if(!requestedSources.isEmpty()&&!requestedSources.containsAll(strings(f.get("sourceIds"))))continue;
                if(!requestedDocs.isEmpty()&&refs.stream().anyMatch(s->!requestedDocs.contains(text(map(s.get("evidence")),"documentId"))))continue;eligible.add(entry);}
            boolean complete=allValid&&eligible.size()==entries.size()&&out.size()+eligible.size()<=limit&&(!requestedSources.isEmpty()?requestedSources.containsAll(allSources.stream().map(s->text(s,"id")).toList()):true);
            Set<String> returnedIds=eligible.stream().limit(limit-out.size()).map(entry->text(map(entry.get("fact")),"id")).collect(java.util.stream.Collectors.toSet());
            List<Map<String,Object>> fields=Boolean.TRUE.equals(req.get("includeFieldCoverage"))?maps(batch.get("fieldCoverage")).stream().filter(row->{
                List<String> refs=strings(row.get("sourceIds"));return (observationKeys.isEmpty()||observationKeys.contains(text(row,"observationKey")))&&(requestedSources.isEmpty()||requestedSources.containsAll(refs))
                    &&allSources.stream().filter(s->refs.contains(text(s,"id"))).count()==refs.size()
                    &&allSources.stream().filter(s->refs.contains(text(s,"id"))).allMatch(s->validSource(scope,s))
                    &&returnedIds.containsAll(strings(row.get("factIds")));}).toList():List.of();
            for(Map<String,Object> entry:eligible){if(out.size()>=limit)break;String occurrence=text(entry,"occurrenceId");Map<String,Object> value=of("occurrenceId",occurrence,"fact",entry.get("fact"),"sources",entry.get("sources"),"engineVersion",batch.get("engineVersion"),"operationIdentity",batch.get("operationIdentity"),"batchId",batchId,"batchFactCount",entries.size(),"coverageComplete",complete,
                    "origins",store.maps("fact_origin",scope.project,occurrence).stream().filter(o->scope.engine.equals(text(o,"engineIdentity"))).map(o->of("runId",o.get("runId"),"checkpointId",o.get("checkpointId"),"usageId",o.get("usageId"))).toList());
                putIdentity(value,EntityIdentity.resolve(map(entry.get("fact")),maps(entry.get("sources")),maps(batch.get("layoutCells"))));
                value.put("observationPolicy",text(batch,"observationPolicy"));if(!fields.isEmpty()){value.put("fieldCoverage",fields);value.put("fieldCoverageSources",allSources);}
                if(complete&&!map(batch.get("extractionCoverage")).isEmpty()){value.put("extractionCoverage",batch.get("extractionCoverage"));value.put("coverageSources",allSources);}out.add(copy(value));}
            if(eligible.isEmpty()&&!fields.isEmpty()&&coverageOnly.size()<limit)coverageOnly.add(copy(of("recordType","FIELD_COVERAGE","engineVersion",batch.get("engineVersion"),"observationPolicy",text(batch,"observationPolicy"),"operationIdentity",batch.get("operationIdentity"),"batchId",batchId,"fieldCoverage",fields,"fieldCoverageSources",allSources)));
            if(out.size()>=limit)break;
        }out.addAll(coverageOnly.stream().limit(Math.max(0,limit-out.size())).toList());return out;
    }

    public Map<String,Object> view(String projectId,String runId,String engineIdentity,List<Document> manifestBidDocs){Scope scope=new Scope(projectId,engineIdentity,manifestBidDocs);checkName(runId);Map<String,Object> frozen=read("fact_snapshot",snapshotId(scope,runId));return frozen.isEmpty()?assemble(scope,runId,false):copy(frozen);}
    public Map<String,Object> snapshot(String projectId,String runId,String engineIdentity,List<Document> manifestBidDocs){Scope scope=new Scope(projectId,engineIdentity,manifestBidDocs);checkName(runId);String id=snapshotId(scope,runId);Map<String,Object> previous=read("fact_snapshot",id);if(!previous.isEmpty())return copy(previous);Map<String,Object> snapshot=assemble(scope,runId,true);store.save("fact_snapshot",id,scope.project,runId,snapshot);return copy(snapshot);}
    private Map<String,Object> assemble(Scope scope,String runId,boolean frozen){
        List<Map<String,Object>> uses=runUses(scope,runId);
        Map<String,List<Map<String,Object>>> byOccurrence=new LinkedHashMap<>();uses.forEach(u->byOccurrence.computeIfAbsent(text(u,"occurrenceId"),k->new ArrayList<>()).add(u));
        List<Map<String,Object>> occurrences=new ArrayList<>(),relations=new ArrayList<>();Map<String,Map<String,Object>> entities=new LinkedHashMap<>();Map<String,List<Map<String,Object>>> conflicts=new LinkedHashMap<>();
        for(var group:byOccurrence.entrySet()){interrupted();Map<String,Object> occurrence=read("fact_occurrence",group.getKey());List<Map<String,Object>> sources=maps(occurrence.get("sources"));if(!scope.project.equals(text(occurrence,"projectId"))||!scope.engine.equals(text(occurrence,"engineIdentity"))||sources.isEmpty()||sources.stream().anyMatch(s->!validSource(scope,s)))continue;
            Map<String,Object> fact=map(occurrence.get("fact")),entity=map(occurrence.get("entity"));List<Map<String,Object>> refs=sources.stream().map(s->map(s.get("evidence"))).toList();
            Map<String,Object> item=new LinkedHashMap<>(occurrence);for(String key:List.of("kind","subject","field","rawValue","value","unit","basis","scope","attributes"))item.put(key,fact.get(key));putIdentity(item,entity);item.put("sourceRefs",refs);item.put("usedByCheckpoints",group.getValue().stream().map(u->text(u,"checkpointId")).distinct().map(id->of("id",id)).toList());item.put("origins",group.getValue());occurrences.add(item);
            Map<String,Object> aggregate=entities.computeIfAbsent(text(entity,"id"),k->{Map<String,Object> e=new LinkedHashMap<>(entity);e.put("occurrenceIds",new ArrayList<String>());e.put("sourceRefs",new ArrayList<Map<String,Object>>());return e;});append(aggregate,"occurrenceIds",group.getKey());for(Map<String,Object> ref:refs)append(aggregate,"sourceRefs",ref);
            if(Set.of("RELATION","PERSON_ROLE").contains(text(fact,"kind"))){Map<String,Object> relation=map(occurrence.get("relation"));if(relation.isEmpty())relation=of("id",group.getKey(),"entityId",entity.get("id"),"kind",fact.get("kind"),"subject",fact.get("subject"),"field",fact.get("field"),"predicate",fact.get("field"),"object","RELATION".equals(text(fact,"kind"))?map(fact.get("attributes")).get("object"):fact.get("value"),"resolution","SOURCE_BOUND","value",fact.get("value"),"attributes",fact.get("attributes"),"scope",fact.get("scope"));relation.put("sourceRefs",refs);relation.put("usedByCheckpoints",item.get("usedByCheckpoints"));relations.add(relation);}
            String key=EntityIdentity.conflictKey(fact,entity,sources);if(!key.isBlank())conflicts.computeIfAbsent(key,k->new ArrayList<>()).add(item);
        }
        List<Map<String,Object>> conflictRows=new ArrayList<>();for(var group:conflicts.entrySet()){List<Map<String,Object>> variants=group.getValue();if(variants.stream().map(v->comparisonValue(map(v.get("fact")))).distinct().count()<2)continue;conflictRows.add(of("id",dbKey("conflict",group.getKey()),"status","REVIEW_REQUIRED","entityId",map(variants.get(0).get("entity")).get("id"),"field",map(variants.get(0).get("fact")).get("field"),"reason","相同强身份、字段、单位、口径、时点及范围出现不同值，保留各自原文，未自动裁决","occurrenceIds",variants.stream().map(v->v.get("occurrenceId")).toList(),"sourceRefs",variants.stream().flatMap(v->maps(v.get("sourceRefs")).stream()).distinct().toList()));}
        List<Map<String,Object>> coverageDependencies=coverageUses(scope,runId),fieldCoverage=new ArrayList<>();
        for(Map<String,Object> usage:coverageDependencies){Map<String,Object> batch=read("fact_batch",text(usage,"batchId"));Map<String,Map<String,Object>> validSources=new LinkedHashMap<>();
            for(Map<String,Object> source:maps(batch.get("sources")))if(validSource(scope,source))validSources.put(text(source,"id"),source);
            for(Map<String,Object> row:FieldCoverage.validate(batch.get("fieldCoverage"),validSources,maps(batch.get("entries")))){Map<String,Object> item=copy(row);item.put("checkpointId",usage.get("checkpointId"));item.put("batchId",usage.get("batchId"));item.put("sources",strings(row.get("sourceIds")).stream().map(validSources::get).toList());fieldCoverage.add(item);}
        }
        long shared=occurrences.stream().filter(o->maps(o.get("usedByCheckpoints")).size()>1).count();Map<String,Object> summary=of("occurrences",occurrences.size(),"entities",entities.size(),"relations",relations.size(),"conflicts",conflictRows.size(),"sharedOccurrences",shared,"fieldObservations",fieldCoverage.size());
        return of("id",snapshotId(scope,runId),"projectId",scope.project,"runId",runId,"engineIdentity",scope.engine,"ledgerVersion",VERSION,"manifestIdentity",scope.manifestIdentity,"frozen",frozen,"summary",summary,"stats",summary,"occurrences",occurrences,"entities",new ArrayList<>(entities.values()),"relations",relations,"conflicts",conflictRows,"dependencies",uses,"coverageDependencies",coverageDependencies,"fieldCoverage",fieldCoverage,"limitations",List.of("同名只作为候选，不据此跨来源确认同一实体；未能绑定强标识的事实保留来源内身份。","冲突汇总只覆盖本运行已使用事实；不把未检出冲突解释为全项目无冲突。","字段读取状态只覆盖列明来源；已读未见不证明整份材料缺失，提取失败不影响其他已验证字段。"));
    }
    @SuppressWarnings("unchecked") private static void append(Map<String,Object> map,String key,Object value){List<Object> values=(List<Object>)map.get(key);if(!values.contains(value))values.add(value);}
    private static void putIdentity(Map<String,Object> target,Map<String,Object> entity){target.put("entity",entity);target.put("entityId",entity.get("id"));target.put("identityStrength",entity.get("identityStrength"));target.put("entityType",entity.get("type"));target.put("displayName",entity.get("displayName"));target.put("identityClaims",entity.getOrDefault("identityClaims",List.of()));}
    static Map<String,Object> source(Scope scope,Map<String,Object> input){Map<String,Object> e=evidence(input.get("evidence"));String id=text(e,"documentId");Document doc=scope.documents.get(id);if(doc==null||!scope.project.equals(text(input,"projectId"))||!doc.sha256().equals(text(input,"documentHash"))||doc.version()!=integer(input.get("documentVersion"),-1))throw new IllegalArgumentException("SOURCE_OUTSIDE_MANIFEST");
        int page=integer(e.get("page"),-1);String pageKey=Scope.pageKey(doc.id(),text(e,"locationType"),page);Page original=scope.pages.get(pageKey);if(original==null)throw new IllegalArgumentException("SOURCE_LOCATION_INVALID");String fingerprint=scope.pageFingerprints.get(pageKey);String quote=text(e,"quote");if(quote.isBlank()||quote.length()>10000||!Objects.toString(original.text(),"").contains(quote))throw new IllegalArgumentException("SOURCE_NOT_IN_MANIFEST");
        if(!fingerprint.equals(text(input,"parserFingerprint")))throw new IllegalArgumentException("SOURCE_PARSER_MISMATCH");
        double confidence=e.get("confidence") instanceof Number number?number.doubleValue():0;if(!Double.isFinite(confidence)||confidence<0||confidence>1)throw new IllegalArgumentException("SOURCE_CONFIDENCE_INVALID");
        Map<String,Object> canonicalEvidence=of("documentId",doc.id(),"documentName",doc.name(),"page",page,"locationType",original.locationType(),"quote",quote,"anchor",text(e,"anchor"),"confidence",confidence);
        return of("id",text(input,"id"),"projectId",scope.project,"evidence",canonicalEvidence,"documentHash",doc.sha256(),"documentVersion",doc.version(),"parserFingerprint",fingerprint,"sourceFingerprint",hash(canonical(List.of(doc.id(),doc.sha256(),doc.version(),fingerprint,canonicalEvidence))));
    }
    static boolean validSource(Scope scope,Map<String,Object> source){try{return text(source(scope,source),"sourceFingerprint").equals(text(source,"sourceFingerprint"));}catch(IllegalArgumentException e){return false;}}
    static Map<String,Object> fact(Map<String,Object> input,Map<String,Map<String,Object>> sources){String kind=text(input,"kind"),raw=text(input,"rawValue");List<String> ids=strings(input.get("sourceIds"));if(!KINDS.contains(kind)||raw.isBlank()||raw.length()>8000||ids.isEmpty()||ids.size()>16||ids.stream().distinct().count()!=ids.size()||!sources.keySet().containsAll(ids))throw new IllegalArgumentException("FACT_SOURCE_OR_TYPE_INVALID");
        if(text(input,"id").length()>200)throw new IllegalArgumentException("FACT_ID_LIMIT");
        if(ids.stream().map(sources::get).map(s->text(map(s.get("evidence")),"quote")).noneMatch(q->clean(q).contains(clean(raw))))throw new IllegalArgumentException("FACT_RAW_VALUE_UNGROUNDED");
        if(!Set.of("CURRENT_PROJECT","HISTORICAL_PROJECT","UNRESOLVED").contains(text(input,"scope")))throw new IllegalArgumentException("FACT_SCOPE_INVALID");
        Map<String,Object> attrs=map(input.get("attributes"));if(canonical(attrs).length()>32000)throw new IllegalArgumentException("FACT_ATTRIBUTES_LIMIT");
        if(kind.equals("RELATION")){String object=text(attrs,"object");if(ids.stream().map(sources::get).map(s->text(map(s.get("evidence")),"quote")).noneMatch(q->EntityIdentity.local(q,text(input,"subject"),object)&&q.contains(text(input,"field"))&&!text(input,"field").isBlank()))throw new IllegalArgumentException("FACT_RELATION_UNGROUNDED");}
        Map<String,Object> fact=new LinkedHashMap<>();for(String key:List.of("kind","subject","field","rawValue","value","unit","basis","scope")){String value=text(input,key);if(value.length()>8000)throw new IllegalArgumentException("FACT_VALUE_LIMIT");fact.put(key,value);}fact.put("sourceIds",List.copyOf(ids));fact.put("attributes",copy(attrs));if(!text(input,"id").isBlank())fact.put("id",text(input,"id"));return fact;
    }
    private static Map<String,Object> coverage(Object raw,Set<String> sources,Set<String> facts,List<?> rejected,int accepted){Map<String,Object> c=map(raw);if(accepted==0||!rejected.isEmpty()||!"VALIDATED".equals(c.get("status"))||text(c,"modelIdentity").isBlank()||strings(c.get("ruleSignatures")).isEmpty()||!sources.equals(new HashSet<>(strings(c.get("sourceIds"))))||!facts.equals(new HashSet<>(strings(c.get("factIds"))))||facts.size()!=accepted)return Map.of();return copy(c);}
    private Map<String,Object> read(String kind,String id){if(kind.equals("fact_occurrence")){Map<String,Object> value=core.get(id);if(!value.isEmpty())return value;}return readRecord(kind,id);}
    private Map<String,Object> readRecord(String kind,String id){try{return store.getMap(kind,id);}catch(ResponseStatusException e){if(e.getStatusCode().value()==404)return Map.of();throw e;}}
    private Map<String,Object> materializeEntry(Scope scope,Map<String,Object> entry){
        Map<String,Object> occurrence=read("fact_occurrence",text(entry,"occurrenceId"));if(occurrence.isEmpty()||!scope.project.equals(text(occurrence,"projectId"))||!scope.engine.equals(text(occurrence,"engineIdentity")))return Map.of();
        List<Map<String,Object>> aliases=maps(entry.get("sources")),originals=maps(occurrence.get("sources"));
        if(!aliases.stream().map(s->text(s,"sourceFingerprint")).sorted().toList().equals(originals.stream().map(s->text(s,"sourceFingerprint")).sorted().toList())||aliases.stream().map(s->text(s,"id")).distinct().count()!=aliases.size())return Map.of();
        Map<String,Map<String,Object>> stored=new HashMap<>();for(Map<String,Object> s:maps(occurrence.get("sources")))stored.put(text(s,"sourceFingerprint"),s);
        List<Map<String,Object>> refs=new ArrayList<>();for(Map<String,Object> alias:aliases){Map<String,Object> actual=stored.get(text(alias,"sourceFingerprint"));if(actual==null)return Map.of();actual=copy(actual);actual.put("id",alias.get("id"));refs.add(actual);}
        if(refs.size()!=maps(occurrence.get("sources")).size())return Map.of();Map<String,Object> fact=copy(map(occurrence.get("fact")));fact.put("sourceIds",refs.stream().map(s->s.get("id")).toList());
        String factId=text(map(entry.get("fact")),"id");if(!factId.isBlank())fact.put("id",factId);
        return of("occurrenceId",entry.get("occurrenceId"),"fact",fact,"sources",refs);
    }

    /** Original deterministic extractor integration. Caller holds the document/project transaction lock. */
    public void persistNativeFacts(Document doc,List<Fact> facts,String engineIdentity){
        Scope scope=new Scope(doc.projectId(),engineIdentity,List.of(doc));if(facts==null||facts.size()>10000)throw new IllegalArgumentException("NATIVE_FACT_LIMIT");int order=0;
        for(Fact original:facts){interrupted();if(original==null||original.evidence()==null)throw new IllegalArgumentException("NATIVE_FACT_SOURCE_MISSING");Evidence e=original.evidence();Page p=scope.pages.get(Scope.pageKey(doc.id(),e.locationType(),e.page()));if(p==null)throw new IllegalArgumentException("SOURCE_LOCATION_INVALID");
            e=nativeEvidence(e,p);
            Map<String,Object> src=source(scope,of("id","N1","projectId",doc.projectId(),"evidence",e,"documentHash",doc.sha256(),"documentVersion",doc.version(),"parserFingerprint",parserFingerprint(p)));
            Map<String,Object> attrs=of("nativeEntityType",Objects.toString(original.entityType(),""),"nativeEntityKey",Objects.toString(original.entityKey(),""),"nativeOrder",order++);if(Set.of("PERSON","COMPANY").contains(Objects.toString(original.entityType(),"")))attrs.put("entityType",original.entityType());
            Map<String,Object> value=fact(of("kind","FIELD","subject",original.entityKey(),"field",original.field(),"rawValue",original.rawValue(),"value",original.normalizedValue(),"unit","","basis","","scope","UNRESOLVED","sourceIds",List.of("N1"),"attributes",attrs),Map.of("N1",src));
            String id=dbKey("native-fact",canonical(List.of(scope.project,scope.engine,scope.manifestIdentity,value,src.get("sourceFingerprint"))));if(!core.get(id).isEmpty())continue;
            core.insert(of("id",id,"occurrenceId",id,"projectId",scope.project,"engineIdentity",scope.engine,"fact",value,"sources",List.of(src),"entity",EntityIdentity.resolve(value,List.of(src)),"layoutIdentity","","createdAt",Instant.now().toString(),"ledgerVersion",VERSION,"sourceDomain","NATIVE_FACT","extractionMethod","NATIVE_EXTRACTOR"));
        }
    }
    public List<Fact> nativeFacts(Document doc,String engineIdentity){
        Scope scope=new Scope(doc.projectId(),engineIdentity,List.of(doc));List<Map<String,Object>> values=new ArrayList<>();for(String id:core.nativeIds(scope.project,scope.engine,doc.id(),doc.version(),doc.sha256())){Map<String,Object> occurrence=core.get(id);List<Map<String,Object>> refs=maps(occurrence.get("sources"));if(!refs.isEmpty()&&refs.stream().allMatch(s->validSource(scope,s)))values.add(occurrence);}
        values.sort(Comparator.comparingInt(v->integer(map(map(v.get("fact")).get("attributes")).get("nativeOrder"),0)));
        return values.stream().map(v->{Map<String,Object> f=map(v.get("fact")),a=map(f.get("attributes")),e=map(maps(v.get("sources")).get(0).get("evidence"));return new Fact(text(a,"nativeEntityType"),text(a,"nativeEntityKey"),text(f,"field"),text(f,"rawValue"),text(f,"value"),new Evidence(text(e,"documentId"),text(e,"documentName"),integer(e.get("page"),0),text(e,"locationType"),text(e,"quote"),text(e,"anchor"),((Number)e.get("confidence")).doubleValue()));}).toList();
    }
    /** Legacy native contexts join trimmed lines. Recover only their exact contiguous original text. */
    static Evidence nativeEvidence(Evidence evidence,Page page){
        String text=Objects.toString(page.text(),""),quote=Objects.toString(evidence.quote(),"");
        if(quote.isBlank()||quote.length()>10000||text.contains(quote))return evidence;
        List<String> lines=Arrays.stream(quote.split("\\R")).map(String::trim).filter(s->!s.isEmpty()).toList();if(lines.size()<2)return evidence;
        String pattern=lines.stream().map(java.util.regex.Pattern::quote).collect(java.util.stream.Collectors.joining("\\s+"));
        var match=java.util.regex.Pattern.compile("(?U)"+pattern).matcher(text);if(!match.find())return evidence;String original=match.group();if(match.find())throw new IllegalArgumentException("NATIVE_SOURCE_ALIGNMENT_AMBIGUOUS");
        return new Evidence(evidence.documentId(),evidence.documentName(),evidence.page(),evidence.locationType(),original,evidence.anchor(),evidence.confidence());
    }
    private static String engineParent(Scope s){return dbKey("fact-engine",canonical(List.of(s.project,s.engine)));}
    private static String documentParent(Scope s,Document d){return s.documentParents.get(d.id());}
    private static String snapshotId(Scope s,String run){return dbKey("fact-snapshot",canonical(List.of(s.project,s.engine,run,s.manifestIdentity)));}
    private static void checkName(String name){if(name==null||name.isBlank()||name.length()>200)throw new IllegalArgumentException("FACT_SCOPE_ID_INVALID");}
    private static List<Map<String,Object>> strictMaps(Object raw){if(!(raw instanceof List<?> list)||list.stream().anyMatch(v->!(v instanceof Map)))throw new IllegalArgumentException("FACT_BATCH_SHAPE_INVALID");return maps(raw);}
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
                documentParents.put(d.id(),dbKey("fact-doc",canonical(List.of(project,engine,d.id(),d.sha256(),d.version(),fingerprints))));
            }manifestIdentity=hash(canonical(documentParents.values().stream().sorted().toList()));
        }
        static String pageKey(String documentId,String location,int page){return documentId+"\0"+location+"\0"+page;}
    }
}
