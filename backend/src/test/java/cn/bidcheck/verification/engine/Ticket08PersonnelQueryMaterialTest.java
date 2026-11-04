package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Checkpoint;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class Ticket08PersonnelQueryMaterialTest {
    private static final String PERSON="李明", ROLE="设计负责人", COMPANY="北方设计有限公司";
    private static final String REQUIREMENT="设计负责人资格要求：具有一级注册建筑师执业资格证书。须提交有效证明文件（并提供查询网址及网址查询截图，查询截图信息应与证明文件信息一致）。";
    private static final String CERT="一级注册建筑师注册证书\n姓名：李明\n证件号码：110101198001010010\n性别：男\n注册编号：AR1001\n聘用单位：北方设计有限公司\n注册有效期：2024年01月01日-2027年01月01日";
    private static final String QUERY="证书查询网址及网址查询截图\nhttps://register.example.gov.cn/result\n一级注册建筑师\n姓名：李明\n性别：男\n注册单位：北方设计有限公司\n电子证书编号：AR1001\n注册编号/执业印章号：9001-001\n注册专业：不分专业\n有效期：2027年01月01日";
    private static final String SAFETY_REQUIREMENT="专职安全员：具有住建主管部门颁发的有效的安全生产考核合格证（C类）或施工企业专职安全生产管理人员安全生产考核合格证书（C3类)。须提交有效证明文件（并提供查询网址及网址查询截图，查询截图信息应与证明文件信息一致）。";
    private static final String SAFETY_CERT="建筑施工企业专职安全生产管理人员安全生产考核合格证书\n编号：京建安C2（2020）0185204\n姓名：李明\n证件号码：110101198001010010\n企业名称：北方设计有限公司\n有效期：2024年01月01日至2027年12月31日";
    private static final String SAFETY_QUERY="证书查询网址及网址查询截图\nhttps://safety.example.gov.cn/query\n|姓名|证书类别|证书编号|企业名称|有效期结束日期|\n|李明|专职安全生产管理人员|京建安C2（2020）0185204|北方设计有限公司|2027-12-31|";

    private Rule material(String expected) {
        return new Rule("common-"+expected,"提供对应人员证书查询"+expected,"MATERIAL",ROLE,"material","",expected,"",
            linked("deterministicFamily","CERTIFICATE_PROOF_DOCUMENTS","certificateRequirementQuote",REQUIREMENT),REQUIREMENT.substring(REQUIREMENT.indexOf("须提交")),"",Collections.emptyList());
    }
    private Rule material(String expected,String requirement,String role) {
        String quote=requirement.substring(requirement.indexOf("须提交"));
        return new Rule("common-"+expected,"提供对应人员证书查询"+expected,"MATERIAL",role,"material","",expected,"",
            linked("deterministicFamily","CERTIFICATE_PROOF_DOCUMENTS","certificateRequirementQuote",requirement),quote,"",Collections.emptyList());
    }
    private Decision evaluate(String query, String expected, String certificate) {
        return evaluator(query,certificate,true,REQUIREMENT).evaluate(material(expected));
    }
    private RuleEvaluator evaluator(String query,String certificate,boolean identity,String requirement) {
        String appointment="投标文件\n项目名称：北方装修工程\n本项目拟派李明担任设计负责人。\n姓名：李明"+(identity?"\n身份证号：110101198001010010":"");
        EvidenceCorpus corpus=new EvidenceCorpus(Collections.singletonList(new Document("b","p","BID","投标文件",1,"hash","",
            Arrays.asList(new Page(1,appointment,"PAGE","TEXT",null),
                new Page(2,certificate,"PAGE","TEXT",null),new Page(3,query,"PAGE","TEXT",null)),Collections.emptyList())));
        VerificationTypes.Fact role=new VerificationTypes.Fact("role","PERSON_ROLE",ROLE,"姓名",PERSON,PERSON,"","","CURRENT_PROJECT",Collections.singletonList(corpus.all.get(0).id()),Collections.emptyMap());
        return new RuleEvaluator(Collections.singletonList(role),corpus,linked("originalRequirement",requirement));
    }
    private RuleEvaluator safetyEvaluator(String certificate,String query) {
        String appointment="投标文件\n本项目拟派李明担任专职安全员。\n姓名：李明\n身份证号：110101198001010010";
        EvidenceCorpus corpus=new EvidenceCorpus(Collections.singletonList(new Document("safety-bid","p","BID","投标文件",1,"safety-hash","",
            Arrays.asList(new Page(1,appointment,"PAGE","TEXT",null),new Page(2,certificate,"PAGE","TEXT",null),new Page(3,query,"PAGE","TEXT",null)),Collections.emptyList())));
        Fact role=new Fact("safety-role","PERSON_ROLE","专职安全员","姓名","李明","李明","","","CURRENT_PROJECT",Collections.singletonList(corpus.all.get(0).id()),Collections.emptyMap());
        return new RuleEvaluator(Collections.singletonList(role),corpus,linked("originalRequirement",SAFETY_REQUIREMENT));
    }

    @Test void c2CertificateQuerySubmissionCanBeProvenWithoutPassingTheCOrC3Requirement() {
        RuleEvaluator evaluator=safetyEvaluator(SAFETY_CERT,SAFETY_QUERY);
        Decision url=evaluator.evaluate(material("查询网址",SAFETY_REQUIREMENT,"专职安全员"));
        Decision screenshot=evaluator.evaluate(material("查询截图",SAFETY_REQUIREMENT,"专职安全员"));
        assertEquals("PASS",url.status(),url.toString());
        assertEquals("PASS",screenshot.status(),screenshot.toString());
        Map<String,Object> leafProof=materialLeafProof(url);
        assertEquals("PERSON_SAME_CERTIFICATE_QUERY_SUBMISSION_ONLY",leafProof.get("proofScope"),url.proof().toString());
        assertEquals(Boolean.FALSE,leafProof.get("qualificationVerified"));
        Rule commonMatch=new Rule("common-match","查询截图信息与证明文件信息一致","SAME_FIELD","专职安全员","证书信息","","","",
            linked("certificateRequirementQuote",SAFETY_REQUIREMENT),
            "提供有效证明文件并提供查询网址及网址查询截图，查询截图信息应与证明文件信息一致。","",Collections.emptyList());
        Decision common=evaluator.evaluate(commonMatch);
        assertEquals("PASS",common.status(),common.toString());
        Rule qualification=new Rule("safety-qualification",SAFETY_REQUIREMENT,"CERTIFICATE","专职安全员","certificate","","","",
            linked("personnelCredentialKind","SAFETY","category","安全生产考核合格证","allowedSafetyClasses",Arrays.asList("C","C3")),SAFETY_REQUIREMENT,"",Collections.emptyList());
        Decision safety=PersonnelCredentialProof.evaluate(qualification,evaluator);
        assertEquals("FAIL",safety.status(),safety.toString());
        assertTrue(safety.reason().contains("C2"));
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> materialLeafProof(Decision decision) {
        Object children=decision.proof().get("children");
        assertTrue(children instanceof List,decision.proof().toString());
        Map<String,Object> child=(Map<String,Object>)((List<?>)children).get(0);
        return (Map<String,Object>)child.get("proof");
    }

    @Test void c2SubmissionComparisonRejectsMissingWrongOrAmbiguousEvidence() {
        Rule url=material("查询网址",SAFETY_REQUIREMENT,"专职安全员");
        assertNotEquals("PASS",safetyEvaluator("无对应证书原件",SAFETY_QUERY).evaluate(url).status());
        assertNotEquals("PASS",safetyEvaluator(SAFETY_CERT,SAFETY_QUERY.replace("李明","王五")).evaluate(url).status());
        assertNotEquals("PASS",safetyEvaluator(SAFETY_CERT.replace("110101198001010010","110101198001010029"),SAFETY_QUERY).evaluate(url).status());
        assertNotEquals("PASS",safetyEvaluator(SAFETY_CERT,SAFETY_QUERY.replace("京建安C2（2020）0185204","京建安C3（2020）0185204")).evaluate(url).status());
        assertNotEquals("PASS",safetyEvaluator(SAFETY_CERT,SAFETY_QUERY.replace("https://safety.example.gov.cn/query","" )).evaluate(url).status());
        assertNotEquals("PASS",safetyEvaluator(SAFETY_CERT,SAFETY_QUERY.replace("https://safety.example.gov.cn/query","https://safety.example.gov.cn/query\nhttps://other.example.gov.cn/query")).evaluate(url).status());
        String secondCertificate=SAFETY_CERT.replace("0185204","0185205");
        assertNotEquals("PASS",safetyEvaluator(SAFETY_CERT+"\n"+secondCertificate,SAFETY_QUERY).evaluate(url).status());
    }

    @Test void exactCurrentPersonAndSameCertificateQueryCanProveUrlAndScreenshotSubmission() {
        Decision url=evaluate(QUERY,"查询网址",CERT),screenshot=evaluate(QUERY,"查询截图",CERT);
        assertEquals("PASS",url.status(),url.toString());
        assertEquals("PASS",screenshot.status(),screenshot.toString());
    }
    @Test void wrongPersonDifferentNumberAndMissingQuerySourceDoNotPass() {
        assertNotEquals("PASS",evaluate(QUERY.replace("李明","王五"),"查询截图",CERT).status());
        assertNotEquals("PASS",evaluate(QUERY.replace("AR1001","AR1002"),"查询截图",CERT).status());
        assertNotEquals("PASS",evaluate("无查询材料","查询截图",CERT).status());
        assertNotEquals("PASS",evaluate(QUERY,"查询截图","无对应证书原件").status());
        String secondRecord="\n|姓名|证书类别|证书编号|注册单位|\n|李明|一级注册建筑师|AR1002|北方设计有限公司|";
        assertNotEquals("PASS",evaluate(QUERY+secondRecord,"查询截图",CERT).status());
        String unrelatedScreenshot="\n查询网址及网址查询截图\nhttps://other.example.gov.cn/result\n# 其他查询主体";
        assertNotEquals("PASS",evaluate(QUERY+unrelatedScreenshot,"查询截图",CERT).status());
        String otherRecordOnlyUrl="证书查询网址及网址查询截图\nhttps://other.example.gov.cn/result\n一级注册建筑师\n姓名：王五\n注册单位：其他公司\n电子证书编号：WX2002";
        assertNotEquals("PASS",evaluate(QUERY.replace("https://register.example.gov.cn/result","" )+otherRecordOnlyUrl,"查询网址",CERT).status());
        assertNotEquals("PASS",evaluate(QUERY.replace("https://register.example.gov.cn/result","" )+otherRecordOnlyUrl,"查询截图",CERT).status());
        String trailingOtherLink=QUERY.replace("https://register.example.gov.cn/result","")+"\n其他人员王五的链接：https://other.example.gov.cn/result";
        assertNotEquals("PASS",evaluate(trailingOtherLink,"查询网址",CERT).status());
        assertNotEquals("PASS",evaluate(trailingOtherLink,"查询截图",CERT).status());
        String leadingOtherLink=QUERY.replace("https://register.example.gov.cn/result","其他人员王五的链接：https://other.example.gov.cn/result");
        assertNotEquals("PASS",evaluate(leadingOtherLink,"查询网址",CERT).status());
        assertNotEquals("PASS",evaluate(leadingOtherLink,"查询截图",CERT).status());
    }
    @Test void urlAndScreenshotClaimsRequireTheirOwnSourceMarkers() {
        assertNotEquals("PASS",evaluate(QUERY.replace("https://register.example.gov.cn/result",""),"查询网址",CERT).status());
        assertNotEquals("PASS",evaluate(QUERY.replace("证书查询网址及网址查询截图","证书详情"),"查询截图",CERT).status());
    }

    @Test void twoIndependentCredentialScreenshotsOnOnePageBindOnlyTheMatchedRecord() {
        String other="\n\n职称查询网址及查询截图\nhttps://other.example.gov.cn/title\n姓名：王五\n证书名称：建筑工程专业技术资格\n证书编号：WX2002\n职称：工程师";
        List<CertificateSourceComparison.QueryFragment> fragments=CertificateSourceComparison.queryMaterialFragments(QUERY+other);
        assertEquals(2,fragments.size());
        assertTrue(CertificateSourceComparison.targetQueryFragment(fragments.get(0).text,PERSON,"AR1001"));
        assertFalse(CertificateSourceComparison.targetQueryFragment(fragments.get(1).text,PERSON,"AR1001"));
        assertTrue(CertificateSourceComparison.targetQueryFragment(fragments.get(1).text,"王五","WX2002"));
        assertFalse(CertificateSourceComparison.targetQueryFragment(fragments.get(0).text,PERSON,"WRONG"));
        assertFalse(CertificateSourceComparison.targetQueryFragment(fragments.get(0).text.replace("https://register.example.gov.cn/result",""),PERSON,"AR1001"));
    }

    @Test void matchedPersonAndCertificateNumberMustBelongToTheSameQueryFragment() {
        String split="证书查询网址及网址查询截图\nhttps://register.example.gov.cn/first\n一级注册建筑师\n姓名：李明\n\n证书查询网址及网址查询截图\nhttps://register.example.gov.cn/second\n一级注册建筑师\n电子证书编号：AR1001\n注册编号/执业印章号：9001-001\n注册专业：不分专业\n有效期：2027年01月01日";
        assertNotEquals("PASS",evaluate(split,"查询网址",CERT).status());
        assertNotEquals("PASS",evaluate(split,"查询截图",CERT).status());
        List<CertificateSourceComparison.QueryFragment> fragments=CertificateSourceComparison.queryMaterialFragments(split);
        assertEquals(2,fragments.size());
        assertFalse(CertificateSourceComparison.targetQueryFragment(fragments.get(0).text,PERSON,"AR1001"));
        assertFalse(CertificateSourceComparison.targetQueryFragment(fragments.get(1).text,PERSON,"AR1001"));
    }

    @Test void inlineWebsiteHostDoesNotCountAsAnotherQueryUrl() {
        String inlineHost=QUERY+"\n页眉：中华人民共和国住房和城乡建设部www.mohurd.gov.cn 建设工程企业";
        assertTrue(CertificateSourceComparison.targetQueryFragment(inlineHost,PERSON,"AR1001"));
        assertFalse(CertificateSourceComparison.targetQueryFragment(QUERY.replace("https://register.example.gov.cn/result","其他人员王五链接：https://other.example.gov.cn/result"),PERSON,"AR1001"));
        assertFalse(CertificateSourceComparison.targetQueryFragment(QUERY+"\nhttps://another.example.gov.cn/query",PERSON,"AR1001"));
    }

    @Test void sourceReviewReopensAndRevalidatesSameCertificateQueryBeforeClosingPass() {
        Rule rule=material("查询截图");RuleEvaluator evaluator=evaluator(QUERY,CERT,true,REQUIREMENT);
        evaluator.incompleteSourceReview.add(rule.id());Decision decision=evaluator.evaluate(rule);
        assertEquals("PASS",decision.status(),decision.toString());
        assertEquals("REVERIFIED_PERSON_SAME_CERTIFICATE_QUERY",((Map<?,?>)decision.proof().get("sourceReviewClosure")).get("basis"),decision.toString());
    }

    @Test void verificationEngineEntryPublishesSameCertificateUrlAndScreenshotConditions() {
        String requirement=REQUIREMENT;
        Document bid=new Document("bid","p","BID","投标文件",1,"hash","",Arrays.asList(
            new Page(1,"投标文件\n项目名称：北方装修工程\n本项目拟派李明担任设计负责人。\n姓名：李明\n身份证号：110101198001010010","PAGE","TEXT",null),
            new Page(2,CERT,"PAGE","TEXT",null),new Page(3,QUERY,"PAGE","TEXT",null)),Collections.emptyList());
        Document tender=new Document("tender","p","TENDER","招标文件",1,"tender-hash","",Collections.singletonList(new Page(1,requirement,"BLOCK","TEXT",null)),Collections.emptyList());
        Evidence source=new Evidence("tender","招标文件",1,"BLOCK",requirement,"chars:0-"+requirement.length(),1);
        Checkpoint checkpoint=new Checkpoint("ticket08-engine","设计负责人资格证明","资格评审","HIGH","SEMANTIC_RESPONSE","qualification","",requirement,"",true,true,true,source);
        VerificationEngine engine=new VerificationEngine(ModelGateway.disabled(),null);
        Map<String,Object> first=engine.evaluate(checkpoint,Collections.singletonList(bid),tender,Collections.emptyMap());
        Map<String,Object> context=new LinkedHashMap<>();context.put("priorSteps",first.get("steps"));
        Map<String,Object> result=engine.evaluate(checkpoint,Collections.singletonList(bid),tender,context);
        assertEquals("PASS",findObligation(result,"提供查询网址").get("status"),result.toString());
        assertEquals("PASS",findObligation(result,"提供查询截图").get("status"),result.toString());
        assertEquals("PASS",result.get("businessDecision"),"本样本的拟派身份、一级建筑师原件和同证号查询页均有源证据");
        assertEquals("PASS",findObligation(result,"一级注册建筑师").get("status"),result.toString());
        Map<String,Object> url=findObligation(result,"提供查询网址"),screenshot=findObligation(result,"提供查询截图");
        List<Map<String,Object>> urlBindings=bindings(url.get("proof")),screenshotBindings=bindings(screenshot.get("proof"));
        assertFalse(urlBindings.isEmpty());assertEquals(urlBindings,screenshotBindings);
        assertTrue(url.get("evidences") instanceof List<?>);assertTrue(screenshot.get("evidences") instanceof List<?>);

        Document missingCertificate=new Document("bid","p","BID","投标文件",1,"hash","",
            Arrays.asList(bid.pages().get(0),bid.pages().get(2)),Collections.emptyList());
        assertNotEquals("PASS",evaluateTwice(engine,checkpoint,missingCertificate,tender).get("businessDecision"),"缺少资格原件不能整项通过");
        Document wrongGrade=new Document("bid","p","BID","投标文件",1,"hash","",Arrays.asList(
            bid.pages().get(0),new Page(2,CERT.replace("一级注册建筑师","二级注册建筑师"),"PAGE","TEXT",null),
            new Page(3,QUERY.replace("一级注册建筑师","二级注册建筑师"),"PAGE","TEXT",null)),Collections.emptyList());
        assertNotEquals("PASS",evaluateTwice(engine,checkpoint,wrongGrade,tender).get("businessDecision"),"错误等级不能整项通过");
    }

    private static Map<String,Object> evaluateTwice(VerificationEngine engine,Checkpoint checkpoint,Document bid,Document tender){
        Map<String,Object> first=engine.evaluate(checkpoint,Collections.singletonList(bid),tender,Collections.emptyMap());
        Map<String,Object> context=new LinkedHashMap<>();context.put("priorSteps",first.get("steps"));
        return engine.evaluate(checkpoint,Collections.singletonList(bid),tender,context);
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> findObligation(Map<String,Object> result,String label){return findObligation((Object)result.get("obligations"),label);}
    @SuppressWarnings("unchecked") private static Map<String,Object> findObligation(Object value,String label){
        if(value instanceof Map<?,?>){Map<String,Object> map=(Map<String,Object>)value;if(Objects.toString(map.get("description"),"").contains(label))return map;for(Object child:map.values()){Map<String,Object> found=findObligationOrNull(child,label);if(found!=null)return found;}}
        else if(value instanceof Iterable<?>)for(Object child:(Iterable<?>)value){Map<String,Object> found=findObligationOrNull(child,label);if(found!=null)return found;}
        throw new AssertionError("Missing obligation "+label);
    }
    private static Map<String,Object> findObligationOrNull(Object value,String label){try{return findObligation(value,label);}catch(AssertionError ignored){return null;}}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> bindings(Object value){
        List<Map<String,Object>> out=new ArrayList<>();if(value instanceof Map<?,?>){Map<?,?> map=(Map<?,?>)value;if(map.get("certificateBindings") instanceof List<?>)out.addAll((List<Map<String,Object>>)map.get("certificateBindings"));for(Object child:map.values())out.addAll(bindings(child));}
        else if(value instanceof Iterable<?>)for(Object child:(Iterable<?>)value)out.addAll(bindings(child));return out;
    }
}
