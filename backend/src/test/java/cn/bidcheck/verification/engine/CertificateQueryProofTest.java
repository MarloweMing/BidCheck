package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class CertificateQueryProofTest {
    private static final String COMPANY="北京示例建设有限公司",CATEGORY="质量管理体系认证",REQUIREMENT="（1）施工单位具备质量管理体系认证，得1分。",PROOF="证明材料：需提交有效证明文件，并提供查询网址及网址查询截图。";
    private Rule rule(String expected){return new Rule("q","提供对应认证查询证明","MATERIAL",COMPANY,"material","",expected,"",cn.bidcheck.compat.Java8Api.mapOf("certificateProofCategory", CATEGORY, "certificateRequirementQuote", REQUIREMENT),PROOF,"",cn.bidcheck.compat.Java8Api.listOf());}
    private String certificate(String number){return CATEGORY+"证书\n证书编号："+number+"\n兹证明\n"+COMPANY+"\n证书有效日期：2025年09月04日至2028年09月03日\n网址：www.cert.example.cn";}
    private String query(String number,String company,String category){return "查询网址及网址查询截图\nhttps://cert.example.cn/results\n证书详情\n| 证书编号 | `"+number+"` |\n| 认证项目 | "+category+" |\n| 证书状态 | 有效 |\n| 组织名称 | "+company+" |";}
    private Decision evaluate(String first,String second,String expected){EvidenceCorpus c=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(new Document("b","p","BID","投标文件",1,"bh","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"投标文件\n项目名称：北方建设项目二次装修工程","PAGE","TEXT",""), new Page(2,first,"PAGE","TEXT",""), new Page(3,second,"PAGE","TEXT","")),cn.bidcheck.compat.Java8Api.listOf())));return new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),c,cn.bidcheck.compat.Java8Api.mapOf("originalRequirement", REQUIREMENT+"\n"+PROOF)).evaluate(rule(expected));}
    @Test void correctCertificateNumberCompanyAndCategoryLinkBothRequirements(){for(String expected:cn.bidcheck.compat.Java8Api.listOf("查询网址", "查询截图")){Decision d=evaluate(certificate("CERT-A001"),query("CERT-A001",COMPANY,CATEGORY),expected);assertEquals("PASS",d.status(),d.toString());assertEquals("SAME_CERTIFICATE_QUERY_DOCUMENT_SUBMISSION_ONLY",d.proof().get("proofScope"));assertEquals(false,d.proof().get("validityVerified"));assertEquals(1,RuleEvaluator.maps(d.proof().get("certificateBindings")).size());}}
    @Test void sameCompanySafetyLicenseCannotProveQualityCertificateQuery(){for(String expected:cn.bidcheck.compat.Java8Api.listOf("查询网址", "查询截图")){Decision d=evaluate(certificate("CERT-A001"),query("CERT-A001",COMPANY,"安全生产许可证"),expected);assertEquals("UNDETERMINED",d.status());assertEquals("CERTIFICATE_QUERY_IDENTITY_REQUIRED",d.gaps().get(0).code());}}
    @Test void sameCategoryDifferentCertificateCannotBeJoined(){assertEquals("UNDETERMINED",evaluate(certificate("CERT-A001"),query("CERT-A002",COMPANY,CATEGORY),"查询截图").status());}
    @Test void categoryMentionOutsideActualQueryFieldCannotReplaceItsValue(){String wrong=query("CERT-A001",COMPANY,"环境管理体系认证")+"\n质量管理体系认证资料索引";assertEquals("UNDETERMINED",evaluate(certificate("CERT-A001"),wrong,"查询截图").status());}
    @Test void conflictingQueryCategoryFieldsCannotBeJoined(){String wrong=query("CERT-A001",COMPANY,CATEGORY)+"\n认证类别：环境管理体系认证";assertEquals("UNDETERMINED",evaluate(certificate("CERT-A001"),wrong,"查询截图").status());}
    @Test void searchFormPlaceholdersDoNotConfuseThePrintedResultCategory() {
        for (String placeholder: Arrays.asList("[下拉菜单]","【请选择】","（下拉选择）","［请选择］")) {
            String form="查询条件\n| 认证项目 | "+placeholder+" |\n";
            String result=form+query("CERT-A001",COMPANY,"中国"+CATEGORY);
            Decision decision=evaluate(certificate("CERT-A001"),result,"查询截图");
            assertEquals("PASS",decision.status(),placeholder);
            assertEquals(false,decision.proof().get("validityVerified"));
            String conflicting=result+"\n认证类别：职业健康安全管理体系认证";
            assertEquals("UNDETERMINED",evaluate(certificate("CERT-A001"),conflicting,"查询截图").status());
        }
    }
    @Test void placeholderByItselfOrBracketsContainingAnotherCategoryDoNotProveAResult() {
        for(String printed: Arrays.asList("[下拉菜单]","【环境管理体系认证】",
                "[下拉菜单]环境管理体系认证","[环境管理体系认证]")) {
            assertEquals("UNDETERMINED",evaluate(certificate("CERT-A001"),
                query("CERT-A001",COMPANY,printed),"查询截图").status(),printed);
        }
    }
    @Test void anotherCompanyCannotBeJoined(){assertEquals("UNDETERMINED",evaluate(certificate("CERT-A001"),query("CERT-A001","北京另一建设有限公司",CATEGORY),"查询截图").status());}
    @Test void mixedQueryRowsCannotBorrowAnotherRowsIdentity(){String mixed=query("CERT-A001",COMPANY,CATEGORY)+"\n| 证书编号 | CERT-A002 |";assertEquals("UNDETERMINED",evaluate(certificate("CERT-A001"),mixed,"查询截图").status());}
    @Test void certificateWithAWebsiteIsNotAQueryScreenshot(){assertEquals("UNDETERMINED",evaluate(certificate("CERT-A001"),"无查询材料","查询截图").status());}
    @Test void queryWithoutCertificateCannotProveAssociation(){assertEquals("UNDETERMINED",evaluate("证书目录",query("CERT-A001",COMPANY,CATEGORY),"查询网址").status());}
    @Test void placeholderUrlCannotBecomeSubmittedQueryUrl(){assertEquals("UNDETERMINED",evaluate(certificate("CERT-A001"),query("CERT-A001",COMPANY,CATEGORY).replace("cert.example.cn/results","example.com/placeholder"),"查询网址").status());}
    private Decision chain(String dateNumber){
        String valid=certificate(dateNumber).replace("证书有效日期：2025年09月04日至2028年09月03日","有效期：2024年01月01日至2028年01月01日");
        List<Page> pages=new ArrayList<>(cn.bidcheck.compat.Java8Api.listOf(new Page(1,"投标文件\n项目名称：北方建设项目二次装修工程","PAGE","TEXT",""), new Page(2,dateNumber.equals("CERT-A001")?valid:certificate("CERT-A001"),"PAGE","TEXT",""), new Page(3,query("CERT-A001",COMPANY,CATEGORY),"PAGE","TEXT","")));
        if(!dateNumber.equals("CERT-A001"))pages.add(new Page(4,valid,"PAGE","TEXT",""));
        EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(new Document("b","p","BID","投标文件",1,"bh","",pages,cn.bidcheck.compat.Java8Api.listOf())));
        Source dateSource=corpus.all.stream().filter(s->s.evidence().page()==(dateNumber.equals("CERT-A001")?2:4)).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        Fact fact=new Fact("dates","DATE_RANGE",COMPANY,"有效期","2024年01月01日至2028年01月01日","2024年01月01日至2028年01月01日","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(dateSource.id()),cn.bidcheck.compat.Java8Api.mapOf("category", CATEGORY, "number", dateNumber, "numberField", "证书编号", "field", "有效期", "start", "2024-01-01", "end", "2028-01-01"));
        Rule original=rule("同证书查询及有效期");Map<String,Object> parameters=new LinkedHashMap<>(original.parameters());parameters.put("certificateProofRequireValidity",true);
        Rule joined=new Rule(original.id(),original.description(),original.predicate(),original.subject(),original.field(),original.operator(),original.expected(),original.unit(),parameters,original.sourceQuote(),original.logic(),original.children());
        Map<String,Object> context=linked("originalRequirement",REQUIREMENT+"\n"+PROOF,"referenceDate","2025-08-05","referenceDateSourceVerified",true,"referenceDateEvidence",cn.bidcheck.compat.Java8Api.mapOf("documentId", "t", "quote", "投标截止日期：2025年8月5日", "confirmation", "SOURCE_UNAMBIGUOUS", "confirmed", true));
        return new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(fact),corpus,context).evaluate(joined);
    }
    @Test void sameCategoryOtherCertificateValidityCannotOverrideOriginalCertificateFutureStart(){Decision result=chain("CERT-B002");assertEquals("FAIL",result.status(),result.toString());assertEquals("SAME_CERTIFICATE_QUERY_AND_VALIDITY_CHAIN",result.proof().get("proofScope"));}
    @Test void sameCertificateQueryAndValidPeriodCanCompleteChain(){Decision result=chain("CERT-A001");assertEquals("PASS",result.status(),result.toString());assertEquals("SAME_CERTIFICATE_QUERY_AND_VALIDITY_CHAIN",result.proof().get("proofScope"));}
}
