package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.verification.agent.AgentInvestigator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the real host/source boundary with an in-process scripted Agent and reviewer.
 * No provider, retrieval service, OCR call or fabricated proof receipt is involved. */
class AgentLedReviewTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String REQUIREMENT="本项目应急预案应说明事故报告、现场处置和人员疏散安排。";
    private static final String PASS_BODY="本项目应急预案\n发生事故后立即向建设单位报告现场情况，由应急负责人组织现场处置，并沿安全通道组织人员疏散至集合区域。";
    private static final String FAIL_BODY="本项目施工应急说明\n遇到事故时仅停止施工，不向建设单位报告，不组织现场处置，也不安排人员疏散，人员继续留在作业区域等待。";

    private record Fixture(AgentLedReview review,RunSession session,Reviewer model,Source source,
                           Map<String,Object> context,Checkpoint checkpoint,List<Document> bids) {}

    private static final class Reviewer implements ModelGateway {
        final List<String> operations=new ArrayList<>();
        final List<Map<String,Object>> inputs=new ArrayList<>();
        boolean approvePlan=true,approveJudgment=true,wrongConditionIds;
        @Override public boolean enabled(){return true;}
        @Override public String identity(){return "isolated-independent-reviewer-v1";}
        @Override public JsonNode request(String operation,Map<String,Object> input)throws IOException{
            AgentReviewContracts.validateInput(operation,input);
            operations.add(operation);inputs.add(new LinkedHashMap<>(input));
            List<String> ids=wrongConditionIds?List.of("foreign-condition"):
                RuleEvaluator.maps(input.get("conditions")).stream().map(c->str(c,"obligationId")).toList();
            if(operation.equals("REVIEW_PLAN"))return JSON.valueToTree(linked("approved",approvePlan,
                "coverageComplete",approvePlan,"conditionIds",ids,"issues",approvePlan?List.of():List.of("原条款的人员疏散安排未纳入计划"),"requiresDeterministic",List.of()));
            if(operation.equals("REVIEW_JUDGMENT"))return JSON.valueToTree(linked("approved",approveJudgment,
                "status",approveJudgment?((Map<?,?>)input.get("proposal")).get("status"):"UNDETERMINED",
                "conditionIds",ids,"issues",approveJudgment?List.of():List.of("只核对事故报告，尚未证明现场处置与人员疏散")));
            throw new AssertionError("Unexpected model operation: "+operation);
        }
    }

    private static Fixture fixture(String requirement,String bidText){return fixture(requirement,bidText,new Reviewer());}
    private static Fixture fixture(String requirement,String bidText,Reviewer reviewer){
        return fixturePages(requirement,reviewer,bidText);
    }
    private static Fixture fixturePages(String requirement,Reviewer reviewer,String... bidTexts){
        Document tender=new Document("tender-1","project-1","TENDER","招标要求.docx",1,
            identity(requirement),"",List.of(new Page(1,requirement,"BLOCK","DOCX","")),List.of());
        List<Page> bidPages=new ArrayList<>();for(int i=0;i<bidTexts.length;i++)bidPages.add(new Page(i+1,bidTexts[i],"PAGE","PDF",""));
        Document bid=new Document("bid-1","project-1","BID","投标文件.pdf",1,
            identity(String.join("\n",bidTexts)),"",bidPages,List.of());
        Evidence evidence=new Evidence(tender.id(),tender.name(),1,"BLOCK",requirement,"chars:0-"+requirement.length(),1);
        Checkpoint checkpoint=new Checkpoint("review-item","应急预案内容","响应检查","HIGH","SEMANTIC_RESPONSE",
            "technicalResponse","RESPONDS",requirement,"",true,true,true,evidence);
        Map<String,Object> context=linked("reviewMode","AGENT_LED","originalRequirement",requirement,
            "requirementProjectId","project-1","tenderDocuments",List.of(tender));
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(bid));Source source=corpus.all.get(0);
        // A source handle authorizes navigation only; judgment must still wait for an actual read.
        context.put("recoveryChangedSourceIds",corpus.all.stream().map(Source::id).toList());
        RunSession session=new RunSession(reviewer,context,"agent-led-isolated-item");
        return new Fixture(new AgentLedReview(checkpoint,List.of(bid),context,session,null),session,reviewer,source,context,checkpoint,List.of(bid));
    }
    private static Map<String,Object> condition(String quote){return linked("obligationId","emergency",
        "description","核对事故报告、现场处置和人员疏散安排","requirementQuote",quote,"method","SEMANTIC",
        "predicate","CONTENT","subject","本项目","field","应急预案","operator","RESPONDS",
        "expected","","unit","","expectedEvidence","对应应急预案正文");}
    private static Map<String,Object> plan(Map<String,Object> condition){return linked("conditions",List.of(condition),
        "coverageExplanation","本条件保留原条款全部应急响应安排，逐项对照正文。");}
    private static String approve(Fixture f){
        Map<String,Object> receipt=f.review().call("submit_review_plan",plan(condition(str(f.context(),"originalRequirement"))));
        assertEquals("COMPLETED",receipt.get("status"),receipt.toString());
        assertEquals(true,((Map<?,?>)receipt.get("planReceipt")).get("approved"));
        assertFalse(str(receipt,"planRevision").isBlank());
        return str(receipt,"planRevision");
    }
    private static void read(Fixture f){
        Map<String,Object> receipt=f.review().call("read_section",Map.of("sourceId",f.source().id(),"targetObligationId","emergency"));
        assertEquals("COMPLETED",receipt.get("status"),receipt.toString());
        assertTrue(ProofVerifier.strings(receipt.get("sourceIds")).contains(f.source().id()));
        assertEquals(false,((Map<?,?>)receipt.get("computationReceipt")).get("businessCheckCompleted"),
            "Reading a matching document body is not itself a semantic decision");
    }
    private static Map<String,Object> judgment(Fixture f,String revision,String status){
        String quote=f.source().evidence().quote();
        return linked("targetObligationId","emergency","planRevision",revision,"status",status,
            "reason",status.equals("PASS")?"正文分别明确事故报告、现场处置与人员疏散安排。":"正文明确排除了原条款要求的应急处置安排。",
            "evidence",List.of(linked("sourceId",f.source().id(),"quote",quote)),
            "conditionChecks",List.of(linked("requirementQuote",str(f.context(),"originalRequirement"),
                "evidenceSourceId",f.source().id(),"evidenceQuote",quote,"explanation","逐项对照报告、处置、疏散的具体安排。")),
            "remainingGaps",List.of());
    }
    private static void rejection(Map<String,Object> result,String code){
        assertEquals("REJECTED",result.get("status"),result.toString());assertEquals(code,result.get("error"),result.toString());
    }

    @Test void fullAgentWorkflowPublishesPassOnlyAfterPlanSourceReadAndIndependentJudgment(){
        exerciseWorkflow("PASS",PASS_BODY);
    }
    @Test void fullAgentWorkflowCanPublishSourceBackedFailInsteadOfTreatingAbsenceAsFailure(){
        exerciseWorkflow("FAIL",FAIL_BODY);
    }
    private void exerciseWorkflow(String status,String body){
        Fixture f=fixture(REQUIREMENT,body);
        AgentInvestigator agent=new AgentInvestigator(){
            @Override public boolean enabled(){return true;}
            @Override public String identity(){return "scripted-no-network-agent";}
            @Override public Map<String,Object> investigate(Map<String,Object> start,ToolHandler handler,Consumer<Map<String,Object>> event){
                Map<?,?> context=(Map<?,?>)start.get("context"),requirement=(Map<?,?>)context.get("requirement");
                assertEquals("AGENT_LED_REVIEW_V1",context.get("workflowPolicy"));
                assertTrue(RuleEvaluator.maps(requirement.get("originalSources")).stream().anyMatch(s->REQUIREMENT.equals(str(s,"quote"))));
                List<String> executableIds=ProofVerifier.strings(context.get("executableSourceIds"));
                List<String> contextOnlyIds=ProofVerifier.strings(context.get("contextOnlySourceIds"));
                assertFalse(executableIds.isEmpty());assertTrue(Collections.disjoint(executableIds,contextOnlyIds));
                assertEquals(executableIds,ProofVerifier.strings(requirement.get("executableSourceIds")));
                List<Map<String,Object>> spans=RuleEvaluator.maps(context.get("executableSpans"));assertFalse(spans.isEmpty());
                assertTrue(spans.get(0).containsKey("start"));assertTrue(spans.get(0).containsKey("end"));assertTrue(spans.get(0).containsKey("sourceHash"));
                Map<?,?> checkpoint=(Map<?,?>)context.get("checkpointContract"),contract=(Map<?,?>)context.get("planContract");
                assertEquals(f.checkpoint().id(),checkpoint.get("checkpointId"));
                assertEquals(15,((List<?>)contract.get("deterministicPredicateValues")).size());
                assertEquals("CONTENT",contract.get("semanticPredicateValue"));
                assertEquals(List.of(),context.get("targetObligationIds"));
                Map<String,Object> approved=handler.call("submit_review_plan",plan(condition(REQUIREMENT)));
                assertEquals("COMPLETED",approved.get("status"),approved.toString());
                Map<String,Object> searched=handler.call("search_evidence",Map.of("query","本项目 应急 事故","targetObligationId","emergency"));
                assertTrue(ProofVerifier.strings(searched.get("sourceIds")).contains(f.source().id()),searched.toString());
                Map<String,Object> read=handler.call("read_section",Map.of("sourceId",f.source().id(),"targetObligationId","emergency"));
                assertEquals("COMPLETED",read.get("status"),read.toString());
                assertEquals(false,((Map<?,?>)read.get("computationReceipt")).get("businessCheckCompleted"));
                Map<String,Object> judged=handler.call("submit_review_judgment",judgment(f,str(approved,"planRevision"),status));
                assertEquals("COMPLETED",judged.get("status"),judged.toString());
                Map<String,Object> receipt=handler.call("recompute_check",Map.of());
                assertEquals(true,receipt.get("businessCheckCompleted"),receipt.toString());
                assertEquals(status,receipt.get("businessDecision"));
                return linked("status","COMPLETED","modelCalls",0,"stopReason","HOST_COMPLETED");
            }
        };
        Map<String,Object> result=f.review().execute(agent);
        assertEquals(true,result.get("complete"),result.toString());assertEquals(status,result.get("businessDecision"));
        assertEquals("COMPLETED",result.get("workflowState"));assertTrue(RuleEvaluator.maps(result.get("gaps")).isEmpty());
        assertEquals(List.of("REVIEW_PLAN","REVIEW_JUDGMENT"),f.model().operations);
        assertEquals(2,f.session().modelCalls);
        Map<String,Object> obligation=RuleEvaluator.maps(result.get("obligations")).get(0);
        assertEquals("SOURCE_GROUNDED_SEMANTIC_REVIEW",((Map<?,?>)obligation.get("proof")).get("method"));
        assertTrue(f.session().steps.stream().anyMatch(s->"AGENT_REVIEW_PLAN_APPROVED".equals(s.get("type"))));
        assertTrue(f.session().steps.stream().anyMatch(s->"AGENT_REVIEW_JUDGMENT_ACCEPTED".equals(s.get("type"))));
    }

    @Test void unapprovedPlanCannotReadSearchSubmitOrPublish(){
        Fixture f=fixture(REQUIREMENT,PASS_BODY);
        for(String tool:List.of("read_section","search_evidence","submit_candidate_fact","submit_review_judgment","recompute_check"))
            rejection(f.review().call(tool,Map.of()),"PLAN_APPROVAL_REQUIRED");
        assertTrue(f.model().operations.isEmpty());assertEquals(false,f.review().result().get("complete"));
        assertTrue(RuleEvaluator.maps(f.review().result().get("gaps")).stream().anyMatch(g->"AGENT_PLAN_NOT_SUBMITTED".equals(g.get("code"))));
    }

    @Test void planToolSchemaPublishesExactPrimitiveContractAndSemanticConstant(){
        Map<String,Object> submit=AgentLedReview.tools().stream().filter(t->"submit_review_plan".equals(t.get("name"))).findFirst().orElseThrow();
        Map<?,?> parameters=(Map<?,?>)submit.get("parameters"),properties=(Map<?,?>)parameters.get("properties");
        Map<?,?> conditions=(Map<?,?>)properties.get("conditions"),item=(Map<?,?>)conditions.get("items"),fields=(Map<?,?>)item.get("properties");
        Map<?,?> predicate=(Map<?,?>)fields.get("predicate");List<?> alternatives=(List<?>)predicate.get("oneOf");
        List<?> deterministic=(List<?>)((Map<?,?>)alternatives.get(0)).get("enum");
        assertEquals(15,deterministic.size());assertEquals(15,new HashSet<>(deterministic).size());
        assertEquals(List.of("CONTENT"),((Map<?,?>)alternatives.get(1)).get("enum"));
        assertEquals(List.of("DETERMINISTIC","SEMANTIC"),((Map<?,?>)fields.get("method")).get("enum"));
        for(String field:List.of("requirementQuote","method","predicate","subject","field","operator","unit"))
            assertFalse(Objects.toString(((Map<?,?>)fields.get(field)).get("description"),"").isBlank(),field);
    }

    @Test void invalidPlanIdentifiesExactFieldWithoutDisclosingContextAsExecutable(){
        Fixture f=fixture(REQUIREMENT,PASS_BODY);Map<String,Object> invalid=condition("改写后的不存在要求");
        Map<String,Object> result=f.review().call("submit_review_plan",plan(invalid));
        rejection(result,"PLAN_REQUIREMENT_QUOTE_NOT_EXECUTABLE");
        Map<String,Object> violation=RuleEvaluator.maps(result.get("violations")).get(0);
        assertEquals("emergency",violation.get("conditionId"));assertEquals("conditions[0].requirementQuote",violation.get("fieldPath"));
        assertFalse(ProofVerifier.strings(violation.get("allowedValues")).isEmpty());
        assertFalse(str(violation,"correctiveAction").isBlank());
        Map<String,Object> finalResult=f.review().result();
        Map<String,Object> gap=RuleEvaluator.maps(finalResult.get("gaps")).stream().filter(g->"AGENT_PLAN_PROTOCOL_FAILED".equals(g.get("code"))).findFirst().orElseThrow();
        assertEquals(1,RunSession.number(gap.get("planSubmissions"),0));assertEquals(1,RunSession.number(gap.get("planRejections"),0));
        Map<?,?> metrics=(Map<?,?>)finalResult.get("metrics");assertEquals("AGENT_PLAN_PROTOCOL_FAILED",metrics.get("stopReason"));assertEquals(false,metrics.get("planApproved"));
    }

    @Test void methodAndPredicateCrossConstraintReturnsCorrectableViolation(){
        Fixture f=fixture(REQUIREMENT,PASS_BODY);Map<String,Object> wrong=condition(REQUIREMENT);wrong.put("predicate","CERTIFICATE");
        Map<String,Object> semantic=f.review().call("submit_review_plan",plan(wrong));
        rejection(semantic,"DETERMINISTIC_REVIEW_REQUIRED");
        assertEquals("conditions[0].method",RuleEvaluator.maps(semantic.get("violations")).get(0).get("fieldPath"));
        wrong.put("method","DETERMINISTIC");wrong.put("predicate","CONTENT");
        Map<String,Object> deterministic=f.review().call("submit_review_plan",plan(wrong));
        rejection(deterministic,"PREDICATE_NOT_SUPPORTED");
        assertEquals(15,((List<?>)RuleEvaluator.maps(deterministic.get("violations")).get(0).get("allowedValues")).size());
    }

    @Test void numericConditionCannotBeRelabeledAsSemanticToBypassCalculation(){
        Fixture f=fixture("本项目投标报价不得超过100万元。",PASS_BODY);
        rejection(f.review().call("submit_review_plan",plan(condition(str(f.context(),"originalRequirement")))),"DETERMINISTIC_REVIEW_REQUIRED");
        assertTrue(f.model().operations.isEmpty());assertEquals(false,f.review().result().get("complete"));
        Fixture typed=fixture(REQUIREMENT,PASS_BODY);Map<String,Object> c=condition(REQUIREMENT);c.put("predicate","CERTIFICATE");
        rejection(typed.review().call("submit_review_plan",plan(c)),"DETERMINISTIC_REVIEW_REQUIRED");
    }

    @Test void omittedRequirementRejectedByIndependentPlanReviewerCannotOpenEvidenceTools(){
        Reviewer reviewer=new Reviewer();reviewer.approvePlan=false;Fixture f=fixture(REQUIREMENT,PASS_BODY,reviewer);
        Map<String,Object> partial=condition("本项目应急预案应说明事故报告");
        rejection(f.review().call("submit_review_plan",plan(partial)),"PLAN_COVERAGE_REJECTED");
        rejection(f.review().call("read_section",Map.of("sourceId",f.source().id())),"PLAN_APPROVAL_REQUIRED");
        assertEquals(List.of("REVIEW_PLAN"),reviewer.operations);assertEquals(false,f.review().result().get("complete"));
    }

    @Test void foundMaterialAndAgentPassProposalDoNotOverrideIndependentMissingConditionReview(){
        Reviewer reviewer=new Reviewer();reviewer.approveJudgment=false;Fixture f=fixture(REQUIREMENT,PASS_BODY,reviewer);
        String revision=approve(f);read(f);Map<String,Object> proposal=judgment(f,revision,"PASS");
        proposal.put("conditionChecks",List.of(linked("requirementQuote",REQUIREMENT,"evidenceSourceId",f.source().id(),
            "evidenceQuote",f.source().evidence().quote(),"explanation","仅检查了事故报告，尚未核对其他条件。")));
        rejection(f.review().call("submit_review_judgment",proposal),"SEMANTIC_REVIEW_REJECTED");
        assertEquals("UNDETERMINED",f.review().result().get("businessDecision"));
        assertEquals(List.of("REVIEW_PLAN","REVIEW_JUDGMENT"),reviewer.operations);
    }

    @Test void unknownUnreadAndInventedContinuousSourcesCannotReachIndependentJudgment(){
        Fixture f=fixture(REQUIREMENT,PASS_BODY);String revision=approve(f);
        rejection(f.review().call("submit_review_judgment",judgment(f,revision,"PASS")),"SEMANTIC_SOURCE_INVALID");
        read(f);Map<String,Object> fabricated=judgment(f,revision,"PASS");
        fabricated.put("evidence",List.of(linked("sourceId","fake-source","quote",PASS_BODY)));
        rejection(f.review().call("submit_review_judgment",fabricated),"SEMANTIC_SOURCE_INVALID");
        Map<String,Object> spliced=judgment(f,revision,"PASS");
        spliced.put("evidence",List.of(linked("sourceId",f.source().id(),"quote","发生事故后立即向建设单位报告现场情况，已满足全部招标要求")));
        rejection(f.review().call("submit_review_judgment",spliced),"SEMANTIC_SOURCE_INVALID");
        assertEquals(List.of("REVIEW_PLAN"),f.model().operations);assertEquals(false,f.review().result().get("complete"));
    }

    @Test void titleOnlyMaterialCannotProveBodyContent(){
        Fixture f=fixture(REQUIREMENT,"本项目应急预案与事故现场处置方案");String revision=approve(f);read(f);
        rejection(f.review().call("submit_review_judgment",judgment(f,revision,"PASS")),"TITLE_ONLY_EVIDENCE");
        assertEquals(List.of("REVIEW_PLAN"),f.model().operations);
    }

    @Test void historicalProjectTextCannotBecomeCurrentProjectProof(){
        Fixture f=fixture(REQUIREMENT,"历史项目\n福建旧项目应急预案\n发生事故后立即向建设单位报告现场情况，由应急负责人组织现场处置，并沿安全通道疏散人员。");
        assertTrue(f.source().historical()||f.source().scopeContext().blocked(),"Fixture must be recognized as historical by the real corpus");
        String revision=approve(f);read(f);
        rejection(f.review().call("submit_review_judgment",judgment(f,revision,"PASS")),"SEMANTIC_SOURCE_SCOPE_INVALID");
        assertEquals(List.of("REVIEW_PLAN"),f.model().operations);assertEquals(false,f.review().result().get("complete"));
    }

    @Test void revisedPlanRejectsJudgmentForOldRevision(){
        Fixture f=fixture(REQUIREMENT,PASS_BODY);String previous=approve(f);read(f);
        Map<String,Object> changed=condition(REQUIREMENT);changed.put("description","分别核对报告路径、处置措施和疏散安排");changed.put("obligationId","emergency-v2");
        Map<String,Object> approved=f.review().call("submit_review_plan",plan(changed));
        assertEquals("COMPLETED",approved.get("status"),approved.toString());String current=str(approved,"planRevision");assertNotEquals(previous,current);
        rejection(f.review().call("submit_review_judgment",judgment(f,previous,"PASS")),"REVIEW_PLAN_REVISION_MISMATCH");
        assertEquals(List.of("REVIEW_PLAN","REVIEW_PLAN"),f.model().operations);
        Map<String,Object> currentJudgment=judgment(f,current,"PASS");currentJudgment.put("targetObligationId","emergency-v2");
        assertEquals("COMPLETED",f.review().call("submit_review_judgment",currentJudgment).get("status"));
        assertEquals("PASS",f.review().result().get("businessDecision"));
    }

    @Test void rewritingExplanationsCannotReopenApprovedPlanOrPayForAnotherReview(){
        Fixture f=fixture(REQUIREMENT,PASS_BODY);String revision=approve(f);read(f);
        assertEquals("COMPLETED",f.review().call("submit_review_judgment",judgment(f,revision,"PASS")).get("status"));
        Map<String,Object> condition=condition(REQUIREMENT);condition.put("description","重新解释同一项应急安排");condition.put("expectedEvidence","同一份正文中的逐项措施");
        Map<String,Object> request=plan(condition);request.put("coverageExplanation","换一种说法说明相同覆盖范围");
        Map<String,Object> approved=f.review().call("submit_review_plan",request);
        assertEquals(revision,approved.get("planRevision"));assertEquals("PASS",f.review().result().get("businessDecision"));
        assertEquals(List.of("REVIEW_PLAN","REVIEW_JUDGMENT"),f.model().operations);
    }

    @Test void mismatchingIndependentReviewConditionIdsNeverApprovePlan(){
        Reviewer reviewer=new Reviewer();reviewer.wrongConditionIds=true;Fixture f=fixture(REQUIREMENT,PASS_BODY,reviewer);
        Map<String,Object> rejected=f.review().call("submit_review_plan",plan(condition(REQUIREMENT)));
        assertEquals("FAILED",rejected.get("status"));assertEquals("INDEPENDENT_REVIEW_UNAVAILABLE",rejected.get("error"));
        rejection(f.review().call("read_section",Map.of("sourceId",f.source().id())),"PLAN_APPROVAL_REQUIRED");
        assertEquals(false,f.review().result().get("complete"));
    }

    @Test void unchangedPlanApprovalAndJudgmentAreIdempotentWithoutExtraReviewerCalls(){
        Fixture f=fixture(REQUIREMENT,PASS_BODY);String revision=approve(f);read(f);
        assertEquals("COMPLETED",f.review().call("submit_review_judgment",judgment(f,revision,"PASS")).get("status"));
        assertEquals(revision,approve(f));
        assertEquals("COMPLETED",f.review().call("submit_review_judgment",judgment(f,revision,"PASS")).get("status"));
        assertEquals(List.of("REVIEW_PLAN","REVIEW_JUDGMENT"),f.model().operations);
        assertEquals(true,f.review().result().get("complete"));
    }

    @Test void missingFrozenTenderSourceStopsBeforeAgentOrReviewerCalls(){
        Fixture f=fixture(REQUIREMENT,PASS_BODY);Map<String,Object> context=new LinkedHashMap<>(f.context());context.remove("tenderDocuments");
        RunSession session=new RunSession(f.model(),context,"unverified-source");
        AgentLedReview review=new AgentLedReview(f.checkpoint(),f.bids(),context,session,null);
        Map<String,Object> result=review.execute(new AgentInvestigator(){
            public boolean enabled(){return true;}public String identity(){return "must-not-run";}
            public Map<String,Object> investigate(Map<String,Object> start,ToolHandler handler,Consumer<Map<String,Object>> event){throw new AssertionError("Unverified tender must never reach Agent");}
        });
        assertEquals(false,result.get("complete"));assertTrue(f.model().operations.isEmpty());
        assertTrue(RuleEvaluator.maps(result.get("gaps")).stream().anyMatch(g->"REQUIREMENT_SCOPE_UNRESOLVED".equals(g.get("code"))));
    }

    private static final String EMERGENCY_REQUIREMENT="应急预案应说明事故报告路径。";
    private static final String CLEANING_REQUIREMENT="文明施工方案应说明现场保洁安排。";
    private static final String CLEANING_BODY="本项目文明施工方案\n施工结束后由专门保洁人员清理现场，建筑垃圾分类装袋，运输通道及时清扫，并由现场负责人检查清理情况。";
    private static Fixture twoConditions(String firstBody){
        return fixturePages(EMERGENCY_REQUIREMENT+"\n"+CLEANING_REQUIREMENT,new Reviewer(),firstBody,CLEANING_BODY);
    }
    private static String approveTwo(Fixture f){
        Map<String,Object> first=condition(EMERGENCY_REQUIREMENT),second=condition(CLEANING_REQUIREMENT);
        second.put("obligationId","cleaning");second.put("field","现场保洁");second.put("description","核对现场保洁安排");
        Map<String,Object> receipt=f.review().call("submit_review_plan",linked("conditions",List.of(first,second),
            "coverageExplanation","分别核对应急报告与现场保洁两项义务，保留每项原文。"));
        assertEquals("COMPLETED",receipt.get("status"),receipt.toString());return str(receipt,"planRevision");
    }
    private static Source page(Fixture f,int number){return new EvidenceCorpus(f.bids()).all.stream()
        .filter(s->s.evidence().page()==number).findFirst().orElseThrow();}
    private static Map<String,Object> readTarget(Fixture f,String target,Source source){
        Map<String,Object> read=f.review().call("read_section",linked("targetObligationId",target,"sourceId",source.id()));
        assertEquals("COMPLETED",read.get("status"),read.toString());return read;
    }
    private static Map<String,Object> targetJudgment(String revision,String target,String requirement,Source source,String status){
        String quote=source.evidence().quote();return linked("targetObligationId",target,"planRevision",revision,"status",status,
            "reason",status.equals("PASS")?"原文明确提供了本项要求的具体安排。":"原文明确排除了本项要求的安排。",
            "evidence",List.of(linked("sourceId",source.id(),"quote",quote)),
            "conditionChecks",List.of(linked("requirementQuote",requirement,"evidenceSourceId",source.id(),"evidenceQuote",quote,
                "explanation","逐字对照本条件的要求和该项投标正文。")),"remainingGaps",List.of());
    }
    private static Map<String,Object> decision(Map<String,Object> result,String target){return RuleEvaluator.maps(result.get("obligations"))
        .stream().filter(d->target.equals(str(d,"id"))).findFirst().orElseThrow();}

    @Test void readingSecondTargetsSourceDoesNotInvalidateFirstTargetsAcceptedJudgment(){
        Fixture f=twoConditions(PASS_BODY);String revision=approveTwo(f);Source first=page(f,1),second=page(f,2);
        readTarget(f,"emergency",first);
        Map<String,Object> accepted=f.review().call("submit_review_judgment",targetJudgment(revision,"emergency",EMERGENCY_REQUIREMENT,first,"PASS"));
        assertEquals("COMPLETED",accepted.get("status"),accepted.toString());
        String firstVersion=str((Map<String,?>)accepted.get("targetEvidenceRevisions"),"emergency");
        assertEquals("PASS",decision(f.review().recompute(),"emergency").get("status"));
        Map<String,Object> readSecond=readTarget(f,"cleaning",second);
        assertEquals(firstVersion,str((Map<String,?>)readSecond.get("targetEvidenceRevisions"),"emergency"));
        Map<String,Object> afterRead=f.review().recompute();
        assertEquals("PASS",decision(afterRead,"emergency").get("status"));
        assertEquals("UNDETERMINED",decision(afterRead,"cleaning").get("status"));
        assertEquals("COMPLETED",f.review().call("submit_review_judgment",targetJudgment(revision,"cleaning",CLEANING_REQUIREMENT,second,"PASS")).get("status"));
        Map<String,Object> result=f.review().result();assertEquals("PASS",result.get("businessDecision"));assertEquals(true,result.get("complete"));
        assertEquals(List.of("REVIEW_PLAN","REVIEW_JUDGMENT","REVIEW_JUDGMENT"),f.model().operations);
    }

    @Test void explicitFailSurvivesOtherTargetPendingWhileCompletionRemainsFalse(){
        Fixture f=twoConditions(FAIL_BODY);String revision=approveTwo(f);Source first=page(f,1);
        readTarget(f,"emergency",first);
        Map<String,Object> judged=f.review().call("submit_review_judgment",targetJudgment(revision,"emergency",EMERGENCY_REQUIREMENT,first,"FAIL"));
        assertEquals("COMPLETED",judged.get("status"),judged.toString());
        Map<String,Object> result=f.review().result();
        assertEquals("FAIL",result.get("businessDecision"),result.toString());assertEquals(false,result.get("complete"));
        assertEquals("FAIL",decision(result,"emergency").get("status"));
        assertEquals("UNDETERMINED",decision(result,"cleaning").get("status"));
        assertFalse(RuleEvaluator.maps(result.get("gaps")).isEmpty());
    }

    @Test void semicolonSeparatedObligationsCannotPassAfterCheckingOnlyOneClause(){
        String requirement="应急预案应说明事故报告路径；应说明现场处置措施；应说明人员疏散安排。";
        Fixture f=fixture(requirement,PASS_BODY);String revision=approve(f);read(f);
        Map<String,Object> partial=judgment(f,revision,"PASS");
        partial.put("conditionChecks",List.of(linked("requirementQuote","应急预案应说明事故报告路径",
            "evidenceSourceId",f.source().id(),"evidenceQuote",f.source().evidence().quote(),"explanation","这里只核对事故报告路径。")));
        rejection(f.review().call("submit_review_judgment",partial),"CONDITION_EVIDENCE_INVALID");
        assertEquals(List.of("REVIEW_PLAN"),f.model().operations,"Incomplete clause coverage is rejected before another model call");
        assertEquals(false,f.review().result().get("complete"));
    }

    @Test void sourceReadingToolsRequireAnApprovedTargetInsteadOfChangingAllGoals(){
        Fixture f=fixture(REQUIREMENT,PASS_BODY);approve(f);
        for(String tool:List.of("search_evidence","read_section","read_table_record")){
            Map<String,Object> args=linked("query","应急","sourceId",f.source().id(),"rowOffset",0);
            rejection(f.review().call(tool,args),"INVALID_TARGET_RECEIPT");
            args.put("targetObligationId","foreign-goal");rejection(f.review().call(tool,args),"INVALID_TARGET_RECEIPT");
        }
        assertEquals(List.of("REVIEW_PLAN"),f.model().operations);
    }

    private static Map<String,Object> completedSemantic(Fixture f){
        String revision=approve(f);read(f);
        assertEquals("COMPLETED",f.review().call("submit_review_judgment",judgment(f,revision,"PASS")).get("status"));
        Map<String,Object> result=f.review().result();assertEquals(true,result.get("complete"),result.toString());return result;
    }
    private static Map<String,Object> restore(Fixture original,Fixture current,Map<String,Object> previous){
        Map<String,Object> context=new LinkedHashMap<>(current.context());context.remove("recoveryChangedSourceIds");
        context.put("recoveryMode","EVIDENCE_RECOMPUTE");context.put("recoveryModelAllowed",false);
        context.put("priorSteps",List.copyOf(original.session().steps));
        // Report projections do not contain or authorize persisted execution steps.
        context.put("previousAtomicCheck",Map.of("agentReview",previous.get("agentReview")));
        RunSession session=new RunSession(current.model(),context,original.session().scope);
        AgentLedReview review=new AgentLedReview(current.checkpoint(),current.bids(),context,session,null);
        int before=current.model().operations.size();Map<String,Object> result=review.execute(new AgentInvestigator(){
            public boolean enabled(){return true;}public String identity(){return "must-not-reinvestigate";}
            public Map<String,Object> investigate(Map<String,Object> start,ToolHandler handler,Consumer<Map<String,Object>> event){
                throw new AssertionError("Local recovery must not restart SDK investigation");
            }
        });
        assertEquals(before,current.model().operations.size());assertEquals(0,session.modelCalls);
        assertEquals(true,((Map<?,?>)result.get("agentReview")).get("localRecomputeOnly"));return result;
    }

    @Test void unchangedLocalRecoveryRestoresApprovedSemanticResultFromPriorStepsOnly(){
        Fixture original=fixture(REQUIREMENT,PASS_BODY);Map<String,Object> previous=completedSemantic(original);
        Fixture current=fixture(REQUIREMENT,PASS_BODY);Map<String,Object> result=restore(original,current,previous);
        assertEquals("PASS",result.get("businessDecision"),result.toString());assertEquals(true,result.get("complete"));
        assertEquals(true,((Map<?,?>)decision(result,"emergency").get("proof")).get("restored"));
        assertTrue(current.model().operations.isEmpty());
    }

    @Test void changedBidOriginalCannotReuseAnEarlierSemanticPassDuringLocalRecovery(){
        Fixture original=fixture(REQUIREMENT,PASS_BODY);Map<String,Object> previous=completedSemantic(original);
        Fixture current=fixture(REQUIREMENT,FAIL_BODY);Map<String,Object> result=restore(original,current,previous);
        assertEquals("UNDETERMINED",result.get("businessDecision"));assertEquals(false,result.get("complete"));
        assertNotEquals("PASS",decision(result,"emergency").get("status"));assertTrue(current.model().operations.isEmpty());
    }

    @Test void changedTenderOriginalCannotRestorePreviousApprovedPlan(){
        Fixture original=fixture(REQUIREMENT,PASS_BODY);Map<String,Object> previous=completedSemantic(original);
        Fixture current=fixture(REQUIREMENT+"应急预案还应说明事故后的现场恢复安排。",PASS_BODY);
        Map<String,Object> result=restore(original,current,previous);
        assertEquals(false,result.get("complete"));assertEquals("UNDETERMINED",result.get("businessDecision"));
        assertTrue(RuleEvaluator.maps(result.get("gaps")).stream().anyMatch(g->"AGENT_PRIOR_PLAN_UNAVAILABLE".equals(g.get("code"))));
        assertTrue(current.model().operations.isEmpty());
    }
}
