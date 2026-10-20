package cn.bidcheck.ocr;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded, optional transcription adapter. OCR text remains untrusted evidence, never a compliance verdict. */
@Component
public final class DashScopeOcrClient implements OcrClient {
    private static final String PROVIDER = "DASHSCOPE";
    private static final String POLICY = "ocr-builtin-transcription-2";
    private static final String TASK = "text_recognition";
    private static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_TEXT_CHARACTERS = 50_000;
    private static final int MAX_CACHE_BYTES = 512 * 1024;
    private static final Set<String> GEOMETRY_FIELDS = Set.of("pos_list", "rotate_rect", "bbox", "bounding_box", "bounding_boxes", "box", "boxes", "polygon", "polygons", "points", "coordinates");
    private static final Set<String> METADATA_FIELDS = Set.of("x", "y", "width", "height", "angle", "rotation", "confidence", "score", "page", "page_index", "index", "data", "result", "results", "regions", "lines");
    private static final Set<String> TRANSCRIPTION_FIELDS = Set.of("text", "texts", "content", "transcription", "word", "words", "label", "value");
    private final OcrRequestGovernor governor;
    private final OcrRequestSettings settings;
    private final DashScopeBatchOcrClient batches;

    private final String key, model, identity;
    private final URI endpoint;
    private final Path cacheDirectory;
    private final Duration timeout;
    private final HttpClient client;
    private final boolean active;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(MAX_RESPONSE_BYTES).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public DashScopeOcrClient(boolean enabled, String url, String key, String model, String data) {
        this(enabled,url,key,model,data,2);
    }

    public DashScopeOcrClient(boolean enabled, String url, String key, String model, String data, int concurrency) {
        this(enabled, url, key, model, data, concurrency, OcrRequestSettings.defaults());
    }

    public DashScopeOcrClient(boolean enabled, String url, String key, String model, String data, int concurrency, OcrRequestSettings settings) {
        this(enabled, url, key, model, data, concurrency, settings, OcrBatchSettings.defaults());
    }

    @Autowired
    public DashScopeOcrClient(@Value("${bidcheck.ocr.enabled:false}") boolean enabled,
                             @Value("${bidcheck.ocr.url:}") String url,
                             @Value("${bidcheck.ocr.key:}") String key,
                             @Value("${bidcheck.ocr.model:qwen-vl-ocr-2025-11-20}") String model,
                             @Value("${bidcheck.data:./data}") String data,
                             @Value("${bidcheck.ocr.concurrency:4}") int concurrency, OcrRequestSettings settings, OcrBatchSettings batchSettings) {
        this(enabled, url, key, model, Path.of(data).resolve("ocr-cache"), Duration.ofSeconds(60), false, concurrency, settings, batchSettings.policy());
    }

    /** Explicit test seam; production construction never permits plain HTTP, including loopback. */
    DashScopeOcrClient(boolean enabled, String url, String key, String model, Path cacheDirectory, Duration timeout, boolean allowLoopbackTest) {
        this(enabled, url, key, model, cacheDirectory, timeout, allowLoopbackTest, 2);
    }

    DashScopeOcrClient(boolean enabled, String url, String key, String model, Path cacheDirectory, Duration timeout, boolean allowLoopbackTest, int concurrency) {
        this(enabled, url, key, model, cacheDirectory, timeout, allowLoopbackTest, concurrency, OcrRequestSettings.defaults());
    }

    DashScopeOcrClient(boolean enabled, String url, String key, String model, Path cacheDirectory, Duration timeout, boolean allowLoopbackTest, int concurrency, OcrRequestSettings settings) {
        this(enabled, url, key, model, cacheDirectory, timeout, allowLoopbackTest, concurrency, settings, OcrBatchPolicy.defaults());
    }

