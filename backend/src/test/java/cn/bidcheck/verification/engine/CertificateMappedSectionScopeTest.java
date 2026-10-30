package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class CertificateMappedSectionScopeTest {
    private static final String PROJECT="2025年北方综合楼装修工程",PARENT="5.10 其他证明文件",CHILD="（1）施工单位具备质量管理体系认证",NEXT="（2）施工单位具备职业健康安全管理体系认证";
    private Page page(int n,String text){return new Page(n,text,"PAGE","PDF_OCR","");}
    private List<Page> pages(){return new ArrayList<>(List.of(
        page(1,"（"+PROJECT+"）\n（招标项目编号：HN-2025-001）\n投标文件（商务部分）\n投标人名称：（盖章）甲建设有限公司\n1"),
        page(2,"目录\n5.9 历史合同 ........ 7\n"+PARENT+" ........ 8\n"+CHILD+" ........ 8\n"+NEXT+" ........ 10\n2"),
        page(7,"合同协议书\n项目名称：南方旧楼历史装修工程\n7"),
        page(8,CHILD+"\n8\n【PDF识别文字】\n"+PARENT+"\n"+CHILD+"\n质量管理体系认证证书\n证书编号：Q1001\n兹证明\n甲建设有限公司\n证书有效日期：2025年01月01日至2027年12月31日"),
        page(9,"查询网址及网址查询截图\n9\n【PDF识别文字】\n证书编号：Q1001\n组织名称：甲建设有限公司"),
        page(10,NEXT+"\n10\n【PDF识别文字】\n职业健康安全管理体系认证证书")
    ));}
    private EvidenceCorpus corpus(List<Page> pages){return new EvidenceCorpus(List.of(new Document("bid","project","BID","bid.pdf",1,"hash","",pages,List.of())));}
    private Source certificate(EvidenceCorpus corpus){return corpus.all.stream().filter(s->s.evidence().page()==8).findFirst().orElseThrow();}
    private void replace(List<Page> pages,int number,String old,String replacement){for(int i=0;i<pages.size();i++)if(pages.get(i).number()==number)pages.set(i,page(number,pages.get(i).text().replace(old,replacement)));}
    @Test void recognizedParentIsOnlyLocalCertificateScopeAndNeverGlobalPersonScope(){
        EvidenceCorpus c=corpus(pages());Source s=certificate(c);assertTrue(s.scopeContext().blocked());
        assertEquals(false,DocumentScopeProof.currentEvidence(c.documents.get(0),s.evidence()).get("verifiedCurrent"));
        Map<String,Object> proof=CertificateEvidenceRecords.mappedScope(s,c,new HashMap<>());assertEquals(true,proof.get("verifiedCurrent"));
        assertEquals("SERVER_MAPPED_CERTIFICATE_SECTION_SCOPE",((Map<?,?>)proof.get("sourceScope")).get("derivedBy"));
        assertTrue(proof.toString().contains("MAPPED_PARENT_RECOGNIZED_TITLE_WITH_NATIVE_CHILD"));assertTrue(s.scopeContext().blocked());
        for(var anchor:RuleEvaluator.maps(((Map<?,?>)proof.get("sourceScope")).get("anchors")))if("MAPPED_PARENT_RECOGNIZED_TITLE_WITH_NATIVE_CHILD".equals(anchor.get("kind")))assertTrue(s.evidence().quote().contains(str(anchor,"quote")),"recognized parent proof must cite a verbatim source substring");
    }
    @Test void wrongPageWrongParentOrRepeatedParentCannotProduceCertificateScope(){
        for(String mode:List.of("wrong-page","wrong-parent","duplicate-navigation","duplicate-recognized-title")){
            List<Page> pages=pages();switch(mode){
                case "wrong-page"->replace(pages,2,PARENT+" ........ 8",PARENT+" ........ 7");
                case "wrong-parent"->replace(pages,8,PARENT,"5.11 其他证明文件");
                case "duplicate-navigation"->replace(pages,2,PARENT+" ........ 8",PARENT+" ........ 8\n"+PARENT+" ........ 8");
                case "duplicate-recognized-title"->replace(pages,8,"质量管理体系认证证书",PARENT+"\n质量管理体系认证证书");
            }EvidenceCorpus c=corpus(pages);assertFalse(CertificateEvidenceRecords.usable(certificate(c),c,new HashMap<>()),mode);
        }
    }
    @Test void historicalOrForeignOcrAndBrokenBoundariesStillVetoLocalRecovery(){
        for(String mode:List.of("historical","example","foreign","missing-footer","broken-sibling","missing-bidder","missing-project-number","wrong-native-child")){
            List<Page> pages=pages();switch(mode){
                case "historical"->replace(pages,8,"证书编号：Q1001","历史项目证明\n证书编号：Q1001");
                case "example"->replace(pages,8,"证书编号：Q1001","填写示例\n证书编号：Q1001");
                case "foreign"->replace(pages,9,"组织名称：甲建设有限公司","组织名称：甲建设有限公司\n项目名称：南方旧楼历史装修工程");
                case "missing-footer"->replace(pages,9,"\n9\n","\n99\n");
                case "broken-sibling"->replace(pages,10,NEXT,"（3）其他说明");
                case "missing-bidder"->replace(pages,1,"投标人名称：（盖章）甲建设有限公司","");
                case "missing-project-number"->replace(pages,1,"（招标项目编号：HN-2025-001）","");
                case "wrong-native-child"->replace(pages,8,CHILD+"\n8\n",NEXT+"\n8\n");
            }EvidenceCorpus c=corpus(pages);assertFalse(CertificateEvidenceRecords.usable(certificate(c),c,new HashMap<>()),mode);
        }
    }
}
