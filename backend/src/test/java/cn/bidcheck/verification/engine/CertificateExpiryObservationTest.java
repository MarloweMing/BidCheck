package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.verification.engine.VerificationTypes.Fact;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class CertificateExpiryObservationTest {
    private static final String OWNER="戴工建设集团有限公司", NUMBER="A111033727", FIELD="有效期", CATEGORY="建筑装饰工程设计专项";
    private static final String PAGE="5.2 投标人有效资质证明文件\n（1）设计单位资质证明\n15\n\n【PDF识别文字】\n建筑装饰工程专项甲级\n"
        +"企业名称："+OWNER+"  \n\n经济性质：有限责任公司\n资质等级："+CATEGORY+"甲级。\n# 资质证书\n"
        +"证书编号："+NUMBER+"  \n\n有效期：至2026年06月04日  \n\n中华人民共和国住房和城乡建设部制\n发证机关：  \n\n2021年06月16日  \nNo.AZ 0101374";
    private static final Rule TARGET=new Rule("TYPED-CERTIFICATE-VALIDITY","证书在投标截止日有效","VALIDITY","","certificateExpiry",">=","2025-08-05","",
        linked("allApplicableCertificates",true,"validityField",FIELD,"referenceEvent","BID_DEADLINE"),"投标截止时间为：2025年08月05日09时30分。","",Collections.emptyList());
    private EvidenceCorpus corpus(String certificate){
        return new EvidenceCorpus(Collections.singletonList(new Document("bid","p","BID","投标文件",1,"frozen-bid","",Arrays.asList(
            new Page(1,"投标文件\n项目名称：北方中心装修工程\n投标人："+OWNER,"PAGE","TEXT",""),
            new Page(15,certificate,"PAGE","OCR",""),
            new Page(18,"施工单位资质证明：另一企业的资质证书","PAGE","OCR","")),Collections.emptyList())));
    }
    private Source page(EvidenceCorpus corpus,int page){return corpus.all.stream().filter(s->s.evidence().page()==page).findFirst().get();}
    private Map<String,Object> candidate(String source){
        return linked("kind","CERTIFICATE","subject",OWNER,"field","certificateExpiry","rawValue","证书编号："+NUMBER+"\n有效期：至2026年06月04日",
            "value","2026-06-04","unit","DATE","basis","","scope","CURRENT_PROJECT","sourceIds",Collections.singletonList(source),"attributes",
            linked("category",CATEGORY,"grade","甲级","number",NUMBER,"numberField","证书编号","validityField",FIELD,"start","2021-06-16","end","2026-06-04"));
    }
    private EvidenceInvestigation.Host host(EvidenceCorpus c){
        Checkpoint cp=new Checkpoint("all-cert","证书在投标截止日有效","资格检查","HIGH","DATE_VALIDITY","certificateExpiry",">=","2025-08-05","",true,true,true,null);
        Plan plan=new Plan("ALL",Collections.singletonList(TARGET),"TYPED_CHECKPOINT_COMPILED",Collections.emptyList());
        Map<String,Object> context=new HashMap<>(SourcePerformanceScoreTest.dates());
        return new EvidenceInvestigation.Host(cp,plan,plan.rules(),c,new LinkedHashMap<>(),context,new SharedFactReuse(c,context,"offline","expiry"),
            new RunSession(ModelGateway.disabled(),context,"expiry"),null,new ArrayList<>(),Collections.emptySet());
    }
    @Test void realFirstSubmissionIsRejectedAndTheVerifiedEndIsIndependentlyPublishedWithinTheSameCall(){
        EvidenceCorpus c=corpus(PAGE);EvidenceInvestigation.Host host=host(c);host.start();
        Source first=page(c,15);host.disclosed.put(first.id(),first);
        host.call("read_section",linked("targetObligationId",TARGET.id(),"sourceId",first.id()));
        Map<String,Object> args=linked("targetObligationId",TARGET.id(),"fact",candidate(first.id()));
        Map<String,Object> result=host.call("submit_candidate_fact",args);
        assertEquals("REJECTED",result.get("status"),result.toString());assertEquals(true,result.get("partialAdoption"));
        assertEquals("CERTIFICATE_DATE_IDENTITY_UNPROVEN",RuleEvaluator.maps(result.get("rejections")).get(0).get("code"));
        assertFalse(host.rejected.isEmpty());
        List<String> ids=ProofVerifier.strings(result.get("acceptedFactIds"));assertEquals(1,ids.size());
        Fact end=host.facts.get(ids.get(0));assertEquals(Collections.singletonList(first.id()),end.sourceIds());
        assertEquals("有效期：至2026年06月04日",end.rawValue());assertEquals("2026-06-04",end.attributes().get("end"));assertFalse(end.attributes().containsKey("start"));
        assertEquals("UNDETERMINED",host.decision.status());assertTrue(host.decision.gaps().stream().anyMatch(g->g.code().equals("CERTIFICATE_SET_COVERAGE_REQUIRED")));
        assertEquals(Collections.emptyList(),host.call("submit_candidate_fact",args).get("newlyAddedFactIds"));
        Source other=page(c,18);host.disclosed.put(other.id(),other);
        assertEquals("COMPLETED",host.call("read_section",linked("targetObligationId",TARGET.id(),"sourceId",other.id())).get("status"));
    }
    @Test void anotherHolderNumberDateFieldOrUndisclosedOriginCannotSupplyAnEnd(){
        for(String text:Arrays.asList(PAGE.replace(OWNER,"其他设计有限公司"),PAGE.replace(NUMBER,"A222222222"),PAGE.replace("有效期：至","使用有效期：至"),
                PAGE.replace("有效期：至2026","有效期：2021年06月16日至2026"),PAGE.replace("有效期：至2026年06月04日","发证日期：2026年06月04日"))){
            EvidenceCorpus c=corpus(text);Source s=page(c,15);
            assertTrue(CertificateExpiryObservation.extract(TARGET,candidate(s.id()),c.byId,Collections.singleton(s.id())).isEmpty(),text);
        }
        EvidenceCorpus c=corpus(PAGE);Source s=page(c,15);
        assertTrue(CertificateExpiryObservation.extract(TARGET,candidate(s.id()),c.byId,Collections.emptySet()).isEmpty());
        Map<String,Object> substituted=candidate(s.id());substituted.put("rawValue","2026-06-04");
        assertTrue(CertificateExpiryObservation.extract(TARGET,substituted,c.byId,Collections.singleton(s.id())).isEmpty());
        substituted=candidate(s.id());substituted.put("sourceIds",Arrays.asList(s.id(),page(c,18).id()));
        assertTrue(CertificateExpiryObservation.extract(TARGET,substituted,c.byId,new HashSet<>(c.byId.keySet())).isEmpty());
    }
}
