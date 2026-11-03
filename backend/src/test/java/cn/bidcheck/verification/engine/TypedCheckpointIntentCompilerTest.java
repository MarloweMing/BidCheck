package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for built-in checkpoint metadata being replaced by broad source prose. */
class TypedCheckpointIntentCompilerTest {
    private Plan compile(Checkpoint checkpoint,boolean focused)throws Exception{
        Map<String,Object> context=new LinkedHashMap<>();
        if(checkpoint.source()!=null)context.put("originalRequirement",checkpoint.source().quote());
        if(focused){context.put("focusedCheck",true);context.put("atomicDomains",cn.bidcheck.compat.Java8Api.listOf("FIELD", "MATERIAL", "DATE", "TEXT"));}
        return new RequirementCompiler().compile(checkpoint,new RunSession(ModelGateway.disabled(),context,"typed-intent-test"));
    }
    private Checkpoint checkpoint(String title,String type,String field,String operator,String expected,Evidence source){
        return new Checkpoint("cp-"+field,title,"检查","HIGH",type,field,operator,expected,"",true,true,true,source);
    }
    private Evidence source(String quote){return new Evidence("tender","招标文件.docx",3,"PARAGRAPH",quote,"p3",1);}
    private Rule only(Plan plan){assertEquals(1,plan.rules().size(),plan.toString());return plan.rules().get(0);}

    @Test void focusedProjectNameUsesCheckpointValueInsteadOfCompilingWholeIntroduction()throws Exception{
        String expected="合成云计算中心装修工程";
        String quote="本招标项目为合成云计算中心装修工程、招标编号：SYN-2026-01，招标人为合成采购单位，项目资金已落实。";
        Plan plan=compile(checkpoint("项目名称一致性","ENTITY_FIELD_CONSISTENCY","projectName","=",expected,source(quote)),true);
        Rule rule=only(plan);
        assertEquals("TYPED_CHECKPOINT_COMPILED",plan.method());assertTrue(plan.issues().isEmpty());
        assertEquals("SAME_FIELD",rule.predicate());assertEquals("projectName",rule.field());assertEquals(expected,rule.expected());
        assertEquals(quote,rule.sourceQuote(),"source prose scopes evidence and must not replace the typed expected value");
    }

    @Test void inventoryEntryWithoutSubmissionVerbRemainsExecutableMaterialIntent()throws Exception{
        Plan plan=compile(checkpoint("投标文件组成：商务投标文件封面","MATERIAL_EXISTENCE","material","EXISTS","商务投标文件封面",source("商务投标文件封面")),true);
        Rule rule=only(plan);
        assertEquals("MATERIAL",rule.predicate());assertEquals("商务投标文件封面",rule.expected());
        assertEquals(Boolean.TRUE,rule.parameters().get("inventoryEntry"));assertFalse(rule.predicate().equals("UNSUPPORTED"));
    }

    @Test void deadlineSourceBindsReferenceTimeButDoesNotEraseCertificateValidityIntent()throws Exception{
        String quote="投标截止时间为：2026年08月05日09时30分。";
        Plan plan=compile(checkpoint("证书在投标截止日有效","DATE_VALIDITY","certificateExpiry",">=","2026-08-05",source(quote)),true);
        Rule rule=only(plan);
        assertEquals("VALIDITY",rule.predicate());assertEquals("certificateExpiry",rule.field());assertEquals("2026-08-05",rule.expected());
        assertEquals("BID_DEADLINE",rule.parameters().get("referenceEvent"));assertEquals(Boolean.TRUE,rule.parameters().get("allApplicableCertificates"));
        assertTrue(plan.issues().isEmpty());
    }

    @Test void internalConsistencyWithoutTenderQuoteStillCompilesFromConfirmedMetadata()throws Exception{
        Plan plan=compile(checkpoint("投标人名称内部一致性","ENTITY_FIELD_CONSISTENCY","companyName","=","",null),true);
        Rule rule=only(plan);
        assertEquals("SAME_FIELD",rule.predicate());assertEquals("companyName",rule.field());assertTrue(rule.expected().isEmpty());
        assertEquals("CURRENT_PROJECT_OCCURRENCES",rule.parameters().get("comparisonScope"));
    }

    @Test void commonFullTextChecksBecomeProgramRulesWithExplicitCapabilityGaps()throws Exception{
        for(String field:cn.bidcheck.compat.Java8Api.listOf("amountAccuracy", "missingFields", "signatureDate", "signingAfterOpening", "typos")){
            Plan plan=compile(checkpoint("全文检查-"+field,"FULL_TEXT_REVIEW",field,"REVIEW","核对"+field,null),true);
            Rule rule=only(plan);
            assertEquals("TEXT_ANOMALY",rule.predicate(),field);assertEquals("全文",rule.parameters().get("textScope"),field);
            assertFalse(((List<?>)rule.parameters().get("anomalyTypes")).isEmpty(),field);assertTrue(plan.issues().isEmpty(),field);
        }
    }

