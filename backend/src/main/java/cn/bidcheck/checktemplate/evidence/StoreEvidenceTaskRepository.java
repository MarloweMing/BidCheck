package cn.bidcheck.checktemplate.evidence;

import cn.bidcheck.checktemplate.Observation;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.Stored;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable evidence-task repository backed by {@code qc_record}.
 *
 * <p>The evidence key digest is used directly as the record primary key. Creation is insert-only;
 * concurrent creators converge after the database rejects one duplicate insert. Every mutation
 * locks the row and checks the durable revision before replacing it, so the coordinator's lease
 * token and revision fence also work across application processes.</p>
 */
public final class StoreEvidenceTaskRepository implements EvidenceTaskRepository {
    static final String KIND = "evidence_task";
    static final String SHARED_PROJECT = "__shared_evidence__";
    static final String SCHEMA = "evidence-task-v1";

    private final Store store;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public StoreEvidenceTaskRepository(Store store, ObjectMapper json,
                                       PlatformTransactionManager transactionManager) {
        this.store = Objects.requireNonNull(store, "store");
        this.json = Objects.requireNonNull(json, "json");
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.transactions = new TransactionTemplate(transactionManager);
        // Evidence flights are independent durable work. A caller rollback must not erase a
        // published lease/result, and duplicate-insert recovery needs a fresh transaction.
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public EvidenceTask createOrGet(EvidenceNeedKey key, int maxAttempts, Instant now) {
        EvidenceTask seed = EvidenceTask.pending(Objects.requireNonNull(key, "key"), maxAttempts,
                Objects.requireNonNull(now, "now"));
        try {
            return required(transactions.execute(status -> {
                Stored existing = store.mapper().getForUpdate(seed.taskId());
                if (existing != null) return decodeAndVerify(existing, key);
                store.mapper().insertRecord(record(seed));
                return seed;
            }));
        } catch (DuplicateKeyException concurrentCreatorWon) {
            // An absent-row SELECT ... FOR UPDATE cannot portably lock a gap on every supported
            // database. The primary-key insert is the creation fence; after losing it, enter a new
            // transaction and read the winner under a row lock.
            return required(transactions.execute(status -> {
                Stored winner = store.mapper().getForUpdate(seed.taskId());
                if (winner == null) throw new IllegalStateException("EVIDENCE_TASK_CREATE_RACE_LOST");
                return decodeAndVerify(winner, key);
            }));
        }
    }

    @Override
    public Optional<EvidenceTask> find(String taskId) {
        String id = requiredId(taskId);
        return required(transactions.execute(status -> {
            Stored stored = store.mapper().get(id);
            if (stored == null) return Optional.empty();
            return Optional.of(decodeAndVerify(stored, null));
        }));
    }

    @Override
    public boolean replace(EvidenceTask replacement, long expectedRevision) {
        Objects.requireNonNull(replacement, "replacement");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must be non-negative");
        if (replacement.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException("replacement revision must advance exactly once");
        }
        return Boolean.TRUE.equals(transactions.execute(status -> {
            Stored stored = store.mapper().getForUpdate(replacement.taskId());
            if (stored == null) return false;
            EvidenceTask current = decodeAndVerify(stored, replacement.key());
            if (current.revision() != expectedRevision) return false;
            verifyTransitionIdentity(current, replacement);
            // The row lock is the cross-process write fence. Preserve the stored scope so an old
            // installation using the same task kind can be upgraded without moving the record.
            store.save(KIND, replacement.taskId(), stored.projectId, stored.parentId,
                    PersistedTask.from(replacement, json));
            return true;
        }));
    }

    private Stored record(EvidenceTask task) {
        Stored row = new Stored();
        row.id = task.taskId();
        row.kind = KIND;
        row.projectId = SHARED_PROJECT;
        row.parentId = "";
        row.body = store.encode(PersistedTask.from(task, json));
        row.createdAt = task.createdAt().toEpochMilli();
        return row;
    }

    private EvidenceTask decodeAndVerify(Stored stored, EvidenceNeedKey expectedKey) {
        if (!KIND.equals(stored.kind)) throw new IllegalStateException("EVIDENCE_TASK_ID_COLLISION");
        PersistedTask snapshot = store.decode(stored.body, PersistedTask.class);
        if (!SCHEMA.equals(snapshot.schemaVersion())) {
            throw new IllegalStateException("EVIDENCE_TASK_SCHEMA_UNSUPPORTED");
        }
        EvidenceTask task = snapshot.toDomain(json);
        if (!stored.id.equals(task.taskId()) || !task.taskId().equals(task.key().id())) {
            throw new IllegalStateException("EVIDENCE_TASK_IDENTITY_CORRUPT");
        }
        if (expectedKey != null && !expectedKey.equals(task.key())) {
            throw new IllegalStateException("EVIDENCE_TASK_KEY_COLLISION");
        }
        return task;
    }

    private static void verifyTransitionIdentity(EvidenceTask current, EvidenceTask replacement) {
        if (!current.taskId().equals(replacement.taskId())
                || !current.key().equals(replacement.key())
                || current.maxAttempts() != replacement.maxAttempts()
                || !current.createdAt().equals(replacement.createdAt())) {
            throw new IllegalArgumentException("evidence task identity is immutable");
        }
        if (replacement.attempts() < current.attempts()
                || replacement.updatedAt().isBefore(current.updatedAt())) {
            throw new IllegalArgumentException("evidence task transition moves backwards");
        }
    }

    private static String requiredId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("taskId is required");
        return value.trim();
    }

