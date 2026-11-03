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
        if (value == null || cn.bidcheck.compat.Java8Api.isBlank(value)) throw new IllegalArgumentException("taskId is required");
        return value.trim();
    }

    private static <T> T required(T value) {
        if (value == null) throw new IllegalStateException("transaction returned no result");
        return value;
    }

    /** Storage DTO adds an explicit discriminator for the sealed Observation interface. */
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class PersistedTask {
        private final String schemaVersion;
        private final String taskId;
        private final EvidenceNeedKey key;
        private final EvidenceTaskStatus status;
        private final long revision;
        private final int attempts;
        private final int maxAttempts;
        private final EvidenceLease lease;
        private final Instant nextAttemptAt;
        private final PersistedResult result;
        private final EvidenceTaskFailure failure;
        private final Set<String> subscriberIds;
        private final Instant createdAt;
        private final Instant updatedAt;
        @com.fasterxml.jackson.annotation.JsonCreator
        public PersistedTask(@com.fasterxml.jackson.annotation.JsonProperty("schemaVersion") String schemaVersion, @com.fasterxml.jackson.annotation.JsonProperty("taskId") String taskId, @com.fasterxml.jackson.annotation.JsonProperty("key") EvidenceNeedKey key, @com.fasterxml.jackson.annotation.JsonProperty("status") EvidenceTaskStatus status, @com.fasterxml.jackson.annotation.JsonProperty("revision") long revision, @com.fasterxml.jackson.annotation.JsonProperty("attempts") int attempts, @com.fasterxml.jackson.annotation.JsonProperty("maxAttempts") int maxAttempts, @com.fasterxml.jackson.annotation.JsonProperty("lease") EvidenceLease lease, @com.fasterxml.jackson.annotation.JsonProperty("nextAttemptAt") Instant nextAttemptAt, @com.fasterxml.jackson.annotation.JsonProperty("result") PersistedResult result, @com.fasterxml.jackson.annotation.JsonProperty("failure") EvidenceTaskFailure failure, @com.fasterxml.jackson.annotation.JsonProperty("subscriberIds") Set<String> subscriberIds, @com.fasterxml.jackson.annotation.JsonProperty("createdAt") Instant createdAt, @com.fasterxml.jackson.annotation.JsonProperty("updatedAt") Instant updatedAt) { this.schemaVersion = schemaVersion; this.taskId = taskId; this.key = key; this.status = status; this.revision = revision; this.attempts = attempts; this.maxAttempts = maxAttempts; this.lease = lease; this.nextAttemptAt = nextAttemptAt; this.result = result; this.failure = failure; this.subscriberIds = subscriberIds; this.createdAt = createdAt; this.updatedAt = updatedAt; }
        public String schemaVersion() { return schemaVersion; }
        public String taskId() { return taskId; }
        public EvidenceNeedKey key() { return key; }
        public EvidenceTaskStatus status() { return status; }
        public long revision() { return revision; }
        public int attempts() { return attempts; }
        public int maxAttempts() { return maxAttempts; }
        public EvidenceLease lease() { return lease; }
        public Instant nextAttemptAt() { return nextAttemptAt; }
        public PersistedResult result() { return result; }
        public EvidenceTaskFailure failure() { return failure; }
        public Set<String> subscriberIds() { return subscriberIds; }
        public Instant createdAt() { return createdAt; }
        public Instant updatedAt() { return updatedAt; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; PersistedTask that = (PersistedTask) other; return java.util.Objects.equals(this.schemaVersion, that.schemaVersion) && java.util.Objects.equals(this.taskId, that.taskId) && java.util.Objects.equals(this.key, that.key) && java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.revision, that.revision) && java.util.Objects.equals(this.attempts, that.attempts) && java.util.Objects.equals(this.maxAttempts, that.maxAttempts) && java.util.Objects.equals(this.lease, that.lease) && java.util.Objects.equals(this.nextAttemptAt, that.nextAttemptAt) && java.util.Objects.equals(this.result, that.result) && java.util.Objects.equals(this.failure, that.failure) && java.util.Objects.equals(this.subscriberIds, that.subscriberIds) && java.util.Objects.equals(this.createdAt, that.createdAt) && java.util.Objects.equals(this.updatedAt, that.updatedAt); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.schemaVersion); hash = 31 * hash + java.util.Objects.hashCode(this.taskId); hash = 31 * hash + java.util.Objects.hashCode(this.key); hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.revision); hash = 31 * hash + java.util.Objects.hashCode(this.attempts); hash = 31 * hash + java.util.Objects.hashCode(this.maxAttempts); hash = 31 * hash + java.util.Objects.hashCode(this.lease); hash = 31 * hash + java.util.Objects.hashCode(this.nextAttemptAt); hash = 31 * hash + java.util.Objects.hashCode(this.result); hash = 31 * hash + java.util.Objects.hashCode(this.failure); hash = 31 * hash + java.util.Objects.hashCode(this.subscriberIds); hash = 31 * hash + java.util.Objects.hashCode(this.createdAt); hash = 31 * hash + java.util.Objects.hashCode(this.updatedAt); return hash; }
        @Override public String toString() { return "PersistedTask[" + "schemaVersion=" + schemaVersion + ", taskId=" + taskId + ", key=" + key + ", status=" + status + ", revision=" + revision + ", attempts=" + attempts + ", maxAttempts=" + maxAttempts + ", lease=" + lease + ", nextAttemptAt=" + nextAttemptAt + ", result=" + result + ", failure=" + failure + ", subscriberIds=" + subscriberIds + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + "]"; }


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

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class PersistedResult {
        private final EvidenceExtractionResult.Disposition disposition;
        private final List<PersistedObservation> observations;
        private final EvidenceScope scope;
        private final String message;
        private final Map<String, String> diagnostics;
        @com.fasterxml.jackson.annotation.JsonCreator
        public PersistedResult(@com.fasterxml.jackson.annotation.JsonProperty("disposition") EvidenceExtractionResult.Disposition disposition, @com.fasterxml.jackson.annotation.JsonProperty("observations") List<PersistedObservation> observations, @com.fasterxml.jackson.annotation.JsonProperty("scope") EvidenceScope scope, @com.fasterxml.jackson.annotation.JsonProperty("message") String message, @com.fasterxml.jackson.annotation.JsonProperty("diagnostics") Map<String, String> diagnostics) { this.disposition = disposition; this.observations = observations; this.scope = scope; this.message = message; this.diagnostics = diagnostics; }
        public EvidenceExtractionResult.Disposition disposition() { return disposition; }
        public List<PersistedObservation> observations() { return observations; }
        public EvidenceScope scope() { return scope; }
        public String message() { return message; }
        public Map<String, String> diagnostics() { return diagnostics; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; PersistedResult that = (PersistedResult) other; return java.util.Objects.equals(this.disposition, that.disposition) && java.util.Objects.equals(this.observations, that.observations) && java.util.Objects.equals(this.scope, that.scope) && java.util.Objects.equals(this.message, that.message) && java.util.Objects.equals(this.diagnostics, that.diagnostics); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.disposition); hash = 31 * hash + java.util.Objects.hashCode(this.observations); hash = 31 * hash + java.util.Objects.hashCode(this.scope); hash = 31 * hash + java.util.Objects.hashCode(this.message); hash = 31 * hash + java.util.Objects.hashCode(this.diagnostics); return hash; }
        @Override public String toString() { return "PersistedResult[" + "disposition=" + disposition + ", observations=" + observations + ", scope=" + scope + ", message=" + message + ", diagnostics=" + diagnostics + "]"; }


        static PersistedResult from(EvidenceExtractionResult result, ObjectMapper json) {
            if (result == null) return null;
            return new PersistedResult(result.disposition(), cn.bidcheck.compat.Java8Api.toList(result.observations().stream()
                    .map(observation -> PersistedObservation.from(observation, json))),
                    result.scope(), result.message(), result.diagnostics());
        }

        EvidenceExtractionResult toDomain(ObjectMapper json) {
            List<Observation> decoded = observations == null ? cn.bidcheck.compat.Java8Api.listOf()
                    : cn.bidcheck.compat.Java8Api.toList(observations.stream().map(observation -> observation.toDomain(json)));
            return new EvidenceExtractionResult(disposition, decoded, scope, message, diagnostics);
        }
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class PersistedObservation {
        private final String type;
        private final JsonNode value;
        @com.fasterxml.jackson.annotation.JsonCreator
        public PersistedObservation(@com.fasterxml.jackson.annotation.JsonProperty("type") String type, @com.fasterxml.jackson.annotation.JsonProperty("value") JsonNode value) { this.type = type; this.value = value; }
        public String type() { return type; }
        public JsonNode value() { return value; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; PersistedObservation that = (PersistedObservation) other; return java.util.Objects.equals(this.type, that.type) && java.util.Objects.equals(this.value, that.value); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.type); hash = 31 * hash + java.util.Objects.hashCode(this.value); return hash; }
        @Override public String toString() { return "PersistedObservation[" + "type=" + type + ", value=" + value + "]"; }

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
            Class<? extends Observation> target;
switch (type) {case "MATERIAL":target = Observation.MaterialObservation.class; break;case "SEAL":target = Observation.SealObservation.class; break;case "CERTIFICATE":target = Observation.CertificateObservation.class; break;case "PRICE":target = Observation.PriceObservation.class; break;case "PERSONNEL":target = Observation.PersonnelObservation.class; break;default:throw new IllegalStateException("EVIDENCE_OBSERVATION_TYPE_UNSUPPORTED:" + type);}
            try {
                return json.treeToValue(value, target);
            } catch (Exception unreadable) {
                throw new IllegalStateException("EVIDENCE_OBSERVATION_CORRUPT", unreadable);
            }
        }
    }
}
