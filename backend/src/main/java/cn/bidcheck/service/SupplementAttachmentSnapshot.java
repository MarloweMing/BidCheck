package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import java.util.*;
import static cn.bidcheck.service.BidService.*;

/** Immutable, item-scoped attachments. An attachment never becomes another BID version. */
public final class SupplementAttachmentSnapshot {
    private SupplementAttachmentSnapshot() {}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Snapshot {
        private final List<Map<String,Object>> manifest;
        private final String digest;
        private final Set<String> affectedCheckpointIds;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Snapshot(@com.fasterxml.jackson.annotation.JsonProperty("manifest") List<Map<String,Object>> manifest, @com.fasterxml.jackson.annotation.JsonProperty("digest") String digest, @com.fasterxml.jackson.annotation.JsonProperty("affectedCheckpointIds") Set<String> affectedCheckpointIds) { this.manifest = manifest; this.digest = digest; this.affectedCheckpointIds = affectedCheckpointIds; }
        public List<Map<String,Object>> manifest() { return manifest; }
        public String digest() { return digest; }
        public Set<String> affectedCheckpointIds() { return affectedCheckpointIds; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Snapshot that = (Snapshot) other; return java.util.Objects.equals(this.manifest, that.manifest) && java.util.Objects.equals(this.digest, that.digest) && java.util.Objects.equals(this.affectedCheckpointIds, that.affectedCheckpointIds); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.manifest); hash = 31 * hash + java.util.Objects.hashCode(this.digest); hash = 31 * hash + java.util.Objects.hashCode(this.affectedCheckpointIds); return hash; }
        @Override public String toString() { return "Snapshot[" + "manifest=" + manifest + ", digest=" + digest + ", affectedCheckpointIds=" + affectedCheckpointIds + "]"; }
}
    public static Snapshot from(List<Map<String,Object>> inherited,List<Map<String,Object>> ready,List<Finding> findings){
        Map<String,Map<String,Object>> selected=new TreeMap<>();
        for(Map<String,Object> row:inherited)if(valid(row))selected.put(text(row,"attachmentId"),new LinkedHashMap<>(row));
        for(Map<String,Object> row:ready)if("READY".equals(text(row,"status"))&&valid(row)){
            Map<String,Object> frozen=new LinkedHashMap<>();
            for(String key:cn.bidcheck.compat.Java8Api.listOf("attachmentId", "documentId", "sha256", "version", "checkpointId", "claimId", "originRunId", "name"))if(row.containsKey(key))frozen.put(key,row.get(key));
            frozen.put("affectedCheckpointIds",cn.bidcheck.compat.Java8Api.listCopyOf(dependents(text(row,"checkpointId"),findings)));selected.put(text(row,"attachmentId"),frozen);
        }
        List<Map<String,Object>> manifest=cn.bidcheck.compat.Java8Api.listCopyOf(selected.values());Set<String> affected=new LinkedHashSet<>();
        Set<String> prior=new HashSet<>();for(Map<String,Object> row:inherited)prior.add(text(row,"attachmentId"));
        for(Map<String,Object> row:manifest)if(!prior.contains(text(row,"attachmentId"))){affected.add(text(row,"checkpointId"));affected.addAll(strings(row.get("affectedCheckpointIds")));}
        // Digest includes bytes and exact item binding; parsing timestamps are deliberately excluded.
        String canonical=manifest.stream().map(r->text(r,"attachmentId")+"|"+text(r,"documentId")+"|"+text(r,"sha256")+"|"+r.get("version")+"|"+text(r,"checkpointId")+"|"+text(r,"claimId")+"|"+String.join(",",new TreeSet<>(strings(r.get("affectedCheckpointIds"))))).collect(java.util.stream.Collectors.joining("\n"));
        return new Snapshot(manifest,manifest.isEmpty()?"":stableId(canonical),cn.bidcheck.compat.Java8Api.setCopyOf(affected));
    }
    private static boolean valid(Map<String,Object> row){return !cn.bidcheck.compat.Java8Api.isBlank(text(row,"attachmentId"))&&!cn.bidcheck.compat.Java8Api.isBlank(text(row,"documentId"))&&!cn.bidcheck.compat.Java8Api.isBlank(text(row,"sha256"))&&!cn.bidcheck.compat.Java8Api.isBlank(text(row,"checkpointId"))&&row.get("version") instanceof Number;}
    static Set<String> dependents(String checkpoint,List<Finding> findings){Set<String> result=new LinkedHashSet<>();result.add(checkpoint);boolean changed;
        do{changed=false;for(Finding finding:findings)if(!result.contains(finding.checkpoint().id())){Set<String> dependencies=new LinkedHashSet<>();references(finding.assessment(),dependencies);if(dependencies.stream().anyMatch(result::contains)){result.add(finding.checkpoint().id());changed=true;}}}while(changed);
        return Collections.unmodifiableSet(result);
    }
    private static void references(Object value,Set<String> result){if(value instanceof Map<?,?>){for(java.util.Map.Entry<?, ?> entry:((Map<?,?>) (value)).entrySet())if(cn.bidcheck.compat.Java8Api.setOf("dependencyCheckpointIds", "requiredCheckpointIds").contains(String.valueOf(entry.getKey())))result.addAll(strings(entry.getValue()));else references(entry.getValue(),result);}else if(value instanceof Collection<?>)((Collection<?>) (value)).forEach(v->references(v,result));}
    private static List<String> strings(Object value){return value instanceof Collection<?>?cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().filter(String.class::isInstance).map(String.class::cast)):cn.bidcheck.compat.Java8Api.listOf();}
}
