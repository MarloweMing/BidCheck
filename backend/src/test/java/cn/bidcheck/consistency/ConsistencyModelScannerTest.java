package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.verification.engine.ModelGateway;
import cn.bidcheck.verification.engine.VerificationModelClient.ModelRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConsistencyModelScannerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void traversesAllSevenHundredPagesIncludingTailInsteadOfRetrievalTopK() {
        List<Page> pages = new ArrayList<>();
        for (int i = 1; i <= 700; i++) pages.add(page(i, "本次投标人名称：企业" + i));
        AtomicInteger calls = new AtomicInteger();
        List<Integer> positions = new ArrayList<>();
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            calls.incrementAndGet();
            List<Map<String,Object>> facts = new ArrayList<>();
            for (Map<String,Object> source : sources(input)) {
                String quote = source.get("quote").toString();
                String company = quote.substring(quote.indexOf('：') + 1);
                facts.add(fact(source, "companyName", company, quote));
                positions.add(Integer.parseInt(company.substring(2)));
            }
            return response(facts);
        }));
        var result = scanner.scan(document(pages));
        assertEquals(700, result.facts().size());
        assertEquals(700, result.coverage().get("scannedPages"));
        assertEquals(0, result.coverage().get("unscannedPages"));
        assertEquals(true, result.coverage().get("complete"));
        assertEquals(false, result.coverage().get("conclusive"));
        assertEquals(700, positions.get(699));
        assertTrue(calls.get() > 1);
        assertTrue(result.facts().stream().allMatch(f -> Boolean.FALSE.equals(f.get("contextVerified"))));
        assertTrue(result.facts().stream().anyMatch(f -> ((Evidence) f.get("evidence")).page() == 700));
    }

    @Test void splitsLongPagesWithoutGapsAndKeepsTailCitationAtOriginalOffset() {
        String tail = "本次投标人名称：北京尾部公司";
        String text = "原始长段落文字。".repeat(2_000) + "\n" + tail;
        List<int[]> ranges = new ArrayList<>();
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            List<Map<String,Object>> facts = new ArrayList<>();
            for (Map<String,Object> source : sources(input)) {
                int start = ((Number) source.get("startOffset")).intValue();
                int end = ((Number) source.get("endOffset")).intValue();
                ranges.add(new int[]{start, end});
                assertEquals(text.substring(start, end), source.get("quote"));
                if (source.get("quote").toString().contains(tail)) facts.add(fact(source, "companyName", "北京尾部公司", tail));
            }
            return response(facts);
        }), 100, 1_000, 100);
        var result = scanner.scan(document(List.of(new Page(9, text, "BLOCK", "POI_NATIVE", ""))));
        int right = 0;
        for (int[] range : ranges) { assertTrue(range[0] <= right); right = Math.max(right, range[1]); }
        assertEquals(text.length(), right);
        assertEquals((long) text.length(), result.coverage().get("scannedCharacters"));
        assertEquals(true, result.coverage().get("complete"));
        assertEquals(1, result.facts().size());
        Evidence evidence = (Evidence) result.facts().get(0).get("evidence");
        assertEquals(9, evidence.page());
        assertEquals("BLOCK", evidence.locationType());
        assertEquals(tail, evidence.quote());
        assertEquals("p9-l2-c" + text.indexOf(tail), evidence.anchor());
        assertEquals(text.indexOf(tail), result.facts().get(0).get("sourceOffset"));
    }

    @Test void bindsIdentityToBackendAndRejectsFabricatedSourcesQuotesAndFields() {
        String quote = "本次投标总报价：40万元（含税）";
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            Map<String,Object> source = sources(input).get(0);
            Map<String,Object> valid = fact(source, "totalPrice", "40万元", quote);
            valid.put("value", "400000"); valid.put("documentId", "foreign-document"); valid.put("page", 999);
            valid.put("id", "model-controlled-id"); valid.put("version", 999);
            List<Map<String,Object>> facts = new ArrayList<>(); facts.add(valid);
            Map<String,Object> wrongSource = fact(source, "totalPrice", "40万元", quote);
            wrongSource.put("sourceIds", List.of("foreign-source")); facts.add(wrongSource);
            facts.add(fact(source, "totalPrice", "400000元", quote));
            facts.add(fact(source, "totalPrice", "40万元", "报价：40万元，保证与原文一致"));
            facts.add(fact(source, "bankAccount", "40万元", quote));
            return response(facts);
        }));
        var result = scanner.scan(document(List.of(page(3, quote))));
        assertEquals(1, result.facts().size());
        Map<String,Object> accepted = result.facts().get(0);
        assertEquals("40万元", accepted.get("value"));
        assertTrue(accepted.get("id").toString().startsWith("cm-"));
        assertNotEquals("model-controlled-id", accepted.get("id"));
        Evidence evidence = (Evidence) accepted.get("evidence");
        assertEquals("document-id", evidence.documentId());
        assertEquals("投标文件.pdf", evidence.documentName());
        assertEquals(3, evidence.page());
        assertEquals(4, result.coverage().get("rejectedFacts"));
        assertEquals(false, result.coverage().get("complete"));
    }

    @Test void ungroundedContextCannotCreateProjectOrPersonnelIdentity() {
        String quote = "项目经理：张三";
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            Map<String,Object> value = fact(sources(input).get(0), "personName", "张三", quote);
            value.put("subject", "虚构主体");
            value.put("attributes", Map.of("quote", quote, "contextQuote", "本次投标确定由张三负责", "lot", "第二标段", "basis", "含税", "time", "2026-10-13"));
            return response(List.of(value));
        }));
        Map<String,Object> accepted = scanner.scan(document(List.of(page(1, quote)))).facts().get(0);
        assertEquals("UNRESOLVED", accepted.get("scope"));
        for (String key : List.of("subject", "lot", "basis", "time", "contextQuote")) assertEquals("", accepted.get(key));
        assertEquals(false, accepted.get("contextVerified"));
    }

    @Test void samePageContextCannotBorrowScopeOrAttributesFromAnotherFact() {
        String current = "本次投标人：甲公司，第一标段，含税，2026年10月13日。";
        String historical = "历史合同供货方：乙公司。";
        String text = current + "\n" + historical;
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            Map<String,Object> value = fact(sources(input).get(0), "companyName", "乙公司", "乙公司");
            value.put("subject", "甲公司");
            value.put("attributes", Map.of("quote", "乙公司", "contextQuote", current,
                    "lot", "第一标段", "basis", "含税", "time", "2026年10月13日"));
            return response(List.of(value));
        }));
        Map<String,Object> accepted = scanner.scan(document(List.of(page(1, text)))).facts().get(0);
        assertEquals("UNRESOLVED", accepted.get("scope"));
        assertEquals("乙公司", accepted.get("rawValue"));
        for (String key : List.of("subject", "lot", "basis", "time", "contextQuote")) assertEquals("", accepted.get(key));
    }

    @Test void historicalContractContextMustContainTheFactAndCannotProveCurrentScope() {
        for (String marker : List.of("历史合同", "过往合同", "往期合同", "已完成合同")) {
            String historical = marker + "供货方：乙公司。";
            String text = "本次投标人：甲公司。\n" + historical;
            var scanner = new ConsistencyModelScanner(gateway(input -> {
                Map<String,Object> source = sources(input).get(0);
                Map<String,Object> history = fact(source, "companyName", "乙公司", "乙公司");
                history.put("scope", "HISTORICAL_PROJECT");
                history.put("attributes", Map.of("quote", "乙公司", "contextQuote", historical));
                Map<String,Object> currentClaim = fact(source, "companyName", "乙公司", historical);
                currentClaim.put("attributes", Map.of("quote", historical, "contextQuote", text));
                return response(List.of(history, currentClaim));
            }));
            var result = scanner.scan(document(List.of(page(1, text))));
            assertEquals(2, result.facts().size());
            assertEquals("HISTORICAL_PROJECT", result.facts().get(0).get("scope"));
            assertEquals(historical, result.facts().get(0).get("contextQuote"));
            assertEquals("UNRESOLVED", result.facts().get(1).get("scope"));
        }
    }

    @Test void failedBatchDoesNotPreventScanningLaterPagesOrLeakFailureDetails() {
        AtomicInteger calls = new AtomicInteger();
        String secret = "Authorization Bearer secret-never-expose https://private.example";
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            if (calls.incrementAndGet() == 1) throw new IOException(secret);
            Map<String,Object> source = sources(input).get(0);
            return response(List.of(fact(source, "companyName", "尾部公司", "本次投标人名称：尾部公司")));
        }), 10, 512, 100);
        List<Page> pages = List.of(page(1, "首".repeat(400)), page(2, "本次投标人名称：尾部公司" + "文".repeat(380)),
                page(3, "本次投标人名称：尾部公司" + "文".repeat(380)));
        var result = scanner.scan(document(pages));
        assertEquals(3, calls.get());
        assertEquals(2, result.facts().size());
        assertEquals(2, result.coverage().get("scannedPages"));
        assertEquals(1, result.coverage().get("failedChunks"));
        assertEquals(false, result.coverage().get("complete"));
        assertFalse(result.coverage().toString().contains(secret));
        assertFalse(result.coverage().toString().contains("private.example"));
    }

    @Test void callBudgetEnumeratesEveryRemainingLocationAndExactUnscannedCharacters() {
        List<Page> pages = new ArrayList<>();
        for (int i = 1; i <= 5; i++) pages.add(page(i, "文".repeat(400)));
        AtomicInteger calls = new AtomicInteger();
        var scanner = new ConsistencyModelScanner(gateway(input -> { calls.incrementAndGet(); return response(List.of()); }), 2, 512, 100);
        var result = scanner.scan(document(pages));
        assertEquals(2, calls.get());
        assertEquals(5, result.coverage().get("totalPages"));
        assertEquals(2, result.coverage().get("scannedPages"));
        assertEquals(3, result.coverage().get("unscannedPages"));
        assertEquals(1_200L, result.coverage().get("unscannedCharacters"));
        assertTrue(result.coverage().get("reasons").toString().contains("CALL_BUDGET"));
        List<?> locations = (List<?>) result.coverage().get("locations");
        assertEquals(5, locations.size());
        assertEquals("UNSCANNED", ((Map<?,?>) locations.get(4)).get("status"));
    }

    @Test void disabledGatewayAndUnreadablePagesNeverClaimCompleteOrPass() {
        var scanner = new ConsistencyModelScanner(ModelGateway.disabled());
        var result = scanner.scan(document(List.of(page(1, "本次投标人名称：某公司"), page(2, ""))));
        assertTrue(result.facts().isEmpty());
        assertEquals(false, result.coverage().get("enabled"));
        assertEquals(false, result.coverage().get("complete"));
        assertEquals(false, result.coverage().get("conclusive"));
        assertEquals(0, result.coverage().get("attemptedBatches"));
        assertEquals(2, result.coverage().get("unscannedPages"));
        assertTrue(result.coverage().get("reason").toString().contains("未配置"));
        assertFalse(result.coverage().toString().contains("PASS"));
    }

    @Test void malformedOutputDoesNotCountAsSuccessfulScan() {
        var scanner = new ConsistencyModelScanner(gateway(input -> JSON.valueToTree(Map.of("facts", "PASS", "status", "PASS"))));
        var result = scanner.scan(document(List.of(page(1, "本次投标人名称：某公司"))));
        assertTrue(result.facts().isEmpty());
        assertEquals(0, result.coverage().get("scannedChunks"));
        assertEquals(1, result.coverage().get("failedChunks"));
        assertFalse(result.coverage().toString().contains("PASS"));
    }

    @Test void observersCanFencePaidRequestsAndInterruptedRequestsStopFurtherCalls() {
        AtomicInteger calls = new AtomicInteger();
        var scanner = new ConsistencyModelScanner(gateway(input -> { calls.incrementAndGet(); return response(List.of()); }));
        assertThrows(IllegalStateException.class, () -> scanner.scan(document(List.of(page(1, "原文"))), message -> { throw new IllegalStateException("lease lost"); }));
        assertEquals(0, calls.get());
        var interrupted = new ConsistencyModelScanner(gateway(input -> { calls.incrementAndGet(); throw new InterruptedIOException("interrupted"); }), 10, 512, 100);
        try {
            var result = interrupted.scan(document(List.of(page(1, "文".repeat(400)), page(2, "文".repeat(400)))));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, calls.get());
            assertEquals(2, result.coverage().get("unscannedPages"));
            assertTrue(result.coverage().get("reasons").toString().contains("INTERRUPTED"));
        } finally { Thread.interrupted(); }
    }

    @Test void factBudgetIsReportedAndIdentityChangesWithPolicyBudgets() {
        ModelGateway gateway = gateway(input -> {
            Map<String,Object> source = sources(input).get(0);
            return response(List.of(fact(source, "personName", "张三", "本次投标项目经理：张三"),
                    fact(source, "personName", "李四", "本次投标技术负责人：李四")));
        });
        var scanner = new ConsistencyModelScanner(gateway, 10, 512, 1);
        var result = scanner.scan(document(List.of(page(1, "本次投标项目经理：张三\n本次投标技术负责人：李四"))));
        assertEquals(1, result.facts().size());
        assertEquals(false, result.coverage().get("complete"));
        assertTrue(result.coverage().get("reasons").toString().contains("FACT_BUDGET"));
        assertEquals(scanner.identity(), new ConsistencyModelScanner(gateway, 10, 512, 1).identity());
        assertNotEquals(scanner.identity(), new ConsistencyModelScanner(gateway, 11, 512, 1).identity());
        assertNotEquals(scanner.identity(), new ConsistencyModelScanner(gateway, 10, 1024, 1).identity());
    }

    @Test void leaseLossAndExplicitGatewayCancellationAreNeverSwallowed() {
        var lost = new ConsistencyModelScanner(gateway(input -> { throw new cn.bidcheck.infrastructure.queue.TaskLeaseLostException("task-id"); }));
        assertThrows(cn.bidcheck.infrastructure.queue.TaskLeaseLostException.class,
                () -> lost.scan(document(List.of(page(1, "本次投标人名称：某公司")))));
        var cancelled = new ConsistencyModelScanner(gateway(input -> { throw new java.util.concurrent.CancellationException("cancelled"); }));
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> cancelled.scan(document(List.of(page(1, "本次投标人名称：某公司")))));
    }

    @Test void retryRestoresSuccessfulBatchesWithoutRepeatingPaidRequests() {
        AtomicInteger calls = new AtomicInteger();
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            calls.incrementAndGet();
            return response(List.of(fact(sources(input).get(0), "companyName", "某公司", "本次投标人名称：某公司")));
        }), 5, 512, 100);
        MemoryCache cache = new MemoryCache();
        Document document = document(List.of(page(1, "本次投标人名称：某公司" + "文".repeat(380)),
                page(2, "本次投标人名称：某公司" + "文".repeat(380))));
        var first = scanner.scan(document, null, cache);
        var retry = scanner.scan(document, null, cache);
        assertEquals(2, calls.get());
        assertEquals(2, cache.reservations);
        assertEquals(first.facts(), retry.facts());
        assertEquals(0, retry.coverage().get("attemptedBatches"));
        assertEquals(2, retry.coverage().get("reusedBatches"));
        assertEquals(true, retry.coverage().get("complete"));
    }

    @Test void failedRequestsCountTowardPersistentBudgetButLaterCachedBatchesRemainAvailable() {
        AtomicInteger calls = new AtomicInteger();
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            if (calls.incrementAndGet() == 2) throw new IOException("temporary failure");
            return response(List.of(fact(sources(input).get(0), "companyName", "某公司", "本次投标人名称：某公司")));
        }), 3, 512, 100);
        MemoryCache cache = new MemoryCache();
        Document document = document(List.of(page(1, "本次投标人名称：某公司" + "文".repeat(380)),
                page(2, "本次投标人名称：某公司" + "文".repeat(380)), page(3, "本次投标人名称：某公司" + "文".repeat(380))));
        scanner.scan(document, null, cache);
        var retry = scanner.scan(document, null, cache);
        assertEquals(3, calls.get());
        assertEquals(3, cache.reservations);
        assertEquals(2, retry.facts().size());
        assertEquals(2, retry.coverage().get("reusedBatches"));
        assertEquals(0, retry.coverage().get("attemptedBatches"));
        assertEquals(2, retry.coverage().get("scannedPages"));
        assertEquals(1, retry.coverage().get("unscannedPages"));
        assertTrue(retry.coverage().get("reasons").toString().contains("CALL_BUDGET"));
        assertTrue(retry.facts().stream().anyMatch(fact -> ((Evidence) fact.get("evidence")).page() == 3));
    }

    @Test void retrySpendsOnlyRemainingBudgetOnThePreviouslyFailedBatch() {
        AtomicInteger calls = new AtomicInteger();
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            if (calls.incrementAndGet() == 2) throw new IOException("temporary failure");
            return response(List.of());
        }), 4, 512, 100);
        MemoryCache cache = new MemoryCache();
        Document document = document(List.of(page(1, "文".repeat(400)), page(2, "文".repeat(400)), page(3, "文".repeat(400))));
        assertEquals(false, scanner.scan(document, null, cache).coverage().get("complete"));
        var retry = scanner.scan(document, null, cache);
        assertEquals(4, calls.get());
        assertEquals(4, cache.reservations);
        assertEquals(1, retry.coverage().get("attemptedBatches"));
        assertEquals(2, retry.coverage().get("reusedBatches"));
        assertEquals(true, retry.coverage().get("complete"));
    }

    @Test void cachedResponsesStillRequireGroundingAndKeysBindDocumentAndSourceText() {
        AtomicInteger calls = new AtomicInteger();
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            calls.incrementAndGet();
            return response(List.of(fact(sources(input).get(0), "companyName", "模型虚构公司", "本次投标人名称：模型虚构公司")));
        }), 10, 512, 100);
        MemoryCache cache = new MemoryCache();
        Document original = document(List.of(page(1, "本次投标人名称：真实公司")));
        scanner.scan(original, null, cache);
        var retry = scanner.scan(original, null, cache);
        assertEquals(1, calls.get());
        assertEquals(1, retry.coverage().get("rejectedFacts"));
        assertTrue(retry.facts().isEmpty());
        Document version = new Document(original.id(), original.projectId(), original.role(), original.name(), 3, original.sha256(), "", original.pages(), List.of());
        scanner.scan(version, null, cache);
        scanner.scan(document(List.of(page(1, "本次投标人名称：另一公司"))), null, cache);
        Document foreign = new Document("different-document", original.projectId(), original.role(), original.name(), original.version(), original.sha256(), "", original.pages(), List.of());
        scanner.scan(foreign, null, cache);
        assertEquals(4, calls.get());
        assertEquals(4, cache.values.size());
    }

    @Test void malformedResponsesDoNotPoisonCacheAndCacheLeaseFailuresPropagate() {
        AtomicInteger calls = new AtomicInteger();
        var scanner = new ConsistencyModelScanner(gateway(input -> { calls.incrementAndGet(); return JSON.valueToTree(Map.of("status", "PASS")); }));
        MemoryCache cache = new MemoryCache();
        Document document = document(List.of(page(1, "原文")));
        scanner.scan(document, null, cache);
        var retry = scanner.scan(document, null, cache);
        assertEquals(2, calls.get());
        assertEquals(1, retry.coverage().get("failedChunks"));
        assertEquals(0, retry.coverage().get("reusedBatches"));
        assertTrue(cache.values.isEmpty());
        var validScanner = new ConsistencyModelScanner(gateway(input -> { calls.incrementAndGet(); return response(List.of()); }));
        var lease = new cn.bidcheck.infrastructure.queue.TaskLeaseLostException("task-id");
        ConsistencyModelScanner.BatchCache rejectedSave = new ConsistencyModelScanner.BatchCache() {
            public JsonNode get(String key) { return null; }
            public boolean reserve(String key, int limit) { return true; }
            public void save(String key, JsonNode response) { throw lease; }
        };
        assertSame(lease, assertThrows(cn.bidcheck.infrastructure.queue.TaskLeaseLostException.class,
                () -> validScanner.scan(document, null, rejectedSave)));
        ConsistencyModelScanner.BatchCache rejectedReservation = new ConsistencyModelScanner.BatchCache() {
            public JsonNode get(String key) { return null; }
            public boolean reserve(String key, int limit) { throw lease; }
            public void save(String key, JsonNode response) { fail("A denied reservation must not reach save"); }
        };
        int before = calls.get();
        assertSame(lease, assertThrows(cn.bidcheck.infrastructure.queue.TaskLeaseLostException.class,
                () -> scanner.scan(document, null, rejectedReservation)));
        assertEquals(before, calls.get());
    }

    @Test void sharesAllBusinessCategoriesAndBindsRegisteredFieldsToCatalogMetadata() {
        String quote = "本项目服务器A质保期：36个月，验收合格后，整机。";
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            assertEquals(18, ((List<?>) input.get("categories")).size());
            assertTrue(((List<?>) input.get("fields")).containsAll(List.of("companyName", "warrantyPeriod", "backupFrequency", "custom")));
            assertTrue(input.get("instructions").toString().contains("不是投标事实来源"));
            @SuppressWarnings("unchecked") var catalog = (List<Map<String,Object>>) input.get("catalog");
            assertTrue(catalog.stream().anyMatch(item -> "warrantyPeriod".equals(item.get("id")) && "warranty".equals(item.get("categoryId"))));
            assertTrue(catalog.stream().noneMatch(item -> item.containsKey("aliases")));
            var candidate = fact(sources(input).get(0), "warrantyPeriod", "36个月", quote);
            candidate.put("categoryId", "model-created-category"); candidate.put("fieldLabel", "质保期");
            candidate.put("subject", "服务器A"); candidate.put("status", "PASS");
            candidate.put("attributes", Map.of("quote", quote, "contextQuote", quote, "dimensions",
                    Map.of("object", "服务器A", "trigger", "验收合格后", "range", "整机")));
            return response(List.of(candidate));
        }));
        var result = scanner.scan(document(List.of(page(1, quote))));
        var accepted = result.facts().get(0);
        assertEquals("warranty", accepted.get("categoryId")); assertEquals("质保期", accepted.get("fieldLabel"));
        assertEquals(Map.of("object", "服务器A", "trigger", "验收合格后", "range", "整机"), accepted.get("dimensions"));
        assertEquals("36个月", accepted.get("value")); assertEquals(false, accepted.get("contextVerified"));
        assertFalse(accepted.containsKey("status")); assertEquals(ConsistencyCatalog.VERSION, result.coverage().get("catalogVersion"));
    }

    @Test void customFieldsRequireLiteralLabelsAndKnownCategories() {
        String quote = "本项目业务数据库备份副本数：3份。";
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            var source = sources(input).get(0);
            List<Map<String,Object>> values = new ArrayList<>();
            var valid = fact(source, "custom", "3份", quote);
            valid.put("fieldLabel", "备份副本数"); valid.put("categoryId", "solution"); values.add(valid);
            var label = new LinkedHashMap<>(valid); label.put("fieldLabel", "异地副本数"); values.add(label);
            var category = new LinkedHashMap<>(valid); category.put("categoryId", "invented-category"); values.add(category);
            var databaseKey = new LinkedHashMap<>(valid); databaseKey.put("field", "dynamic.model-controlled"); values.add(databaseKey);
            var oversized = new LinkedHashMap<>(valid); oversized.put("fieldLabel", "备".repeat(81)); values.add(oversized);
            var conclusion = new LinkedHashMap<>(valid); conclusion.put("kind", "RELATION"); values.add(conclusion);
            return response(values);
        }));
        var result = scanner.scan(document(List.of(page(1, quote))));
        assertEquals(1, result.facts().size()); assertEquals(5, result.coverage().get("rejectedFacts"));
        assertEquals("custom", result.facts().get(0).get("field")); assertEquals("备份副本数", result.facts().get(0).get("fieldLabel"));
        assertEquals("solution", result.facts().get(0).get("categoryId")); assertEquals(false, result.coverage().get("complete"));
    }

    @Test void attributeCannotBeGroundedByAnArtificialJoinOfQuoteAndContext() {
        String context = "本项目备份副本数：3份。";
        String quote = "副本数：3份";
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            var candidate = fact(sources(input).get(0), "custom", "3份", quote);
            candidate.put("categoryId", "solution"); candidate.put("fieldLabel", quote + "\n本项目");
            candidate.put("attributes", Map.of("quote", quote, "contextQuote", context));
            return response(List.of(candidate));
        }));
        var result = scanner.scan(document(List.of(page(1, context))));
        assertTrue(result.facts().isEmpty()); assertEquals(1, result.coverage().get("rejectedFacts"));
    }

    @Test void dimensionsMustBelongToTheFactContextAndRejectedAttributesAreCounted() {
        String first = "本项目服务器A型号：X100。";
        String other = "服务器B版本V2，验收后使用，人民币。";
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            var candidate = fact(sources(input).get(0), "productModel", "X100", first);
            candidate.put("attributes", Map.of("quote", first, "contextQuote", first, "dimensions",
                    Map.of("object", "服务器A", "version", "V2", "trigger", "验收后", "currency", "人民币",
                            "condition", "未".repeat(201), "arbitraryKey", "X100", "stage", 42)));
            return response(List.of(candidate));
        }));
        var result = scanner.scan(document(List.of(page(1, first + "\n" + other))));
        assertEquals(Map.of("object", "服务器A"), result.facts().get(0).get("dimensions"));
        assertEquals(6, result.coverage().get("contextAttributesRejected"));
        assertTrue(result.facts().get(0).get("droppedContextAttributes").toString().contains("dimensions.version"));
        assertFalse(result.facts().get(0).get("droppedContextAttributes").toString().contains("arbitraryKey"));
    }

    @Test void repeatedQuotesAreAnchoredWithinTheirOwnContextAndDistinctObjectsRemainSeparate() {
        String first = "本项目服务器A，质保期：36个月。";
        String second = "本项目服务器B，质保期：36个月。";
        String quote = "质保期：36个月";
        String original = first + "\n" + second;
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            List<Map<String,Object>> facts = new ArrayList<>();
            for (String context : List.of(first, second)) {
                var candidate = fact(sources(input).get(0), "warrantyPeriod", "36个月", quote);
                String object = context.contains("服务器A") ? "服务器A" : "服务器B";
                candidate.put("subject", object);
                candidate.put("attributes", Map.of("quote", quote, "contextQuote", context, "dimensions", Map.of("object", object)));
                facts.add(candidate);
            }
            return response(facts);
        }));
        var result = scanner.scan(document(List.of(page(3, original))));
        assertEquals(2, result.facts().size());
        assertEquals(original.indexOf(quote), result.facts().get(0).get("sourceOffset"));
        assertEquals(original.lastIndexOf(quote), result.facts().get(1).get("sourceOffset"));
        assertEquals("p3-l2-c" + original.lastIndexOf(quote), ((Evidence) result.facts().get(1).get("evidence")).anchor());
        assertNotEquals(result.facts().get(0).get("id"), result.facts().get(1).get("id"));
    }

    @Test void hintsAreBoundedAndTheirExpectedValuesNeverBecomeEvidence() {
        String original = "本项目交货期：30日历天。";
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            @SuppressWarnings("unchecked") var hints = (List<Map<String,Object>>) input.get("requirementHints");
            assertTrue(hints.size() < 64);
            for (var hint : hints) {
                assertTrue(hint.get("title").toString().length() <= 160);
                assertTrue(hint.get("field").toString().length() <= 80);
                assertTrue(hint.get("expected").toString().length() <= 400);
                assertFalse(hint.containsKey("sourceIds"));
            }
            return response(List.of(fact(sources(input).get(0), "deliveryPeriod", "10日历天", "本项目交货期：10日历天。")));
        }));
        List<Map<String,Object>> hints = new ArrayList<>();
        for (int i = 0; i < 100; i++) hints.add(Map.of("title", "交".repeat(200), "field", "d".repeat(100), "expected", "10日历天".repeat(120), "sourceIds", "forged-source"));
        var result = scanner.scan(document(List.of(page(1, original))), hints, null, new MemoryCache());
        assertEquals(100, result.coverage().get("requirementHintsProvided"));
        assertEquals(true, result.coverage().get("requirementHintsTruncated"));
        assertTrue(((Number) result.coverage().get("requirementHintsUsed")).intValue() > 0);
        assertTrue(result.facts().isEmpty()); assertEquals(1, result.coverage().get("rejectedFacts"));
    }

    @Test void changedRequirementHintsInvalidateTheBatchCacheWhileEquivalentHintsReuseIt() {
        AtomicInteger calls = new AtomicInteger();
        var scanner = new ConsistencyModelScanner(gateway(input -> { calls.incrementAndGet(); return response(List.of()); }));
        Document doc = document(List.of(page(1, "本项目交货期：30日历天。")));
        var cache = new MemoryCache();
        List<Map<String,Object>> first = List.of(Map.of("title", "交付期限", "field", "deliveryPeriod", "expected", "10日历天"));
        List<Map<String,Object>> second = List.of(Map.of("expected", "20日历天", "field", "deliveryPeriod", "title", "交付期限"));
        scanner.scan(doc, first, null, cache);
        assertEquals(1, scanner.scan(doc, first, null, cache).coverage().get("reusedBatches"));
        assertEquals(0, scanner.scan(doc, second, null, cache).coverage().get("reusedBatches"));
        assertEquals(2, calls.get());
    }

    @Test void escapedLargeSourcesSplitBeforeChargingCallsAndRemainFullyCoveredWithHints() {
        String original = "\"".repeat(35_000);
        AtomicInteger calls = new AtomicInteger();
        var scanner = new ConsistencyModelScanner(gateway(input -> {
            calls.incrementAndGet();
            assertTrue(JSON.writeValueAsString(Map.of("operation", "EXTRACT", "input", input)).length() <= 64_000);
            for (var source : sources(input)) {
                int start = ((Number) source.get("startOffset")).intValue();
                int end = ((Number) source.get("endOffset")).intValue();
                assertEquals(original.substring(start, end), source.get("quote"));
            }
            return response(List.of());
        }), 100, 40_000, 100);
        List<Map<String,Object>> hints = new ArrayList<>();
        for (int i = 0; i < 64; i++) hints.add(Map.of("title", "项目检查" + i, "field", "custom", "expected", "要求".repeat(200)));
        var result = scanner.scan(document(List.of(page(1, original))), hints, null, new MemoryCache());
        assertTrue(calls.get() > 1); assertEquals(calls.get(), result.coverage().get("attemptedBatches"));
        assertEquals((long) original.length(), result.coverage().get("scannedCharacters"));
        assertEquals(0L, result.coverage().get("unscannedCharacters"));
        assertEquals(true, result.coverage().get("complete")); assertEquals(1, result.coverage().get("scannedPages"));
    }

    @Test void omittedHintsAndOldScanOverloadsPreserveTheSameCacheRequest() {
        AtomicInteger calls = new AtomicInteger();
        var scanner = new ConsistencyModelScanner(gateway(input -> { calls.incrementAndGet(); assertEquals(List.of(), input.get("requirementHints")); return response(List.of()); }));
        Document doc = document(List.of(page(1, "本项目原文")));
        var cache = new MemoryCache();
        scanner.scan(doc, null, cache);
        var result = scanner.scan(doc, null, null, cache);
        assertEquals(1, calls.get()); assertEquals(1, result.coverage().get("reusedBatches"));
        assertEquals(0, result.coverage().get("requirementHintsProvided"));
        assertEquals(false, result.coverage().get("requirementHintsTruncated"));
    }

    @Test void boundedConcurrencyKeepsBudgetCacheAndLeaseHooksOnCallerThread() {
        Thread owner=Thread.currentThread(); AtomicInteger active=new AtomicInteger(), peak=new AtomicInteger(), calls=new AtomicInteger();
        CyclicBarrier firstWave=new CyclicBarrier(3); MemoryCache memory=new MemoryCache();
        var scanner=new ConsistencyModelScanner(gateway(input -> {
            assertNotSame(owner,Thread.currentThread()); int number=calls.incrementAndGet();
            int concurrent=active.incrementAndGet(); peak.accumulateAndGet(concurrent,Math::max);
            try {
                if(number<=3) try { firstWave.await(3,TimeUnit.SECONDS); } catch(Exception error) { throw new IOException("barrier failed"); }
                return response(List.of());
            } finally { active.decrementAndGet(); }
        }),20,512,100,3,1,2);
        ConsistencyModelScanner.BatchCache fenced=new ConsistencyModelScanner.BatchCache() {
            public JsonNode get(String key) { assertSame(owner,Thread.currentThread()); return memory.get(key); }
            public boolean reserve(String key,int maximum) { assertSame(owner,Thread.currentThread()); return memory.reserve(key,maximum); }
            public void save(String key,JsonNode value) { assertSame(owner,Thread.currentThread()); memory.save(key,value); }
        };
        List<Page> pages=new ArrayList<>(); for(int i=1;i<=7;i++)pages.add(page(i,"原".repeat(400)));
        var result=scanner.scan(document(pages), message -> assertSame(owner,Thread.currentThread()),fenced);
        assertEquals(3,peak.get()); assertEquals(7,calls.get()); assertEquals(7,memory.reservations);
        assertEquals(7,result.coverage().get("scannedPages")); assertEquals(true,result.coverage().get("complete"));
        assertEquals(3,result.coverage().get("peakInFlight"));
    }

    @Test void transientFailuresRetryWithinBudgetAndExposeOnlySafeDiagnostics() {
        AtomicInteger calls=new AtomicInteger(); MemoryCache cache=new MemoryCache();
        var scanner=new ConsistencyModelScanner(gateway(input -> {
            if(calls.incrementAndGet()==1)throw new ModelRequestException("HTTP_503",503,true,false);
            return response(List.of());
        }),4,8000,100,3,1,2);
        var result=scanner.scan(document(List.of(page(1,"本项目原文"))),null,cache);
        assertEquals(2,calls.get()); assertEquals(2,cache.reservations);
        assertEquals(1,result.coverage().get("retryRequests")); assertEquals(1,result.coverage().get("failedAttempts"));
        assertEquals(true,result.coverage().get("complete")); assertEquals(List.of(),result.coverage().get("reasons"));
        List<?> diagnostics=(List<?>)result.coverage().get("requestDiagnostics");
        assertEquals("HTTP_503",((Map<?,?>)diagnostics.get(0)).get("outcome"));
        assertEquals(503,((Map<?,?>)diagnostics.get(0)).get("httpStatus"));
        assertTrue(((Number)((Map<?,?>)diagnostics.get(1)).get("elapsedMillis")).longValue()>=0);
    }

    @Test void timeoutSplitsOriginalRangesAndResumeReusesChildrenWithoutRepeatingParent() {
        String quote="本次投标人名称：尾部公司", original="文".repeat(1980)+quote;
        AtomicInteger calls=new AtomicInteger(); MemoryCache cache=new MemoryCache();
        var scanner=new ConsistencyModelScanner(gateway(input -> {
            calls.incrementAndGet(); var source=sources(input).get(0); String text=source.get("quote").toString();
            if(text.length()>1500)throw new ModelRequestException("REQUEST_TIMEOUT",0,true,true);
            return response(text.contains(quote)?List.of(fact(source,"companyName","尾部公司",quote)):List.of());
        }),10,4000,100,3,1,2);
        Document doc=document(List.of(page(1,original))); var first=scanner.scan(doc,null,cache);
        assertEquals(4,calls.get()); assertEquals(1,first.coverage().get("splitBatches"));
        assertEquals(2,first.coverage().get("scannedChunks")); assertEquals(1,first.coverage().get("scannedPages"));
        assertEquals((long)original.length(),first.coverage().get("scannedCharacters"));
        assertEquals(0L,first.coverage().get("unscannedCharacters")); assertEquals(true,first.coverage().get("complete"));
        assertEquals(1980,first.facts().get(0).get("sourceOffset"));
        var resumed=scanner.scan(doc,null,cache);
        assertEquals(4,calls.get()); assertEquals(4,cache.reservations); assertEquals(first.facts(),resumed.facts());
        assertEquals(0,resumed.coverage().get("attemptedBatches")); assertEquals(2,resumed.coverage().get("reusedBatches"));
        assertEquals(1,resumed.coverage().get("reusedSplitPlans")); assertEquals(true,resumed.coverage().get("complete"));
    }

    @Test void invalidProtocolSplitsMultipleSourcesWithoutCachingInvalidOutput() {
        AtomicInteger calls=new AtomicInteger(); MemoryCache cache=new MemoryCache();
        var scanner=new ConsistencyModelScanner(gateway(input -> {
            calls.incrementAndGet();
            return sources(input).size()>1?JSON.valueToTree(Map.of("status","invented")):response(List.of());
        }),10,2000,100,2,1,2);
        var result=scanner.scan(document(List.of(page(1,"文".repeat(600)),page(2,"文".repeat(600)))),null,cache);
        assertEquals(4,calls.get()); assertEquals(true,result.coverage().get("complete"));
        assertEquals(2,result.coverage().get("validResponseBatches")); assertEquals(2,result.coverage().get("failedAttempts"));
        assertTrue(cache.values.values().stream().noneMatch(value -> value.has("status")));
    }

    @Test void permanentAuthenticationFailureDoesNotRetryOrSplit() {
        AtomicInteger calls=new AtomicInteger();
        var scanner=new ConsistencyModelScanner(gateway(input -> { calls.incrementAndGet(); throw new ModelRequestException("HTTP_401",401,false,false); }),20,8000,100,3,2,3);
        var result=scanner.scan(document(List.of(page(1,"文".repeat(4000)))));
        assertEquals(1,calls.get()); assertEquals(0,result.coverage().get("splitBatches"));
        assertEquals(false,result.coverage().get("complete")); assertEquals(4000L,result.coverage().get("unscannedCharacters"));
        assertTrue(result.coverage().get("locations").toString().contains("HTTP_401"));
    }

    @Test void retriesAndSplitChildrenSharePersistentBudgetAndNeverMaskRemainingRanges() {
        AtomicInteger calls=new AtomicInteger(); MemoryCache cache=new MemoryCache();
        var scanner=new ConsistencyModelScanner(gateway(input -> {
            calls.incrementAndGet();
            if(sources(input).get(0).get("quote").toString().length()>1500)throw new ModelRequestException("REQUEST_TIMEOUT",0,true,true);
            return response(List.of());
        }),3,4000,100,2,1,2);
        Document doc=document(List.of(page(1,"文".repeat(2000)))); var first=scanner.scan(doc,null,cache);
        assertEquals(3,calls.get()); assertEquals(3,cache.reservations); assertEquals(false,first.coverage().get("complete"));
        assertEquals(1000L,first.coverage().get("scannedCharacters")); assertEquals(1000L,first.coverage().get("unscannedCharacters"));
        assertEquals(1,first.coverage().get("scannedChunks")); assertEquals(1,first.coverage().get("unscannedChunks"));
        assertTrue(first.coverage().get("reasons").toString().contains("CALL_BUDGET"));
        var resumed=scanner.scan(doc,null,cache);
        assertEquals(3,calls.get()); assertEquals(1,resumed.coverage().get("reusedBatches"));
        assertEquals(1000L,resumed.coverage().get("unscannedCharacters")); assertEquals(false,resumed.coverage().get("complete"));
    }

    @Test void inFlightRequestsAreInterruptedWhenHeartbeatLosesLease() throws Exception {
        CountDownLatch stopped=new CountDownLatch(2); AtomicInteger calls=new AtomicInteger();
        var lease=new cn.bidcheck.infrastructure.queue.TaskLeaseLostException("offline-task");
        var scanner=new ConsistencyModelScanner(gateway(input -> {
            calls.incrementAndGet();
            try { new CountDownLatch(1).await(); throw new AssertionError("must be cancelled"); }
            catch(InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new InterruptedIOException("cancelled"); }
            finally { stopped.countDown(); }
        }),20,512,100,2,1,2);
        Document doc=document(List.of(page(1,"文".repeat(400)),page(2,"文".repeat(400)),page(3,"文".repeat(400))));
        assertSame(lease,assertThrows(cn.bidcheck.infrastructure.queue.TaskLeaseLostException.class,() -> scanner.scan(doc,message -> {
            if(message.contains("进行中"))throw lease;
        })));
        assertTrue(stopped.await(2,TimeUnit.SECONDS)); assertEquals(2,calls.get()); assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test void recoveryDepthIsBoundedEvenWhenEveryResponseIsMalformed() {
        AtomicInteger calls=new AtomicInteger();
        var scanner=new ConsistencyModelScanner(gateway(input -> { calls.incrementAndGet(); return JSON.valueToTree(Map.of("facts","invalid")); }),100,4000,100,3,1,2);
        var result=scanner.scan(document(List.of(page(1,"文".repeat(4000)))));
        assertEquals(14,calls.get()); assertEquals(3,result.coverage().get("splitBatches"));
        assertEquals(4,result.coverage().get("failedChunks")); assertEquals(0L,result.coverage().get("scannedCharacters"));
        assertEquals(4000L,result.coverage().get("unscannedCharacters")); assertEquals(false,result.coverage().get("complete"));
    }

    @Test void protocolCoverageAndGroundingCoverageStayDistinctAndGoodFactsRemainAvailable() {
        String quote="本次投标人名称：真实公司";
        var scanner=new ConsistencyModelScanner(gateway(input -> response(List.of(
                fact(sources(input).get(0),"companyName","真实公司",quote),
                fact(sources(input).get(0),"companyName","虚构公司","本次投标人名称：虚构公司")))),10,8000,100,3,1,2);
        var result=scanner.scan(document(List.of(page(1,quote),page(2,"本项目其他原文"))));
        assertEquals(1,result.facts().size()); assertEquals(2,result.coverage().get("protocolScannedChunks"));
        assertEquals(true,result.coverage().get("protocolComplete")); assertEquals(false,result.coverage().get("complete"));
        var grounding=(Map<?,?>)result.coverage().get("evidenceValidatedCoverage");
        assertEquals(false,grounding.get("quoteGroundingComplete")); assertEquals(1L,grounding.get("sourceGroundingReviewPages"));
        assertEquals(1L,grounding.get("chunksWithoutRejectedAssertions"));
    }

    private static Map<String,Object> fact(Map<String,Object> source, String field, String value, String quote) {
        Map<String,Object> fact = new LinkedHashMap<>();
        fact.put("kind", "FIELD"); fact.put("field", field); fact.put("subject", "");
        fact.put("scope", "CURRENT_PROJECT"); fact.put("rawValue", value); fact.put("value", value);
        fact.put("sourceIds", List.of(source.get("id")));
        fact.put("attributes", Map.of("quote", quote, "contextQuote", quote));
        return fact;
    }
    private static JsonNode response(List<Map<String,Object>> facts) { return JSON.valueToTree(Map.of("facts", facts)); }
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> sources(Map<String,Object> input) { return (List<Map<String,Object>>) input.get("sources"); }
    private static Page page(int number, String text) { return new Page(number, text, "PAGE", "PDFBOX_NATIVE", ""); }
    private static Document document(List<Page> pages) { return new Document("document-id", "project", "BID", "投标文件.pdf", 2, "document-sha", "", pages, List.of()); }
    private static ModelGateway gateway(Responder responder) {
        return new ModelGateway() {
            public boolean enabled() { return true; }
            public String identity() { return "mock-model-v1"; }
            public JsonNode request(String operation, Map<String,Object> input) throws IOException {
                assertEquals("EXTRACT", operation);
                return responder.respond(input);
            }
        };
    }
    @FunctionalInterface private interface Responder { JsonNode respond(Map<String,Object> input) throws IOException; }

    private static final class MemoryCache implements ConsistencyModelScanner.BatchCache {
        private final Map<String,JsonNode> values = new LinkedHashMap<>();
        private int reservations;
        public JsonNode get(String key) { return values.get(key); }
        public boolean reserve(String key, int maxCalls) {
            if (reservations >= maxCalls) return false;
            reservations++; return true;
        }
        public void save(String key, JsonNode response) { values.put(key, response.deepCopy()); }
    }
}
