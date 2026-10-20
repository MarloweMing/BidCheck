package cn.bidcheck.retrieval;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import static cn.bidcheck.retrieval.LexicalIndex.*;

/** Document-isolated exact cosine index, BM25/RRF and real remote reranking. No vector database is implied. */
@Service
public class HybridRetriever {
    private static final String POLICY = "hybrid-bm25-cosine-rrf-rerank-1";
    private static final int BATCH = 10, MAX_HITS = 16, RESPONSE_LIMIT = 2 * 1024 * 1024;
    private final boolean requested;
    private final URI embeddingUrl, rerankUrl;
    private final String key, embeddingModel, rerankModel, configurationIssue, vectorIdentity;
    private final int dimensions, concurrency;
    private final Path cacheRoot;
    private final Duration totalBudget, preparationBudget, requestBudget;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(RESPONSE_LIMIT).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Semaphore network;
    private final ReentrantLock[] locks = new ReentrantLock[16];
    private final Map<String, Index> indexes = new LinkedHashMap<>(4, .75f, true);

    public HybridRetriever() { this(false, "", "", "", "text-embedding-v4", "qwen3-rerank", "data/retrieval", 1024); }

    public HybridRetriever(boolean enabled, String embeddingUrl, String rerankUrl, String key, String embeddingModel,
                           String rerankModel, String cacheDirectory, int dimensions) {
        this(enabled, embeddingUrl, rerankUrl, key, embeddingModel, rerankModel, cacheDirectory, dimensions,
                Duration.ofSeconds(90), Duration.ofSeconds(30), Duration.ofMinutes(10), 4);
    }

    @Autowired
    public HybridRetriever(@Value("${bidcheck.retrieval.enabled:false}") boolean enabled,
            @Value("${bidcheck.retrieval.embedding-url:https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding}") String embeddingUrl,
            @Value("${bidcheck.retrieval.rerank-url:https://dashscope.aliyuncs.com/compatible-api/v1/reranks}") String rerankUrl,
            @Value("${bidcheck.retrieval.key:}") String key,
            @Value("${bidcheck.retrieval.embedding-model:text-embedding-v4}") String embeddingModel,
            @Value("${bidcheck.retrieval.rerank-model:qwen3-rerank}") String rerankModel,
            @Value("${bidcheck.retrieval.cache-dir:${bidcheck.data}/retrieval}") String cacheDirectory,
            @Value("${bidcheck.retrieval.dimensions:1024}") int dimensions,
            @Value("${bidcheck.retrieval.embedding-concurrency:4}") int concurrency,
            @Value("${bidcheck.retrieval.preparation-timeout-seconds:600}") int preparationSeconds,
            @Value("${bidcheck.retrieval.request-timeout-seconds:30}") int requestSeconds) {
        this(enabled, embeddingUrl, rerankUrl, key, embeddingModel, rerankModel, cacheDirectory, dimensions,
                Duration.ofSeconds(90), Duration.ofSeconds(requestSeconds), Duration.ofSeconds(preparationSeconds), concurrency);
    }

    HybridRetriever(boolean enabled, String embeddingUrl, String rerankUrl, String key, String embeddingModel,
                    String rerankModel, String cacheDirectory, int dimensions, Duration totalBudget, Duration requestBudget) {
        this(enabled, embeddingUrl, rerankUrl, key, embeddingModel, rerankModel, cacheDirectory, dimensions,
                totalBudget, requestBudget, totalBudget, 4);
    }

