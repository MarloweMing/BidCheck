package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.ContractRecordSubmissionTest.*;
import static cn.bidcheck.verification.engine.ContractRecordAnchorTest.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class ContractSigningOriginalTest {
    private static final String MONTH="2023年5月";
    private Fixture prepared(String dateLine)throws Exception {
        Fixture f=new Fixture(identityBody()+"\n签订日期："+MONTH,"乙方："+COMPANY+"。合同金额：200万元。\n"+dateLine);
        f.read(3);
        List<Map<String,Object>> fields=Arrays.asList(amount(source(f.corpus,5)), linked("field","party","rawValue",COMPANY,
            "attributes",linked("party",COMPANY),"sourceIds",Collections.singletonList(source(f.corpus,5).id())));
        f.host.call("submit_candidate_fact",linked("targetObligationId",f.target,"submissionMode","CONTRACT_FIELDS",
            "fact",identity(f),"contractFields",fields)); return f;
    }

    private Map<String,Object> rejectedMonth(Fixture f,Map<String,Object> attrs) {
        return f.host.call("submit_candidate_fact",linked("targetObligationId",f.target,"submissionMode","CONTRACT_FIELDS",
            "fact",linked("kind","CONTRACT","field","signedDate","rawValue",MONTH,"attributes",attrs,
                "sourceIds",Collections.singletonList(source(f.corpus,4).id()))));
    }
    private Map<String,Object> row(Fixture f) {
        return RuleEvaluator.maps(new RuleEvaluator(new ArrayList<>(f.host.facts.values()),f.corpus,f.host.ctx)
            .evaluate(f.parent).proof().get("contractChecks")).stream().filter(x->x.containsKey("originalSigningDateChecks")).findFirst().get();
    }
    private Map<String,Object> observe(Fixture f,String value,String clarity) {
        Source src=source(f.corpus,5);
        return linked("type","FIELD","requestedTask","FIELD","requestedFields",Collections.singletonList("合同签订日期"),
            "obligationId",f.parent.id(),"requestedSourceIds",Collections.singletonList(src.id()),
            "documentId",src.evidence().documentId(),"location",5,"locationType","PAGE","imageHash","synthetic-image",
            "sourceBbox",Arrays.asList(10,10,180,36),"source",linked("documentId",src.evidence().documentId(),
                "documentHash",src.documentHash(),"documentVersion",src.documentVersion(),"page",5,"renderedSha256","synthetic-image",
                "renderedWidth",800,"renderedHeight",600),"readability",clarity,"label","合同签订日期","value",value);
    }

    @Test void omittedArrayIsRejectedAndOnlyItsBoundMonthIsConsumedByTextWork()throws Exception {
        Fixture f=prepared("此处未载完整签订日。");
        Map<String,Object> result=rejectedMonth(f,linked("contractNumber",NUMBER,"signedDate",MONTH));
        assertEquals("REJECTED",result.get("status"));
        Map<String,Object> rejection=RuleEvaluator.maps(result.get("rejections")).get(0);
        assertEquals("/contractFields",rejection.get("fieldPath"));assertEquals("/fact/rawValue",rejection.get("submittedValuePath"));
        Map<String,Object> plan=(Map<String,Object>)result.get("contractFieldWorkPlan");
        assertEquals("NO_EXECUTABLE_CORRECTION",plan.get("status"),plan::toString);
        assertFalse(f.host.facts.values().stream().anyMatch(x->"signedDate".equals(x.field())));
        result=rejectedMonth(f,linked("contractNumber",NUMBER,"signedDate",MONTH,"amount","200"));
        assertFalse(result.containsKey("protocolFieldRepair"));
        assertEquals("INCOMPLETE",((Map<?,?>)result.get("contractFieldWorkPlan")).get("status"));
        result=rejectedMonth(f,linked("contractNumber","another-contract","signedDate",MONTH));
        assertFalse(result.containsKey("protocolFieldRepair"));
    }

    @Test void independentFullSigningDayRepairsMissingEnvelopeThroughOrdinaryHost()throws Exception {
        Fixture f=prepared("9.签订时间\n本合同于2023年5月26日签订。");
        Map<String,Object> first=rejectedMonth(f,linked("contractNumber",NUMBER,"signedDate",MONTH));
        assertEquals("REJECTED",first.get("status"));
        Map<String,Object> status=linked("status","EVIDENCE_UPDATED","code","REPEATED_ACTION_WITHOUT_NEW_EVIDENCE","failedToolCount",0);
        assertEquals(1,f.host.applyContractFieldCorrections(status).size());
        assertTrue(f.host.facts.values().stream().anyMatch(x->"signedDate".equals(x.field())&&x.rawValue().equals("2023年5月26日")
            &&x.sourceIds().contains(source(f.corpus,5).id())));
        assertFalse(f.host.facts.values().stream().anyMatch(x->"signedDate".equals(x.field())&&MONTH.equals(x.rawValue())));
    }

    @Test void damagedSigningSentenceRequestsItsOwnImageAndConsumesVerifiedBoundDate()throws Exception {
        Fixture f=prepared("9.签订时间\n本合同于_202%年6月26日签订。");
        Map<String,Object> first=rejectedMonth(f,linked("contractNumber",NUMBER,"signedDate",MONTH));
        assertTrue(first.get("contractFieldWorkPlan").toString().contains("ORIGINAL_SIGNING_DATE_IMAGE_REQUIRED"));
        Source body=source(f.corpus,5);
        assertTrue(f.host.visualRequests.stream().anyMatch(r->Objects.equals(r.get("sourceIds"),Collections.singletonList(body.id()))
            && f.parent.id().equals(r.get("obligationId")) && Integer.valueOf(5).equals(r.get("location"))));
        Map<String,Object> image=observe(f,"2023年6月26日","CLEAR");
        f.host.ctx.put("visualObservations",Collections.singletonList(image));f.corpus.addVisualObservations(Collections.singletonList(image));
        Map<String,Object> check=row(f);
        assertEquals("CONFIRMED",((Map<?,?>)check.get("signingDateEvidence")).get("state"));
        assertTrue(((Map<?,?>)check.get("signingDateEvidence")).get("dates").toString().contains("2023-06-26"));
        assertTrue(RuleEvaluator.maps(check.get("originalSigningDateChecks")).stream().anyMatch(x->
            "SIGNING_MONTH_DIFFERS_FROM_ORIGINAL_DAY".equals(x.get("status"))));
        assertFalse(Boolean.TRUE.equals(check.get("counted")));
    }

    @Test void unreadableOrUnrelatedImageCannotSupplyCurrentContractSigningDay()throws Exception {
        for(String fault:Arrays.asList("wrongSource","wrongImageHash","wrongPage","unreadable")) {
            Fixture f=prepared("9.签订时间\n本合同于_202%年6月26日签订。");
            Map<String,Object> image=observe(f,"2023年6月26日","CLEAR");
            if("wrongSource".equals(fault)) image.put("requestedSourceIds",Collections.singletonList(source(f.corpus,4).id()));
            if("wrongImageHash".equals(fault)) image.put("imageHash","different");
            if("wrongPage".equals(fault)) ((Map<String,Object>)image.get("source")).put("page",4);
            if("unreadable".equals(fault))image.put("readability","UNCLEAR");
            f.host.ctx.put("visualObservations",Collections.singletonList(image));f.corpus.addVisualObservations(Collections.singletonList(image));
            Map<String,Object> check=row(f);
            assertEquals("NOT_FOUND",((Map<?,?>)check.get("signingDateEvidence")).get("state"),fault);
            String expected="unreadable".equals(fault)?"ORIGINAL_IMAGE_REQUIRES_MANUAL_REVIEW":"ORIGINAL_IMAGE_REQUIRED";
            assertEquals(expected,RuleEvaluator.maps(check.get("originalSigningDateChecks")).get(0).get("status"),fault);
        }
    }

    @Test void cachedProvisoAboutFinalEffectDoesNotInvalidateVisibleDateDigits()throws Exception {
        Fixture f=prepared("9.签订时间\n本合同于_202%年6月26日签订。");
        Map<String,Object> image=observe(f,"2023年6月26日","CLEAR");
        image.put("modelLimitations",Arrays.asList("仅依据当前图片内容识别",
            "未见完整合同封面或签署页全貌，无法确认该日期是否为最终生效签署日期",
            "手写部分‘2023年6月26日’虽清晰可辨，但未见对应签署人签字或盖章紧邻该日期以佐证其为正式签订时间"));
        image.put("constraintAssessment",linked("version","visual-field-admission-1","valueUsable",false,"relationUsable",false));
        assertFalse(cn.bidcheck.verification.source.VisualObservationPolicy.assess(image).valueUsable());
        f.host.ctx.put("visualObservations",Collections.singletonList(image));
        f.corpus.addVisualObservations(Collections.singletonList(image));
        Map<String,Object> check=row(f);
        assertEquals("CONFIRMED",((Map<?,?>)check.get("signingDateEvidence")).get("state"));
        Map<String,Object> reading=RuleEvaluator.maps(RuleEvaluator.maps(check.get("originalSigningDateChecks")).get(0).get("visualReadings")).get(0);
        assertEquals(false,((Map<?,?>)reading.get("admission")).get("relationUsable"));
        assertEquals(false,reading.get("provesContractEffectOrSignature"));
        assertFalse(Boolean.TRUE.equals(check.get("counted")));
    }

    @Test void ambiguousValueOrSourceStillBlocksASigningDateWhoseOldFlagWasUsable()throws Exception {
        for(String note:Arrays.asList("手写日期最后一位看不清，无法辨认","不能确认该日期是否为合同签订日期还是验收日期",
                "无法判断该日期归属哪份合同")) {
            Fixture f=prepared("9.签订时间\n本合同于_202%年6月26日签订。");
            Map<String,Object> image=observe(f,"2023年6月26日","CLEAR");
            image.put("constraintAssessment",linked("valueUsable",true));image.put("modelLimitations",Collections.singletonList(note));
            f.host.ctx.put("visualObservations",Collections.singletonList(image)); f.corpus.addVisualObservations(Collections.singletonList(image));
            assertEquals("NOT_FOUND",((Map<?,?>)row(f).get("signingDateEvidence")).get("state"),note);
        }
    }
}
