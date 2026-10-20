package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SemanticCheckerTest {
    @Test void normalizedPrintedDateAndProspectiveAdvicePassButActualSigningDoesNot() throws Exception {
        var doc=synthetic("BID",List.of(new Page(1,"技术响应方案\n履约承诺书\n我方承诺遵守技术要求。\n投标人（盖章）：甲公司\n日期：2027年4月6日","PAGE","PDF","")));
        var claim=Map.of("kind","DATE","scope","CURRENT_PROJECT","text","履约承诺书签署日期","value","2027年4月6日","evidenceIds",List.of("C1"));
        reply=envelope(mapper.writeValueAsString(Map.of("status","PASS","reason","原文印刷日期已提取。","suggestion","建议检查要求位置，避免因未签章导致否决投标。","evidenceIds",List.of("C1"),"claims",List.of(claim))));
        var assessed=new SemanticChecker(url,"mock-secret","mock-model").assess(rule("打印日期","提取承诺书打印日期"),doc,null);
        assertEquals("PASS",assessed.evaluation().status(),assessed.evaluation().reason());
        assertTrue(assessed.audit().toString().contains("EXPLICIT_ASSERTED_FIELD"));
        reply=envelope(mapper.writeValueAsString(Map.of("status","PASS","reason","已签署履约承诺书。","suggestion","避免因未签章导致否决投标。","evidenceIds",List.of("C1"),"claims",List.of(claim))));
        assertEquals("UNDETERMINED",new SemanticChecker(url,"mock-secret","mock-model").evaluate(rule("打印日期","提取承诺书打印日期"),doc).status());
    }
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private String url;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private List<String> replySequence = List.of();
    private String redirectLocation = "";
    private String reply;
    private int status = 200;
    private long delayMillis;

    @BeforeEach void createMockService() throws Exception {
        reply = envelope("{\"status\":\"PASS\",\"reason\":\"原文明确描述了技术响应。\",\"suggestion\":\"保留原始材料。\",\"evidenceIds\":[\"C1\"]}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        HttpHandler handler = exchange -> {
            int attempt = calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(mapper.readTree(exchange.getRequestBody()));
            requests.add(request.get());
            if (delayMillis > 0) {
                try { Thread.sleep(delayMillis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            byte[] bytes = (attempt <= replySequence.size() ? replySequence.get(attempt - 1) : reply).getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (!redirectLocation.isEmpty()) exchange.getResponseHeaders().set("Location", redirectLocation);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        };
        server.createContext("/api/v1/services/aigc/text-generation/generation", handler);
        server.createContext("/compatible-mode/v1/chat/completions", handler);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/services/aigc/text-generation/generation";
    }

    @AfterEach void closeMockService() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test void sendsNativeDashScopeProtocolAndUsesOnlyOriginalEvidenceLocations() throws Exception {
        var checker = new SemanticChecker(url, "mock-secret", "mock-qwen");
        assertTrue(checker.enabled());
        var evaluation = checker.evaluate(checkpoint(), document());
        assertEquals("PASS", evaluation.status());
        assertEquals("DASHSCOPE:mock-qwen", evaluation.method());
        assertEquals("Bearer mock-secret", authorization.get());
        assertEquals("mock-qwen", request.get().path("model").asText());
        assertEquals("message", request.get().path("parameters").path("result_format").asText());
        assertEquals(0, request.get().path("parameters").path("temperature").asInt());
        assertTrue(request.get().path("parameters").has("enable_thinking"));
        assertFalse(request.get().path("parameters").path("enable_thinking").asBoolean());
        assertFalse(request.get().has("messages"));
        var messages = request.get().path("input").path("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).path("role").asText());
        assertTrue(messages.get(0).path("content").asText().contains("不可信数据"));
        JsonNode user = mapper.readTree(messages.get(1).path("content").asText());
        assertEquals("C1", user.path("candidates").get(0).path("id").asText());
        assertEquals(7, evaluation.evidences().get(0).page());
        assertEquals("BLOCK", evaluation.evidences().get(0).locationType());
        assertEquals("p7-l1", evaluation.evidences().get(0).anchor());
        assertTrue(document().pages().get(0).text().contains(evaluation.evidences().get(0).quote()));
    }

    @Test void compatibleProtocolUsesTopLevelMessagesAndOriginalEvidenceLocations() throws Exception {
        reply = compatibleEnvelope("{\"status\":\"PASS\",\"reason\":\"原文明确描述了技术响应。\",\"suggestion\":\"保留原始材料。\",\"evidenceIds\":[\"C1\"]}");
        var checker = new SemanticChecker(compatibleUrl() + "/?fixture=true", "mock-secret", "qwen3.8-max-0902");
        var result = checker.evaluate(checkpoint(), document());
        assertEquals("PASS", result.status());
        assertEquals("DASHSCOPE:qwen3.8-max-0902", result.method());
        assertEquals(1, calls.get());
        assertEquals("Bearer mock-secret", authorization.get());
        assertEquals("qwen3.8-max-0902", request.get().path("model").asText());
        assertFalse(request.get().has("input"));assertFalse(request.get().has("parameters"));
        assertTrue(request.get().has("enable_thinking"));assertFalse(request.get().path("enable_thinking").asBoolean());
        assertTrue(request.get().has("preserve_thinking"));assertFalse(request.get().path("preserve_thinking").asBoolean());
        assertEquals(2048, request.get().path("max_tokens").asInt());assertEquals(0, request.get().path("temperature").asInt());
        JsonNode messages = request.get().path("messages");assertEquals(2, messages.size());
        assertEquals("system", messages.path(0).path("role").asText());
        assertTrue(messages.path(0).path("content").asText().contains("不可信数据"));
        assertEquals("C1", mapper.readTree(messages.path(1).path("content").asText()).path("candidates").path(0).path("id").asText());
        assertEquals(7, result.evidences().get(0).page());assertEquals("BLOCK", result.evidences().get(0).locationType());
        assertEquals("p7-l1", result.evidences().get(0).anchor());
        assertEquals(document().pages().get(0).text(), result.evidences().get(0).quote());
    }

    @Test void compatibleRepairUsesSameProtocolModelAndBoundSources() throws Exception {
        replySequence = List.of(
            compatibleEnvelope("{\"status\":\"PASS\",\"reason\":\"原文支持。\",\"suggestion\":\"\",\"evidenceIds\":[\"FORGED\"]}"),
            compatibleEnvelope("{\"status\":\"PASS\",\"reason\":\"原文明确描述了技术响应。\",\"suggestion\":\"保留原始材料。\",\"evidenceIds\":[\"C1\"]}"));
        var result = new SemanticChecker(compatibleUrl(), "mock-secret", "qwen3.8-max-0902").evaluate(checkpoint(), document());
        assertEquals("PASS", result.status());assertEquals(2, calls.get());
        for (JsonNode sent : requests) {
            assertEquals("qwen3.8-max-0902", sent.path("model").asText());assertFalse(sent.has("input"));
            assertEquals(2, sent.path("messages").size());assertTrue(sent.has("enable_thinking"));assertFalse(sent.path("enable_thinking").asBoolean());
            assertTrue(sent.has("preserve_thinking"));assertFalse(sent.path("preserve_thinking").asBoolean());
        }
        assertEquals(requests.get(0).path("messages").path(0), requests.get(1).path("messages").path(0));
        String original = requests.get(0).path("messages").path(1).path("content").asText();
        String repaired = requests.get(1).path("messages").path(1).path("content").asText();
        assertTrue(repaired.startsWith(original + "\n修复"));assertTrue(repaired.contains("MODEL_CITATION_INVALID"));
        assertEquals(document().pages().get(0).text(), result.evidences().get(0).quote());
    }

    @Test void compatibleEnvelopeAndHttpFailuresDoNotLeakBodiesOrRetry() throws Exception {
        var checker = new SemanticChecker(compatibleUrl(), "mock-secret", "qwen3.8-max-0902");
        for (String invalid : List.of(
                envelope("{\"status\":\"PASS\"}"),
                compatibleEnvelope("{}").replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"length\""),
                mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", List.of(Map.of("text", "never-publish-body"))))))),
                "{invalid-never-publish-body}", "[]", "")) {
            reply = invalid;int before = calls.get();
            var result = checker.assess(checkpoint(), document(), null);
            assertEquals("UNDETERMINED", result.evaluation().status());assertEquals(before + 1, calls.get());
            assertFalse(mapper.writeValueAsString(result).contains("never-publish-body"));
        }
        status = 401;reply = "never-publish-body mock-secret";int before = calls.get();
        var failure = checker.assess(checkpoint(), document(), null);
        assertEquals("UNDETERMINED", failure.evaluation().status());assertEquals(before + 1, calls.get());
        String encoded = mapper.writeValueAsString(failure);assertFalse(encoded.contains("mock-secret"));assertFalse(encoded.contains("never-publish-body"));
    }

    @Test void compatibleProtocolKeepsCitationAndProductionClaimGuards() throws Exception {
        reply = compatibleEnvelope("{\"status\":\"PASS\",\"reason\":\"原文支持。\",\"suggestion\":\"\",\"evidenceIds\":[\"FORGED\"]}");
        var invalidCitation = new SemanticChecker(compatibleUrl(), "mock-secret", "qwen3.8-max-0902").evaluate(checkpoint(), document());
        assertEquals("UNDETERMINED", invalidCitation.status());assertTrue(invalidCitation.evidences().isEmpty());assertEquals(2, calls.get());
        reply = compatibleEnvelope("{\"status\":\"PASS\",\"reason\":\"原文明确描述了技术响应。\",\"suggestion\":\"保留原始材料。\",\"evidenceIds\":[\"C1\"]}");
        var missingClaims = new SemanticChecker(compatibleUrl(), "mock-secret", "qwen3.8-max-0902", new cn.bidcheck.retrieval.HybridRetriever())
            .assess(checkpoint(), document(), null);
        assertEquals("UNDETERMINED", missingClaims.evaluation().status());
        assertTrue(mapper.writeValueAsString(missingClaims.audit()).contains("MODEL_SCHEMA_INVALID"));
        assertTrue(missingClaims.evaluation().reason().contains("缺少逐项事实和引用映射"));
        assertEquals(4096, request.get().path("max_tokens").asInt());
        assertTrue(request.get().path("messages").path(0).path("content").asText().contains("生产核验还必须输出claims数组"));
    }

    @Test void compatibleRedirectDoesNotForwardAuthorizationOrFollowTarget() throws Exception {
        AtomicInteger followed = new AtomicInteger();
        server.createContext("/redirect-target", exchange -> { followed.incrementAndGet();exchange.sendResponseHeaders(200, -1);exchange.close(); });
        status = 307;reply = "never-publish-redirect";redirectLocation = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect-target";
        var result = new SemanticChecker(compatibleUrl(), "mock-secret", "qwen3.8-max-0902").assess(checkpoint(), document(), null);
        assertEquals("UNDETERMINED", result.evaluation().status());assertEquals(1, calls.get());assertEquals(0, followed.get());
        assertFalse(mapper.writeValueAsString(result).contains("mock-secret"));assertFalse(mapper.writeValueAsString(result).contains("never-publish-redirect"));
    }

    @Test void compatibleIdentityIsStableAcrossKeysAndDistinctFromNativeConfiguration() {
        var compatible = new SemanticChecker(compatibleUrl(), "mock-secret", "qwen3.8-max-0902");
        assertEquals(compatible.identity(), new SemanticChecker(compatibleUrl(), "changed-secret", "qwen3.8-max-0902").identity());
        assertNotEquals(compatible.identity(), new SemanticChecker(url, "mock-secret", "qwen3.8-max-0902").identity());
        assertTrue(compatible.identity().matches("DASHSCOPE:[0-9a-f]{64}"));assertEquals(0, calls.get());
    }

    private String compatibleUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1/chat/completions"; }
    private String compatibleEnvelope(String content) throws Exception {
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content)))));
    }

    @Test void recalledOcrEvidenceHasUnknownAccuracyWhileNativeEvidenceKeepsItsConfidence() {
        var checker = new SemanticChecker(url, "mock-secret", "mock-qwen");
        for (String parser : List.of("PDF_OCR", "POI_NATIVE_WITH_OCR", "POI_NATIVE")) {
            Document original = document();
            Page page = original.pages().get(0);
            Document input = new Document(original.id(), original.projectId(), original.role(), original.name(),
                    original.version(), original.sha256(), original.createdAt(),
                    List.of(new Page(page.number(), page.text(), page.locationType(), parser, "")), List.of());
            var evaluation = checker.evaluate(checkpoint(), input);
            assertEquals("PASS", evaluation.status());
            assertEquals(1, evaluation.evidences().size());
            var evidence = evaluation.evidences().get(0);
            assertEquals(parser.contains("OCR") ? 0d : 0.75d, evidence.confidence());
            assertEquals(input.id(), evidence.documentId());
            assertEquals(page.text(), evidence.quote());
            assertEquals(7, evidence.page());
            assertEquals("BLOCK", evidence.locationType());
            assertEquals("p7-l1", evidence.anchor());
        }
    }

    @Test void downgradesUnsupportedConclusionsAndForgedCitations() throws Exception {
        var checker = new SemanticChecker(url, "mock-secret", "mock-qwen");
        reply = envelope("{\"status\":\"PASS\",\"reason\":\"通过\",\"suggestion\":\"\",\"evidenceIds\":[]}");
        assertEquals("UNDETERMINED", checker.evaluate(checkpoint(), document()).status());
        reply = envelope("{\"status\":\"FAIL\",\"reason\":\"失败\",\"suggestion\":\"\",\"evidenceIds\":[\"C1\",\"FAKE_PAGE_999\"]}");
        var forged = checker.evaluate(checkpoint(), document());
        assertEquals("UNDETERMINED", forged.status());
        assertTrue(forged.evidences().isEmpty());
        assertTrue(forged.reason().contains("不存在的证据"));
    }

    @Test void malformedOutputHttpErrorsAndOversizedResponsesFailClosed() throws Exception {
        var checker = new SemanticChecker(url, "mock-secret", "mock-qwen");
        reply = envelope("not JSON");
        assertEquals("UNDETERMINED", checker.evaluate(checkpoint(), document()).status());
        reply = envelope("{\"status\":\"PASS\",\"reason\":\"通过\",\"suggestion\":\"\",\"evidenceIds\":[\"C1\"]} {\"extra\":true}");
        assertEquals("UNDETERMINED", checker.evaluate(checkpoint(), document()).status());
        status = 401;
        reply = "mock-secret should never appear in the reported reason";
        var failed = checker.evaluate(checkpoint(), document());
        assertEquals("UNDETERMINED", failed.status());
        assertTrue(failed.reason().contains("401"));
        assertFalse(failed.reason().contains("mock-secret"));
        status = 200;
        reply = "x".repeat(140 * 1024);
        assertEquals("UNDETERMINED", checker.evaluate(checkpoint(), document()).status());
    }

    @Test void unconfiguredInvalidAndUnconfirmedRequestsNeverConnect() {
        var missing = new SemanticChecker(url, "", "mock-qwen");
        assertFalse(missing.enabled());
        assertEquals("UNDETERMINED", missing.evaluate(checkpoint(), document()).status());
        var insecure = new SemanticChecker("http://example.com/api", "mock-secret", "mock-qwen");
        assertFalse(insecure.enabled());
        assertEquals("UNDETERMINED", insecure.evaluate(checkpoint(), document()).status());
        var malformed = new SemanticChecker("https://user:secret@example.com/api", "mock-secret", "mock-qwen");
        assertFalse(malformed.enabled());
        Checkpoint unconfirmed = new Checkpoint("id", "技术响应", "技术", "HIGH", "SEMANTIC_RESPONSE", "response", "", "技术方案满足要求", "", true, false, true, null);
        assertEquals("UNDETERMINED", new SemanticChecker(url, "mock-secret", "mock-qwen").evaluate(unconfirmed, document()).status());
        assertEquals(0, calls.get());
    }

    @Test void retrievalLimitsCandidateCountAndTotalPromptAndDoesNotCallOnNoMatches() throws Exception {
        List<Page> pages = new ArrayList<>();
        for (int i = 1; i <= 30; i++) pages.add(new Page(i, "技术响应方案\n" + "提供技术方案及服务响应内容。".repeat(180), "PAGE", "UTF8_TEXT", ""));
        Document large = new Document("doc", "project", "BID", "large.txt", 1, "hash", "now", pages, List.of());
        var checker = new SemanticChecker(url, "mock-secret", "mock-qwen");
        assertEquals("PASS", checker.evaluate(checkpoint(), large).status());
        var messages = request.get().path("input").path("messages");
        String system = messages.get(0).path("content").asText();
        String user = messages.get(1).path("content").asText();
        assertTrue(system.length() + user.length() <= 18_000);
        assertTrue(mapper.readTree(user).path("candidates").size() <= 8);
        Document unrelated = new Document("other", "project", "BID", "other.txt", 1, "hash", "now",
                List.of(new Page(1, "ABCDEFGHIJKLMN", "PAGE", "UTF8_TEXT", "")), List.of());
        assertEquals("UNDETERMINED", checker.evaluate(checkpoint(), unrelated).status());
        assertEquals(1, calls.get());
    }

    @Test void timesOutInsteadOfLeavingAConclusionWithoutAResponse() {
        delayMillis = 700;
        var checker = new SemanticChecker(url, "mock-secret", "mock-qwen", Duration.ofMillis(100));
        long start = System.nanoTime();
        var result = checker.evaluate(checkpoint(), document());
        assertEquals("UNDETERMINED", result.status());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(3)) < 0);
    }

    @Test void configurationIdentityChangesWithProviderAndModelAndNeverIncludesCredentials() {
        var checker = new SemanticChecker(url, "mock-secret", "mock-qwen");
        assertEquals("mock-qwen", checker.modelName());
        assertTrue(checker.identity().matches("DASHSCOPE:[0-9a-f]{64}"));
        assertFalse(checker.identity().contains("mock-secret"));
        assertEquals(checker.identity(), new SemanticChecker(url, "changed-key", "mock-qwen").identity());
        assertNotEquals(checker.identity(), new SemanticChecker(url, "mock-secret", "other-model").identity());
        assertNotEquals(checker.identity(), new SemanticChecker(url + "-other", "mock-secret", "mock-qwen").identity());
        assertEquals("DISABLED", new SemanticChecker(url, "", "mock-qwen").identity());
        assertEquals(0, calls.get());
    }

    private String envelope(String content) throws Exception {
        return mapper.writeValueAsString(Map.of("output", Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))))));
    }

    @Test void rejectsTruncatedModelCompletionAndDuplicateJsonFields() throws Exception {
        var checker = new SemanticChecker(url, "mock-secret", "mock-qwen");
        reply = envelope("{\"status\":\"PASS\",\"reason\":\"通过\",\"suggestion\":\"\",\"evidenceIds\":[\"C1\"]}")
                .replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"length\"");
        assertEquals("UNDETERMINED", checker.evaluate(checkpoint(), document()).status());
        reply = envelope("{\"status\":\"UNDETERMINED\",\"status\":\"PASS\",\"reason\":\"通过\",\"suggestion\":\"\",\"evidenceIds\":[\"C1\"]}");
        assertEquals("UNDETERMINED", checker.evaluate(checkpoint(), document()).status());
    }

    @Test void neverSilentlyTruncatesACheckpointRequirementBeforeJudgment() {
        Checkpoint oversized = new Checkpoint("id", "技术响应方案", "技术", "HIGH", "SEMANTIC_RESPONSE", "response", "",
                "技术要求".repeat(500) + "最后一条关键约束", "", true, true, true, null);
        var result = new SemanticChecker(url, "mock-secret", "mock-qwen").evaluate(oversized, document());
        assertEquals("UNDETERMINED", result.status());
        assertTrue(result.reason().contains("不能截断"));
        assertEquals(0, calls.get());
    }

    private static Checkpoint checkpoint() {
        return new Checkpoint("id", "技术响应方案", "技术", "HIGH", "SEMANTIC_RESPONSE", "response", "", "技术方案满足要求", "", true, true, true, null);
    }

    @Test void retainsHistoricalProjectContextAndExplicitEvidenceBoundaries() throws Exception {
        String history = "往年东城改造项目竣工人员表\n" + "历史项目说明。".repeat(330) + "\n专职安全员：示例甲；C3证书。";
        Document input = synthetic("BID", List.of(new Page(1, "西城新建工程\n投标文件", "PAGE", "PDFBOX_NATIVE", ""),
                new Page(2, history, "PAGE", "PDFBOX_NATIVE_WITH_OCR", "")));
        new SemanticChecker(url, "mock-secret", "mock-qwen").evaluate(rule("专职安全员C3证资格", "核对本项目拟派专职安全员有效C3证书"), input);
        JsonNode payload = userPayload();
        assertTrue(payload.path("documentOpening").toString().contains("西城新建工程"));
        boolean found = false;
        for (JsonNode candidate : payload.path("candidates")) {
            if (!candidate.path("quote").asText().contains("示例甲")) continue;
            found = true;
            assertTrue(candidate.path("pageOpening").asText().contains("往年东城改造项目"));
            assertTrue(history.contains(candidate.path("quote").asText()));
            assertTrue(candidate.path("textSource").asText().contains("OCR"));
        }
        assertTrue(found);
        String system = request.get().path("input").path("messages").get(0).path("content").asText();
        for (String boundary : List.of("历史业绩", "不同项目或岗位", "联合体各成员", "自填的索引", "简称", "主体等同", "真实签字盖章", "系统当前日期", "不能反推出", "跨版本冲突", "实际提交证据", "社保证明", "负责人委派", "内部重编号", "新义务", "未召回某个角色", "仅见牵头人", "自创免责解释", "完整身份证")) {
            assertTrue(system.contains(boundary), boundary);
        }
    }

    @Test void shortCertificateOutranksRepeatedSelfDeclaredIndexAndOtherProjectPersonnel() throws Exception {
        List<Page> pages = new ArrayList<>();
        for (int i = 1; i <= 12; i++) pages.add(new Page(i, "资格自查表及响应索引\n" +
                "专职安全员资格满足要求，承诺提供C3证书、人员表、人员配置及社保证明。".repeat(45), "PAGE", "PDFBOX_NATIVE", ""));
        pages.add(new Page(13, "历史项目合同协议书及人员业绩\n专职安全员：示例乙；C3证书；人员配置符合要求。", "PAGE", "PDFBOX_NATIVE", ""));
        pages.add(new Page(14, "安全生产考核合格证书\n专职安全员C3\n姓名：示例丙\n证书有效期：2024年1月1日至2027年1月1日", "PAGE", "PDFBOX_OCR", ""));
        new SemanticChecker(url, "mock-secret", "mock-qwen").evaluate(rule("专职安全员C3证资格", "本项目专职安全员应有有效C3证书及人员配置"), synthetic("BID", pages));
        JsonNode candidates = userPayload().path("candidates");
        assertEquals(14, candidates.get(0).path("location").asInt());
        assertTrue(candidates.size() <= 8);
    }

    @Test void currentAppointmentLinksSameNameProofsEvenWhenHistoryAndPromisesDominateKeywords() throws Exception {
        List<Page> pages = new ArrayList<>();
        for (int i = 1; i <= 12; i++) pages.add(new Page(i, "人员响应承诺书\n" +
                "项目负责人注册资格、查询、社保及有效证明完全响应，承诺提供。".repeat(45), "PAGE", "PDFBOX_NATIVE", ""));
        pages.add(new Page(31, "历史项目业绩情况表\n姓名：许青河\n拟在本合同任职 项目负责人\n注册资格及社保。", "PAGE", "PDFBOX_NATIVE", ""));
        pages.add(new Page(72, "项目负责人情况表\n姓 名 陈明远 年龄 42\n拟在本合同任职 项目负责人\n毕业学校 合成院校", "PAGE", "PDFBOX_NATIVE", ""));
        pages.add(new Page(93, "注册建造师证书\n姓名：陈明远\n聘用单位：示例公司\n注册专业：建筑工程\n有效期：2024至2027年", "PAGE", "PDFBOX_NATIVE_WITH_OCR", ""));
        pages.add(new Page(155, "证书查询结果\n姓名：陈明远\n证书编号：测试编号\n查询网址：https://example.test/check", "PAGE", "PDFBOX_NATIVE_WITH_OCR", ""));
        pages.add(new Page(207, "社会保险个人权益记录（单位职工缴费信息）\n姓名 缴费起止月份\n陈明远 2025年1月至3月\n许青河 2025年2月至4月", "PAGE", "PDFBOX_NATIVE_WITH_OCR", ""));
        Document input = synthetic("BID", pages);
        new SemanticChecker(url, "mock-secret", "mock-qwen").evaluate(
                rule("项目负责人注册资格", "本项目负责人应提供注册证书、查询截图及任意连续三个月社保证明；历史项目人员不得替代本次任职。"), input);
        JsonNode payload = userPayload();
        List<Integer> locations = new ArrayList<>();
        for (JsonNode candidate : payload.path("candidates")) {
            locations.add(candidate.path("location").asInt());
            String quote = candidate.path("quote").asText();
            assertTrue(pages.stream().anyMatch(page -> page.number() == candidate.path("location").asInt() && page.text().contains(quote)));
        }
        assertEquals(72, locations.get(0));
        assertTrue(locations.containsAll(List.of(93, 155, 207)), locations.toString());
        assertTrue(locations.size() <= 8);
        assertTrue(payload.path("personnelCoverage").path("unresolved").isEmpty());
        assertTrue(payload.path("personnelCoverage").path("boundary").asText().contains("不确认任职"));
    }

    @Test void corporateLeaderUsesCorporateFormWithoutInventingAProjectAppointmentRequirement() throws Exception {
        Document input = synthetic("BID", List.of(
                new Page(33, "2.施工企业主要负责人\n施工企业主要负责人情况表\n姓名：陈柏山\n拟在本合同任职 /\n担任示例公司法定代表人。", "PAGE", "PDFBOX_NATIVE", ""),
                new Page(58, "安全生产考核合格证书\n姓名：陈柏山\nA证 证书有效期：2024年至2027年", "PAGE", "PDFBOX_NATIVE", "")));
        new SemanticChecker(url, "mock-secret", "mock-qwen").evaluate(rule("施工企业主要负责人A证", "核对企业主要负责人身份及安全生产A证"), input);
        JsonNode payload = userPayload();
        assertEquals(33, payload.path("candidates").get(0).path("location").asInt());
        assertTrue(payload.path("relations").toString().contains("PERSON_CORPORATE_ROLE"));
        assertFalse(payload.path("relations").toString().contains("PERSON_ROLE_APPOINTMENT"));
        assertTrue(payload.path("personnelCoverage").path("unresolved").isEmpty());
    }

    @Test void fiveRolesKeepSeventeenMinimumSourcesWithinTheBoundedHybridContext() throws Exception {
        List<Page> pages = new ArrayList<>();
        pages.add(new Page(1, "示例综合改造工程\n投标文件", "PAGE", "PDFBOX_NATIVE", ""));
        pages.add(new Page(2, "联合体协议书\n示例综合改造工程\n示例甲有限公司为牵头人，示例乙有限公司负责设计。", "PAGE", "PDFBOX_NATIVE", ""));
        List<String> roles = List.of("项目负责人", "施工企业主要负责人", "施工部分负责人", "设计部分负责人", "专职安全员");
        List<String> names = List.of("陈云松", "王柏林", "李清河", "周明山", "吴林海");
        for (int i = 0; i < roles.size(); i++) {
            pages.add(new Page(10 + i, (i + 1) + "." + roles.get(i) + "\n" + roles.get(i) + "情况表\n姓名：" + names.get(i)
                    + "\n拟在本合同任职 " + (i == 1 ? "/" : roles.get(i)), "PAGE", "PDFBOX_NATIVE", ""));
            pages.add(new Page(30 + i, "社会保险个人权益记录\n姓名：" + names.get(i) + "\n缴费情况：2025年1月至3月", "PAGE", "PDFBOX_NATIVE", ""));
            pages.add(new Page(50 + i, "资格证书\n姓名：" + names.get(i) + "\n证书编号：示例编号\n有效期2027年", "PAGE", "PDFBOX_NATIVE", ""));
        }
        reply = envelope("{\"status\":\"UNDETERMINED\",\"reason\":\"候选仅供核对。\",\"suggestion\":\"\",\"evidenceIds\":[]}");
        new SemanticChecker(url, "mock-secret", "mock-qwen", new cn.bidcheck.retrieval.HybridRetriever()).evaluate(
                rule("人员配置及连续社保", String.join("、", roles) + "均需社会保险及有效证明文件"), synthetic("BID", pages));
        var payload = userPayload();
        List<Integer> locations = new ArrayList<>();
        for (var candidate : payload.path("candidates")) locations.add(candidate.path("location").asInt());
        assertEquals(17, locations.size());
        for (Page page : pages) assertTrue(locations.contains(page.number()), "Missing source " + page.number());
        assertTrue(payload.path("personnelCoverage").path("unresolved").isEmpty());
        assertTrue(payload.path("obligations").toString().contains("施工企业主要负责人企业岗位身份及"));
        assertFalse(payload.path("obligations").toString().contains("施工企业主要负责人本次任职"));
        var messages = request.get().path("input").path("messages");
        assertTrue(messages.get(0).path("content").asText().length() + messages.get(1).path("content").asText().length() <= 32000);
    }

    @Test void noAppointmentAnchorKeepsOriginalRetrievalInsteadOfInventingAPersonBinding() throws Exception {
        Document input = synthetic("BID", List.of(
                new Page(40, "历史项目情况表\n姓名：苏文海\n拟在本合同任职 专职安全员\nC3证书", "PAGE", "PDFBOX_NATIVE", ""),
                new Page(87, "安全生产考核合格证\n专职安全员C3\n姓名：陆云峰\n证书有效期：2024年至2027年", "PAGE", "PDFBOX_NATIVE_WITH_OCR", "")));
        new SemanticChecker(url, "mock-secret", "mock-qwen").evaluate(rule("专职安全员C3证资格", "本项目专职安全员应有有效C3证书及人员配置"), input);
        assertFalse(userPayload().has("personnelCoverage"));
        assertEquals(2, userPayload().path("candidates").size());
        assertTrue(userPayload().path("candidates").toString().contains("陆云峰"));
    }

    @Test void incompleteMultiRoleCoverageCannotBeReportedAsAllPersonnelCompliant() throws Exception {
        Document input = synthetic("BID", List.of(
                new Page(12, "项目负责人情况表\n姓名：姜柏林\n拟在本合同任职 项目负责人", "PAGE", "PDFBOX_NATIVE", ""),
                new Page(51, "社会保险个人权益记录\n姓名：姜柏林\n缴费情况：2025年1月至3月", "PAGE", "PDFBOX_NATIVE_WITH_OCR", ""),
                new Page(99, "人员承诺书\n施工部分负责人和专职安全员承诺提供所有社保证明。", "PAGE", "PDFBOX_NATIVE", "")));
        reply = envelope("{\"status\":\"PASS\",\"reason\":\"全部人员材料齐全且符合要求。\",\"suggestion\":\"\",\"evidenceIds\":[\"C1\"]}");
        var result = new SemanticChecker(url, "mock-secret", "mock-qwen").evaluate(
                rule("人员配置及社保", "项目负责人、施工部分负责人、专职安全员应提交本次任职表和社保证明。"), input);
        assertEquals("UNDETERMINED", result.status());
        assertTrue(result.reason().contains("未召回不等于原文件缺失"));
        assertTrue(result.reason().contains("已撤回"));
        String unresolved = userPayload().path("personnelCoverage").path("unresolved").toString();
        assertTrue(unresolved.contains("施工部分负责人"));
        assertTrue(unresolved.contains("专职安全员"));
        assertEquals(12, result.evidences().get(0).page());
    }

    @Test void conflictingAppointmentNamesRemainUnresolvedRatherThanChoosingOneAutomatically() throws Exception {
        Document input = synthetic("BID", List.of(
                new Page(16, "设计部分负责人情况表\n姓名：宋维宁\n拟在本合同任职 设计部分负责人", "PAGE", "PDFBOX_NATIVE", ""),
                new Page(18, "设计部分负责人情况表\n姓名：余书平\n拟在本合同任职 设计部分负责人", "PAGE", "PDFBOX_NATIVE", ""),
                new Page(73, "一级注册建筑师证书\n姓名：宋维宁\n注册单位：示例公司", "PAGE", "PDFBOX_NATIVE_WITH_OCR", "")));
        var result = new SemanticChecker(url, "mock-secret", "mock-qwen").evaluate(
                rule("设计负责人注册资格", "核对本次拟派设计部分负责人注册证书。"), input);
        assertEquals("UNDETERMINED", result.status());
        assertTrue(userPayload().path("personnelCoverage").path("unresolved").toString().contains("不同姓名任职线索"));
    }

    @Test void historicalSectionTitleOnPreviousPageDoesNotCreateACurrentAppointmentAnchor() throws Exception {
        Document input = synthetic("BID", List.of(
                new Page(4, "历史项目业绩\n往年改造工程", "PAGE", "PDFBOX_NATIVE", ""),
                new Page(5, "续页说明\n本节合同附件和证明材料继续列示。", "PAGE", "PDFBOX_NATIVE", ""),
                new Page(6, "项目负责人情况表\n姓名：许青河\n拟在本合同任职 项目负责人", "PAGE", "PDFBOX_NATIVE", ""),
                new Page(9, "注册建造师证书\n姓名：许青河\n注册单位：示例公司", "PAGE", "PDFBOX_NATIVE_WITH_OCR", "")));
        new SemanticChecker(url, "mock-secret", "mock-qwen").evaluate(rule("项目负责人注册资格", "本次负责人注册证书"), input);
        assertFalse(userPayload().has("personnelCoverage"));
    }

    @Test void titleNamingOneRoleAndOtherPersonnelStillReportsMissingExplicitOtherRoles() throws Exception {
        Document input = synthetic("BID", List.of(new Page(19,
                "项目负责人情况表\n姓名：林志川\n拟在本合同任职 项目负责人", "PAGE", "PDFBOX_NATIVE", "")));
        var result = new SemanticChecker(url, "mock-secret", "mock-qwen").evaluate(
                rule("项目负责人及其他人员社保", "项目负责人和专职安全员均需提交社保证明。"), input);
        assertEquals("UNDETERMINED", result.status());
        assertTrue(userPayload().path("personnelCoverage").path("unresolved").toString().contains("专职安全员"));
    }

    @Test void personnelBudgetTrimmingPreservesAnchorBeforeLinkedProofsAndNeverPromotesNameSubstring() throws Exception {
        List<Page> pages = new ArrayList<>();
        pages.add(new Page(42, "项目负责人情况表\n姓名：周明\n拟在本合同任职 项目负责人", "PAGE", "PDFBOX_NATIVE", ""));
        for (int i = 60; i < 70; i++) pages.add(new Page(i, "社会保险个人权益记录\n姓名：周明华\n缴费情况：2025年1月至3月\n" +
                "项目负责人社保要求说明。".repeat(110), "PAGE", "PDFBOX_NATIVE_WITH_OCR", ""));
        pages.add(new Page(121, "社会保险个人权益记录\n姓名：周明\n缴费情况：2025年1月至3月\n" + "缴纳信息。".repeat(250), "PAGE", "PDFBOX_NATIVE_WITH_OCR", ""));
        new SemanticChecker(url, "mock-secret", "mock-qwen").evaluate(
                rule("项目负责人社保证明", "本次项目负责人须提供社保缴费记录。" + "核对所属单位及连续月份。".repeat(120)), synthetic("BID", pages));
        JsonNode candidates = userPayload().path("candidates");
        assertEquals(42, candidates.get(0).path("location").asInt());
        assertEquals(121, candidates.get(1).path("location").asInt());
        assertTrue(candidates.size() <= 8);
        var messages = request.get().path("input").path("messages");
        assertTrue(messages.get(0).path("content").asText().length() + messages.get(1).path("content").asText().length() <= 18000);
    }

    @Test void actualHttpPromptAndAuditShareTheSameUnverifiedSocialHeaderRelation() throws Exception {
        String title = "合成地区社会保险个人权益记录(单位职工缴费信息)\n";
        Document input = synthetic("BID", List.of(
                new Page(10, "设计部分负责人情况表\n姓名：合成人员甲\n拟在本合同任职 设计部分负责人", "PAGE", "PDFBOX_NATIVE", ""),
                new Page(20, title + "单位名称：合成设计有限公司\n第1页共9页", "PAGE", "PDFBOX_NATIVE", ""),
                new Page(21, title + "姓名：合成人员甲\n缴费情况：养老保险2025年1月至6月\n第6页共9页", "PAGE", "PDFBOX_NATIVE", ""),
                new Page(22, title + "备注：供本人核验。\n第9页共9页", "PAGE", "PDFBOX_NATIVE", "")));
        reply = envelope("{\"status\":\"UNDETERMINED\",\"reason\":\"同份材料归属待核。\",\"suggestion\":\"核对原件。\",\"evidenceIds\":[]}");
        var assessment = new SemanticChecker(url, "mock-secret", "mock-qwen", new cn.bidcheck.retrieval.HybridRetriever())
                .assess(rule("设计负责人社会保险", "设计负责人需提供社会保险缴费记录"), input, null);
        JsonNode sent = userPayload();
        assertEquals(mapper.valueToTree(assessment.audit().get("relations")), sent.path("relations"));
        JsonNode header = null;
        for (JsonNode relation : sent.path("relations")) if ("PAGINATED_SOCIAL_INSURANCE_HEADER".equals(relation.path("type").asText())) header = relation;
        assertNotNull(header);assertFalse(header.path("attributes").path("membershipVerified").asBoolean(true));
        assertTrue(header.path("attributes").path("omittedPrintedPages").asBoolean());
        List<Integer> locations = new ArrayList<>();
        for(JsonNode id : header.path("sourceEvidenceIds")) for(JsonNode candidate : sent.path("candidates"))
            if(candidate.path("id").asText().equals(id.asText())) locations.add(candidate.path("location").asInt());
        assertTrue(locations.containsAll(List.of(20,21)), locations.toString());
    }

    @Test void dateExcerptsAcceptSeparateEndpointsWithoutInventingContinuityOrRequiringDuplicateValue() throws Exception {
        for (Map<String,Object> claim : List.<Map<String,Object>>of(
                Map.of("kind","DATE","scope","CURRENT_PROJECT","text","注册有效期为2024年5月13日至2026年5月12日","validityField","注册有效期","startDate","2024-05-13","endDate","2026-05-12","evidenceIds",List.of("C1")),
                Map.of("kind","DATE","scope","CURRENT_PROJECT","text","缴费起止年月为2024年9月至2025年8月，连续性另核","value","2024年09月至2025年08月","evidenceIds",List.of("C1")),
                Map.of("kind","DATE","scope","CURRENT_PROJECT","text","缴费起止年月摘录，连续性另核","startDate","2024-09","endDate","2025-08","evidenceIds",List.of("C1")))) {
            reply = envelope(mapper.writeValueAsString(Map.of("status","WARNING","reason","仅摘录原文起止范围，适用时点及连续性另核。","suggestion","核对原文。","evidenceIds",List.of("C1"),"claims",List.of(claim))));
            var assessment = new SemanticChecker(url,"mock-secret","mock-qwen").assess(rule("注册有效期与社会保险缴费起止","摘录注册有效期及社会保险起始年月和截止年月"),
                    synthetic("BID",List.of(new Page(1,"注册有效期：2024年05月13日至2026年05月12日\n社会保险个人权益记录\n起始年月 截止年月\n2024年9月 2025年8月", "PAGE","PDFBOX_NATIVE",""))),null);
            assertEquals("WARNING",assessment.evaluation().status(),assessment.evaluation().reason());
            assertTrue(mapper.valueToTree(assessment.audit()).path("guards").isEmpty());
        }
    }

    @Test void socialEmployerCannotBeSmuggledThroughDateOrTextClaims() throws Exception {
        for (Map<String,Object> claim : List.<Map<String,Object>>of(
                Map.of("kind","DATE","scope","CURRENT_PROJECT","text","合成人员甲在示例施工有限公司的社保缴费时间为2024年9月至2025年8月","value","2024年09月至2025年08月","evidenceIds",List.of("C1")),
                Map.of("kind","TEXT","scope","CURRENT_PROJECT","text","社保缴纳单位为示例施工有限公司","evidenceIds",List.of("C1")))) {
            reply = envelope(mapper.writeValueAsString(Map.of("status","WARNING","reason","社保单位需核对。","suggestion","核对原文。","evidenceIds",List.of("C1"),"claims",List.of(claim))));
            var assessment = new SemanticChecker(url,"mock-secret","mock-qwen").assess(rule("人员社保归属","核对单位原文"),
                    synthetic("BID",List.of(new Page(1,"社会保险缴费记录\n姓名：合成人员甲\n起始年月 截止年月\n2024年9月 2025年8月", "PAGE","PDFBOX_NATIVE",""))),null);
            assertEquals("UNDETERMINED",assessment.evaluation().status());
            assertTrue(assessment.evaluation().reason().contains("主体"));
            assertTrue(mapper.valueToTree(assessment.audit()).path("guards").toString().contains("CLAIM_GROUNDING_INVALID"));
        }
    }

    @Test void optionalConsecutiveMonthsDoNotRequireTheEarliestAllowedMonth() throws Exception {
        Checkpoint cp = rule("社保连续月份核对", "应提交2023年11月（含）至文件递交截止时间任意连续三个月社保证明。");
        Document input = synthetic("BID", List.of(new Page(1, "社会保险个人权益记录\n缴费起止：2024年1月至2024年3月\n缴费月数：3", "PAGE", "PDFBOX_NATIVE", "")));
        for (String[] explanation : List.of(
                new String[]{"社保未覆盖2023年11月，与要求存在偏差。", "核对记录。"},
                new String[]{"记录提供连续三个月缴费。", "建议补充2023年11月的缴费记录或特殊情况说明。"})) {
            reply = envelope(mapper.writeValueAsString(Map.of("status", "WARNING", "reason", explanation[0], "suggestion", explanation[1], "evidenceIds", List.of("C1"))));
            var assessment = new SemanticChecker(url, "mock-secret", "mock-qwen").assess(cp, input, null);
            assertEquals("WARNING", assessment.rawEvaluation().status());
            assertEquals("UNDETERMINED", assessment.evaluation().status());
            assertTrue(mapper.valueToTree(assessment.audit()).path("guards").toString().contains("OPTIONAL_PERIOD_MISINTERPRETED"));
            assertFalse(assessment.evaluation().suggestion().contains("建议补充2023年11月"));
        }
        reply = envelope(mapper.writeValueAsString(Map.of("status", "PASS", "reason", "2024年1月至3月在允许区间内连续三个月；不要求包含2023年11月。", "suggestion", "保留原文。", "evidenceIds", List.of("C1"))));
        var accepted = new SemanticChecker(url, "mock-secret", "mock-qwen").assess(cp, input, null);
        assertEquals("PASS", accepted.evaluation().status());
        assertTrue(mapper.valueToTree(accepted.audit()).path("guards").isEmpty());
        reply = envelope(mapper.writeValueAsString(Map.of("status", "WARNING", "reason", "所选月份早于2023年11月，需重新核对范围。", "suggestion", "核对实际月份。", "evidenceIds", List.of("C1"))));
        assertEquals("WARNING", new SemanticChecker(url, "mock-secret", "mock-qwen").assess(cp, input, null).evaluation().status());
    }

    @Test void suppliesOnlyExplicitTenderDateReferencesWithOriginalLocationsAndCannotPassOnThemAlone() throws Exception {
        Document tender = synthetic("TENDER", List.of(new Page(41,
                "编制日期：2025年9月1日\n投标截止时间：\n2025年8月5日09时00分\n开标时间：2025-08-05 09:00\n落款日期：2025年10月8日", "BLOCK", "WORD_NATIVE", "")));
        reply = envelope("{\"status\":\"WARNING\",\"reason\":\"版本日期存在冲突，缺少延期或递交证据。\",\"suggestion\":\"核实最终截止时间。\",\"evidenceIds\":[\"T1\",\"C1\"]}");
        var checker = new SemanticChecker(url, "mock-secret", "mock-qwen");
        var result = checker.evaluate(checkpoint(), document(), tender);
        assertEquals("WARNING", result.status());
        assertEquals(tender.id(), result.evidences().get(0).documentId());
        assertEquals(41, result.evidences().get(0).page());
        assertEquals("BLOCK", result.evidences().get(0).locationType());
        assertEquals("p41-l2", result.evidences().get(0).anchor());
        assertEquals("投标截止时间：\n2025年8月5日09时00分", result.evidences().get(0).quote());
        JsonNode candidates = userPayload().path("candidates");
        assertEquals("T1", candidates.get(0).path("id").asText());
        assertEquals("T2", candidates.get(1).path("id").asText());
        assertFalse(candidates.get(0).path("quote").asText().contains("编制日期"));
        reply = envelope("{\"status\":\"PASS\",\"reason\":\"时间符合。\",\"suggestion\":\"\",\"evidenceIds\":[\"T1\"]}");
        assertEquals("UNDETERMINED", checker.evaluate(checkpoint(), document(), tender).status());
        Document noDate = synthetic("TENDER", List.of(new Page(1, "投标截止时间：另行通知\n编制日期不作为截止基准", "BLOCK", "WORD_NATIVE", "")));
        checker.evaluate(checkpoint(), document(), noDate);
        assertEquals("C1", userPayload().path("candidates").get(0).path("id").asText());
    }

    @Test void preservesBothMemberLicensesBesideTenderReferencesAndBoundsContext() throws Exception {
        List<Page> pages = new ArrayList<>();
        for (int i = 1; i <= 15; i++) pages.add(new Page(i, "资格响应索引\n" +
                "联合体各成员主体登记与有效证照：全部满足，已登记注册且合法成立的独立法人。".repeat(65), "PAGE", "PDFBOX_NATIVE", ""));
        pages.add(new Page(16, "施工单位营业执照\n企业名称：合成甲公司\n类型：有限责任公司\n经营范围：建筑施工", "PAGE", "PDFBOX_OCR", ""));
        pages.add(new Page(17, "设计单位营业执照\n企业名称：合成乙公司\n类型：有限责任公司\n经营范围：工程设计", "PAGE", "PDFBOX_OCR", ""));
        Document tender = synthetic("TENDER", List.of(new Page(3, "投标截止时间：2025年8月5日\n开标时间：2025年8月5日", "BLOCK", "WORD_NATIVE", "")));
        new SemanticChecker(url, "mock-secret", "mock-qwen").evaluate(rule("联合体各成员主体登记与有效证照", "应分别提交各成员的有效营业执照或登记证书，核对名称及经营范围。"), synthetic("BID", pages), tender);
        List<Integer> actual = new ArrayList<>();
        for (JsonNode candidate : userPayload().path("candidates")) if (candidate.path("id").asText().startsWith("C")) actual.add(candidate.path("location").asInt());
        assertTrue(actual.containsAll(List.of(16, 17)));
        var messages = request.get().path("input").path("messages");
        assertTrue(messages.get(0).path("content").asText().length() + messages.get(1).path("content").asText().length() <= 18000);
        assertTrue(userPayload().path("candidates").size() <= 10);
    }

    @Test void withdrawsPositiveSealAndReviewAssertionsButPreservesExplicitUncertainty() throws Exception {
        var checker = new SemanticChecker(url, "mock-secret", "mock-qwen");
        for (String reason : List.of("投标函载有公章，符合要求。", "自填索引满足，已被评审认可。",
                "提供了加盖合成区税务机关公章的通知书。", "并加盖投标人公章及签署日期。", "已签署承诺书。", "提供了投标人签署的承诺书。")) {
            reply = envelope(mapper.writeValueAsString(Map.of("status", "PASS", "reason", reason, "suggestion", "", "evidenceIds", List.of("C1"))));
            assertEquals("UNDETERMINED", checker.evaluate(checkpoint(), document()).status());
        }
        for (String reason : List.of("无法确认原件载有公章，需人工查看图像。", "自填满足不等于已被评审认可。", "是否签章齐全尚未证实。",
                "无法验证是否加盖合成区税务机关公章。", "招标要求加盖投标人公章。", "未见原图，不能确认已签署。")) {
            reply = envelope(mapper.writeValueAsString(Map.of("status", "WARNING", "reason", reason, "suggestion", "核实原件。", "evidenceIds", List.of("C1"))));
            assertEquals("WARNING", checker.evaluate(checkpoint(), document()).status());
        }
    }

    @Test void withdrawsDefiniteDeadlineVerdictsWhileKeepingDateDifferencesAndUncertainty() throws Exception {
        var checker = new SemanticChecker(url, "mock-secret", "mock-qwen");
        for (String reason : List.of("签字页含无效日期（2024年10月9日，晚于招标截止日2024年8月6日）。",
                "到账时间远超投标截止日，违反强制性条款，其时间实质性不满足要求。",
                "回单日期在投标截止日之前，保证金缴纳及时。", "依据落款日期推断投标截止尚未到达，已按时递交。")) {
            reply = envelope(mapper.writeValueAsString(Map.of("status", "WARNING", "reason", reason, "suggestion", "", "evidenceIds", List.of("C1"))));
            var result = checker.evaluate(checkpoint(), document());
            assertEquals("UNDETERMINED", result.status(), reason);
            assertTrue(result.reason().contains("已撤回"));assertEquals(1, result.evidences().size());
            assertEquals(document().pages().get(0).text(), result.evidences().get(0).quote());
        }
        for (String reason : List.of("签署日期晚于招标文本中的截止日期，存在日期差异需核对最终版本。",
                "最终截止时间未确认，不能认定逾期废标。", "回单日期不等于到账日期，无法确认缴款及时。",
                "招标要求在截止日前到账，否则逾期废标；目前无法确认实际到账时间。",
                "日期格式无效，月份超出合法范围。", "该证书有效期为2024年1月至2027年1月。")) {
            reply = envelope(mapper.writeValueAsString(Map.of("status", "WARNING", "reason", reason, "suggestion", "核对原文。", "evidenceIds", List.of("C1"))));
            assertEquals("WARNING", checker.evaluate(checkpoint(), document()).status(), reason);
        }
    }

    @Test void guardsSuggestionsAndMasksFullIdentityNumbersWithoutChangingEvidence() throws Exception {
        var checker = new SemanticChecker(url, "mock-secret", "mock-qwen");
        reply = envelope(mapper.writeValueAsString(Map.of("status", "WARNING", "reason", "需核对原件。", "suggestion", "该承诺书已加盖投标人公章。", "evidenceIds", List.of("C1"))));
        assertEquals("UNDETERMINED", checker.evaluate(checkpoint(), document()).status());
        reply = envelope(mapper.writeValueAsString(Map.of("status", "WARNING", "reason", "签署版本存在疑点。", "suggestion", "若确为2024年10月补签，则该承诺无效，应按招标文件否决投标条款处理。", "evidenceIds", List.of("C1"))));
        var withdrawn = checker.evaluate(checkpoint(), document());
        assertEquals("UNDETERMINED", withdrawn.status());assertTrue(withdrawn.reason().contains("已撤回"));
        reply = envelope(mapper.writeValueAsString(Map.of("status", "WARNING", "reason", "签署版本存在疑点。", "suggestion", "若存在补签，应核实延期澄清与实际提交记录；无法据此认定承诺无效。", "evidenceIds", List.of("C1"))));
        assertEquals("WARNING", checker.evaluate(checkpoint(), document()).status());
        String first="000000200001010011",second="00000019991231002X";
        String original="技术响应方案\n合成人员甲身份证号："+first+"；合成人员乙身份证号："+second;
        Document input=synthetic("BID",List.of(new Page(7,original,"PAGE","PDFBOX_NATIVE","")));
        reply=envelope(mapper.writeValueAsString(Map.of("status","WARNING","reason","人员证号为"+first+"和"+second+"，角色归属需核对。","suggestion","核对证号"+first+"。","evidenceIds",List.of("C1"))));
        var result=checker.evaluate(checkpoint(),input);
        assertEquals("WARNING",result.status());
        assertFalse(result.reason().contains(first));assertFalse(result.reason().contains(second));assertFalse(result.suggestion().contains(first));
        assertTrue(result.reason().contains("000000********0011"));assertTrue(result.reason().contains("000000********002X"));
        assertEquals(original,result.evidences().get(0).quote());
    }

    @Test void interruptionCancelsWaitingAndPreservesInterruptFlag() throws Exception {
        delayMillis = 5000;
        var checker = new SemanticChecker(url, "mock-secret", "mock-qwen");
        AtomicReference<String> actual = new AtomicReference<>();
        Thread worker = new Thread(() -> { var result = checker.evaluate(checkpoint(), document()); actual.set(result.status() + ":" + Thread.currentThread().isInterrupted()); });
        worker.start();
        long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (calls.get() == 0 && System.nanoTime() < until) Thread.sleep(10);
        assertEquals(1, calls.get());
        worker.interrupt();worker.join(2000);
        assertFalse(worker.isAlive());
        assertEquals("UNDETERMINED:true", actual.get());
    }

    private JsonNode userPayload() throws Exception { return mapper.readTree(request.get().path("input").path("messages").get(1).path("content").asText()); }
    private static Checkpoint rule(String title, String expected) { return new Checkpoint("id", title, "资格", "HIGH", "SEMANTIC_RESPONSE", "", "", expected, "", true, true, true, null); }
    private static Document synthetic(String role, List<Page> pages) { return new Document(role + "-doc", "project", role, "synthetic.pdf", 1, "hash", "now", pages, List.of()); }

    private static Document document() {
        return new Document("doc", "project", "BID", "bid.docx", 1, "hash", "now",
                List.of(new Page(7, "技术响应方案\n本项目提供技术方案及服务响应内容。", "BLOCK", "POI_NATIVE", "")), List.of());
    }
}
