package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class ApplicableSystemCertificatesTest {
    private final Rule rule=new Rule("valid","证书在投标截止日有效","VALIDITY","","certificateExpiry",">=","2025-08-05","",
        linked("allApplicableCertificates",true,"validityField","有效期","referenceEvent","BID_DEADLINE"),"投标截止时间为2025年8月5日。","",Collections.emptyList());
    private final String party="北方建设有限公司";
    private String original(String type,String holder,String number,String range){
        return type+"认证证书\n证书编号："+number+"\n兹证明\n\n# "+holder+"\n证书有效日期："+range;
    }
    @Test void oneNotYetEffectiveOriginalRemainsARiskEvenWithAnEffectiveOriginalAndPartialInventory(){
        Document bid=new Document("b","p","BID","投标文件",1,"frozen","",Arrays.asList(
            new Page(1,"投标文件\n项目名称：北方综合楼装修工程\n投标人："+party,"PAGE","TEXT",""),
            new Page(9,original("质量管理体系",party,"Q10001","2025年09月04日至2028年09月03日"),"PAGE","TEXT",""),
            new Page(10,original("环境管理体系",party,"E10002","2023年09月04日至2026年09月03日"),"PAGE","TEXT",""),
            new Page(11,original("质量管理体系","他方建设有限公司","Q10003","2025年10月04日至2028年10月03日"),"PAGE","TEXT",""),
            new Page(12,"历史项目证明\n"+original("质量管理体系",party,"Q10004","2025年09月04日至2028年09月03日"),"PAGE","TEXT",""),
            new Page(13,"填写示例\n"+original("质量管理体系",party,"Q10005","2025年09月04日至2028年09月03日"),"PAGE","TEXT","")),Collections.emptyList());
        EvidenceCorpus corpus=new EvidenceCorpus(Collections.singletonList(bid));
        List<Fact> found=NativeCertificateFacts.currentSystemCertificates(Collections.singletonList(rule),corpus);
        assertEquals(2,found.size());Decision d=new RuleEvaluator(found,corpus,SourcePerformanceScoreTest.dates()).evaluate(rule);
        assertEquals("FAIL",d.status(),d.map().toString());assertEquals(false,d.proof().get("certificateSetComplete"));
        assertEquals(1,d.evidences().size());assertEquals(9,d.evidences().get(0).page());
    }
    @Test void frozenHainanPdfRecoversTheIndependentQualityAndEnvironmentalCounterexamples()throws Exception{
        Path root=Paths.get("").toAbsolutePath();if(!Files.isDirectory(root.resolve("backend")))root=root.getParent();
        Path file=root.resolve("artifacts/failure-driven-full109-retest-20261026/run-final.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(file));ObjectMapper json=new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        List<Document> docs=new ArrayList<>();for(JsonNode node:json.readTree(file.toFile()).path("documents"))if(node.path("role").asText().equals("BID"))docs.add(json.treeToValue(node,Document.class));
        EvidenceCorpus corpus=new EvidenceCorpus(docs);List<Fact> found=NativeCertificateFacts.currentSystemCertificates(Collections.singletonList(rule),corpus);
        Decision d=new RuleEvaluator(found,corpus,SourcePerformanceScoreTest.dates()).evaluate(rule);
        assertEquals("FAIL",d.status(),d.map().toString());Set<Integer> pages=new HashSet<>();d.evidences().forEach(e->pages.add(e.page()));
        assertTrue(pages.containsAll(Arrays.asList(449,455)),pages.toString());assertEquals(false,d.proof().get("certificateSetComplete"));
    }
}
