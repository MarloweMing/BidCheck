package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.verification.source.VisualObservationPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class NegativeMarkClassificationTest {
    private static final String PERSON = "王甲";
    private static final String COMPANY = "合成甲建设有限公司";

    @Test void explicitBlankWithSignerDisclaimerRemainsObservedAbsence() {
        for (String disclaimer : Arrays.asList(
                "无法确认是否由王甲签署", "无法确认是否由‘王甲’签署或他人代签",
                "无法确认王甲是否已签署", "无法确认是否已签署或由谁签署")) {
            Map<String,Object> observation = negative("SIGNATURE", PERSON,
                "签字处为空白横线，未见任何手写签名；" + disclaimer);
            assertEquals("NOT_OBSERVED", VisualMarkReview.outcome(observation), disclaimer);
        }
    }

    @Test void printedEmptyPlaceholderIsNotBlurredHandwriting() {
        Map<String,Object> observation = negative("SIGNATURE", PERSON,
            "图片中未观察到手写签名；（签字）：________为印刷占位符，非实际签名痕迹");
        assertEquals("NOT_OBSERVED", VisualMarkReview.outcome(observation));
        assertFalse(VisualObservationPolicy.assess(observation).valueUsable());
    }

    @Test void exactNegativeClosesTheBoundVisualLeafAsRiskNeverPass() {
        EvidenceCorpus corpus = corpus();
        Map<String,Object> observation = bound(corpus, "SIGNATURE", PERSON,
            "签字处为空白横线，未见手写签名；无法确认是否由王甲签署");
        RuleEvaluator evaluator = evaluator(corpus, observation);
        Decision decision = evaluator.evaluate(rule("SIGNATURE", PERSON));
        assertEquals("FAIL", decision.status(), decision.toString());
        assertTrue(decision.gaps().isEmpty());
        assertTrue(evaluator.visualRequests.isEmpty());
        assertEquals(false, decision.proof().get("automaticRejection"));
    }

    @Test void blankPlusActualBlurOrCropMustStayUncertain() {
        for (String restriction : Arrays.asList(
                "签字区域模糊，无法辨认", "签名笔迹不清晰", "签字位置被遮挡",
                "原图右侧被裁切", "签章处在裁剪区域外", "送检范围不全",
                "可见签名痕迹但无法辨认姓名")) {
            Map<String,Object> observation = negative("SIGNATURE", PERSON,
                "签字处为空白横线，未见手写签名；无法确认是否由王甲签署", restriction);
            assertEquals("UNREADABLE", VisualMarkReview.outcome(observation), restriction);
        }
    }

    @Test void unknownRestrictionsAreNotExplainedAwayByBlankText() {
        for (String restriction : Arrays.asList(
                "无法确定本次结果是否适用", "图像内容可能经过修改，无法确认完整性",
                "无法确认是否由王甲签署且位置可能错误", "无法确定本次结果是否适用，未见签名，签字字段是印刷占位符，非实际签名痕迹")) {
            Map<String,Object> observation = negative("SIGNATURE", PERSON,
                "签字处为空白横线，未见手写签名", restriction);
            assertEquals("UNREADABLE", VisualMarkReview.outcome(observation), restriction);
        }
    }

    @Test void disclaimerAloneDoesNotEstablishAnEmptySigningPosition() {
        assertEquals("UNREADABLE", VisualMarkReview.outcome(
            negative("SIGNATURE", PERSON, "无法确认是否由王甲签署")));
        assertEquals("UNREADABLE", VisualMarkReview.outcome(
            negative("SIGNATURE", PERSON, "签名值为占位符")));
    }

    @Test void physicalUncertaintyAndPlaceholderInOneClauseRemainUnreadable() {
        for (String uncertainty : Arrays.asList("不清", "不详", "未完全清晰")) {
            Map<String,Object> observation = negative("SIGNATURE", PERSON,
                "签字区域" + uncertainty + "，未见签名，签字字段是印刷占位符，非实际签名痕迹");
            assertEquals("UNREADABLE", VisualMarkReview.outcome(observation), uncertainty);
        }
    }

    @Test void nativeUnreadableAndPartialStatusesNeverBecomeNegative() {
        for (String status : Arrays.asList("UNREADABLE", "PARTIAL")) {
            Map<String,Object> observation = negative("SIGNATURE", PERSON,
                "签字处为空白横线，未见手写签名；无法确认是否由王甲签署");
            observation.put("readability", status);
            assertEquals(status, VisualMarkReview.outcome(observation));
        }
    }

    @Test void printedSealTextNeverBecomesAPositiveMark() {
        EvidenceCorpus corpus = corpus();
        Map<String,Object> observation = bound(corpus, "SEAL", COMPANY,
            "未见实际印章，仅为印刷文字");
        observation.put("readability", "CLEAR");
        observation.put("value", COMPANY);
        observation.put("observedValue", COMPANY);
        observation.put("sourceBbox", Arrays.asList(1, 1, 20, 20));
        assertFalse(VisualObservationPolicy.assess(observation).valueUsable());
        assertNotEquals("PASS", evaluator(corpus, observation).evaluate(rule("SEAL", COMPANY)).status());
    }

    @Test void changedSourceVersionHashTaskAndRoiCannotSupplyNegative() {
        for (String change : Arrays.asList("version", "hash", "roi", "task", "source", "subject")) {
            EvidenceCorpus corpus = corpus();
            Map<String,Object> observation = bound(corpus, "SIGNATURE", PERSON,
                "签字处为空白横线，未见手写签名；无法确认是否由王甲签署");
            @SuppressWarnings("unchecked") Map<String,Object> source =
                (Map<String,Object>) observation.get("source");
            if (change.equals("version")) source.put("documentVersion", 2);
            if (change.equals("hash")) source.put("documentHash", "stale");
            if (change.equals("roi")) source.put("roiApplied", true);
            if (change.equals("task")) observation.put("requestedTask", "SEAL_VISIBILITY");
            if (change.equals("source")) observation.put("requestedSourceIds", Collections.singletonList("other"));
            if (change.equals("subject")) observation.put("requestedFields", Collections.singletonList("李乙"));
            assertNotEquals("FAIL", evaluator(corpus, observation)
                .evaluate(rule("SIGNATURE", PERSON)).status(), change);
        }
    }

    @Test void aClearCounterObservationStillPreventsAutomaticAbsence() {
        EvidenceCorpus corpus = corpus();
        Map<String,Object> negative = bound(corpus, "SIGNATURE", PERSON,
            "签字处为空白横线，未见手写签名；无法确认是否由王甲签署");
        Map<String,Object> positive = bound(corpus, "SIGNATURE", PERSON);
        positive.put("id", "clear-other");
        positive.put("readability", "CLEAR");
        positive.put("value", PERSON);
        positive.put("observedValue", PERSON);
        positive.put("sourceBbox", Arrays.asList(1, 1, 20, 20));
        RuleEvaluator evaluator = new RuleEvaluator(Collections.emptyList(), corpus,
            linked("visualObservations", Arrays.asList(negative, positive)));
        Decision decision = evaluator.evaluate(rule("SIGNATURE", PERSON));
        assertEquals("UNDETERMINED", decision.status());
        assertEquals("VISUAL_MARK_OBSERVATION_CONFLICT", decision.gaps().get(0).code());
    }

    private static Map<String,Object> negative(String type, String label, String... notes) {
        return linked("type", type, "label", label, "value", "", "observedValue", "",
            "readability", "NOT_OBSERVED", "modelLimitations", Arrays.asList(notes));
    }

    private static EvidenceCorpus corpus() {
        List<Page> pages = Arrays.asList(
            new Page(1, "投标文件\n项目名称：合成装修工程\n投标人：" + COMPANY, "PAGE", "TEXT", ""),
            new Page(2, "本项目授权委托书\n项目名称：合成装修工程\n投标人：" + COMPANY
                + "\n法定代表人：" + PERSON + "\n签字：________", "PAGE", "TEXT", ""));
        return new EvidenceCorpus(Collections.singletonList(new Document(
            "bid", "p", "BID", "synthetic.pdf", 1, "sha", "", pages, Collections.emptyList())));
    }

    private static Rule rule(String type, String subject) {
        return new Rule("mark", "授权委托书签章位置", "VISUAL", subject,
            type.equals("SEAL") ? "seal" : "signature", "", "", "",
            linked("documentType", "授权委托书", "visualType", type),
            "须在指定位置签署", "", Collections.emptyList());
    }

    private static Map<String,Object> bound(EvidenceCorpus corpus, String type,
            String subject, String... notes) {
        Source original = corpus.all.stream().filter(s -> s.evidence().page() == 2)
            .findFirst().get();
        Map<String,Object> observation = negative(type, subject, notes);
        observation.putAll(linked("id", "negative", "obligationId", "mark",
            "documentId", "bid", "location", 2, "locationType", "PAGE", "imageHash", "img",
            "requestedTask", type + "_VISIBILITY", "requestedFields", Collections.singletonList(subject),
            "requestedSourceIds", Collections.singletonList(original.id()),
            "source", linked("documentId", "bid", "documentHash", "sha", "documentVersion", 1,
                "page", 2, "renderedSha256", "img", "renderedWidth", 100, "renderedHeight", 100,
                "roiApplied", false, "transform", linked("cropX", 0, "cropY", 0,
                    "cropWidth", 100, "cropHeight", 100))));
        return observation;
    }

    private static RuleEvaluator evaluator(EvidenceCorpus corpus, Map<String,Object> observation) {
        return new RuleEvaluator(Collections.emptyList(), corpus,
            linked("visualObservations", Collections.singletonList(observation)));
    }
}
