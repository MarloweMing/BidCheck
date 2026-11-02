package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import java.util.*;
import java.util.function.Supplier;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Server-side adapter for the frozen confirmation ledger, with the same citation checks as atomic rules. */
public final class ConfirmedInferenceEvidence {
    private record Receipt(String digest) {}
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private ConfirmedInferenceEvidence() {}
    private static String digest(Map<String,Object> value){Map<String,Object> copy=new LinkedHashMap<>(value);copy.remove("_confirmedRelationReceipt");return identity(JSON.valueToTree(copy).toString());}
    public static boolean isValidated(Map<String,Object> value){return value.get("_confirmedRelationReceipt") instanceof Receipt receipt&&receipt.digest().equals(digest(value));}
    public static List<Map<String,Object>> validated(List<Map<String,Object>> ledgerRecords,List<Document> documents){
        if(ledgerRecords==null||ledgerRecords.isEmpty())return List.of();
        List<Map<String,Object>> stored=ledgerRecords.stream().filter(r->!str(r,"confirmationLedgerId").isBlank()).toList();
        Map<String,Object> context=new HashMap<>();context.put("confirmedInferenceLookup",(Supplier<List<Map<String,Object>>>)(()->stored));
        EvidenceCorpus corpus=new EvidenceCorpus(documents);List<Map<String,Object>> result=new ArrayList<>();
        for(Fact fact:InferenceClaims.confirmed(context,corpus)){
            InferenceClaims.HumanConfirmation confirmation=(InferenceClaims.HumanConfirmation)fact.attributes().get("humanConfirmation");
            Map<String,Object> record=stored.stream().filter(r->str(r,"id").equals(confirmation.claimId())).findFirst().orElseThrow();
            Map<String,Object> attributes=new LinkedHashMap<>(fact.attributes());attributes.remove("humanConfirmation");
            Map<String,Object> value=linked("id",fact.id(),"kind",fact.kind(),"subject",fact.subject(),"field",fact.field(),"value",fact.value(),"rawValue",fact.rawValue(),"unit",fact.unit(),"basis",fact.basis(),"scope",fact.scope(),"attributes",attributes,"sourceIds",fact.sourceIds(),
                "evidences",record.get("evidences"),"confirmationId",confirmation.claimId(),"confirmationLedgerId",record.get("confirmationLedgerId"),"revision",record.get("revision"),"confirmedAt",confirmation.decidedAt(),"targetId",confirmation.targetId(),"targetFingerprint",confirmation.targetFingerprint());
            value.put("_confirmedRelationReceipt",new Receipt(digest(value)));result.add(value);
        }
        return List.copyOf(result);
    }
}