    DashScopeOcrClient(boolean enabled, String url, String key, String model, Path cacheDirectory, Duration timeout, boolean allowLoopbackTest, int concurrency, OcrRequestSettings settings, OcrBatchPolicy batchPolicy) {
        if(concurrency < 1 || concurrency > 8) throw new IllegalArgumentException("OCR 并发数必须在 1 至 8 之间。");
        this.settings = Objects.requireNonNull(settings);
        this.key = key == null ? "" : key.strip();
        this.model = model == null ? "" : model.strip();
        this.cacheDirectory = cacheDirectory.toAbsolutePath().normalize();
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(60) : timeout.compareTo(Duration.ofSeconds(60)) > 0 ? Duration.ofSeconds(60) : timeout;
        URI parsed = null;
        boolean valid = enabled && !this.key.isBlank() && this.key.length() <= 4096 && !this.key.contains("\r") && !this.key.contains("\n")
            && this.model.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}") && url != null && url.length() <= 2048;
        if (valid) {
            try {
                parsed = URI.create(url.strip());
                boolean secure = "https".equalsIgnoreCase(parsed.getScheme());
                boolean local = allowLoopbackTest && "http".equalsIgnoreCase(parsed.getScheme()) && "127.0.0.1".equals(parsed.getHost());
                valid = (secure || local) && parsed.getHost() != null && parsed.getUserInfo() == null && parsed.getQuery() == null && parsed.getFragment() == null;
            } catch (IllegalArgumentException invalid) { valid = false; }
        }
        this.endpoint = parsed;
        this.active = valid;
        this.governor = OcrRequestGovernor.forService(parsed, this.model, this.key, concurrency, settings);
        this.identity = valid ? PROVIDER + ":" + digest((POLICY + "\n" + TASK + "\n" + parsed.toASCIIString() + "\n" + this.model).getBytes(StandardCharsets.UTF_8)) : "DISABLED";
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout.compareTo(Duration.ofSeconds(10)) < 0 ? this.timeout : Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        this.batches = new DashScopeBatchOcrClient(this.client, this.endpoint, this.key, this.model,
            this.cacheDirectory.resolve("batch"), this.timeout, this.governor, this.settings, batchPolicy, this::validateTranscription);
    }

    @Override public boolean enabled() { return active; }
    @Override public String modelName() { return model; }
    @Override public String identity() { return active && batchPolicy().enabled() ? identity + ":" + DashScopeBatchOcrClient.POLICY : identity; }
    @Override public OcrBatchPolicy batchPolicy() { return active ? batches.policy() : OcrBatchPolicy.single(); }
    @Override public void recognizeBatch(List<OcrBatch.Input> inputs, OcrBatch.Listener listener) throws IOException {
        if (!batchPolicy().enabled()) { OcrClient.super.recognizeBatch(inputs, listener); return; }
        batches.recognize(inputs, listener);
    }

    @Override public OcrResult recognize(byte[] image, String mimeType) throws IOException {
        checkInterrupted();
        if (!enabled()) throw safe("OCR 尚未启用或配置无效，请人工核对图片原件");
        validateImage(image, mimeType);
        String imageHash = digest(image);
        String cacheKey = digest((identity + "\n" + mimeType + "\n" + imageHash).getBytes(StandardCharsets.UTF_8));
        Path cached = cacheDirectory.resolve(cacheKey + ".json");
        OcrResult hit = readCache(cached, imageHash);
        if (hit != null) { checkInterrupted(); governor.cacheHit(); return hit; }
        var shared = governor.singleFlight(cacheKey, timeout, () -> recognizeUncached(image, mimeType, cached, imageHash));
        OcrResult result = shared.value();
        return shared.reused() ? new OcrResult(result.text(), result.provider(), result.model(), true, result.layout()) : result;
    }

    private OcrResult recognizeUncached(byte[] image, String mimeType, Path cached, String imageHash) throws IOException {
        try {
            OcrResult hit = readCache(cached, imageHash);
            if (hit != null) { checkInterrupted(); governor.cacheHit(); return hit; }
            checkInterrupted();
            Map<String, Object> payload = Map.of("model", model,
                "input", Map.of("messages", List.of(Map.of("role", "user", "content", List.of(
                    Map.of("image", "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(image), "min_pixels", 3072, "max_pixels", 8388608, "enable_rotate", false))))),
                "parameters", Map.of("max_tokens", 8192, "temperature", 0, "ocr_options", Map.of("task", TASK)));
            byte[] requestBody = json.writeValueAsBytes(payload);
            checkInterrupted();
            HttpResponse<byte[]> response = governor.send(client, endpoint, key, requestBody, MAX_RESPONSE_BYTES, timeout, settings);
            if (response.headers().firstValue("x-dashscope-partialresponse").map("true"::equalsIgnoreCase).orElse(false)) throw safe("OCR 回复不完整，请重新识别或人工核对");
            String text = parse(response.body());
            checkInterrupted();
            OcrResult result = new OcrResult(text, PROVIDER, model, false);
            writeCache(cached, imageHash, result);
            checkInterrupted();
            return result;
        } catch (InterruptedIOException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (OcrFailure | SafeOcrException controlled) {
            throw controlled;
        } catch (Exception invalid) {
            // No causes or provider response text: either could include credentials or document content.
            throw safe("OCR 返回无效数据或通信失败，本次识别未完成");
        }
    }

    private String parse(byte[] bytes) throws IOException {
        JsonNode envelope = json.readTree(bytes);
        if (envelope == null || !envelope.isObject() || !envelope.path("code").asText("").isEmpty()
            || (envelope.has("status_code") && envelope.path("status_code").asInt() != 200)) throw safe("OCR 返回无效结果，本次识别未完成");
        JsonNode choices = envelope.path("output").path("choices");
        if (!choices.isArray() || choices.size() != 1 || !"stop".equals(choices.get(0).path("finish_reason").asText())) throw safe("OCR 回复不完整或已截断，请人工核对");
        JsonNode content = choices.get(0).path("message").path("content");
        if (!content.isArray() || content.isEmpty()) throw safe("OCR 未返回有效转录正文");
        StringBuilder result = new StringBuilder();
        for (JsonNode part : content) {
            if (!part.path("text").isTextual()) throw safe("OCR 返回结构无效，本次识别未完成");
            if (!result.isEmpty()) result.append('\n');
            result.append(part.path("text").textValue());
            if (result.length() > MAX_TEXT_CHARACTERS) throw safe("OCR 正文超过长度限制，请拆分图片后重试");
        }
        String text = result.toString().strip();
        validateTranscription(text);
        return text;
    }

    /** Inspect complete structured replies only; source prose, page numbers and textual JSON remain verbatim. */
    private void validateTranscription(String text) throws IOException {
        if (text.isBlank() || text.length() > MAX_TEXT_CHARACTERS || text.indexOf('\0') >= 0) throw safe("OCR 未识别出可用文字，请人工核对图片原件");
        String candidate = text.strip();
        if (candidate.startsWith("```")) {
            int line = candidate.indexOf('\n');
            if (line < 0 || !candidate.endsWith("```")) return;
            String language = candidate.substring(3, line).strip();
            if (!language.isEmpty() && !"json".equalsIgnoreCase(language)) return;
            candidate = candidate.substring(line + 1, candidate.length() - 3).strip();
        }
        if (!candidate.startsWith("[") && !candidate.startsWith("{")) return;
        JsonNode structured;
        try { structured = json.readTree(candidate); }
        catch (com.fasterxml.jackson.core.JsonProcessingException sourceText) { return; }
        if (emptyStructure(structured) || (hasGeometry(structured) && onlyGeometryMetadata(structured))) {
            throw safe("OCR 仅返回位置坐标或空结构，未获得转录文字，请重新识别或人工核对原图");
        }
    }

    private static boolean emptyStructure(JsonNode value) {
        if (value == null || value.isNull()) return true;
        if (value.isTextual()) return value.textValue().isBlank();
        if (!value.isContainerNode()) return false;
        for (JsonNode child : value) if (!emptyStructure(child)) return false;
        return true;
    }

    private static boolean hasGeometry(JsonNode value) {
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (GEOMETRY_FIELDS.contains(field.getKey()) || hasGeometry(field.getValue())) return true;
            }
        } else if (value.isArray()) for (JsonNode child : value) if (hasGeometry(child)) return true;
        return false;
    }

    private static boolean onlyGeometryMetadata(JsonNode value) {
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                // Any real transcription value, including a numeric page number, makes this usable source JSON.
                if (TRANSCRIPTION_FIELDS.contains(field.getKey())) {
                    if (hasTranscriptionValue(field.getValue())) return false;
                } else if (METADATA_FIELDS.contains(field.getKey()) && field.getValue().isValueNode()) {
                    // A metadata value such as page:"1" or confidence:"0.98" is not transcribed prose.
                    continue;
                } else if ((!GEOMETRY_FIELDS.contains(field.getKey()) && !METADATA_FIELDS.contains(field.getKey()))
                    || !onlyGeometryMetadata(field.getValue())) return false;
            }
            return true;
        }
        if (value.isArray()) {
            for (JsonNode child : value) if (!onlyGeometryMetadata(child)) return false;
            return true;
        }
        return value.isNumber() || value.isBoolean() || value.isNull() || (value.isTextual()
            && (value.textValue().isBlank() || value.textValue().strip().matches("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?")));
    }

    private static boolean hasTranscriptionValue(JsonNode value) {
        if (value.isTextual()) return !value.textValue().isBlank();
        if (value.isNumber()) return true;
        if (value.isArray()) {
            for (JsonNode child : value) if (hasTranscriptionValue(child)) return true;
        } else if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (TRANSCRIPTION_FIELDS.contains(field.getKey()) && hasTranscriptionValue(field.getValue())) return true;
            }
        }
        // Boolean flags, nulls and geometry objects do not contain transcription.
        return false;
    }

    private OcrResult readCache(Path path, String imageHash) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_CACHE_BYTES) return null;
            byte[] bytes;
            try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_CACHE_BYTES + 1); }
            if (bytes.length > MAX_CACHE_BYTES) return null;
            JsonNode value = json.readTree(bytes);
            String text = value.path("text").asText("");
            if (!identity.equals(value.path("identity").asText()) || !imageHash.equals(value.path("imageHash").asText())
                || !model.equals(value.path("model").asText()) || !PROVIDER.equals(value.path("provider").asText())
                || text.isBlank() || text.length() > MAX_TEXT_CHARACTERS || text.indexOf('\0') >= 0
                || !digest(text.getBytes(StandardCharsets.UTF_8)).equals(value.path("textHash").asText())) return null;
            validateTranscription(text);
            return new OcrResult(text, PROVIDER, model, true);
        } catch (Exception corrupt) { return null; }
    }

    private void writeCache(Path path, String imageHash, OcrResult result) {
        Path temporary = null;
        try {
            Files.createDirectories(cacheDirectory);
            byte[] bytes = json.writeValueAsBytes(Map.of("identity", identity, "imageHash", imageHash, "provider", PROVIDER,
                "model", model, "text", result.text(), "textHash", digest(result.text().getBytes(StandardCharsets.UTF_8))));
            if (bytes.length > MAX_CACHE_BYTES) return;
            temporary = Files.createTempFile(cacheDirectory, "ocr-", ".tmp");
            Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            // If atomic replacement is unavailable, skip caching rather than publish a partial result.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // The successful OCR result remains usable if this optional cache cannot be written.
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }

    private static void validateImage(byte[] image, String mime) throws IOException {
        if (image == null || image.length == 0 || image.length > MAX_IMAGE_BYTES) throw safe("OCR 图片不能为空或超过 6MB，请调整后重试");
        boolean png = "image/png".equals(mime) && image.length >= 8 && Arrays.equals(Arrays.copyOf(image, 8), new byte[]{(byte)137,80,78,71,13,10,26,10});
        boolean jpeg = "image/jpeg".equals(mime) && image.length >= 3 && (image[0] & 255) == 255 && (image[1] & 255) == 216 && (image[2] & 255) == 255;
        if (!png && !jpeg) throw safe("OCR 仅支持内容与类型一致的 PNG 或 JPEG 图片");
    }
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("OCR 识别已中断，任务停止");
    }
    private static String digest(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static SafeOcrException safe(String message) { return new SafeOcrException(message); }
    private static final class SafeOcrException extends IOException { SafeOcrException(String message) { super(message); } }

    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> future = new CompletableFuture<>();
        private volatile Flow.Subscription subscription;
        private volatile boolean cancelled;
        private volatile boolean limitExceeded;
        LimitedBodySubscriber(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return future; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (cancelled) subscription.cancel(); else subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (cancelled || future.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) { limitExceeded = true;cancel();future.completeExceptionally(safe("OCR 响应超过限制"));return; }
                byte[] copy = new byte[buffer.remaining()];buffer.get(copy);bytes.writeBytes(copy);
            }
            if (!cancelled) subscription.request(1);
        }
        @Override public void onError(Throwable error) {
            future.completeExceptionally(error instanceof java.net.http.HttpTimeoutException
                    ? new java.net.http.HttpTimeoutException("OCR 响应超时") : safe("OCR 响应传输失败"));
        }
        @Override public void onComplete() { future.complete(bytes.toByteArray()); }
        void cancel() { cancelled = true;Flow.Subscription current = subscription;if (current != null) current.cancel(); }
        boolean limitExceeded() { return limitExceeded; }
    }
}
