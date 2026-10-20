package cn.bidcheck.checktemplate.evidence;

import cn.bidcheck.checktemplate.Observation;
import cn.bidcheck.checktemplate.SourceRef;
import cn.bidcheck.checktemplate.TemplateTypes;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceNeedCoordinatorTest {
    private static final EvidenceRetryPolicy FAST_POLICY = new EvidenceRetryPolicy(
            3, Duration.ofMillis(5), Duration.ofMillis(20), Duration.ofSeconds(2), Duration.ofMillis(5));

    @Test void stableKeyNormalizesDocumentAndDimensionOrderAndInvalidatesChangedInputs() {
        EvidenceNeedKey.DocumentVersion bid = document("bid", 3, "bid-hash", "parser-v2");
        EvidenceNeedKey.DocumentVersion tender = document("tender", 1, "tender-hash", "parser-v1");
        EvidenceNeedKey first = new EvidenceNeedKey(List.of(bid, tender), "PERSON:ZHANG_SAN",
                "CERTIFICATE", "validUntil", Map.of("major", "建筑工程", "lot", "A"),
                "certificate-local", "2");
        EvidenceNeedKey reordered = new EvidenceNeedKey(List.of(tender, bid), "PERSON:ZHANG_SAN",
                "CERTIFICATE", "validUntil", Map.of("lot", "A", "major", "建筑工程"),
                "certificate-local", "2");
        EvidenceNeedKey changedParser = new EvidenceNeedKey(
                List.of(document("bid", 3, "bid-hash", "parser-v3"), tender), "PERSON:ZHANG_SAN",
                "CERTIFICATE", "validUntil", Map.of("lot", "A", "major", "建筑工程"),
                "certificate-local", "2");

        assertEquals(first, reordered);
        assertEquals(first.id(), reordered.id());
        assertNotEquals(first.id(), changedParser.id());
        assertNotEquals(first.id(), new EvidenceNeedKey(List.of(bid, tender), "PERSON:ZHANG_SAN",
                "CERTIFICATE", "validUntil", first.dimensions(), "certificate-local", "3").id());
    }

    @Test void concurrentSubscribersExecuteOneFlightAndReceiveIndependentReceipts() throws Exception {
        InMemoryEvidenceTaskRepository repository = new InMemoryEvidenceTaskRepository();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LocalEvidenceExtractor extractor = extractor(context -> {
            calls.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return positive();
        });

        try (EvidenceNeedCoordinator coordinator = new EvidenceNeedCoordinator(repository, FAST_POLICY)) {
            ArrayList<CompletableFuture<EvidenceReadReceipt>> requests = new ArrayList<>();
            requests.add(coordinator.request(key(), "check-0", extractor));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int index = 1; index < 24; index++) {
                requests.add(coordinator.request(key(), "check-" + index, extractor));
            }
            release.countDown();

            List<EvidenceReadReceipt> receipts = requests.stream().map(CompletableFuture::join).toList();
            assertEquals(1, calls.get());
            assertEquals(1, receipts.stream().map(EvidenceReadReceipt::taskId).distinct().count());
            assertEquals(24, receipts.stream().map(EvidenceReadReceipt::receiptId).distinct().count());
            assertEquals(Set.of(EvidenceTaskStatus.SUCCEEDED),
                    receipts.stream().map(EvidenceReadReceipt::status).collect(java.util.stream.Collectors.toSet()));
            assertTrue(receipts.stream().allMatch(EvidenceReadReceipt::sharedTask));
            assertEquals(24, repository.find(key().id()).orElseThrow().subscriberIds().size());
        }
    }

    @Test void closedNegativeIsCachedButPartialScopeRemainsExplicit() throws Exception {
        InMemoryEvidenceTaskRepository repository = new InMemoryEvidenceTaskRepository();
        AtomicInteger negativeCalls = new AtomicInteger();
        LocalEvidenceExtractor negative = extractor(context -> {
            negativeCalls.incrementAndGet();
            return EvidenceExtractionResult.notFound(EvidenceScope.closed(10, "10页均完成解析"), "未找到证书");
        });

        try (EvidenceNeedCoordinator coordinator = new EvidenceNeedCoordinator(repository, FAST_POLICY)) {
            EvidenceReadReceipt first = coordinator.request(key(), "check-a", negative).get(2, TimeUnit.SECONDS);
            EvidenceReadReceipt cached = coordinator.request(key(), "check-b", negative).get(2, TimeUnit.SECONDS);
            assertEquals(EvidenceTaskStatus.NOT_FOUND_IN_CLOSED_SCOPE, first.status());
            assertEquals(EvidenceTaskStatus.NOT_FOUND_IN_CLOSED_SCOPE, cached.status());
            assertTrue(first.result().scope().closed());
            assertEquals(1, negativeCalls.get(), "a closed negative must be reused for the exact key");
        }

        EvidenceNeedKey partialKey = new EvidenceNeedKey(key().documents(), "PERSON:LI_SI", "CERTIFICATE",
                "validUntil", key().dimensions(), "local-test", "1");
        try (EvidenceNeedCoordinator coordinator = new EvidenceNeedCoordinator(repository, FAST_POLICY)) {
            LocalEvidenceExtractor partial = extractor(context -> EvidenceExtractionResult.partial(
                    List.of(), EvidenceScope.partial(8, 10, Set.of("page:9", "page:10"), "OCR未完成"),
                    "只能证明当前已扫描范围"));
            EvidenceReadReceipt receipt = coordinator.request(partialKey, "check-c", partial)
                    .get(2, TimeUnit.SECONDS);
            assertEquals(EvidenceTaskStatus.PARTIAL_SCOPE, receipt.status());
            assertFalse(receipt.result().scope().closed());
            assertThrows(IllegalArgumentException.class, () -> EvidenceExtractionResult.notFound(
                    receipt.result().scope(), "不得把部分范围缓存为未找到"));
        }
    }

    @Test void retryableFailureStopsAtBoundaryAndPermanentFailureDoesNotRetry() throws Exception {
        AtomicInteger transientCalls = new AtomicInteger();
        LocalEvidenceExtractor eventuallySucceeds = extractor(context -> {
            if (transientCalls.incrementAndGet() < 3) {
                throw EvidenceExtractionException.retryable("LOCAL_INDEX_BUSY", "本地索引暂不可用");
            }
            return positive();
        });
        try (EvidenceNeedCoordinator coordinator = new EvidenceNeedCoordinator(
                new InMemoryEvidenceTaskRepository(), FAST_POLICY)) {
            EvidenceReadReceipt receipt = coordinator.request(key(), "retry-success", eventuallySucceeds)
                    .get(3, TimeUnit.SECONDS);
            assertEquals(EvidenceTaskStatus.SUCCEEDED, receipt.status());
            assertEquals(3, receipt.attempts());
            assertEquals(3, transientCalls.get());
        }

        AtomicInteger exhaustedCalls = new AtomicInteger();
        LocalEvidenceExtractor alwaysTransient = extractor(context -> {
            exhaustedCalls.incrementAndGet();
            throw EvidenceExtractionException.retryable("LOCAL_INDEX_BUSY", "仍不可用");
        });
        try (EvidenceNeedCoordinator coordinator = new EvidenceNeedCoordinator(
                new InMemoryEvidenceTaskRepository(), FAST_POLICY)) {
            EvidenceReadReceipt receipt = coordinator.request(key(), "retry-exhausted", alwaysTransient)
                    .get(3, TimeUnit.SECONDS);
            assertEquals(EvidenceTaskStatus.FAILED, receipt.status());
            assertEquals(3, receipt.attempts());
            assertEquals(3, exhaustedCalls.get());
            assertTrue(receipt.failure().retryable());
        }

        AtomicInteger permanentCalls = new AtomicInteger();
        LocalEvidenceExtractor permanent = extractor(context -> {
            permanentCalls.incrementAndGet();
            throw EvidenceExtractionException.permanent("INVALID_LOCAL_FACT", "结构化事实不合法");
        });
        try (EvidenceNeedCoordinator coordinator = new EvidenceNeedCoordinator(
                new InMemoryEvidenceTaskRepository(), FAST_POLICY)) {
            EvidenceReadReceipt receipt = coordinator.request(key(), "permanent", permanent)
                    .get(2, TimeUnit.SECONDS);
            assertEquals(EvidenceTaskStatus.FAILED, receipt.status());
            assertEquals(1, receipt.attempts());
            assertEquals(1, permanentCalls.get());
            assertFalse(receipt.failure().retryable());
        }
    }

    @Test void expiredLeaseCanBeReplacedAndLateWorkerCannotPublish() {
        Instant start = Instant.parse("2026-10-19T00:00:00Z");
        EvidenceTask pending = EvidenceTask.pending(key(), 3, start);
        EvidenceTask first = pending.acquire("node-a", "lease-a", start, Duration.ofSeconds(10));
        Instant afterExpiry = start.plusSeconds(11);
        EvidenceTask second = first.acquire("node-b", "lease-b", afterExpiry, Duration.ofSeconds(10));

        assertEquals(2, second.attempts());
        assertThrows(IllegalStateException.class, () -> second.complete("lease-a", positive(), afterExpiry));
        EvidenceTask completed = second.complete("lease-b", positive(), afterExpiry.plusSeconds(1));
        assertEquals(EvidenceTaskStatus.SUCCEEDED, completed.status());
    }

    @Test void resultSemanticsRejectFalseNegativesAndUnboundPositives() {
        EvidenceScope partial = EvidenceScope.partial(2, 3, Set.of("page:3"), "一页未解析");
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceExtractionResult(EvidenceExtractionResult.Disposition.NOT_FOUND,
                        List.of(), partial, "", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceExtractionResult(EvidenceExtractionResult.Disposition.POSITIVE,
                        List.of(), EvidenceScope.closed(3, "完整"), "", Map.of()));
    }

    private static EvidenceNeedKey key() {
        return new EvidenceNeedKey(List.of(document("bid", 3, "bid-hash", "parser-v2")),
                "PERSON:ZHANG_SAN", "CERTIFICATE", "validUntil",
                Map.of("certificateType", "建造师注册证", "scope", "CURRENT_PROJECT"),
                "local-test", "1");
    }

    private static EvidenceNeedKey.DocumentVersion document(String id, long version, String hash, String parser) {
        return new EvidenceNeedKey.DocumentVersion(id, version, hash, parser);
    }

    private static EvidenceExtractionResult positive() {
        SourceRef source = new SourceRef("bid", 3, "bid-hash", "parser-v2", "source-hash",
                "PAGE", 6, "有效期至2027年12月31日", null, null);
        Observation.MaterialObservation observation = new Observation.MaterialObservation(
                "obs-1", "建造师注册证", Set.of(), "PERSON:ZHANG_SAN", true,
                true, false, Set.of(), TemplateTypes.ObservationState.SUPPORTED, List.of(source), 1.0);
        return EvidenceExtractionResult.positive(List.of(observation), EvidenceScope.closed(10, "完整文档范围"));
    }

    private static LocalEvidenceExtractor extractor(Extraction action) {
        return new LocalEvidenceExtractor() {
            @Override public String id() { return "local-test"; }
            @Override public String version() { return "1"; }
            @Override public EvidenceExtractionResult extract(EvidenceExtractionContext context)
                    throws EvidenceExtractionException {
                try {
                    return action.run(context);
                } catch (EvidenceExtractionException expected) {
                    throw expected;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw EvidenceExtractionException.permanent("INTERRUPTED", "测试提取被中断", interrupted);
                } catch (Exception failure) {
                    throw EvidenceExtractionException.permanent("TEST_FAILURE", failure.getMessage(), failure);
                }
            }
        };
    }

    @FunctionalInterface
    private interface Extraction {
        EvidenceExtractionResult run(EvidenceExtractionContext context) throws Exception;
    }
}
