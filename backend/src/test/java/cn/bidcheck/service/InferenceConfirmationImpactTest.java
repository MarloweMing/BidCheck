package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.service.BidService.*;
import static org.junit.jupiter.api.Assertions.*;

class InferenceConfirmationImpactTest {
    final List<Map<String,Object>> versions=List.of(Map.of("id","claim","revision",1));
    Map<String,Object> decision(String status){return fields("id","claim","revision",1,"status",status,"proposedFact",fields("kind","RELATION","subject","张国超","field","同一人","value","张*超","scope","CURRENT_PROJECT","sourceIds",List.of("s22","s31"),"attributes",Map.of("object","张*超")),"evidences",List.of(Map.of("sourceId","s22"),Map.of("sourceId","s31")));}
    Map<String,Object> dependency(String predicate,String subject,List<String> names,String source){return fields("obligationId","r","observationKey","key","target",fields("predicate",predicate,"subject",subject,"field","number","unit","","parameters",Map.of()),"resolvedSubjects",names,"sourceIds",source.isBlank()?List.of():List.of(source));}
    Finding finding(String id,List<Map<String,Object>> dependencies){Checkpoint cp=new Checkpoint(id,id,"资格","HIGH","SEMANTIC_RESPONSE","role","EXISTS","要求","",true,true,true,null);return new Finding(id,"parent",cp,new Evaluation("PASS","通过","",List.of(),0,"RULE"),Map.of("atomicCheck",fields("complete",true,"gaps",List.of(),"recoveryEvidenceDependencies",dependencies)));}
    @Test void newIdentityConfirmationInvalidatesPassedCredentialAndCountButNotUnrelatedPrice(){
        Finding certificate=finding("certificate",List.of(dependency("CERTIFICATE","项目负责人",List.of("张国超"),"")));
        Finding count=finding("count",List.of(dependency("PERSON_COUNT","技术人员",List.of(),"")));
        Finding price=finding("price",List.of(dependency("NUMBER","报价单位",List.of(),"price-source")));
        assertEquals(Set.of("certificate","count"),InferenceConfirmationImpact.affected(List.of(certificate,count,price),List.of(),versions,List.of(decision("CONFIRMED"))));
    }
    @Test void sourceOverlapAndMissingIndexAreConservativeDependencies(){
        Finding overlap=finding("overlap",List.of(dependency("CERTIFICATE","另一字段",List.of(),"s31")));Finding legacy=finding("legacy",List.of());
        assertEquals(Set.of("overlap","legacy"),InferenceConfirmationImpact.affected(List.of(overlap,legacy),List.of(),versions,List.of(decision("CONFIRMED"))));
    }
    @Test void rejectedUnchangedOrUnfrozenDecisionsDoNotInvalidatePasses(){List<Finding> passed=List.of(finding("legacy",List.of()));
        assertTrue(InferenceConfirmationImpact.affected(passed,List.of(),versions,List.of(decision("REJECTED"))).isEmpty());
        assertTrue(InferenceConfirmationImpact.affected(passed,versions,versions,List.of(decision("CONFIRMED"))).isEmpty());
        assertTrue(InferenceConfirmationImpact.affected(passed,List.of(),List.of(),List.of(decision("CONFIRMED"))).isEmpty());
    }
}
