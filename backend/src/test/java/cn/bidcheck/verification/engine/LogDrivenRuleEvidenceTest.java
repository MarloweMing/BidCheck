package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class LogDrivenRuleEvidenceTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String COMPANY="北方建设有限公司";
    private EvidenceCorpus corpus(String... texts){List<Page> pages=new ArrayList<>();for(int i=0;i<texts.length;i++)pages.add(new Page(i+1,texts[i],"PAGE","TEXT",""));return new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(new Document("bid","project","BID","投标文件",1,"hash","",pages,cn.bidcheck.compat.Java8Api.listOf())));}
    private Map<String,Object> reference(){return linked("referenceDate","2025-08-05","referenceDateSourceVerified",true,"referenceDateEvidence",cn.bidcheck.compat.Java8Api.mapOf("documentId", "t", "quote", "投标截止日期：2025年8月5日", "confirmation", "SOURCE_UNAMBIGUOUS", "confirmed", true));}
    private Rule validity(String category){return new Rule("v","证书有效期","VALIDITY",COMPANY,"有效期","","","",cn.bidcheck.compat.Java8Api.mapOf("category", category, "validityField", "有效期"),category+"在投标截止日期有效","",cn.bidcheck.compat.Java8Api.listOf());}
    private String certificate(String issuance){return "资质证书\n企业名称："+COMPANY+"\n资质等级：建筑装饰工程设计专项甲级。\n证书编号：A12345678\n有效期：至2026年06月04日\n"+issuance;}
    private Decision validityResult(String text,String... other){List<String> pages=new ArrayList<>();pages.add(text);pages.addAll(cn.bidcheck.compat.Java8Api.listOf(other));EvidenceCorpus c=corpus(pages.toArray(new String[0]));Rule r=validity("建筑装饰工程设计专项");List<Fact> facts=c.nativeFacts(cn.bidcheck.compat.Java8Api.listOf(r),c.all);assertFalse(facts.isEmpty(),"fixture must expose the original end-only fact");return new RuleEvaluator(facts,c,reference()).validity(r,facts);}

    @Test void confirmedBidValidityNumberSurvivesFocusedCompileAndBoundaryValues()throws Exception {
        String source="3.4.1\t投标有效期\t投标有效期：180天";
        Checkpoint cp=new Checkpoint("validity","投标有效期要求","响应检查","HIGH","VALUE_COMPARE","bidValidity",">=","180","DAY",true,true,true,new Evidence("t","招标文件",164,"BLOCK",source,"p164-l1",1));
        cn.bidcheck.verification.engine.DeterministicRequirementCompiler.Result compiled=new DeterministicRequirementCompiler(JSON).compile(cp,source,null,cn.bidcheck.compat.Java8Api.mapOf());assertTrue(compiled.complete(),compiled.audit().toString());
        cn.bidcheck.verification.engine.VerificationTypes.Plan plan=new RequirementCompiler().compileResponse(cp,compiled.root(),source,true,cn.bidcheck.compat.Java8Api.setOf("DOCUMENT_COMPLIANCE", "PERSONNEL_CONSORTIUM", "CERTIFICATE_VALIDITY", "PRICE_BASIS"));
        assertTrue(plan.issues().isEmpty(),plan.issues().toString());Rule rule=RequirementCompiler.leaves(plan.rules()).get(0);
        assertEquals("NUMBER",rule.predicate());assertEquals("bidValidity",rule.field());assertEquals(">=",rule.operator());assertEquals("180",rule.expected());assertEquals("DAY",rule.unit());
        com.fasterxml.jackson.databind.node.ObjectNode forged=compiled.root().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)forged.path("rules").get(0)).put("operator","<=");
        cn.bidcheck.verification.engine.VerificationTypes.Plan rejected=new RequirementCompiler().compileResponse(cp,forged,source,true,cn.bidcheck.compat.Java8Api.setOf());assertFalse(rejected.issues().isEmpty(),"copied provenance must not authorize another comparator");
        Checkpoint unconfirmed=new Checkpoint(cp.id(),cp.title(),cp.category(),cp.severity(),cp.type(),cp.field(),cp.operator(),cp.expected(),cp.unit(),true,false,true,cp.source());
        assertFalse(new RequirementCompiler().compileResponse(unconfirmed,compiled.root(),source,true,cn.bidcheck.compat.Java8Api.setOf()).issues().isEmpty(),"model metadata must not authorize an unconfirmed checkpoint");
        for(int days:cn.bidcheck.compat.Java8Api.listOf(179, 180, 181)){EvidenceCorpus c=corpus("投标有效期："+days+"天");Fact fact=new Fact("days","NUMBER","","bidValidity",""+days,""+days,"天","投标有效期","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(c.all.get(0).id()),cn.bidcheck.compat.Java8Api.mapOf());
            assertEquals(days<180?"FAIL":"PASS",new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(fact),c,cn.bidcheck.compat.Java8Api.mapOf()).number(rule,cn.bidcheck.compat.Java8Api.listOf(fact)).status());}
        cn.bidcheck.verification.engine.DeterministicRequirementCompiler.Result wrong=new DeterministicRequirementCompiler(JSON).compile(cp,"施工工期：180天",null,cn.bidcheck.compat.Java8Api.mapOf());assertTrue(wrong.rules().stream().noneMatch(r->r.field().equals("bidValidity")));
        cn.bidcheck.verification.engine.DeterministicRequirementCompiler.Result opposite=new DeterministicRequirementCompiler(JSON).compile(cp,"投标有效期不超过180天",null,cn.bidcheck.compat.Java8Api.mapOf());assertTrue(opposite.rules().stream().noneMatch(r->r.field().equals("bidValidity")&&r.operator().equals(">=")));
    }
    @Test void endOnlyCertificateUsesIssuanceWithoutFabricatingValidityStart(){Decision decision=validityResult(certificate("发证机关：\n2021年06月16日"));assertEquals("PASS",decision.status(),decision.toString());assertTrue(decision.proof().toString().contains("EXPIRY_DATE"));assertTrue(decision.proof().toString().contains("issueDateIsNotValidityStart=true"));}
    @Test void endOnlyCertificateIssuedAfterTenderCannotBeRetroactivelyValid(){assertEquals("FAIL",validityResult(certificate("发证日期：2025年08月06日")).status());}
    @Test void expiryOnlyDoesNotBorrowTheNeighbourCertificateDate(){EvidenceCorpus c=corpus(certificate(""),certificate("发证日期：2025年08月06日").replace("A12345678","A87654321"));Rule base=validity("建筑装饰工程设计专项");Map<String,Object> p=new LinkedHashMap<>(base.parameters());p.put("number","A12345678");Rule r=new Rule(base.id(),base.description(),base.predicate(),base.subject(),base.field(),base.operator(),base.expected(),base.unit(),p,base.sourceQuote(),base.logic(),base.children());List<Fact> facts=c.nativeFacts(cn.bidcheck.compat.Java8Api.listOf(r),c.all);assertTrue(facts.stream().anyMatch(f->"A12345678".equals(f.attributes().get("number"))));Decision decision=new RuleEvaluator(facts,c,reference()).validity(r,facts);assertEquals("PASS",decision.status(),decision.toString());assertTrue(decision.proof().toString().contains("EXPIRY_AT_REFERENCE_DATE_ONLY"));assertFalse(decision.proof().toString().contains("issuedOn=2025-08-06"));}
    @Test void expiryOnlyDoesNotIntroduceAnIssuanceRequirementButConflictsRemainUnresolved(){for(String issuance:cn.bidcheck.compat.Java8Api.listOf("", "说明：发证日期：2021年06月16日（推断）")){assertEquals("PASS",validityResult(certificate(issuance)).status(),issuance);}assertEquals("UNDETERMINED",validityResult(certificate("发证日期：2021年06月16日\n签发日期：2023年03月01日")).status());}
    @Test void anotherDocumentsEmbeddedIssuanceDateIsIgnored(){Decision decision=validityResult(certificate("单位经办人护照签发日期：2025年08月06日"));assertEquals("PASS",decision.status(),decision.toString());assertFalse(decision.proof().toString().contains("issuedOn=2025-08-06"));}
    @Test void usePeriodDoesNotBecomeOrExtendCertificateExpiry(){String text=certificate("发证日期：2021年06月16日\n使用期限：2025年08月06日至2025年11月06日");assertEquals("FAIL",validityResult(text).status());}
    @Test void explicitLongTermWithoutIssuanceDoesNotInventMissingStartDate(){assertEquals("PASS",validityResult(certificate("发证日期：2021年06月16日").replace("至2026年06月04日","长期")).status());assertEquals("PASS",validityResult(certificate("").replace("至2026年06月04日","长期")).status());assertEquals("FAIL",validityResult(certificate("发证日期：2025年08月06日").replace("至2026年06月04日","长期")).status());}
    @Test void explicitIssuanceObligationStillRequiresSameCertificateOriginal(){EvidenceCorpus c=corpus(certificate(""));Rule r=validity("建筑装饰工程设计专项");Fact fact=c.nativeFacts(cn.bidcheck.compat.Java8Api.listOf(r),c.all).stream().filter(f->f.kind().equals("CERTIFICATE")&&"A12345678".equals(f.attributes().get("number"))&&"有效期".equals(f.attributes().get("validityField"))).findFirst().orElseThrow(java.util.NoSuchElementException::new);assertEquals("2026-06-04",fact.attributes().get("end"));cn.bidcheck.verification.engine.CertificateValidityDates.Result result=CertificateValidityDates.fact(fact,c,"有效期",java.time.LocalDate.of(2025,8,5),true);assertEquals("CERTIFICATE_ISSUANCE_REQUIRED",result.code());assertFalse(result.complete());}
    @Test void categorySpecificDateCannotBecomeWholeCertificateExtension(){assertEquals("UNDETERMINED",validityResult(certificate("发证日期：2021年06月16日\n备注：上述标注红色资质有效期至2025年07月05日")).status());}

    private String license(String number){return "# 安全生产许可证\n编号："+number+"\n单位名称："+COMPANY+"\n许可范围：建筑施工\n有效期：2022年11月01日至2025年10月31日";}
    private String query(String company,String number){return "查询网址及网址查询截图\nhttps://gov.example.cn/licenses\n安全生产许可证信息公示\n查询条件\n| 企业名称 | "+COMPANY+" |\n"+cn.bidcheck.compat.Java8Api.repeat("提示文字\n", 400)+"查询结果表格\n| 序号 | 证号 | 企业名称 | 证书状态 |\n|---|---|---|---|\n| 1 | "+number+" | "+company+" | 有效 |";}
    private Decision licenseResult(String query){String requirement="施工单位须具有有效的安全生产许可证，并提供查询网址及网址查询截图。";EvidenceCorpus c=corpus(license("（京）JZ安许证字〔2022〕123456"),query);assertTrue(c.all.size()>2);
        Rule rule=new Rule("q","安全生产许可证查询证明","MATERIAL",COMPANY,"material","","查询截图","",cn.bidcheck.compat.Java8Api.mapOf("certificateProofCategory", "安全生产许可证", "certificateRequirementQuote", requirement, "certificateProofRequireValidity", true),requirement,"",cn.bidcheck.compat.Java8Api.listOf());Map<String,Object> context=new LinkedHashMap<>(reference());context.put("originalRequirement",requirement);return new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),c,context).evaluate(rule);}
    @Test void safetyLicenseQueryRowLinksAcrossSourceChunksAndBracketFormatting(){Decision decision=licenseResult(query(COMPANY,"(京)JZ安许证字〔2022〕123456"));assertEquals("PASS",decision.status(),decision.toString());}
    @Test void licenseCannotBorrowQueryInputCompanyOrChangeNumberDigits(){for(String query:cn.bidcheck.compat.Java8Api.listOf(query("南方建设有限公司","(京)JZ安许证字〔2022〕123456"), query(COMPANY,"(京)JZ安许证字〔2022〕123457"))){assertNotEquals("PASS",licenseResult(query).status());}}
}