    HybridRetriever(boolean enabled, String embeddingUrl, String rerankUrl, String key, String embeddingModel,
                    String rerankModel, String cacheDirectory, int dimensions, Duration totalBudget, Duration requestBudget,
                    Duration preparationBudget, int concurrency) {
        if (concurrency < 1 || concurrency > 8 || !validBudget(totalBudget) || !validBudget(requestBudget)
                || !validBudget(preparationBudget)) throw new IllegalArgumentException("RETRIEVAL_BUDGET_INVALID");
        this.requested = enabled;
        this.key = safe(key).strip(); this.embeddingModel = safe(embeddingModel).strip(); this.rerankModel = safe(rerankModel).strip();
        this.embeddingUrl = endpoint(embeddingUrl); this.rerankUrl = endpoint(rerankUrl); this.dimensions = dimensions;
        this.cacheRoot = Path.of(cacheDirectory).toAbsolutePath().normalize();
        this.totalBudget = totalBudget; this.requestBudget = requestBudget; this.preparationBudget = preparationBudget;
        this.concurrency = concurrency; this.network = new Semaphore(concurrency, true);
        this.configurationIssue = this.embeddingUrl == null || this.rerankUrl == null || this.key.isBlank()
                || this.key.length() > 4096 || this.key.contains("\n") || this.key.contains("\r")
                || this.embeddingModel.isBlank() || this.embeddingModel.length() > 160 || !"qwen3-rerank".equals(this.rerankModel)
                || !Set.of(64,128,256,512,768,1024,1536,2048).contains(dimensions) ? "CONFIGURATION_INVALID" : "";
        vectorIdentity = hash(POLICY + "\n" + this.embeddingUrl + "\n" + this.embeddingModel + "\n" + dimensions);
        for (int i = 0; i < locks.length; i++) locks[i] = new ReentrantLock();
    }

    /** True means explicitly requested; configuration/network failures remain visible as degraded results. */
    public boolean enabled() { return requested; }
    public String embeddingModelName() { return embeddingModel; }
    public String rerankModelName() { return rerankModel; }
    public String identity() { return requested ? "HYBRID:" + hash(vectorIdentity + "\n" + rerankUrl + "\n" + rerankModel + "\n" + configurationIssue) : "BM25_DISABLED:" + POLICY; }
    public RetrievalResult retrieve(Checkpoint checkpoint, Document document) throws InterruptedIOException {
        String query = checkpoint == null ? "" : safe(checkpoint.title()) + "\n" + safe(checkpoint.expected())
                + "\n" + safe(checkpoint.field()) + "\n" + (checkpoint.source() == null ? "" : safe(checkpoint.source().quote()));
        return search(document, query, MAX_HITS);
    }
    public RetrievalResult search(Document document, String query, int limit) throws InterruptedIOException {
        if (limit < 1 || limit > MAX_HITS) throw new IllegalArgumentException("检索结果上限必须在 1 到 16 之间。");
        return execute(document, safe(query), limit, false, ignored -> {});
    }
    /** Doubt review uses the same index with a smaller per-query deadline. */
    public RetrievalResult search(Document document, String query, int limit, Duration budget) throws InterruptedIOException {
        if (limit < 1 || limit > MAX_HITS || budget == null || budget.isNegative() || budget.isZero()) throw new IllegalArgumentException("Invalid bounded retrieval budget");
        return execute(document, safe(query), limit, false, ignored -> {}, budget.compareTo(totalBudget) < 0 ? budget : totalBudget);
    }
    /** Incremental warm-up; verified batches are cached as they finish. Never calls rerank. */
    public RetrievalResult warmup(Document document) throws InterruptedIOException { return warmup(document, ignored -> {}); }
    public RetrievalResult warmup(Document document, Consumer<Map<String,Object>> progress) throws InterruptedIOException {
        Objects.requireNonNull(progress);
        try {
            return execute(document, "", MAX_HITS, true, snapshot -> {
                try { progress.accept(snapshot); } catch (RuntimeException failure) { throw new ProgressFailure(failure); }
            });
        } catch (ProgressFailure failure) { throw failure.original; }
    }

