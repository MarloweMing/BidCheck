package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class Ticket231BusinessRulesTest {
    final PersonnelCountRoleTest fixture=new PersonnelCountRoleTest();
    @Test void twoIndependentConditionsReuseOnlyTheApprovedSourcePair(){
        ConfirmedSubjectBindingsTest f=new ConfirmedSubjectBindingsTest();EvidenceCorpus c=f.corpus();
        List<Fact> facts=Arrays.asList(f.role(c),f.certificate(c),f.confirmation(c,c.all));
        Rule credential=f.rule(false);
        Rule sameCredential=new Rule("different-condition",credential.description(),credential.predicate(),credential.subject(),credential.field(),
            credential.operator(),credential.expected(),credential.unit(),credential.parameters(),credential.sourceQuote(),"",Collections.emptyList());
        RuleEvaluator evaluator=new RuleEvaluator(facts,c,Collections.emptyMap());
        assertEquals("PASS",evaluator.evaluate(credential).status());
        assertEquals("PASS",evaluator.evaluate(sameCredential).status());
        assertEquals("UNDETERMINED",new RuleEvaluator(Arrays.asList(f.role(c),f.certificate(c)),c,Collections.emptyMap()).evaluate(sameCredential).status());
    }
    @Test void c2CannotSatisfyAnExplicitCOrC3Requirement(){
        String requirement="专职安全员须具有安全生产考核合格证（C类）或安全生产考核合格证书（C3类）";
        Rule rule=new Rule("safety",requirement,"CERTIFICATE","专职安全员","certificate","","","",
            linked("personnelCredentialKind","SAFETY","category","安全生产考核合格证","allowedSafetyClasses",Arrays.asList("C","C3")),requirement,"",Collections.emptyList());
        for(String subtype:Arrays.asList("C2","C3")){
            String personId="110101198001010010";
            EvidenceCorpus c=fixture.corpus("投标文件\n项目名称：北方中心装修工程\n本项目拟派李明担任专职安全员。\n姓名：李明\n身份证号："+personId,
                "建筑施工企业"+("C2".equals(subtype)?"土建类":"综合类")+"专职安全生产管理人员 安全生产考核合格证书\n姓名：李明\n证件号码："+personId+"\n企业名称：北方设计有限公司\n编号：京建安"+subtype+"(2015)0205389");
            Fact role=fixture.role(c,1,"专职安全员","李明","","");
            Decision d=PersonnelCredentialProof.evaluate(rule,new RuleEvaluator(Collections.singletonList(role),c,Collections.emptyMap()));
            assertEquals(subtype.equals("C2")?"FAIL":"PASS",d.status(),d.toString());
            if(subtype.equals("C2"))assertTrue(d.reason().contains("C2"));
        }
    }
    @Test void projectReferencesReadParenthesesAndNewlinesWithoutBorrowingHistory(){
        String expected="TEST-BID-102"; Rule rule=new Rule("n","编号","SAME_FIELD","本项目","projectNumber","=",expected,"",Collections.emptyMap(),"本项目编号","",Collections.emptyList());
        for(String second:Arrays.asList(expected,"TEST-BID-103")){
            EvidenceCorpus c=fixture.corpus("本项目 招标编号：\n"+expected+"）","本项目 招标项目编号：（\n"+second+"）","历史项目编号：OLD-AAA");
            List<Fact> facts=new ArrayList<>();for(int i=1;i<=3;i++)facts.add(new Fact("f"+i,"FIELD","本项目","projectNumber","（","（","","",i==3?"HISTORICAL_PROJECT":"CURRENT_PROJECT",Collections.singletonList(fixture.source(c,i).id()),Collections.emptyMap()));
            Decision d=new RuleEvaluator(facts,c,Collections.emptyMap()).sameField(rule,facts);
            assertEquals(expected.equals(second)?"PASS":"FAIL",d.status(),d.toString());
        }
    }
    @Test void fourSubmittedRowsRouteToFourSeparateContractPackets(){
        EvidenceCorpus corpus=fixture.corpus("业绩清单\n近年完成的类似项目情况表\n序号 合同名称 签订时间 金额（万元）\n1 甲工程\n2 乙工程\n3 丙工程\n4 丁工程",
            "①甲工程合同","PO-2023001 金额100万元","②乙工程合同","PO-2023002 金额200万元","③丙工程合同","④丁工程合同");
        Rule rule=new Rule("score","业绩","SCORE_COUNT","施工单位","","","","",Collections.emptyMap(),"施工单位业绩","",Collections.emptyList());
        List<Map<String,Object>> rows=DeclaredPerformanceRows.read(rule,"施工单位",corpus,Collections.emptyList());
        assertEquals(4,rows.size()); assertEquals(2,rows.get(0).get("proofPageStart"));assertEquals(3,rows.get(0).get("proofPageEnd"));
        assertEquals(4,rows.get(1).get("proofPageStart"));assertEquals(5,rows.get(1).get("proofPageEnd"));
        assertTrue(rows.get(0).get("orderPages").toString().contains("PO-2023001"));
        assertFalse(rows.get(0).get("orderPages").toString().contains("PO-2023002"));
        assertEquals(4,DeclaredPerformanceRows.unverifiedChecks(rows).size());
        assertTrue(DeclaredPerformanceRows.unverifiedChecks(rows).stream().noneMatch(r->Boolean.TRUE.equals(r.get("counted"))));
    }
    @Test void maskedTitleScreenshotGeneratesCitedQuestionOnly(){
        EvidenceCorpus corpus=fixture.corpus(fixture.appointment("项目负责人","李明扬","110101199001010011","",""),
            "全国职称评审信息查询\n| 姓名 | 李*扬 |\n| 证书编号 | 20****18 |");
        List<Fact> facts=fixture.checked(corpus,fixture.role(corpus,1,"项目负责人","李明扬","",""));
        Rule rule=new Rule("title","项目负责人职称评分","SCORE_COUNT","项目负责人","职称","","","",Collections.emptyMap(),"项目负责人实力","",Collections.emptyList());
        Decision decision=new Decision("title","评分","UNDETERMINED","缺口",Collections.emptyList(),Arrays.asList(gap("SCORING_TITLE_IDENTITY_REQUIRED","职称查询姓名遮蔽"),gap("AGENT_INVESTIGATION_STALLED","重复搜索")),Collections.emptyMap());
        List<Map<String,Object>> candidates=ProgrammaticInferenceCandidates.collect(rule,decision,facts,corpus);
        assertTrue(candidates.stream().anyMatch(c->"MASKED_PERSON_IDENTITY".equals(c.get("type"))),candidates.toString());
        assertEquals("UNDETERMINED",decision.status());
    }
}