    @Test void unknownFullTextFieldKeepsConservativeGlobalReviewGuard()throws Exception{
        Plan plan=compile(checkpoint("未知全文检查","FULL_TEXT_REVIEW","unknownReview","REVIEW","核对",null),true);
        assertTrue(plan.rules().isEmpty());assertEquals("NATIVE",plan.method());assertFalse(plan.issues().isEmpty());
    }

    @Test void unconfirmedMetadataIsNeverPromotedToAuthoritativeTypedIntent()throws Exception{
        Checkpoint unconfirmed=new Checkpoint("draft","项目名称一致性","检查","HIGH","ENTITY_FIELD_CONSISTENCY",
                "projectName","=","未确认项目","",true,false,true,source("本招标项目为另一个项目。"));
        Plan plan=compile(unconfirmed,true);
        assertNotEquals("TYPED_CHECKPOINT_COMPILED",plan.method());
        assertTrue(plan.rules().stream().noneMatch(rule->"CONFIRMED_CHECKPOINT_METADATA".equals(rule.parameters().get("intentAuthority"))));
    }

    @Test void expiredCurrentCertificateCanFailButHistoricalCertificateCannotContaminateTheCheck()throws Exception{
        Rule rule=only(compile(checkpoint("证书在投标截止日有效","DATE_VALIDITY","certificateExpiry",">=","2026-08-05",source("投标截止时间为：2026年08月05日09时30分。")),true));
        EvidenceCorpus corpus=certificateCorpus();
        Fact expired=certificate(corpus,"expired","CURRENT_PROJECT","2024-01-01","2026-08-04");
        Decision failure=new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(expired),corpus,deadlineContext(true)).evaluate(rule);
        assertEquals("FAIL",failure.status(),failure.toString());

        Fact valid=certificate(corpus,"valid","CURRENT_PROJECT","2024-01-01","2027-08-04");
        Fact historical=certificate(corpus,"historical","HISTORICAL","2020-01-01","2021-01-01");
        Decision pass=new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(valid, historical),corpus,deadlineContext(true)).evaluate(rule);
        assertEquals("PASS",pass.status(),pass.toString());
        assertEquals(1,pass.evidences().size(),"historical credentials do not enter the current-project decision");
    }

    @Test void partialCertificateRetrievalNeverTurnsObservedValidRecordsIntoAFullPass()throws Exception{
        Rule rule=only(compile(checkpoint("证书在投标截止日有效","DATE_VALIDITY","certificateExpiry",">=","2026-08-05",source("投标截止时间为：2026年08月05日09时30分。")),true));
        EvidenceCorpus corpus=certificateCorpus();
        Fact valid=certificate(corpus,"valid","CURRENT_PROJECT","2024-01-01","2027-08-04");
        Decision pending=new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(valid),corpus,deadlineContext(false)).evaluate(rule);
        assertEquals("UNDETERMINED",pending.status(),pending.toString());
        assertEquals("CERTIFICATE_SET_COVERAGE_REQUIRED",pending.gaps().get(0).code());
    }

    private EvidenceCorpus certificateCorpus(){
        Document document=new Document("bid","project","BID","投标文件.pdf",1,"bid-hash","",
                cn.bidcheck.compat.Java8Api.listOf(new Page(1,"张三 注册证书 证书编号：R001 有效期2024年1月1日至2027年8月4日","PAGE","TEXT","")),cn.bidcheck.compat.Java8Api.listOf());
        return new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(document));
    }
    private Fact certificate(EvidenceCorpus corpus,String id,String scope,String start,String end){
        return new Fact(id,"CERTIFICATE","张三","certificateExpiry",start+"至"+end,end,"","",scope,
                cn.bidcheck.compat.Java8Api.listOf(corpus.all.get(0).id()),cn.bidcheck.compat.Java8Api.mapOf("category", "注册证书", "number", "R001", "numberField", "证书编号", "validityField", "有效期", "start", start, "end", end));
    }
    private Map<String,Object> deadlineContext(boolean complete){
        return cn.bidcheck.compat.Java8Api.mapOf("certificateSetComplete", complete, "referenceDates", cn.bidcheck.compat.Java8Api.mapOf("BID_DEADLINE", cn.bidcheck.compat.Java8Api.mapOf("referenceDate", "2026-08-05", "referenceDateSourceVerified", true, "referenceDateEvidence", cn.bidcheck.compat.Java8Api.mapOf("documentId", "tender", "quote", "投标截止时间为：2026年08月05日09时30分。", "confirmation", "USER_SELECTED_VERSION"))));
    }
}
