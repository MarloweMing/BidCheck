package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class RequirementDependencyProofTest {
    private final Document bid=new Document("b","p","BID","投标",1,"bid-hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"投标人：甲方有限公司","PAGE","TEXT",null)),cn.bidcheck.compat.Java8Api.listOf());
    private final Document tender=new Document("t","p","TENDER","招标",1,"tender-hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"须提供营业执照","BLOCK","TEXT",null)),cn.bidcheck.compat.Java8Api.listOf());
    private final Checkpoint cp=new Checkpoint("child","营业执照","资格","HIGH","SEMANTIC_RESPONSE","material","RESPONDS","须提供营业执照","",true,true,true,new Evidence("t","招标",1,"BLOCK","须提供营业执照","",1));
    private List<Map<String,Object>> ids(){return cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "b", "version", 1, "sha256", "bid-hash"), cn.bidcheck.compat.Java8Api.mapOf("id", "t", "version", 1, "sha256", "tender-hash"));}
    private Map<String,Object> snapshot(String run,boolean complete,List<Map<String,Object>> identities){
        Finding finding=new Finding("finding",run,cp,new Evaluation("PASS","已核对","",cn.bidcheck.compat.Java8Api.listOf(new Evidence("b","投标",1,"PAGE","投标人：甲方有限公司","chars:0-11",1)),1,"RULE"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("complete", complete, "gaps", cn.bidcheck.compat.Java8Api.listOf(), "documentIdentities", identities, "conditions", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("status", "PASS", "gaps", cn.bidcheck.compat.Java8Api.listOf())))));
        return cn.bidcheck.compat.Java8Api.mapOf("verified", true, "sourceRunId", run, "engineIdentity", "engine-v1", "documentIdentities", identities, "findings", cn.bidcheck.compat.Java8Api.listOf(finding), "checkpoints", cn.bidcheck.compat.Java8Api.listOf(cp), "revision", "revision-1");
    }
    private RuleEvaluator evaluator(Map<String,Object> snapshot){return new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(bid)),new HashMap<>(cn.bidcheck.compat.Java8Api.mapOf("sourceRunId", "run", "requirementDependencyEngineIdentity", "engine-v1", "tenderDocuments", cn.bidcheck.compat.Java8Api.listOf(tender), "_requirementDependencySnapshot", (Supplier<Map<String,Object>>)()->snapshot)));}
    @Test void onlyCurrentCompleteProofCanBeReused(){cn.bidcheck.verification.engine.RequirementDependencyProof.Snapshot result=RequirementDependencyProof.current(evaluator(snapshot("run",true,ids())));assertTrue(result.verified());assertEquals(1,result.findings().size());}
    @Test void oldRunAndChangedDocumentAreRejected(){assertFalse(RequirementDependencyProof.current(evaluator(snapshot("previous-run",true,ids()))).verified());
        List<Map<String,Object>> changed=new ArrayList<>(ids());changed.set(0,cn.bidcheck.compat.Java8Api.mapOf("id", "b", "version", 2, "sha256", "other-hash"));
        assertFalse(RequirementDependencyProof.current(evaluator(snapshot("run",true,changed))).verified());
    }
    @Test void passLabelWithoutWholeProofIsNotReusable(){cn.bidcheck.verification.engine.RequirementDependencyProof.Snapshot result=RequirementDependencyProof.current(evaluator(snapshot("run",false,ids())));assertTrue(result.verified());assertTrue(result.findings().isEmpty());}
    @Test void arbitraryContextBooleanCannotCreateProof(){cn.bidcheck.verification.engine.RequirementDependencyProof.Snapshot result=RequirementDependencyProof.current(new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(bid)),cn.bidcheck.compat.Java8Api.mapOf("requirementDependencyInputsVerified", true)));assertFalse(result.verified());}
    @Test void emptyEvidencePassAndChangedQuoteAreNotReusable(){
        for(List<Evidence> evidence:cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.<Evidence>listOf(), cn.bidcheck.compat.Java8Api.listOf(new Evidence("b","投标",1,"PAGE","不存在的材料正文","",1)))){
            Map<String,Object> snap=new HashMap<>(snapshot("run",true,ids()));Finding original=(Finding)((List<?>)snap.get("findings")).get(0);
            snap.put("findings",cn.bidcheck.compat.Java8Api.listOf(new Finding(original.id(),original.runId(),cp,new Evaluation("PASS","已核对","",evidence,1,"RULE"),original.assessment())));
            assertTrue(RequirementDependencyProof.current(evaluator(snap)).findings().isEmpty());
        }
    }
    @Test void unresolvedConditionsCannotHideBehindACompleteFlag(){
        Map<String,Object> snap=new HashMap<>(snapshot("run",true,ids()));Finding original=(Finding)((List<?>)snap.get("findings")).get(0);
        Map<String,Object> atomic=new HashMap<>((Map<String,Object>)original.assessment().get("atomicCheck"));atomic.put("conditions",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("status", "UNDETERMINED", "gaps", cn.bidcheck.compat.Java8Api.listOf())));
        snap.put("findings",cn.bidcheck.compat.Java8Api.listOf(new Finding(original.id(),original.runId(),cp,original.evaluation(),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", atomic))));
        assertTrue(RequirementDependencyProof.current(evaluator(snap)).findings().isEmpty());
    }
    @Test void aCopiedCheckpointMustStillHaveARealFrozenTenderSource(){
        Map<String,Object> snap=new HashMap<>(snapshot("run",true,ids()));Finding original=(Finding)((List<?>)snap.get("findings")).get(0);
        Checkpoint forged=new Checkpoint(cp.id(),cp.title(),cp.category(),cp.severity(),cp.type(),cp.field(),cp.operator(),cp.expected(),cp.unit(),true,true,true,
                new Evidence("t","招标",99,"BLOCK","须提供营业执照","",1));
        snap.put("checkpoints",cn.bidcheck.compat.Java8Api.listOf(forged));snap.put("findings",cn.bidcheck.compat.Java8Api.listOf(new Finding(original.id(),original.runId(),forged,original.evaluation(),original.assessment())));
        assertTrue(RequirementDependencyProof.current(evaluator(snap)).findings().isEmpty());
    }
    @Test void unrelatedRunRevisionDoesNotInvalidateAnUnchangedParentProof(){
        Map<String,Object> original=snapshot("run",true,ids()),changed=new HashMap<>(original);changed.put("revision","other-item-has-new-evidence");
        cn.bidcheck.verification.engine.RequirementDependencyProof.Snapshot first=RequirementDependencyProof.current(evaluator(original));cn.bidcheck.verification.engine.RequirementDependencyProof.Snapshot second=RequirementDependencyProof.current(evaluator(changed));
        assertEquals(RequirementDependencyProof.relevantRevision(first,cn.bidcheck.compat.Java8Api.listOf(cp.id())),RequirementDependencyProof.relevantRevision(second,cn.bidcheck.compat.Java8Api.listOf(cp.id())));
        Map<String,Object> statusChanged=new HashMap<>(original);Finding f=(Finding)((List<?>)original.get("findings")).get(0);
        statusChanged.put("findings",cn.bidcheck.compat.Java8Api.listOf(new Finding(f.id(),f.runId(),f.checkpoint(),new Evaluation("FAIL","内容存在冲突","",f.evaluation().evidences(),1,"RULE"),f.assessment())));
        assertNotEquals(RequirementDependencyProof.relevantRevision(first,cn.bidcheck.compat.Java8Api.listOf(cp.id())),
                RequirementDependencyProof.relevantRevision(RequirementDependencyProof.current(evaluator(statusChanged)),cn.bidcheck.compat.Java8Api.listOf(cp.id())));
    }
    @Test void exactVerifiedImageFieldCanBeReusedButAnotherImageCannot(){
        String value="姓名：张三；证书号：A12345";Map<String,Object> snap=new HashMap<>(snapshot("run",true,ids()));
        Finding original=(Finding)((List<?>)snap.get("findings")).get(0);
        snap.put("findings",cn.bidcheck.compat.Java8Api.listOf(new Finding(original.id(),original.runId(),cp,new Evaluation("PASS","原图字段已核","",cn.bidcheck.compat.Java8Api.listOf(new Evidence("b","投标",1,"PAGE",value,"image:image-hash",0)),1,"RULE"),original.assessment())));
        Map<String,Object> observation=VerificationTypes.linked("id","image-field","documentId","b","location",1,"locationType","PAGE","type","FIELD","label","查询截图原文","value",value,"readability","CLEAR","modelLimitations",cn.bidcheck.compat.Java8Api.listOf(),"sourceBbox",cn.bidcheck.compat.Java8Api.listOf(1, 1, 100, 100),"imageHash","image-hash","source",cn.bidcheck.compat.Java8Api.mapOf("documentId", "b", "documentHash", "bid-hash", "documentVersion", 1, "page", 1, "renderedSha256", "image-hash"));
        RuleEvaluator originalEvaluator=evaluator(snap);Map<String,Object> context=new HashMap<>(originalEvaluator.context);context.put("visualObservations",cn.bidcheck.compat.Java8Api.listOf(observation));
        assertEquals(1,RequirementDependencyProof.current(new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),originalEvaluator.corpus,context)).findings().size());
        observation.put("imageHash","another-image");
        assertTrue(RequirementDependencyProof.current(new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),originalEvaluator.corpus,context)).findings().isEmpty());
    }
}
