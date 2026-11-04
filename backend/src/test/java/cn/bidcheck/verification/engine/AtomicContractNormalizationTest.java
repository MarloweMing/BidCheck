package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ContractRecordSubmissionTest.*;

/** Synthetic Host receipts; normalization never substitutes for source validation. */
class AtomicContractNormalizationTest {
    private Map<String,Object> atom(Fixture f,String field,String raw,Map<String,Object> attrs){
        return linked("field",field,"rawValue",raw,"sourceIds",Collections.singletonList(source(f.corpus,4).id()),"attributes",attrs);
    }
    private Map<String,Object> submit(Fixture f,Map<String,Object>... atoms){
        Map<String,Object> original=f.candidate("contractName","这段引文在文件中不存在");
        original.put("attributes",linked("contractName",NAME));
        return f.host.call("submit_candidate_fact",linked("targetObligationId",f.target,"fact",original,
            "submissionMode","CONTRACT_FIELDS","contractFields",Arrays.asList(atoms)));
    }
    private List<Fact> accepted(Fixture f){
        List<Fact> facts=new ArrayList<>();
        for(Fact fact:f.host.facts.values())if("CONTRACT".equals(fact.kind()))facts.add(fact);
        return facts;
    }
    @Test void missingDuplicateIdentityValuesUseOnlyTheirOwnReadRawText() throws Exception {
        Fixture f=fixture();
        Map<String,Object> receipt=submit(f,atom(f,"contractName",NAME,linked()),atom(f,"contractNumber",NUMBER,linked()));
        assertEquals(2,ProofVerifier.strings(receipt.get("acceptedFactIds")).size(),receipt.toString());
        assertTrue(receipt.toString().contains("RAW_VALUE_UNGROUNDED"));
        assertEquals(true,receipt.get("partialAdoption"));
        for(Fact fact:accepted(f)){
            assertEquals(fact.rawValue(),fact.attributes().get(fact.field()));
            assertFalse(fact.attributes().containsKey("amount"));
            assertFalse(fact.attributes().containsKey("signedDate"));
        }
        assertEquals(false,receipt.get("businessProof"));
    }
    @Test void amountScalarCanBeCopiedButUnitAndBasisRemainIndependentGuards() throws Exception {
        Fixture f=new Fixture(completeBody(),"续页");
        Map<String,Object> valid=atom(f,"amount","200",linked("contractName",NAME,"amountBasis","合同金额","unit","万元"));
        Map<String,Object> invalid=atom(f,"amount","200",linked("contractName",NAME,"amountBasis","合同金额","unit","元"));
        Map<String,Object> receipt=submit(f,valid,invalid);
        assertEquals(1,ProofVerifier.strings(receipt.get("acceptedFactIds")).size(),receipt.toString());
        assertTrue(receipt.toString().contains("CONTRACT_AMOUNT_UNIT_UNPROVEN"));
        assertEquals("200",accepted(f).get(0).attributes().get("amount"));
        assertEquals("万元",accepted(f).get(0).attributes().get("unit"));
        assertFalse(((Map<?,?>)valid.get("attributes")).containsKey("amount"));
    }
    @Test void completeDateCanNormalizeButAnUnrelatedDateStillFailsItsSigningRole() throws Exception {
        Fixture f=new Fixture(completeBody()+"\n开工日期：2024年2月2日", "续页");
        Map<String,Object> receipt=submit(f,
            atom(f,"signedDate","2023年1月1日",linked("contractName",NAME)),
            atom(f,"signedDate","2024年2月2日",linked("contractName",NAME)));
        assertEquals(1,ProofVerifier.strings(receipt.get("acceptedFactIds")).size(),receipt.toString());
        assertEquals("2023-01-01",accepted(f).get(0).attributes().get("signedDate"));
        assertTrue(receipt.toString().contains("CONTRACT_SIGNING_DATE_ROLE_UNPROVEN"));
    }
    @Test void scalarNormalizationDoesNotInventMissingContractIdentity() throws Exception {
        Fixture f=fixture();
        Map<String,Object> receipt=submit(f,atom(f,"party",COMPANY,linked()));
        assertTrue(accepted(f).isEmpty());
        assertTrue(receipt.toString().contains("CONTRACT_IDENTITY_UNPROVEN"),receipt.toString());
        assertFalse(receipt.toString().contains("CONTRACT_ATOMIC_FIELDS_INVALID"),receipt.toString());
    }
    @Test void explicitConflictsAndExtraAttributesCannotBeOverwritten() throws Exception {
        Fixture f=fixture();
        Map<String,Object> receipt=submit(f,
            atom(f,"contractNumber",NUMBER,linked("contractNumber","OTHER-001")),
            atom(f,"contractName",NAME,linked("amount","999999")));
        assertTrue(accepted(f).isEmpty());
        assertTrue(receipt.toString().contains("CONTRACT_ATOMIC_FIELDS_INVALID"));
        Map<String,Object> wrongKind=atom(f,"contractName",NAME,linked());
        wrongKind.put("kind","NUMBER");
        Map<String,Object> kindReceipt=submit(f,wrongKind);
        assertTrue(ProofVerifier.strings(kindReceipt.get("acceptedFactIds")).isEmpty(),kindReceipt.toString());
        assertTrue(kindReceipt.toString().contains("CONTRACT_ATOMIC_FIELDS_INVALID"));
    }
    @Test void NavigationOnlyAndForgedRawTextNeverBecomeAcceptedFields() throws Exception {
        Fixture unread=new Fixture(identityBody(),"续页",false);
        assertTrue(ProofVerifier.strings(submit(unread,atom(unread,"contractName",NAME,linked())).get("acceptedFactIds")).isEmpty());
        Fixture f=fixture();
        assertTrue(ProofVerifier.strings(submit(f,atom(f,"contractNumber","SYN-9999",linked())).get("acceptedFactIds")).isEmpty());
    }
    @Test void absenceOfMoneyBasisAndIncompleteDateCannotBeInferred() throws Exception {
        Fixture f=new Fixture(completeBody(),"续页");
        Map<String,Object> receipt=submit(f,
            atom(f,"amount","200",linked("contractName",NAME,"unit","万元")),
            atom(f,"signedDate","2023年1月",linked("contractName",NAME)));
        assertTrue(accepted(f).isEmpty());
        assertTrue(receipt.toString().contains("CONTRACT_AMOUNT_BASIS_UNPROVEN"));
        assertTrue(receipt.toString().contains("CONTRACT_ATOMIC_FIELDS_INVALID"));
    }
}
