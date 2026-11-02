package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import java.util.*;
import static cn.bidcheck.service.BidService.*;

/** Immutable, item-scoped attachments. An attachment never becomes another BID version. */
public final class SupplementAttachmentSnapshot {
    private SupplementAttachmentSnapshot() {}
    public record Snapshot(List<Map<String,Object>> manifest,String digest,Set<String> affectedCheckpointIds) {}
    public static Snapshot from(List<Map<String,Object>> inherited,List<Map<String,Object>> ready,List<Finding> findings){
        Map<String,Map<String,Object>> selected=new TreeMap<>();
        for(Map<String,Object> row:inherited)if(valid(row))selected.put(text(row,"attachmentId"),new LinkedHashMap<>(row));
        for(Map<String,Object> row:ready)if("READY".equals(text(row,"status"))&&valid(row)){
            Map<String,Object> frozen=new LinkedHashMap<>();
            for(String key:List.of("attachmentId","documentId","sha256","version","checkpointId","claimId","originRunId","name"))if(row.containsKey(key))frozen.put(key,row.get(key));
            frozen.put("affectedCheckpointIds",List.copyOf(dependents(text(row,"checkpointId"),findings)));selected.put(text(row,"attachmentId"),frozen);
        }
        List<Map<String,Object>> manifest=List.copyOf(selected.values());Set<String> affected=new LinkedHashSet<>();
        Set<String> prior=new HashSet<>();for(Map<String,Object> row:inherited)prior.add(text(row,"attachmentId"));
        for(Map<String,Object> row:manifest)if(!prior.contains(text(row,"attachmentId"))){affected.add(text(row,"checkpointId"));affected.addAll(strings(row.get("affectedCheckpointIds")));}
        // Digest includes bytes and exact item binding; parsing timestamps are deliberately excluded.
        String canonical=manifest.stream().map(r->text(r,"attachmentId")+"|"+text(r,"documentId")+"|"+text(r,"sha256")+"|"+r.get("version")+"|"+text(r,"checkpointId")+"|"+text(r,"claimId")+"|"+String.join(",",new TreeSet<>(strings(r.get("affectedCheckpointIds"))))).collect(java.util.stream.Collectors.joining("\n"));
        return new Snapshot(manifest,manifest.isEmpty()?"":stableId(canonical),Set.copyOf(affected));
    }
    private static boolean valid(Map<String,Object> row){return !text(row,"attachmentId").isBlank()&&!text(row,"documentId").isBlank()&&!text(row,"sha256").isBlank()&&!text(row,"checkpointId").isBlank()&&row.get("version") instanceof Number;}
    static Set<String> dependents(String checkpoint,List<Finding> findings){Set<String> result=new LinkedHashSet<>();result.add(checkpoint);boolean changed;
        do{changed=false;for(Finding finding:findings)if(!result.contains(finding.checkpoint().id())){Set<String> dependencies=new LinkedHashSet<>();references(finding.assessment(),dependencies);if(dependencies.stream().anyMatch(result::contains)){result.add(finding.checkpoint().id());changed=true;}}}while(changed);
        return Collections.unmodifiableSet(result);
    }
    private static void references(Object value,Set<String> result){if(value instanceof Map<?,?> map){for(var entry:map.entrySet())if(Set.of("dependencyCheckpointIds","requiredCheckpointIds").contains(String.valueOf(entry.getKey())))result.addAll(strings(entry.getValue()));else references(entry.getValue(),result);}else if(value instanceof Collection<?> list)list.forEach(v->references(v,result));}
    private static List<String> strings(Object value){return value instanceof Collection<?> list?list.stream().filter(String.class::isInstance).map(String.class::cast).toList():List.of();}
}