    private static <T> T required(T value) {
        if (value == null) throw new IllegalStateException("transaction returned no result");
        return value;
    }

    /** Storage DTO adds an explicit discriminator for the sealed Observation interface. */
    public record PersistedTask(
            String schemaVersion,
            String taskId,
            EvidenceNeedKey key,
            EvidenceTaskStatus status,
            long revision,
            int attempts,
            int maxAttempts,
            EvidenceLease lease,
            Instant nextAttemptAt,
            PersistedResult result,
            EvidenceTaskFailure failure,
            Set<String> subscriberIds,
            Instant createdAt,
            Instant updatedAt) {

        static PersistedTask from(EvidenceTask task, ObjectMapper json) {
            return new PersistedTask(SCHEMA, task.taskId(), task.key(), task.status(),
                    task.revision(), task.attempts(), task.maxAttempts(), task.lease(),
                    task.nextAttemptAt(), PersistedResult.from(task.result(), json), task.failure(),
                    task.subscriberIds(), task.createdAt(), task.updatedAt());
        }

        EvidenceTask toDomain(ObjectMapper json) {
            return new EvidenceTask(taskId, key, status, revision, attempts, maxAttempts, lease,
                    nextAttemptAt, result == null ? null : result.toDomain(json), failure,
                    subscriberIds, createdAt, updatedAt);
        }
    }

    public record PersistedResult(
            EvidenceExtractionResult.Disposition disposition,
            List<PersistedObservation> observations,
            EvidenceScope scope,
            String message,
            Map<String, String> diagnostics) {

        static PersistedResult from(EvidenceExtractionResult result, ObjectMapper json) {
            if (result == null) return null;
            return new PersistedResult(result.disposition(), result.observations().stream()
                    .map(observation -> PersistedObservation.from(observation, json)).toList(),
                    result.scope(), result.message(), result.diagnostics());
        }

        EvidenceExtractionResult toDomain(ObjectMapper json) {
            List<Observation> decoded = observations == null ? List.of()
                    : observations.stream().map(observation -> observation.toDomain(json)).toList();
            return new EvidenceExtractionResult(disposition, decoded, scope, message, diagnostics);
        }
    }

    public record PersistedObservation(String type, JsonNode value) {
        static PersistedObservation from(Observation observation, ObjectMapper json) {
            String type;
            if (observation instanceof Observation.MaterialObservation) type = "MATERIAL";
            else if (observation instanceof Observation.SealObservation) type = "SEAL";
            else if (observation instanceof Observation.CertificateObservation) type = "CERTIFICATE";
            else if (observation instanceof Observation.PriceObservation) type = "PRICE";
            else if (observation instanceof Observation.PersonnelObservation) type = "PERSONNEL";
            else throw new IllegalArgumentException("unsupported observation type: " + observation.getClass());
            return new PersistedObservation(type, json.valueToTree(observation));
        }

        Observation toDomain(ObjectMapper json) {
            if (type == null || value == null) throw new IllegalStateException("EVIDENCE_OBSERVATION_CORRUPT");
            Class<? extends Observation> target = switch (type) {
                case "MATERIAL" -> Observation.MaterialObservation.class;
                case "SEAL" -> Observation.SealObservation.class;
                case "CERTIFICATE" -> Observation.CertificateObservation.class;
                case "PRICE" -> Observation.PriceObservation.class;
                case "PERSONNEL" -> Observation.PersonnelObservation.class;
                default -> throw new IllegalStateException("EVIDENCE_OBSERVATION_TYPE_UNSUPPORTED:" + type);
            };
            try {
                return json.treeToValue(value, target);
            } catch (Exception unreadable) {
                throw new IllegalStateException("EVIDENCE_OBSERVATION_CORRUPT", unreadable);
            }
        }
    }
}
