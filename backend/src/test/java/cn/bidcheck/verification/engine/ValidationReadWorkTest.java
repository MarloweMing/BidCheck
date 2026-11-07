package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class ValidationReadWorkTest {
    private EvidenceCorpus corpus(String... bodies) {
        List<Page> pages=new ArrayList<>();
        for(String body:bodies)pages.add(new Page(pages.size()+1,body,"PAGE","PDF_TEXT",""));
        return new EvidenceCorpus(Collections.singletonList(new Document("bid","project","BID","bid.pdf",1,"hash","",pages,Collections.emptyList())));
    }
    private Rule rule(String predicate,String subject,String field,Map<String,Object> params) {
        return new Rule("target","test",predicate,subject,field,"","","",params,"","",Collections.emptyList());
    }

    @Test void missingAppointeeCitationDoesNotConsumeIndependentUnreadAgreementOrCorrectableNamedCitation() {
        EvidenceCorpus corpus=corpus("投标文件\n项目名称：办公楼改造工程\n项目负责人情况表 姓名 李明 拟在本合同任职 项目负责人",
            "投标承诺书。承诺委派本合同关键岗位人员。","联合体协议书\n项目名称：办公楼改造工程\n两方自愿组成联合体共同投标，甲公司为牵头人。",
            "联合体协议书\n项目名称：旧仓库改造工程\n两方自愿组成联合体共同投标，乙公司为牵头人。");
        String profile=corpus.all.get(0).id(), commitment=corpus.all.get(1).id(), agreement=corpus.all.get(2).id();
        Rule rule=rule("RELATION","项目负责人","委派",Collections.emptyMap());
        ValidationReadWork work=new ValidationReadWork(Collections.singletonList(rule),corpus);
        work.bindSubjects(Collections.singletonList(linked("obligationId","target","bindingStatus","RESOLVED",
            "resolvedSubjects",Collections.singletonList(linked("subject","李明")))),corpus);
        assertFalse(work.records().get("target").contains(corpus.all.get(3)),"a different project's agreement must stay out");
        assertEquals(agreement,work.prioritize("target",Collections.singletonList(corpus.all.get(0)),
            new HashSet<>(Arrays.asList(profile,commitment))).get(0).id());
        Map<String,Object> citation=linked("sourceId",commitment,"quote","承诺委派本合同关键岗位人员");
        Map<String,Object> fact=linked("kind","INFERRED","field","委派","value","李明","sourceIds",Arrays.asList(profile,commitment),
            "attributes",linked("inference",linked("evidence",Collections.singletonList(citation))));
        Map<String,Object> rejection=linked("status","REJECTED","error","INFERENCE_PROTOCOL_INVALID");
        work.observe("target",linked("fact",fact),rejection);
        Map<String,Object> incomplete=work.receipt("target","evidence",new HashSet<>(Arrays.asList(profile,commitment)));
        assertEquals(Collections.singletonList(agreement),incomplete.get("pendingSourceIds"));
        Map<String,Object> complete=work.receipt("target","evidence",new HashSet<>(Arrays.asList(profile,commitment,agreement)));
        assertEquals(Collections.emptyList(),complete.get("pendingSourceIds"));
        assertEquals(false,complete.get("businessProof"));
        citation.put("sourceId",profile);citation.put("quote","姓名 李明");
        work.observe("target",linked("fact",fact),rejection);
        assertTrue(work.receipt("target","evidence",new HashSet<>(Arrays.asList(profile,commitment,agreement))).isEmpty());
    }

    @Test void coverageRuleKeepsAnUnreadIndependentCertificateAndDoesNotRetireTargetedHolderRepair() {
        EvidenceCorpus corpus=corpus("工程企业资质证书 证书编号 AA001 有效期至2028年5月4日",
            "证书原件：注册编号 BB002；注册有效期2024年8月至2026年5月。");
        String other=corpus.all.get(0).id(),cited=corpus.all.get(1).id();
        Map<String,Object> rejection=linked("status","REJECTED","rejections",Collections.singletonList(linked("code","RAW_VALUE_UNGROUNDED","fieldPath","/fact/rawValue")));
        Map<String,Object> submission=linked("fact",linked("kind","CERTIFICATE","field","certificateExpiry","sourceIds",Collections.singletonList(cited)));
        Rule all=rule("VALIDITY","","certificateExpiry",linked("allApplicableCertificates",true));
        ValidationReadWork work=new ValidationReadWork(Collections.singletonList(all),corpus);
        work.observe("target",submission,rejection);
        assertEquals(Collections.singletonList(other),work.receipt("target","e1",Collections.singleton(cited)).get("pendingSourceIds"));
        assertEquals(Collections.emptyList(),work.receipt("target","e1",new HashSet<>(Arrays.asList(cited,other))).get("pendingSourceIds"));
        ValidationReadWork bound=new ValidationReadWork(Collections.singletonList(rule("VALIDITY","李明","certificateExpiry",Collections.emptyMap())),corpus);
        bound.observe("target",submission,rejection);
        assertTrue(bound.receipt("target","e1",new HashSet<>(Arrays.asList(cited,other))).isEmpty());
    }

    @Test void splicedLeaderAndPersonSourcesAreNotAnAppointmentAndANamedNewOriginalReopensIt() {
        for(boolean explicit:Arrays.asList(false,true)){
            EvidenceCorpus corpus=corpus("投标文件\n项目名称：办公楼改造工程\n项目负责人情况表 姓名：李明",
                "联合体协议书\n项目名称：办公楼改造工程\n两方自愿组成联合体共同投标。甲公司为联合体牵头人。",
                "证书查询\n企业名称：甲公司\n姓名：李明","我方承诺拟委派关键岗位人员未超出可承接项目的最大数量。",
                explicit?"甲公司任命书\n"+String.join("",Collections.nCopies(1400,"。"))+"拟派项目负责人为李明。":"资格材料目录");
            Rule rule=rule("RELATION","项目负责人","委派",Collections.emptyMap());
            ValidationReadWork work=new ValidationReadWork(Collections.singletonList(rule),corpus);
            work.bindSubjects(Collections.singletonList(linked("obligationId","target","bindingStatus","RESOLVED",
                "resolvedSubjects",Collections.singletonList(linked("subject","李明")))),corpus);
            Source agreement=corpus.all.get(1),certificate=corpus.all.get(2);
            Map<String,Object> fact=linked("kind","RELATION","field","委派","subject","甲公司","value","李明",
                "rawValue","甲公司为联合体牵头人。\n企业名称：甲公司\n姓名：李明","sourceIds",Arrays.asList(agreement.id(),certificate.id()));
            Map<String,Object> issue=linked("status","REJECTED","rejections",Collections.singletonList(linked("code","RAW_VALUE_UNGROUNDED","fieldPath","/fact/rawValue")));
            work.observe("target",linked("fact",fact),issue);
            Set<String> read=new HashSet<>();
            for(Source source:corpus.all)read.add(source.id());
            Map<String,Object> receipt=work.receipt("target","revision",read);
            if(explicit)assertTrue(receipt.isEmpty(),"a named current appointment crossing chunks still has executable work");
            else{
                assertEquals("APPOINTMENT_ENDPOINT_UNCITED",receipt.get("reason"));
                assertEquals(Collections.emptyList(),receipt.get("pendingSourceIds"));
                assertEquals(false,receipt.get("businessProof"));
                fact.put("rawValue","一个无法定位的候选字符串");work.observe("target",linked("fact",fact),issue);
                assertTrue(work.receipt("target","revision",read).isEmpty(),"a rejection label alone is not proof of a splice");
            }
        }
    }
}
