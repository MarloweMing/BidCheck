package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceNameCandidateTest {
    @Test void similarNameIsReviewCandidateAndCannotCountOrCrossDocuments(){
        String form="本次投标业绩清单\n项目名称：北方中心装修工程\n附表6-4 近年完成的类似项目情况表\n序号 合同名称 签订时间 金额\n1 合同名称：北方中心装饰装修工程施工合同 签订时间：2023年1月1日 金额：200万元";
        EvidenceCorpus c=new PersonnelCountRoleTest().corpus("投标文件\n项目名称：北方中心装修工程\n投标人：合成建设有限公司",form);cn.bidcheck.verification.engine.PerformanceInventoryProof proof=new PerformanceInventoryProof("附表6-4",new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),c,cn.bidcheck.compat.Java8Api.mapOf()));String name="北方中心装饰装修工程施工总合同";
        cn.bidcheck.verification.engine.PerformanceInventoryProof.Match match=proof.match(name,cn.bidcheck.compat.Java8Api.setOf(),true,cn.bidcheck.compat.Java8Api.setOf("d"));assertFalse(match.present());assertEquals(1,match.candidates().size());assertEquals(false,match.candidates().get(0).get("countsAsPerformance"));assertEquals(true,match.candidates().get(0).get("doesNotChangeHistoricalScope"));assertTrue(proof.match(name,cn.bidcheck.compat.Java8Api.setOf(),true,cn.bidcheck.compat.Java8Api.setOf("other-doc")).candidates().isEmpty());
    }
}
