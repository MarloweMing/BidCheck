package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Document;
import java.util.*;
import static cn.bidcheck.service.BidService.*;

/** Versioned decisions for run idempotency. Rejections trigger recomputation without adding facts. */
final class InferenceConfirmationSnapshot {
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Snapshot {
        private final String digest;
        private final Set<String> checkpointIds;
        private final List<Map<String,Object>> records;
        @com.fasterxml.jackson.annotation.JsonCreator
        Snapshot(@com.fasterxml.jackson.annotation.JsonProperty("digest") String digest, @com.fasterxml.jackson.annotation.JsonProperty("checkpointIds") Set<String> checkpointIds, @com.fasterxml.jackson.annotation.JsonProperty("records") List<Map<String,Object>> records) { this.digest = digest; this.checkpointIds = checkpointIds; this.records = records; }
        public String digest() { return digest; }
        public Set<String> checkpointIds() { return checkpointIds; }
        public List<Map<String,Object>> records() { return records; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Snapshot that = (Snapshot) other; return java.util.Objects.equals(this.digest, that.digest) && java.util.Objects.equals(this.checkpointIds, that.checkpointIds) && java.util.Objects.equals(this.records, that.records); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.digest); hash = 31 * hash + java.util.Objects.hashCode(this.checkpointIds); hash = 31 * hash + java.util.Objects.hashCode(this.records); return hash; }
        @Override public String toString() { return "Snapshot[" + "digest=" + digest + ", checkpointIds=" + checkpointIds + ", records=" + records + "]"; }
}
    private static int number(Object value,int fallback){return value instanceof Number&&((Number) (value)).doubleValue()==((Number) (value)).intValue()?((Number) (value)).intValue():fallback;}
    static Snapshot from(List<Map<String,Object>> rows,Document bid,Document tender){
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> eligible=cn.bidcheck.compat.Java8Api.toList(rows.stream().filter(r->cn.bidcheck.compat.Java8Api.setOf("CONFIRMED", "REJECTED").contains(text(r,"status"))
            &&bid.projectId().equals(text(r,"projectId"))&&bid.id().equals(text(r,"documentId"))
            &&bid.sha256().equals(text(r,"documentHash"))&&bid.version()==number(r.get("documentVersion"),-1)
            &&Objects.equals(tender==null?"":tender.id(),text(r,"tenderDocumentId"))
            &&Objects.equals(tender==null?"":tender.sha256(),text(r,"tenderHash")))
            .sorted(Comparator.comparing(r->text(r,"id"))));
        if(eligible.isEmpty())return new Snapshot("",cn.bidcheck.compat.Java8Api.setOf(),cn.bidcheck.compat.Java8Api.listOf());
        String content=eligible.stream().map(r->text(r,"id")+":"+number(r.get("revision"),0)+":"+text(r,"status")+":"+text(r,"engineClaimId")).collect(java.util.stream.Collectors.joining("\n"));
        Set<String> ids=new LinkedHashSet<>();eligible.forEach(r->{if(!cn.bidcheck.compat.Java8Api.isBlank(text(r,"checkpointId")))ids.add(text(r,"checkpointId"));});
        return new Snapshot(stableId("confirmed-inference-snapshot:"+content),cn.bidcheck.compat.Java8Api.setCopyOf(ids),cn.bidcheck.compat.Java8Api.toList(eligible.stream().map(r->fields("id",text(r,"id"),"revision",number(r.get("revision"),0)))));
    }
}
