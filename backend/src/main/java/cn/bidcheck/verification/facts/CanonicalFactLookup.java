package cn.bidcheck.verification.facts;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.Stored;
import java.util.*;
import org.springframework.web.server.ResponseStatusException;
import static cn.bidcheck.verification.facts.LedgerValues.*;

/** Rebuildable source/target pointers. Audit batches and usages never serve as search entries.
 * All mutations run under the fact publisher's existing project fence and transaction. */
final class CanonicalFactLookup {
    static final String VERSION="canonical-source-lookup-1";
    private final Store store;
    CanonicalFactLookup(Store store){this.store=store;}
    static String stateId(String documentParent){return dbKey("lookup-state",VERSION+documentParent);}
    static String bucket(String documentParent,String sourceToken,String type,String target,String model){
        return dbKey("lookup-bucket",canonical(List.of(VERSION,documentParent,sourceToken,type,target,model)));
    }
    static String factItemId(FactLedgerService.Scope scope,Map<String,Object> entry){
        return dbKey("lookup-fact",canonical(List.of(VERSION,scope.project,scope.engine,text(entry,"occurrenceId"))));
    }
    Map<String,Object> read(String kind,String id){try{return store.getMap(kind,id);}catch(ResponseStatusException missing){if(missing.getStatusCode().value()==404)return Map.of();throw missing;}}
    boolean ready(FactLedgerService.Scope scope,Collection<String> docs){
        List<String> ids=docs.stream().map(scope.documentParents::get).map(CanonicalFactLookup::stateId).toList();
        Map<String,Map<String,Object>> states=store.mapsByIds("fact_lookup_state",scope.project,ids);
        return ids.stream().allMatch(id->{Map<String,Object> state=states.getOrDefault(id,Map.of());return id.equals(text(state,"id"))&&id.equals(stateId(text(state,"documentParent")))&&VERSION.equals(text(state,"version"))&&"READY".equals(text(state,"status"))&&scope.project.equals(text(state,"projectId"));});
    }
    /** Both newly extracted and validated legacy data use exactly the same immutable index shape. */
    record Publication(int writes,List<Map<String,Object>> changedEvidence) {}
    Publication index(FactLedgerService.Scope scope,Map<String,Object> batch,List<Map<String,Object>> entries,List<Map<String,Object>> fields){
        if(Set.of("REUSED","REUSED_OBSERVATION").contains(text(batch,"origin")))return new Publication(0,List.of());
        Map<String,Map<String,Object>> items=new LinkedHashMap<>(),groundedFacts=new LinkedHashMap<>();Map<String,String> byOriginal=new HashMap<>();
        for(Map<String,Object> entry:entries){String itemId=factItemId(scope,entry);Map<String,Object> item=base(scope,batch,itemId);
            // The fact body stays in the canonical occurrence store. Only source aliases and its
            // immutable producer identity are repeated here, never an adopted batch's fact copy.
            item.put("occurrenceId",text(entry,"occurrenceId"));item.put("factKind",text(map(entry.get("fact")),"kind"));
            groundedFacts.put(itemId,copy(map(entry.get("fact"))));
            item.put("sources",maps(entry.get("sources")));item.put("originalFactId",text(map(entry.get("fact")),"id"));
            // Layout revalidation only consumes cells on the fact's fully cited source pages;
            // keep every row/header on those pages, including cross-page dependencies.
            item.put("layoutCells",maps(batch.get("layoutCells")).stream().filter(cell->maps(entry.get("sources")).stream().anyMatch(source->{Map<String,Object> evidence=evidence(source.get("evidence"));return text(evidence,"documentId").equals(text(cell,"documentId"))&&text(evidence,"locationType").equals(text(cell,"locationType"))&&integer(evidence.get("page"),-1)==integer(cell.get("location"),-2);})).toList());items.put(itemId,item);
            byOriginal.put(text(entry,"originalFactId"),itemId);byOriginal.put(text(map(entry.get("fact")),"id"),itemId);
        }
        for(Map<String,Object> field:fields){
            if(!FieldCoverage.completeRead(field)||Boolean.TRUE.equals(field.get("reusedObservation")))continue;
            List<String> dependencies=strings(field.get("factIds")).stream().map(byOriginal::get).toList();
            if(dependencies.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("LOOKUP_FIELD_DEPENDENCY_INVALID");
            List<Map<String,Object>> sources=maps(batch.get("sources")).stream().filter(s->strings(field.get("sourceIds")).contains(text(s,"id"))).toList();
            Map<String,Object> semantic=copy(field);semantic.put("factIds",dependencies.stream().sorted().toList());
            semantic.put("sourceIds",sources.stream().map(s->text(s,"sourceFingerprint")).sorted().toList());
            for(String key:List.of("reusedFromBatchId","reusedObservation","observedInCurrentRun"))semantic.remove(key);
            String itemId=dbKey("lookup-field",canonical(List.of(VERSION,scope.project,scope.engine,semantic)));
            Map<String,Object> item=base(scope,batch,itemId);item.put("fieldCoverage",copy(field));item.put("sources",sources);item.put("dependencyItemIds",dependencies);items.put(itemId,item);
        }
        Map<String,Map<String,Object>> old=store.mapsByIds("fact_lookup_item",scope.project,items.keySet());
        List<Stored> writes=new ArrayList<>();Map<String,Map<String,Object>> refs=new LinkedHashMap<>();
        for(var entry:items.entrySet()){
            String id=entry.getKey();Map<String,Object> value=entry.getValue();
            if(old.containsKey(id)){if(!validItem(old.get(id),scope))throw new IllegalStateException("LOOKUP_ITEM_CORRUPT");value=old.get(id);}
            else{seal(value);writes.add(store.newRow("fact_lookup_item",id,scope.project,scope.engine.length()<=64?scope.engine:dbKey("lookup-engine",scope.engine),value));}
            Map<String,Object> field=map(value.get("fieldCoverage"));String kind=field.isEmpty()?"fact_lookup_ref":"field_lookup_ref";
            for(Map<String,Object> source:maps(value.get("sources"))){String doc=text(evidence(source.get("evidence")),"documentId"),parent=scope.documentParents.get(doc);if(parent==null)throw new IllegalArgumentException("LOOKUP_SOURCE_OUTSIDE_MANIFEST");
                for(String token:List.of("A:"+text(source,"id"),"F:"+text(source,"sourceFingerprint"))){
                    String bucket=bucket(parent,token,field.isEmpty()?text(value,"factKind"):"FIELD",text(field,"observationKey"),text(field,"modelIdentity"));
                    String refId=dbKey("lookup-ref",bucket+id);refs.put(refId,of("id",refId,"projectId",scope.project,"version",VERSION,"itemId",id,"itemDigest",text(value,"digest"),"bucket",bucket,"kind",kind));
                }
            }
        }
        for(String kind:List.of("fact_lookup_ref","field_lookup_ref")){
            List<Map<String,Object>> candidates=refs.values().stream().filter(v->kind.equals(v.get("kind"))).toList();
            Set<String> existing=store.mapsByIds(kind,scope.project,candidates.stream().map(r->text(r,"id")).toList()).keySet();
            for(Map<String,Object> value:candidates)if(!existing.contains(text(value,"id")))writes.add(store.newRow(kind,text(value,"id"),scope.project,text(value,"bucket"),value));
        }
        store.insertRows(writes);
        // Revision tracks source observations, never their use by another checkpoint/run.
        Map<String,List<String>> changed=new LinkedHashMap<>();List<Map<String,Object>> events=new ArrayList<>();
        for(var entry:items.entrySet())if(!old.containsKey(entry.getKey())){
            Map<String,Object> item=entry.getValue(),field=map(item.get("fieldCoverage"));
            Map<String,Object> event=of("itemId",entry.getKey(),"recordType",field.isEmpty()?"FACT":"FIELD_OBSERVATION","occurrenceId",text(item,"occurrenceId"),"factKind",text(item,"factKind"),"observationKey",text(field,"observationKey"),"modelIdentity",text(field,"modelIdentity"),"sources",copy(of("sources",item.get("sources"))).get("sources"));
            if(field.isEmpty())event.put("fact",groundedFacts.get(entry.getKey()));events.add(event);
            for(String doc:maps(item.get("sources")).stream().map(s->text(evidence(s.get("evidence")),"documentId")).distinct().toList())changed.computeIfAbsent(doc,k->new ArrayList<>()).add(entry.getKey());
        }
        for(var change:changed.entrySet()){String parent=scope.documentParents.get(change.getKey()),id=revisionId(parent);Map<String,Object> previous=read("fact_lookup_revision",id);
            String digest=hash(canonical(List.of(text(previous,"digest"),change.getValue().stream().sorted().toList())));
            store.save("fact_lookup_revision",id,scope.project,parent,of("id",id,"projectId",scope.project,"version",VERSION,"digest",digest));
        }
        return new Publication(writes.size(),List.copyOf(events));
    }
    static String revisionId(String parent){return dbKey("lookup-revision",VERSION+parent);}
    private static Map<String,Object> base(FactLedgerService.Scope scope,Map<String,Object> batch,String id){return of("id",id,"projectId",scope.project,"engineIdentity",scope.engine,"version",VERSION,"batchId",text(batch,"id"),"engineVersion",text(batch,"engineVersion"),"observationPolicy",text(batch,"observationPolicy"),"operationIdentity",text(batch,"operationIdentity"));}
    private static void seal(Map<String,Object> row){row.put("digest",hash(canonical(row)));}
    static boolean validItem(Map<String,Object> row,FactLedgerService.Scope scope){Map<String,Object> unsigned=copy(row);String digest=text(unsigned,"digest");unsigned.remove("digest");return VERSION.equals(text(row,"version"))&&scope.project.equals(text(row,"projectId"))&&scope.engine.equals(text(row,"engineIdentity"))&&!text(row,"id").isBlank()&&digest.equals(hash(canonical(unsigned)));}

    /** Snapshot legacy membership once while fenced. Subsequent writers dual-write canonical refs;
     * cursor progress cannot hide a missing/unvalidated legacy batch or an interrupted transaction. */
    Map<String,Object> migrate(FactLedgerService.Scope scope,int maximum,java.util.function.Function<Map<String,Object>,List<Map<String,Object>>> validate){
        if(maximum<1||maximum>500)throw new IllegalArgumentException("LOOKUP_MIGRATION_LIMIT");
        int processed=0,writes=0;List<Map<String,Object>> summaries=new ArrayList<>();
        for(Document doc:scope.documents.values()){
            String parent=scope.documentParents.get(doc.id()),id=stateId(parent);Map<String,Object> state=read("fact_lookup_state",id);
            if(state.isEmpty()||!VERSION.equals(text(state,"version"))){
                List<String> pending=store.maps("fact_index",scope.project,parent).stream().map(v->text(v,"batchId")).distinct().sorted().toList();
                state=of("id",id,"projectId",scope.project,"documentParent",parent,"version",VERSION,"status","BUILDING","pendingBatchIds",pending,"cursor",0,"validatedBatches",0,"coverageDigest",hash(canonical(pending)),"errors",List.of());
            }
            if("READY".equals(text(state,"status"))||"BLOCKED".equals(text(state,"status"))){summaries.add(of("documentId",doc.id(),"status",state.get("status"),"errors",state.getOrDefault("errors",List.of())));continue;}
            List<String> pending=strings(state.get("pendingBatchIds"));int cursor=integer(state.get("cursor"),0);
            if(!hash(canonical(pending)).equals(text(state,"coverageDigest"))||cursor<0||cursor>pending.size())throw new IllegalStateException("LOOKUP_MIGRATION_STATE_INVALID");
            int end=Math.min(pending.size(),cursor+maximum-processed);List<String> part=pending.subList(cursor,Math.max(cursor,end));
            Map<String,Map<String,Object>> batches=store.mapsByIds("fact_batch",scope.project,part);List<String> errors=new ArrayList<>();
            for(String batchId:part){interrupted();Map<String,Object> batch=batches.getOrDefault(batchId,Map.of());
                try{if(!batchId.equals(text(batch,"id")))throw new IllegalArgumentException("LOOKUP_LEGACY_BATCH_MISSING");
                    List<Map<String,Object>> entries=validate.apply(batch);Map<String,Map<String,Object>> sources=new LinkedHashMap<>();for(Map<String,Object> s:maps(batch.get("sources")))sources.put(text(s,"id"),s);
                    // Obsolete observation keys/policies cannot be reused by today's consumer.
                    // Exclude them explicitly while migrating their still-valid grounded facts;
                    // an incompatible old receipt must not block the whole document forever.
                    List<Map<String,Object>> compatible=maps(batch.get("fieldCoverage")).stream().filter(field->FieldCoverage.POLICY.equals(text(field,"observationPolicy"))&&FieldCoverage.key(map(field.get("target"))).equals(text(field,"observationKey"))).toList();
                    List<Map<String,Object>> fields=FieldCoverage.validate(compatible,sources,entries);
                    if(fields.size()!=compatible.size())throw new IllegalArgumentException("LOOKUP_LEGACY_FIELDS_INVALID");
                    int skipped=maps(batch.get("fieldCoverage")).size()-compatible.size();state.put("obsoleteObservationsExcluded",integer(state.get("obsoleteObservationsExcluded"),0)+skipped);
                    writes+=index(scope,batch,entries,fields).writes();cursor++;processed++;
                }catch(IllegalArgumentException invalid){errors.add(batchId+":"+Objects.toString(invalid.getMessage(),"INVALID"));break;}
            }
            state.put("cursor",cursor);state.put("validatedBatches",cursor);state.put("errors",errors);
            state.put("status",!errors.isEmpty()?"BLOCKED":cursor==pending.size()?"READY":"BUILDING");
            if("READY".equals(state.get("status"))){state.put("legacyBatchCount",pending.size());state.put("pendingBatchIds",List.of());}
            store.save("fact_lookup_state",id,scope.project,parent,state);summaries.add(of("documentId",doc.id(),"status",state.get("status"),"remaining",pending.size()-cursor,"errors",errors));
            if(processed>=maximum)break;
        }
        return of("processed",processed,"writes",writes,"documents",summaries);
    }
}