    private RetrievalResult execute(Document document, String query, int limit, boolean warming, Consumer<Map<String,Object>> progress) throws InterruptedIOException {
        return execute(document, query, limit, warming, progress, warming ? preparationBudget : totalBudget);
    }
    private RetrievalResult execute(Document document, String query, int limit, boolean warming, Consumer<Map<String,Object>> progress, Duration budget) throws InterruptedIOException {
        interrupted();
        Audit audit = new Audit(budget, progress);
        audit.values.put("operation", warming ? "PREPARE" : "SEARCH");
        audit.values.put("embeddingBatchSize", BATCH); audit.values.put("embeddingConcurrency", concurrency);
        List<Scored> ranked = List.of(); Index index = null; String mode = requested ? "BM25_DEGRADED" : "BM25_DISABLED";
        String clippedQuery = clip(query, 2000); audit.values.put("queryTruncated", query.length() > clippedQuery.length());
        try {
            if (document == null || safe(document.projectId()).isBlank() || safe(document.id()).isBlank() || safe(document.sha256()).isBlank()) throw new Failure("DOCUMENT_SCOPE_MISSING");
            String scope = scope(document);
            ReentrantLock lock = locks[Math.floorMod(scope.hashCode(), locks.length)];
            acquire(lock, audit);
            try {
                synchronized (indexes) { index = indexes.get(scope); }
                if (index == null) {
                    index = new Index(scope, new LexicalIndex(document));
                    synchronized (indexes) {
                        indexes.put(scope, index);
                        while (indexes.size() > 2) indexes.remove(indexes.keySet().iterator().next());
                    }
                }
                audit.values.put("chunks", index.lexical.chunks.size());
                audit.documentChunks = index.lexical.chunks.size();
                audit.values.put("indexBackend", "LOCAL_EXACT_COSINE");
                audit.values.put("scopeFingerprint", scope);
                ranked = index.lexical.search(clippedQuery, TOP_K);
                audit.lexicalRanks = rankMap(ranked);
                if (!requested) return finish(index, ranked, mode, limit, audit);
                if (!configurationIssue.isEmpty()) throw new Failure(configurationIssue);
                if (index.lexical.chunks.isEmpty()) throw new Failure("DOCUMENT_EMPTY");
                hydrate(index, audit);
            } finally { lock.unlock(); }
            audit.values.put("indexedChunks", index.vectors.length);
            audit.values.put("denseCoverageComplete", true);
            if (warming) return finish(index, List.of(), "INDEX_READY", limit, audit);
            if (clippedQuery.isBlank()) throw new Failure("QUERY_EMPTY");
            double[] queryVector = vectors(index.scope, List.of(clippedQuery), "query", audit).get(0);
            List<Scored> dense = cosine(index.vectors, queryVector);
            audit.denseRanks = rankMap(dense);
            ranked = fuse(ranked, dense);
            audit.values.put("fusedCandidates", ranked.size());
            mode = "HYBRID_RRF_DEGRADED";
            ranked = rerank(clippedQuery, index, ranked, limit, audit);
            mode = "HYBRID_RERANKED";
        } catch (InterruptedIOException interrupted) { throw interrupted; }
        catch (Failure failure) { audit.values.put("failureCode", failure.code); audit.failures.putIfAbsent(failure.code, new AtomicInteger(1)); }
        catch (IllegalArgumentException failure) { audit.values.put("failureCode", "DOCUMENT_OR_INDEX_INVALID"); }
        catch (IOException failure) { audit.values.put("failureCode", "CACHE_OR_RESPONSE_INVALID"); }
        return finish(index, ranked, mode, limit, audit);
    }

    private RetrievalResult finish(Index index, List<Scored> ranked, String mode, int limit, Audit audit) throws InterruptedIOException {
        interrupted();
        List<Evidence> hits = new ArrayList<>(); List<Map<String,Object>> detail = new ArrayList<>();
        for (Scored scored : ranked.subList(0, Math.min(limit, ranked.size()))) {
            Chunk chunk = index.lexical.chunks.get(scored.index()); hits.add(chunk.evidence());
            detail.add(Map.of("anchor", chunk.evidence().anchor(), "location", chunk.evidence().page(), "scopeHint", chunk.scopeHint(),
                    "bm25Rank", audit.lexicalRanks.getOrDefault(scored.index(), 0), "denseRank", audit.denseRanks.getOrDefault(scored.index(), 0),
                    "rankingScore", scored.score()));
        }
        audit.values.put("mode", mode); audit.values.put("embeddingCalls", audit.embeddingCalls.get()); audit.values.put("rerankCalls", audit.rerankCalls.get());
        audit.values.put("cacheHits", audit.cacheHits); audit.values.put("memoryVectorHits", audit.memoryHits); audit.values.put("cacheInvalid", audit.cacheInvalid);
        audit.values.put("cacheWriteFailures", audit.cacheWriteFailures.get()); audit.values.put("returned", hits.size()); audit.values.put("hits", detail);
        audit.values.putAll(progressSnapshot(audit));
        audit.values.put("newlyEmbeddedTexts", audit.newlyEmbeddedTexts.get());
        audit.values.put("peakInFlightRequests", audit.peakInFlight.get());
        audit.values.put("requestMillis", TimeUnit.NANOSECONDS.toMillis(audit.requestNanos.get()));
        audit.values.put("networkQueueMillis", TimeUnit.NANOSECONDS.toMillis(audit.queueNanos.get()));
        audit.values.put("failureCounts", failureCounts(audit));
        audit.values.put("retryableFailure", retryable(audit.values.getOrDefault("failureCode", "").toString()));
        audit.values.putIfAbsent("denseCoverageComplete", false);
        audit.values.put("scopeBoundary", "仅提供原文位置及检索线索，不确认项目、主体、人员身份或证据真实性。");
        reportProgress(audit);
        return new RetrievalResult(hits, mode, audit.values);
    }

