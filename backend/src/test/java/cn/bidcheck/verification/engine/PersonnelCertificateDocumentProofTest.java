package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class PersonnelCertificateDocumentProofTest {
    private static final String COMMON="须提交有效证明文件（并提供查询网址及网址查询截图，查询截图信息应与证明文件信息一致）(注册类资质须已在本单位完成注册)。";
    private static final String REQUIREMENT="项目负责人资格要求：具有贰级含以上注册建造师执业资格证书（房屋建筑工程专业）。"+COMMON;
    private static final String CERT="一级建造师注册证书\n姓名：李明\n身份证号：110101198001010010\n注册编号：京1999999999990001\n聘用企业：北方建设有限公司\n注册专业：建筑工程\n有效期：2024-01-01至2027-01-01";
    private Rule rule(String requirement){return new Rule("COMMON-PROOF","提供证书证明文件","MATERIAL","项目负责人","material","","证明文件","",cn.bidcheck.compat.Java8Api.mapOf("deterministicFamily", "CERTIFICATE_PROOF_DOCUMENTS", "certificateRequirementQuote", requirement),COMMON,"",cn.bidcheck.compat.Java8Api.listOf());}
    private RuleEvaluator evaluator(String cert,boolean identity,String requirement){
        String appointment="投标文件\n项目名称：北方装修工程\n本项目拟派李明担任项目负责人。\n姓名：李明"+(identity?"\n身份证号：110101198001010010":"");
        cn.bidcheck.verification.engine.EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(new Document("b","p","BID","商务册",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,appointment,"PAGE","TEXT",null), new Page(2,cert,"PAGE","TEXT",null)),cn.bidcheck.compat.Java8Api.listOf())));
        Fact role=new Fact("role","PERSON_ROLE","项目负责人","姓名","李明","李明","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(corpus.all.get(0).id()),cn.bidcheck.compat.Java8Api.mapOf());
        return new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(role),corpus,cn.bidcheck.compat.Java8Api.mapOf("originalRequirement", requirement));
    }
    @Test void typedOriginalRecordProvesSubmissionWithoutLiteralProofDocumentWords(){
        Decision d=evaluator(CERT,true,REQUIREMENT).evaluate(rule(REQUIREMENT));assertEquals("PASS",d.status(),d.toString());
        assertEquals("TYPED_CERTIFICATE_RECORD_SUBMISSION_ONLY",d.proof().get("proofScope"));
        assertEquals(false,d.proof().get("validityVerified"));assertEquals(false,d.proof().get("fullPageVisualVerification"));
        assertTrue(d.evidences().stream().anyMatch(e->e.quote().contains("京1999999999990001")));
    }
    @Test void identicalNameWithoutIdentityCannotProveCurrentPersonsCertificate(){
        Decision d=evaluator(CERT,false,REQUIREMENT).evaluate(rule(REQUIREMENT));assertNotEquals("PASS",d.status());
        assertTrue(d.gaps().stream().anyMatch(g->cn.bidcheck.compat.Java8Api.setOf("ENTITY_IDENTITY_AMBIGUOUS", "INFERENCE_CONFIRMATION_REQUIRED").contains(g.code())),d.toString());
    }
    @Test void screenshotOrDirectoryHeadingIsNotAnOriginalRecord(){
        for(String text:cn.bidcheck.compat.Java8Api.listOf("证书查询截图\n一级注册建造师\n姓名：李明\n注册编号：京1999999999990001", "目录\n李明注册建造师证明文件......2"))
            assertNotEquals("PASS",evaluator(text,true,REQUIREMENT).evaluate(rule(REQUIREMENT)).status());
    }
    @Test void originalOfAnotherCredentialFamilyCannotCloseDocumentObligation(){
        assertNotEquals("PASS",evaluator(CERT.replace("建造师","建筑师"),true,REQUIREMENT).evaluate(rule(REQUIREMENT)).status());
    }
    @Test void compoundCredentialClauseRequiresBothDocuments(){
        String requirement=REQUIREMENT.replace("。"+COMMON,"及有效的安全生产考核合格证（B证）。"+COMMON);
        assertNotEquals("PASS",evaluator(CERT,true,requirement).evaluate(rule(requirement)).status());
    }
    @Test void historicalSourceCannotProveCurrentCertificateSubmission(){assertNotEquals("PASS",evaluator("历史项目\n"+CERT,true,REQUIREMENT).evaluate(rule(REQUIREMENT)).status());}
    @Test void changingCompiledRequirementCannotChangeTheOriginalDocumentScope(){
        String fake=REQUIREMENT.replace("注册建造师","注册建筑师");
        assertNotEquals("PASS",evaluator(CERT,true,REQUIREMENT).evaluate(rule(fake)).status());
    }
    @Test void savedProofCannotCloseSourceReviewAfterSourceOrIdentityChanges(){
        Rule rule=rule(REQUIREMENT);RuleEvaluator original=evaluator(CERT,true,REQUIREMENT);
        Decision result=original.evaluate(rule);assertEquals("PASS",result.status());assertTrue(PersonnelCertificateDocumentProof.proven(rule,result,original));
        assertFalse(PersonnelCertificateDocumentProof.proven(rule,result,evaluator(CERT,false,REQUIREMENT)));
        assertFalse(PersonnelCertificateDocumentProof.proven(rule,result,evaluator(CERT.replace("京1999999999990001","京1999999999990002"),true,REQUIREMENT)));
    }
    @Test void localRecordExistenceDoesNotCloseTruncatedGlobalFactLookup(){
        Rule rule=rule(REQUIREMENT);RuleEvaluator original=evaluator(CERT,true,REQUIREMENT);Map<String,Object> context=new HashMap<>(original.context);
        context.put("_recoveryCoverageDependencies",cn.bidcheck.compat.Java8Api.mapOf(rule.id(), cn.bidcheck.compat.Java8Api.mapOf("obligationId", rule.id(), "observationKey", cn.bidcheck.verification.facts.FieldCoverage.key(SharedFactReuse.observationTarget(rule)), "gapCode", "EVIDENCE_LOOKUP_INCOMPLETE")));
        Decision result=new RuleEvaluator(original.facts,original.corpus,context).evaluate(rule);
        assertEquals("UNDETERMINED",result.status());assertTrue(result.gaps().stream().anyMatch(g->g.code().equals("EVIDENCE_LOOKUP_INCOMPLETE")));
    }
}
