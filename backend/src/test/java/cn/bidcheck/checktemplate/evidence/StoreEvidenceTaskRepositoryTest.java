package cn.bidcheck.checktemplate.evidence;

import cn.bidcheck.checktemplate.Observation;
import cn.bidcheck.checktemplate.SourceRef;
import cn.bidcheck.checktemplate.TemplateTypes;
import cn.bidcheck.persistence.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evidence-task-store;DB_CLOSE_DELAY=-1",
        "bidcheck.ocr.enabled=false",
        "bidcheck.tasks.poll-ms=600000"
})
class StoreEvidenceTaskRepositoryTest {
    private static final Path DATA = temp();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("bidcheck.data", DATA::toString);
    }

    @Autowired Store store;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EvidenceTaskRepository configuredRepository;
    @Autowired EvidenceNeedCoordinator configuredCoordinator;

    @Test
    void productionBeansUseDurableRepository() {
        assertThat(configuredRepository).isInstanceOf(StoreEvidenceTaskRepository.class);
        assertThat(configuredCoordinator).isNotNull();
    }

    @Test
    void aRecreatedCoordinatorReusesTerminalResultAndPreservesSubscriptions() throws Exception {
        EvidenceNeedKey key = key("restart-" + UUID.randomUUID());
        AtomicInteger extractionCalls = new AtomicInteger();
        LocalEvidenceExtractor firstExtractor = extractor(context -> {
            extractionCalls.incrementAndGet();
            return positive();
        });

        EvidenceTaskRepository firstRepository = repository();
        try (EvidenceNeedCoordinator first = new EvidenceNeedCoordinator(firstRepository, fastPolicy())) {
            EvidenceReadReceipt receipt = first.request(key, "check-a", firstExtractor)
                    .get(3, TimeUnit.SECONDS);
            assertThat(receipt.status()).isEqualTo(EvidenceTaskStatus.SUCCEEDED);
            assertThat(receipt.result().observations()).hasSize(1);
        }

        // A new adapter and coordinator simulate a process restart. The extractor must not run.
        EvidenceTaskRepository restartedRepository = repository();
        LocalEvidenceExtractor forbidden = extractor(context -> {
            extractionCalls.incrementAndGet();
            throw EvidenceExtractionException.permanent("SHOULD_NOT_RUN", "cached result was lost");
        });
        try (EvidenceNeedCoordinator restarted = new EvidenceNeedCoordinator(restartedRepository, fastPolicy())) {
            EvidenceReadReceipt receipt = restarted.request(key, "check-b", forbidden)
                    .get(3, TimeUnit.SECONDS);
            assertThat(receipt.status()).isEqualTo(EvidenceTaskStatus.SUCCEEDED);
            assertThat(receipt.sharedTask()).isTrue();
            assertThat(receipt.result().observations().get(0))
                    .isInstanceOf(Observation.MaterialObservation.class);
        }

        EvidenceTask durable = restartedRepository.find(key.id()).orElseThrow();
        assertThat(durable.status()).isEqualTo(EvidenceTaskStatus.SUCCEEDED);
        assertThat(durable.subscriberIds()).containsExactlyInAnyOrder("check-a", "check-b");
        assertThat(extractionCalls).hasValue(1);
    }

    @Test
    void revisionCompareAndSetRejectsStaleWriterAndSurvivesNewAdapter() {
        EvidenceTaskRepository repository = repository();
        EvidenceNeedKey key = key("cas-" + UUID.randomUUID());
        Instant start = Instant.parse("2026-10-19T00:00:00Z");
        EvidenceTask initial = repository.createOrGet(key, 3, start);

        EvidenceTask winner = initial.subscribe("winner", start.plusSeconds(1));
        EvidenceTask stale = initial.subscribe("stale", start.plusSeconds(1));
        assertThat(repository.replace(winner, 0)).isTrue();
        assertThat(repository.replace(stale, 0)).isFalse();

        EvidenceTask restored = repository().find(key.id()).orElseThrow();
        assertThat(restored.revision()).isEqualTo(1);
        assertThat(restored.subscriberIds()).containsExactly("winner");
    }

    @Test
    void concurrentCreatorsAndWritersConvergeOnOneStableTask() throws Exception {
        EvidenceNeedKey key = key("race-" + UUID.randomUUID());
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ArrayList<java.util.concurrent.Future<EvidenceTask>> creates = new ArrayList<>();
        for (int index = 0; index < workers; index++) {
            creates.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return repository().createOrGet(key, 3, Instant.parse("2026-10-19T00:00:00Z"));
            }));
        }
        assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (var created : creates) assertThat(created.get(3, TimeUnit.SECONDS).taskId()).isEqualTo(key.id());

        EvidenceTask initial = repository().find(key.id()).orElseThrow();
        CountDownLatch writeStart = new CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> writes = new ArrayList<>();
        for (int index = 0; index < workers; index++) {
            String subscriber = "subscriber-" + index;
            writes.add(pool.submit(() -> {
                writeStart.await();
                return repository().replace(initial.subscribe(subscriber, initial.updatedAt().plusSeconds(1)),
                        initial.revision());
            }));
        }
        writeStart.countDown();
        int successes = 0;
        for (var write : writes) if (write.get(3, TimeUnit.SECONDS)) successes++;
        pool.shutdownNow();

        assertThat(successes).isEqualTo(1);
        assertThat(repository().find(key.id()).orElseThrow().subscriberIds()).hasSize(1);
    }

    @Test
    void stablePrimaryKeyCannotAliasAnotherRecordKind() {
        EvidenceNeedKey key = key("collision-" + UUID.randomUUID());
        store.save("finding", key.id(), "project", "", Map.of("value", "unrelated"));
        assertThatThrownBy(() -> repository().createOrGet(key, 3, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("EVIDENCE_TASK_ID_COLLISION");
    }

    private EvidenceTaskRepository repository() {
        return new StoreEvidenceTaskRepository(store, json, transactionManager);
    }

    private static EvidenceNeedKey key(String documentId) {
        return new EvidenceNeedKey(List.of(new EvidenceNeedKey.DocumentVersion(
                documentId, 1, "a".repeat(64), "parser-v1")),
                "BIDDER", "MATERIAL", "营业执照", Map.of("scope", "CURRENT_PROJECT"),
                "durable-local", "1");
    }

    private static EvidenceExtractionResult positive() {
        SourceRef source = new SourceRef("bid", 1, "a".repeat(64), "parser-v1",
                "b".repeat(64), "PAGE", 3, "营业执照正文", null, null);
        Observation observation = new Observation.MaterialObservation(
                "observation-1", "营业执照", Set.of("营业执照副本"), "BIDDER",
                true, true, false, Set.of(), TemplateTypes.ObservationState.SUPPORTED,
                List.of(source), 0.99);
        return EvidenceExtractionResult.positive(List.of(observation),
                EvidenceScope.closed(10, "全部十页均已读取"));
    }

    private static EvidenceRetryPolicy fastPolicy() {
        return new EvidenceRetryPolicy(3, Duration.ofMillis(5), Duration.ofMillis(20),
                Duration.ofSeconds(1), Duration.ofMillis(10));
    }

    private static LocalEvidenceExtractor extractor(Extraction action) {
        return new LocalEvidenceExtractor() {
            @Override public String id() { return "durable-local"; }
            @Override public String version() { return "1"; }
            @Override public EvidenceExtractionResult extract(EvidenceExtractionContext context)
                    throws EvidenceExtractionException {
                return action.extract(context);
            }
        };
    }

    private static Path temp() {
        try {
            return Files.createTempDirectory("evidence-task-store-");
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @FunctionalInterface
    private interface Extraction {
        EvidenceExtractionResult extract(EvidenceExtractionContext context)
                throws EvidenceExtractionException;
    }
}
