package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.service.BidService.*;
import static org.junit.jupiter.api.Assertions.*;

class InferenceConfirmationImpactTest {
    final List<Map<String,Object>> versions=cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "claim", "revision", 1));
    Map<String,Object> decision(String status){return fields("id","claim","revision",1,"status",status,"proposedFact",fields("kind","RELATION","subject","张国超","field","同一人","value","张*超","scope","CURRENT_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.listOf("s22", "s31"),"attributes",cn.bidcheck.compat.Java8Api.mapOf("object", "张*超")),"evidences",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("sourceId", "s22"), cn.bidcheck.compat.Java8Api.mapOf("sourceId", "s31")));}
    Map<String,Object> dependency(String predicate,String subject,List<String> names,String source){return fields("obligationId","r","observationKey","key","target",fields("predicate",predicate,"subject",subject,"field","number","unit","","parameters",cn.bidcheck.compat.Java8Api.mapOf()),"resolvedSubjects",names,"sourceIds",cn.bidcheck.compat.Java8Api.isBlank(source)?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listOf(source));}
    Finding finding(String id,List<Map<String,Object>> dependencies){Checkpoint cp=new Checkpoint(id,id,"资格","HIGH","SEMANTIC_RESPONSE","role","EXISTS","要求","",true,true,true,null);return new Finding(id,"parent",cp,new Evaluation("PASS","通过","",cn.bidcheck.compat.Java8Api.listOf(),0,"RULE"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", fields("complete",true,"gaps",cn.bidcheck.compat.Java8Api.listOf(),"recoveryEvidenceDependencies",dependencies)));}
    @Test void newIdentityConfirmationInvalidatesPassedCredentialAndCountButNotUnrelatedPrice(){
        Finding certificate=finding("certificate",cn.bidcheck.compat.Java8Api.listOf(dependency("CERTIFICATE","项目负责人",cn.bidcheck.compat.Java8Api.listOf("张国超"),"")));
        Finding count=finding("count",cn.bidcheck.compat.Java8Api.listOf(dependency("PERSON_COUNT","技术人员",cn.bidcheck.compat.Java8Api.listOf(),"")));
        Finding price=finding("price",cn.bidcheck.compat.Java8Api.listOf(dependency("NUMBER","报价单位",cn.bidcheck.compat.Java8Api.listOf(),"price-source")));
        assertEquals(cn.bidcheck.compat.Java8Api.setOf("certificate", "count"),InferenceConfirmationImpact.affected(cn.bidcheck.compat.Java8Api.listOf(certificate, count, price),cn.bidcheck.compat.Java8Api.listOf(),versions,cn.bidcheck.compat.Java8Api.listOf(decision("CONFIRMED"))));
    }
    @Test void sourceOverlapAndMissingIndexAreConservativeDependencies(){
        Finding overlap=finding("overlap",cn.bidcheck.compat.Java8Api.listOf(dependency("CERTIFICATE","另一字段",cn.bidcheck.compat.Java8Api.listOf(),"s31")));Finding legacy=finding("legacy",cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(cn.bidcheck.compat.Java8Api.setOf("overlap", "legacy"),InferenceConfirmationImpact.affected(cn.bidcheck.compat.Java8Api.listOf(overlap, legacy),cn.bidcheck.compat.Java8Api.listOf(),versions,cn.bidcheck.compat.Java8Api.listOf(decision("CONFIRMED"))));
    }
    @Test void rejectedUnchangedOrUnfrozenDecisionsDoNotInvalidatePasses(){List<Finding> passed=cn.bidcheck.compat.Java8Api.listOf(finding("legacy",cn.bidcheck.compat.Java8Api.listOf()));
        assertTrue(InferenceConfirmationImpact.affected(passed,cn.bidcheck.compat.Java8Api.listOf(),versions,cn.bidcheck.compat.Java8Api.listOf(decision("REJECTED"))).isEmpty());
        assertTrue(InferenceConfirmationImpact.affected(passed,versions,versions,cn.bidcheck.compat.Java8Api.listOf(decision("CONFIRMED"))).isEmpty());
        assertTrue(InferenceConfirmationImpact.affected(passed,cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(decision("CONFIRMED"))).isEmpty());
    }
}
