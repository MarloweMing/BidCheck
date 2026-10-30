package cn.bidcheck.service;

import java.text.Normalizer;
import java.util.*;
import static cn.bidcheck.service.BidService.*;
import static cn.bidcheck.service.CheckResolution.*;

/** Work identity, not proof: equivalent claims on the same frozen source are one input. */
final class RecoveryEvidenceIdentity {
    private RecoveryEvidenceIdentity() {}
    private static final Set<String> AUDIT_KEYS=Set.of("id","factId","originalFactId","sourceIds","receiptId","batchId","usageId",
        "occurrenceId","itemId","executionId","attemptId","savedAt","createdAt","updatedAt","confidence","description","reason",
        "reusedFromBatchId","reusedObservation","observedInCurrentRun");

    static String key(Map<String,Object> change){
        Map<String,Object> body=fields("recordType",text(change,"recordType"));
        if("FACT".equals(text(change,"recordType"))){
            Map<String,Object> fact=map(change.get("fact"));
            body.put("fact",cn.bidcheck.verification.facts.SemanticFactIdentity.value(select(fact,List.of("kind","subject","field","value","unit","basis","scope","attributes"))));
            if(text(fact,"value").isBlank())body.put("rawValue",fact.getOrDefault("rawValue",""));
        }else body.putAll(select(change,List.of("observationKey","modelIdentity","status","fieldCoverage")));
        body.put("sources",maps(change.get("sources")).stream().map(RecoveryEvidenceIdentity::sourceIdentity)
            .map(RecoveryEvidenceIdentity::canonical).distinct().sorted().toList());
        return hash(canonical(body));
    }

    private static Map<String,Object> sourceIdentity(Map<String,Object> source){
        Map<String,Object> out=select(source,List.of("documentId","documentHash","documentVersion","parserFingerprint","locationType","page","location","bbox","renderedSha256"));
        Map<String,Object> evidence=map(source.get("evidence"));
        out.putAll(select(evidence,List.of("documentId","locationType","page","location","bbox","offset","startOffset","endOffset")));
        // Legacy fixtures may have no location. A stable alias is then safer than conflating pages.
        if(!out.containsKey("page")&&!out.containsKey("location"))out.put("source",source.getOrDefault("sourceFingerprint",source.getOrDefault("id","")));
        return out;
    }
    private static Map<String,Object> select(Map<String,Object> value,List<String> keys){Map<String,Object> out=new TreeMap<>();for(String key:keys)if(value.containsKey(key))out.put(key,value.get(key));return out;}
    static String canonical(Object value){
        if(value instanceof Map<?,?> raw){Map<String,String> sorted=new TreeMap<>();raw.forEach((key,v)->{String name=String.valueOf(key);if(!AUDIT_KEYS.contains(name))sorted.put(name,canonical(v));});return sorted.toString();}
        // Coordinates, table cells and attribute paths are positional data.
        // Only actual sets are order-independent; source collections are
        // explicitly canonicalized as sets by key() above.
        if(value instanceof Set<?> values)return values.stream().map(RecoveryEvidenceIdentity::canonical).distinct().sorted().toList().toString();
        if(value instanceof Collection<?> values)return values.stream().map(RecoveryEvidenceIdentity::canonical).toList().toString();
        return Normalizer.normalize(Objects.toString(value,""),Normalizer.Form.NFKC).strip().replaceAll("\\s+"," ");
    }
}
