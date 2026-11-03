package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import java.util.*;
import java.util.function.Supplier;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Server-side adapter for the frozen confirmation ledger, with the same citation checks as atomic rules. */
public final class ConfirmedInferenceEvidence {
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Receipt {
        private final String digest;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Receipt(@com.fasterxml.jackson.annotation.JsonProperty("digest") String digest) { this.digest = digest; }
        public String digest() { return digest; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Receipt that = (Receipt) other; return java.util.Objects.equals(this.digest, that.digest); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.digest); return hash; }
        @Override public String toString() { return "Receipt[" + "digest=" + digest + "]"; }
}
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private ConfirmedInferenceEvidence() {}
    private static String digest(Map<String,Object> value){Map<String,Object> copy=new LinkedHashMap<>(value);copy.remove("_confirmedRelationReceipt");return identity(JSON.valueToTree(copy).toString());}
    public static boolean isValidated(Map<String,Object> value){return value.get("_confirmedRelationReceipt") instanceof Receipt&&((Receipt) (value.get("_confirmedRelationReceipt"))).digest().equals(digest(value));}
    public static List<Map<String,Object>> validated(List<Map<String,Object>> ledgerRecords,List<Document> documents){
        if(ledgerRecords==null||ledgerRecords.isEmpty())return cn.bidcheck.compat.Java8Api.listOf();
        List<Map<String,Object>> stored=cn.bidcheck.compat.Java8Api.toList(ledgerRecords.stream().filter(r->!cn.bidcheck.compat.Java8Api.isBlank(str(r,"confirmationLedgerId"))));
        Map<String,Object> context=new HashMap<>();context.put("confirmedInferenceLookup",(Supplier<List<Map<String,Object>>>)(()->stored));
        EvidenceCorpus corpus=new EvidenceCorpus(documents);List<Map<String,Object>> result=new ArrayList<>();
        for(Fact fact:InferenceClaims.confirmed(context,corpus)){
            InferenceClaims.HumanConfirmation confirmation=(InferenceClaims.HumanConfirmation)fact.attributes().get("humanConfirmation");
            Map<String,Object> record=stored.stream().filter(r->str(r,"id").equals(confirmation.claimId())).findFirst().orElseThrow(java.util.NoSuchElementException::new);
            Map<String,Object> attributes=new LinkedHashMap<>(fact.attributes());attributes.remove("humanConfirmation");
            Map<String,Object> value=linked("id",fact.id(),"kind",fact.kind(),"subject",fact.subject(),"field",fact.field(),"value",fact.value(),"rawValue",fact.rawValue(),"unit",fact.unit(),"basis",fact.basis(),"scope",fact.scope(),"attributes",attributes,"sourceIds",fact.sourceIds(),
                "evidences",record.get("evidences"),"confirmationId",confirmation.claimId(),"confirmationLedgerId",record.get("confirmationLedgerId"),"revision",record.get("revision"),"confirmedAt",confirmation.decidedAt(),"targetId",confirmation.targetId(),"targetFingerprint",confirmation.targetFingerprint());
            value.put("_confirmedRelationReceipt",new Receipt(digest(value)));result.add(value);
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(result);
    }
}