    private void hydrate(Index index, Audit audit) throws IOException {
        if (index.vectors != null) {
            audit.memoryHits += index.vectors.length; audit.documentChunks = index.vectors.length;
            audit.completedChunks.set(index.vectors.length); reportProgress(audit); return;
        }
        List<String> texts = index.lexical.chunks.stream().map(Chunk::searchText).toList();
        List<double[]> vectors = vectors(index.scope, texts, "document", audit);
        index.vectors = vectors.toArray(double[][]::new); // Publish only a complete, ordered index.
    }

    private List<double[]> vectors(String scope, List<String> texts, String type, Audit audit) throws IOException {
        List<double[]> result = new ArrayList<>(Collections.nCopies(texts.size(), null));
        List<Integer> missing = new ArrayList<>();
        boolean document = "document".equals(type);
        if (document) audit.documentChunks = texts.size();
        for (int i = 0; i < texts.size(); i++) {
            remaining(audit);
            double[] cached = readVector(scope, type, texts.get(i), audit);
            if (cached != null) {
                result.set(i, cached); audit.cacheHits++;
                if (document) audit.completedChunks.incrementAndGet();
            } else missing.add(i);
            if (document && i > 0 && i % 128 == 0) reportProgress(audit);
        }
        if (document) {
            audit.cachedDocumentChunks = audit.completedChunks.get();
            audit.totalBatches = (missing.size() + BATCH - 1) / BATCH;
            reportProgress(audit);
        }
        if (missing.isEmpty()) return result;

        // Queue only as many batches as there are workers. One failure stops new dispatch;
        // other in-flight successes still validate and persist before the operation returns.
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(concurrency, (missing.size() + BATCH - 1) / BATCH), task -> {
            Thread thread = new Thread(task, "retrieval-embedding"); thread.setDaemon(true); return thread;
        });
        CompletionService<EmbeddedBatch> completed = new ExecutorCompletionService<>(executor);
        Set<Future<EmbeddedBatch>> pending = new LinkedHashSet<>();
        Failure firstFailure = null;
        try {
            int next = 0;
            while (next < missing.size() || !pending.isEmpty()) {
                while (firstFailure == null && next < missing.size() && pending.size() < concurrency) {
                    remaining(audit);
                    List<Integer> positions = List.copyOf(missing.subList(next, Math.min(next + BATCH, missing.size())));
                    pending.add(completed.submit(() -> embedBatch(scope, texts, positions, type, audit)));
                    next += positions.size();
                    if (document) audit.submittedBatches++;
                }
                if (pending.isEmpty()) break;
                Future<EmbeddedBatch> ready = completed.poll(Math.min(remaining(audit), TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS);
                if (ready == null) { reportProgress(audit); continue; }
                pending.remove(ready);
                try {
                    EmbeddedBatch batch = ready.get();
                    for (int j = 0; j < batch.positions.size(); j++) result.set(batch.positions.get(j), batch.vectors[j]);
                    if (document) audit.completedBatches++;
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof InterruptedIOException interrupted) throw interrupted;
                    Failure failure = cause instanceof Failure known ? known : new Failure("EMBEDDING_RESPONSE_INVALID");
                    audit.failures.computeIfAbsent(failure.code, ignored -> new AtomicInteger()).incrementAndGet();
                    if (firstFailure == null) firstFailure = failure;
                    if (document) audit.failedBatches++;
                }
                reportProgress(audit);
            }
            if (firstFailure != null) throw firstFailure;
            remaining(audit);
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("检索已中断。");
        } finally {
            for (Future<?> task : pending) { if (!task.isDone() && document) audit.cancelledBatches++; task.cancel(true); }
            executor.shutdownNow();
            try { executor.awaitTermination(250, TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    private EmbeddedBatch embedBatch(String scope, List<String> texts, List<Integer> positions, String type, Audit audit) throws IOException {
        List<String> batch = positions.stream().map(texts::get).toList();
        JsonNode response = post(embeddingUrl, Map.of("model", embeddingModel, "input", Map.of("texts", batch),
                "parameters", Map.of("text_type", type, "dimension", dimensions, "output_type", "dense")), "EMBEDDING", audit);
        JsonNode embeddings = response.path("output").path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != batch.size()) throw new Failure("EMBEDDING_COUNT_INVALID");
        double[][] ordered = new double[batch.size()][];
        for (JsonNode item : embeddings) {
            JsonNode position = item.path("text_index");
            if (!position.isIntegralNumber() || !position.canConvertToInt() || position.intValue() < 0 || position.intValue() >= ordered.length
                    || ordered[position.intValue()] != null) throw new Failure("EMBEDDING_INDEX_INVALID");
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray() || embedding.size() != dimensions) throw new Failure("EMBEDDING_DIMENSION_INVALID");
            double[] vector = new double[dimensions];
            for (int j = 0; j < dimensions; j++) {
                if (!embedding.get(j).isNumber()) throw new Failure("EMBEDDING_VALUE_INVALID");
                vector[j] = embedding.get(j).doubleValue();
            }
            ordered[position.intValue()] = normalize(vector);
        }
        // Validate the whole response before persisting any vector from this batch.
        for (int j = 0; j < ordered.length; j++) {
            writeVector(scope, type, texts.get(positions.get(j)), ordered[j], audit);
            audit.newlyEmbeddedTexts.incrementAndGet();
            if ("document".equals(type)) audit.completedChunks.incrementAndGet();
        }
        return new EmbeddedBatch(positions, ordered);
    }

    private List<Scored> rerank(String query, Index index, List<Scored> candidates, int limit, Audit audit) throws IOException {
        List<String> documents = candidates.stream().map(scored -> index.lexical.chunks.get(scored.index()).searchText()).toList();
        int count = Math.min(limit, documents.size());
        JsonNode response = post(rerankUrl, Map.of("model", rerankModel, "query", query, "documents", documents, "top_n", count,
                "instruct", "Retrieve source passages supporting the tender requirement. Preserve current assignments and historical project boundaries; similarity does not verify identity or compliance."), "RERANK", audit);
        JsonNode results = response.path("results");
        if (!results.isArray() || results.size() != count) throw new Failure("RERANK_COUNT_INVALID");
        Set<Integer> seen = new HashSet<>(); List<Scored> reranked = new ArrayList<>();
        for (JsonNode item : results) {
            JsonNode position = item.path("index"), score = item.path("relevance_score");
            if (!position.isIntegralNumber() || !position.canConvertToInt() || position.intValue() < 0 || position.intValue() >= candidates.size()
                    || !seen.add(position.intValue())) throw new Failure("RERANK_INDEX_INVALID");
            if (!score.isNumber() || !Double.isFinite(score.doubleValue()) || score.doubleValue() < 0 || score.doubleValue() > 1) throw new Failure("RERANK_SCORE_INVALID");
            reranked.add(new Scored(candidates.get(position.intValue()).index(), score.doubleValue()));
        }
        reranked.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::index));
        return List.copyOf(reranked);
    }

    private JsonNode post(URI uri, Object body, String stage, Audit audit) throws IOException {
        CompletableFuture<HttpResponse<byte[]>> pending = null; boolean acquired = false, startedRequest = false;
        boolean deadlineLimited = false; long started = 0, queued = System.nanoTime();
        try {
            if (!network.tryAcquire(Math.min(remaining(audit), requestBudget.toNanos()), TimeUnit.NANOSECONDS))
                throw new Failure(System.nanoTime() >= audit.deadline ? "RETRIEVAL_TIMEOUT" : stage + "_QUEUE_TIMEOUT");
            acquired = true;
            audit.queueNanos.addAndGet(System.nanoTime() - queued);
            long operationRemaining = remaining(audit);
            deadlineLimited = operationRemaining <= requestBudget.toNanos();
            long budget = Math.min(operationRemaining, requestBudget.toNanos());
            byte[] bytes = json.writeValueAsBytes(body);
            if (bytes.length > 700_000) throw new Failure(stage + "_INPUT_LIMIT");
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(budget)).header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + key).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            started = System.nanoTime(); startedRequest = true;
            audit.peakInFlight.accumulateAndGet(audit.activeRequests.incrementAndGet(), Math::max);
            ("EMBEDDING".equals(stage) ? audit.embeddingCalls : audit.rerankCalls).incrementAndGet();
            pending = client.sendAsync(request, ignored -> new LimitedBody(RESPONSE_LIMIT));
            var response = pending.get(budget, TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) throw new Failure(stage + "_HTTP_" + response.statusCode());
            JsonNode parsed;
            try { parsed = json.readTree(response.body()); } catch (IOException malformed) { throw new Failure(stage + "_RESPONSE_INVALID"); }
            if (parsed == null || !parsed.isObject() || !parsed.path("code").asText("").isBlank()
                    || (parsed.has("model") && !parsed.path("model").asText().equals(stage.equals("EMBEDDING") ? embeddingModel : rerankModel))) throw new Failure(stage + "_ENVELOPE_INVALID");
            return parsed;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new InterruptedIOException("检索已中断。"); }
        catch (TimeoutException timeout) { throw timeout(stage, audit, deadlineLimited); }
        catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof HttpTimeoutException) throw timeout(stage, audit, deadlineLimited);
            if (cause instanceof IOException && "RESPONSE_LIMIT".equals(cause.getMessage())) throw new Failure(stage + "_RESPONSE_LIMIT");
            throw new Failure(stage + "_COMMUNICATION_FAILED");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            if (startedRequest) { audit.requestNanos.addAndGet(System.nanoTime() - started); audit.activeRequests.decrementAndGet(); }
            if (acquired) network.release();
        }
    }

    private static Failure timeout(String stage, Audit audit, boolean deadlineLimited) {
        return new Failure(deadlineLimited || System.nanoTime() >= audit.deadline ? "RETRIEVAL_TIMEOUT" : stage + "_REQUEST_TIMEOUT");
    }

    private double[] readVector(String scope, String type, String text, Audit audit) throws InterruptedIOException {
        interrupted();
        try {
            Path path = vectorPath(scope, type, text);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 64_000) throw new IOException();
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)))) {
                if (input.readInt() != 0x42435631 || !scope.equals(input.readUTF()) || !vectorIdentity.equals(input.readUTF())
                        || !inputHash(type,text).equals(input.readUTF()) || input.readInt() != dimensions) throw new IOException();
                double[] vector = new double[dimensions]; for (int i = 0; i < dimensions; i++) vector[i] = input.readDouble();
                if (!vectorChecksum(vector).equals(input.readUTF()) || input.read() != -1) throw new IOException();
                return normalize(vector);
            }
        } catch (IOException | IllegalArgumentException invalid) { audit.cacheInvalid++; return null; }
    }

    private void writeVector(String scope, String type, String text, double[] vector, Audit audit) throws InterruptedIOException {
        interrupted(); Path temporary = null;
        try {
            Path target = vectorPath(scope,type,text);
            Files.createDirectories(target.getParent());
            checkedDirectories(target.getParent());
            if (Files.isSymbolicLink(target)) throw new IOException();
            temporary = Files.createTempFile(target.getParent(), ".vector-", ".tmp");
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(0x42435631); output.writeUTF(scope); output.writeUTF(vectorIdentity); output.writeUTF(inputHash(type,text));
                output.writeInt(dimensions); for (double value : vector) output.writeDouble(value); output.writeUTF(vectorChecksum(vector));
            }
            try { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING); }
            temporary = null;
        } catch (IOException invalid) { audit.cacheWriteFailures.incrementAndGet(); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
    private Path vectorPath(String scope, String type, String text) throws IOException {
        Path path = cacheRoot.resolve(scope).resolve(vectorIdentity).resolve(inputHash(type,text) + ".vec");
        checkedDirectories(path.getParent()); return path;
    }
    private void checkedDirectories(Path path) throws IOException {
        for (Path current = path; current != null && current.startsWith(cacheRoot); current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("CACHE_LINK");
    }

    private static List<Scored> cosine(double[][] vectors, double[] query) throws InterruptedIOException {
        List<Scored> scores = new ArrayList<>();
        for (int i = 0; i < vectors.length; i++) {
            interrupted(); double score = 0;
            for (int j = 0; j < query.length; j++) score += query[j] * vectors[i][j];
            scores.add(new Scored(i, Math.max(-1, Math.min(1, score))));
        }
        scores.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::index));
        return List.copyOf(scores.subList(0, Math.min(TOP_K,scores.size())));
    }
    static List<Scored> fuse(List<Scored> lexical, List<Scored> dense) {
        Map<Integer,Double> scores = new HashMap<>();
        for (List<Scored> route : List.of(lexical,dense)) for (int i = 0; i < route.size(); i++) scores.merge(route.get(i).index(),1d/(60+i+1),Double::sum);
        return scores.entrySet().stream().map(e->new Scored(e.getKey(),e.getValue()))
                .sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::index)).toList();
    }
    private static Map<Integer,Integer> rankMap(List<Scored> ranked) {
        Map<Integer,Integer> ranks = new HashMap<>(); for(int i=0;i<ranked.size();i++)ranks.put(ranked.get(i).index(),i+1); return ranks;
    }
    private static double[] normalize(double[] vector) throws Failure {
        double norm = 0; for (double value : vector) { if (!Double.isFinite(value) || Math.abs(value)>1e10) throw new Failure("EMBEDDING_VALUE_INVALID"); norm += value*value; }
        if (!Double.isFinite(norm) || norm < 1e-20) throw new Failure("EMBEDDING_ZERO_VECTOR");
        double[] normalized = vector.clone(); norm = Math.sqrt(norm); for(int i=0;i<normalized.length;i++)normalized[i]/=norm; return normalized;
    }
    private static URI endpoint(String value) {
        try {
            if (value == null || value.length()>2048) return null;
            URI uri=URI.create(value.strip());
            return uri.getHost()!=null && uri.getUserInfo()==null && uri.getFragment()==null && uri.getQuery()==null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || ("http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()))) ? uri : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }
    private static String scope(Document document) throws InterruptedIOException {
        MessageDigest digest=digest();
        for (String value : List.of(safe(document.projectId()),safe(document.id()),Integer.toString(document.version()),safe(document.sha256()))) update(digest,value);
        if(document.pages()==null || document.pages().size()>20_000)throw new IllegalArgumentException("DOCUMENT_LIMIT");
        long characters=0;
        for(Page page:document.pages()) { interrupted(); if(page==null)throw new IllegalArgumentException("DOCUMENT_INVALID");
            characters+=safe(page.text()).length();if(characters>2_000_000)throw new IllegalArgumentException("DOCUMENT_LIMIT");
            update(digest,Integer.toString(page.number()));update(digest,safe(page.locationType()));update(digest,safe(page.parser()));update(digest,safe(page.text())); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void update(MessageDigest digest,String value) { byte[] bytes=value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes); }
    private static String inputHash(String type,String text) { return hash(type+"\n"+text); }
    private static String vectorChecksum(double[] vector) { MessageDigest digest=digest();for(double value:vector)digest.update(ByteBuffer.allocate(8).putDouble(value).array());return HexFormat.of().formatHex(digest.digest()); }
    private static String hash(String value) { return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static MessageDigest digest() { try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);} }
    private static String safe(String value) { return value==null?"":value; }
    private static boolean validBudget(Duration budget) {
        return budget != null && !budget.isZero() && !budget.isNegative() && budget.compareTo(Duration.ofHours(1)) <= 0;
    }
    private static boolean retryable(String code) {
        return "RETRIEVAL_TIMEOUT".equals(code) || code.endsWith("_REQUEST_TIMEOUT") || code.endsWith("_QUEUE_TIMEOUT")
                || code.endsWith("_COMMUNICATION_FAILED") || code.endsWith("_HTTP_408") || code.endsWith("_HTTP_425")
                || code.endsWith("_HTTP_429") || code.matches(".*_HTTP_5[0-9]{2}");
    }
    private static Map<String,Integer> failureCounts(Audit audit) {
        Map<String,Integer> counts = new TreeMap<>(); audit.failures.forEach((code, count) -> counts.put(code, count.get()));
        return Collections.unmodifiableMap(counts);
    }
    private static Map<String,Object> progressSnapshot(Audit audit) {
        Map<String,Object> progress = new LinkedHashMap<>();
        int completed = audit.completedChunks.get();
        progress.put("chunks", audit.documentChunks);
        progress.put("completedChunks", completed);
        progress.put("remainingChunks", Math.max(0, audit.documentChunks - completed));
        progress.put("cachedDocumentChunks", audit.cachedDocumentChunks);
        progress.put("embeddingBatchesTotal", audit.totalBatches);
        progress.put("embeddingBatchesSubmitted", audit.submittedBatches);
        progress.put("embeddingBatchesCompleted", audit.completedBatches);
        progress.put("embeddingBatchesFailed", audit.failedBatches);
        progress.put("embeddingBatchesCancelled", audit.cancelledBatches);
        progress.put("embeddingCalls", audit.embeddingCalls.get());
        progress.put("activeRequests", audit.activeRequests.get());
        progress.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - audit.started));
        progress.put("operationBudgetMs", audit.budget.toMillis());
        progress.put("operationDeadlineExceeded", System.nanoTime() >= audit.deadline || audit.failures.containsKey("RETRIEVAL_TIMEOUT"));
        progress.put("denseCoverageComplete", audit.values.getOrDefault("denseCoverageComplete", false));
        return Collections.unmodifiableMap(progress);
    }
    private static void reportProgress(Audit audit) { audit.progress.accept(progressSnapshot(audit)); }
    private static long remaining(Audit audit) throws IOException { interrupted();long remaining=audit.deadline-System.nanoTime();if(remaining<=0)throw new Failure("RETRIEVAL_TIMEOUT");return remaining; }
    private static void acquire(ReentrantLock lock,Audit audit)throws IOException {
        try {
            while (!lock.tryLock(Math.min(remaining(audit), TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS)) reportProgress(audit);
        }
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("检索已中断。");}
    }
    private static final class Index {
        final String scope;final LexicalIndex lexical;volatile double[][] vectors;
        Index(String scope,LexicalIndex lexical){this.scope=scope;this.lexical=lexical;}
    }
    private static final class Audit {
        final long started = System.nanoTime(), deadline;
        final Duration budget;
        final Consumer<Map<String,Object>> progress;
        final Map<String,Object> values = new LinkedHashMap<>();
        final ConcurrentMap<String,AtomicInteger> failures = new ConcurrentHashMap<>();
        final AtomicInteger embeddingCalls = new AtomicInteger(), rerankCalls = new AtomicInteger(), cacheWriteFailures = new AtomicInteger(),
                newlyEmbeddedTexts = new AtomicInteger(), completedChunks = new AtomicInteger(), activeRequests = new AtomicInteger(), peakInFlight = new AtomicInteger();
        final AtomicLong requestNanos = new AtomicLong(), queueNanos = new AtomicLong();
        int cacheHits, memoryHits, cacheInvalid, documentChunks, cachedDocumentChunks, totalBatches,
                submittedBatches, completedBatches, failedBatches, cancelledBatches;
        Map<Integer,Integer> lexicalRanks=Map.of(),denseRanks=Map.of();
        Audit(Duration budget, Consumer<Map<String,Object>> progress) { this.budget = budget; this.deadline = started + budget.toNanos(); this.progress = progress; }
    }
    private record EmbeddedBatch(List<Integer> positions, double[][] vectors) { }
    private static final class ProgressFailure extends RuntimeException {
        final RuntimeException original; ProgressFailure(RuntimeException original) { this.original = original; }
    }
    private static final class Failure extends IOException { final String code;Failure(String code){super(code);this.code=code;} }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final int limit;final ByteArrayOutputStream bytes=new ByteArrayOutputStream();final CompletableFuture<byte[]> result=new CompletableFuture<>();Flow.Subscription subscription;
        LimitedBody(int limit){this.limit=limit;} public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        public void onNext(List<ByteBuffer> items){for(ByteBuffer item:items){if((long)bytes.size()+item.remaining()>limit){subscription.cancel();result.completeExceptionally(new IOException("RESPONSE_LIMIT"));return;}byte[] chunk=new byte[item.remaining()];item.get(chunk);bytes.writeBytes(chunk);}subscription.request(1);}
        public void onError(Throwable error){result.completeExceptionally(error);}public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
