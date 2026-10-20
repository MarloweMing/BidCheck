package cn.bidcheck.ocr;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Native advanced_recognition: transcription and pixel boxes share one provider response.
 * No custom prompt, inferred normalization, table relations, or compliance conclusions. */
public final class DashScopeAdvancedOcrClient implements OcrClient {
    public static final String POLICY = "ocr-native-advanced-2-source";
    private static final String PROVIDER = "DASHSCOPE", TASK = "advanced_recognition";
    private static final int MAX_IMAGE = 6 * 1024 * 1024, MAX_RESPONSE = 1024 * 1024;
    private static final int MAX_CACHE = MAX_RESPONSE + 16384, MAX_TEXT = 50000;
    private final String key, model, identity;
    private final boolean active;
    private final URI endpoint;
    private final Path cache;
    private final Duration timeout;
    private final OcrRequestGovernor governor;
    private final OcrRequestSettings settings;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(MAX_RESPONSE).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public DashScopeAdvancedOcrClient(boolean enabled, String url, String key, String model, String data) {
        this(enabled, url, key, model, data, 2);
    }
    public DashScopeAdvancedOcrClient(boolean enabled, String url, String key, String model, String data, int concurrency) {
        this(enabled, url, key, model, data, concurrency, OcrRequestSettings.defaults());
    }
    public DashScopeAdvancedOcrClient(boolean enabled, String url, String key, String model, String data, int concurrency, OcrRequestSettings settings) {
        this(enabled, url, key, model, Path.of(data).resolve("ocr-advanced-cache"), Duration.ofSeconds(60), false, concurrency, settings);
    }
    /** Local HTTP is available only through this explicit package-private mock seam. */
    DashScopeAdvancedOcrClient(boolean enabled, String url, String key, String model, Path cache,
                              Duration timeout, boolean allowLoopbackTest, int concurrency) {
        this(enabled, url, key, model, cache, timeout, allowLoopbackTest, concurrency, OcrRequestSettings.defaults());
    }
    DashScopeAdvancedOcrClient(boolean enabled, String url, String key, String model, Path cache,
                              Duration timeout, boolean allowLoopbackTest, int concurrency, OcrRequestSettings settings) {
        if (concurrency < 1 || concurrency > 8) throw new IllegalArgumentException("OCR 并发数必须在 1 至 8 之间。");
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("OCR 请求超时必须大于 0 且不超过 60 秒。");
        this.settings = Objects.requireNonNull(settings);
        this.key = key == null ? "" : key.strip(); this.model = model == null ? "" : model.strip();
        this.cache = cache.toAbsolutePath().normalize(); this.timeout = timeout;
        URI parsed = null;
        boolean valid = enabled && !this.key.isBlank() && this.key.length() <= 4096 && !this.key.contains("\n") && !this.key.contains("\r")
                && this.model.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}") && url != null && url.length() <= 2048;
        if (valid) try {
            parsed = URI.create(url.strip());
            valid = parsed.getHost() != null && parsed.getRawUserInfo() == null && parsed.getRawQuery() == null && parsed.getRawFragment() == null
                    && !parsed.getPath().contains("/compatible-mode/") && !parsed.getPath().endsWith("/chat/completions")
                    && ("https".equalsIgnoreCase(parsed.getScheme()) || allowLoopbackTest && "http".equalsIgnoreCase(parsed.getScheme()) && "127.0.0.1".equals(parsed.getHost()));
        } catch (IllegalArgumentException invalid) { valid = false; }
        endpoint = parsed; active = valid;
        governor = OcrRequestGovernor.forService(parsed, this.model, this.key, concurrency, settings);
        identity = valid ? PROVIDER + ":" + digest((POLICY + "\n" + OcrLayout.VERSION + "\n" + TASK + "\n" + parsed.toASCIIString() + "\n" + this.model).getBytes(StandardCharsets.UTF_8)) : "DISABLED";
        client = HttpClient.newBuilder().connectTimeout(timeout.compareTo(Duration.ofSeconds(10)) < 0 ? timeout : Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public boolean enabled() { return active; }
    @Override public String modelName() { return model; }
    @Override public String identity() { return identity; }

    @Override public OcrResult recognize(byte[] image, String mimeType) throws IOException {
        interrupted(); if (!active) throw failure("DISABLED");
        OcrLayout.Frame frame = frame(image, mimeType);
        String cacheKey = digest((identity + "\n" + mimeType + "\n" + frame.imageHash()).getBytes(StandardCharsets.UTF_8));
        Path path = cache.resolve(cacheKey + ".json");
        OcrResult hit = readCache(path, frame);
        if (hit != null) { interrupted(); governor.cacheHit(); return hit; }
        try {
            var shared = governor.singleFlight(cacheKey, timeout, () -> recognizeUncached(image, mimeType, path, frame));
            OcrResult result = shared.value();
            return shared.reused() ? new OcrResult(result.text(), result.provider(), result.model(), true, result.layout()) : result;
        } catch (AdvancedOcrException controlled) { throw controlled; }
        catch (OcrFailure controlled) { throw new AdvancedOcrException(controlled); }
    }

    private OcrResult recognizeUncached(byte[] image, String mimeType, Path path, OcrLayout.Frame frame) throws IOException {
        try {
            OcrResult hit = readCache(path, frame); if (hit != null) { interrupted(); governor.cacheHit(); return hit; }
            interrupted();
            var payload = Map.of("model", model, "input", Map.of("messages", List.of(Map.of("role", "user", "content", List.of(
                    Map.of("image", "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(image),
                            "min_pixels", 3072, "max_pixels", 8388608, "enable_rotate", false))))),
                    "parameters", Map.of("max_tokens", 8192, "temperature", 0, "ocr_options", Map.of("task", TASK)));
            byte[] requestBytes = json.writeValueAsBytes(payload); interrupted();
            HttpResponse<byte[]> response = governor.send(client, endpoint, key, requestBytes, MAX_RESPONSE, timeout, settings);
            if (response.headers().firstValue("x-dashscope-partialresponse").map("true"::equalsIgnoreCase).orElse(false)) throw failure("PARTIAL_RESPONSE");
            JsonNode words = responseWords(response.body());
            OcrResult result = parseWords(words, frame, false); interrupted();
            writeCache(path, frame.imageHash(), words); interrupted(); return result;
        } catch (InterruptedIOException cancelled) { Thread.currentThread().interrupt(); throw cancelled; }
        catch (AdvancedOcrException controlled) { throw controlled; }
        catch (OcrFailure controlled) { throw new AdvancedOcrException(controlled); }
        catch (Exception invalid) { throw failure("INVALID_RESPONSE"); }
    }

    private JsonNode responseWords(byte[] bytes) throws IOException {
        JsonNode root = json.readTree(bytes);
        if (root == null || !root.isObject() || !root.path("code").asText("").isEmpty()
                || root.has("status_code") && (!root.path("status_code").isIntegralNumber() || root.path("status_code").intValue() != 200)) throw failure("INVALID_RESPONSE");
        JsonNode choices = root.path("output").path("choices");
        if (!choices.isArray() || choices.size() != 1 || !"stop".equals(choices.get(0).path("finish_reason").asText())) throw failure("MODEL_TRUNCATED");
        JsonNode content = choices.get(0).path("message").path("content");
        if (!content.isArray() || content.size() != 1) throw failure("INVALID_RESPONSE");
        // content.text is often a coordinate JSON preview, not transcription; never use it.
        return content.get(0).path("ocr_result").path("words_info");
    }
    private OcrResult parseWords(JsonNode words, OcrLayout.Frame frame, boolean cached) throws IOException {
        if (!words.isArray() || words.isEmpty() || words.size() > OcrLayout.MAX_BOXES) throw failure("WORDS_LIMIT_OR_MISSING");
        StringBuilder text = new StringBuilder(); List<OcrLayout.TextBox> boxes = new ArrayList<>(); List<String> order = new ArrayList<>();
        for (JsonNode word : words) {
            if (!word.isObject() || !word.path("text").isTextual()) throw failure("INVALID_WORD");
            String value = word.path("text").textValue();
            if (value.isBlank() || value.length() > 10000 || value.indexOf('\0') >= 0) throw failure("INVALID_WORD");
            if (!text.isEmpty()) text.append('\n'); text.append(value);
            if (text.length() > MAX_TEXT) throw failure("TEXT_LIMIT");
            OcrLayout.Box bbox = rectangle(word.path("rotate_rect"), frame.width(), frame.height());
            String id = "w" + (boxes.size() + 1); order.add(id); boxes.add(new OcrLayout.TextBox(id, frame.id(), value, bbox, null, "NATIVE_OCR"));
        }
        OcrLayout layout = new OcrLayout(OcrLayout.VERSION, "COMPLETE", OcrLayout.COORDINATES, List.of(frame), boxes, List.of(), order,
                List.of("文字和像素框来自同次原生高精 OCR 响应；旋转框转为轴对齐外包框。仅覆盖返回的文字行，不保证图中所有文字已识别；不提供表格关系或身份、签章核验。"));
        try { layout = layout.validated(text.toString()); } catch (IllegalArgumentException invalid) { throw failure("INVALID_GEOMETRY"); }
        return new OcrResult(text.toString(), PROVIDER, model, cached, layout);
    }
    /** Provider contract: absolute source pixels, clockwise/anticlockwise degrees [-90,90].
     * The axis-aligned envelope is independent of rotation sign; never scale guessed normalized values. */
    private static OcrLayout.Box rectangle(JsonNode values, int width, int height) throws IOException {
        if (!values.isArray() || values.size() != 5) throw failure("INVALID_GEOMETRY");
        double[] v = new double[5];
        for (int i = 0; i < 5; i++) { if (!values.get(i).isNumber()) throw failure("INVALID_GEOMETRY"); v[i] = values.get(i).doubleValue(); if (!Double.isFinite(v[i])) throw failure("INVALID_GEOMETRY"); }
        if (v[2] <= 0 || v[3] <= 0 || v[4] < -90 || v[4] > 90) throw failure("INVALID_GEOMETRY");
        double radians = Math.toRadians(v[4]), cosine = Math.abs(Math.cos(radians)), sine = Math.abs(Math.sin(radians));
        double halfW = (v[2] * cosine + v[3] * sine) / 2, halfH = (v[2] * sine + v[3] * cosine) / 2;
        double left = v[0] - halfW, top = v[1] - halfH, right = v[0] + halfW, bottom = v[1] + halfH;
        if (!Double.isFinite(left) || !Double.isFinite(top) || !Double.isFinite(right) || !Double.isFinite(bottom)
                || left < -1e-7 || top < -1e-7 || right > width + 1e-7 || bottom > height + 1e-7) throw failure("INVALID_GEOMETRY");
        // Only floating-point roundoff at a mathematically exact image edge is removed.
        left = Math.max(0, left); top = Math.max(0, top); right = Math.min(width, right); bottom = Math.min(height, bottom);
        return new OcrLayout.Box(left, top, right - left, bottom - top);
    }
    private OcrResult readCache(Path path, OcrLayout.Frame frame) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_CACHE) return null;
            byte[] bytes; try (var in = Files.newInputStream(path)) { bytes = in.readNBytes(MAX_CACHE + 1); } if (bytes.length > MAX_CACHE) return null;
            JsonNode stored = json.readTree(bytes), words = stored.path("words");
            if (!identity.equals(stored.path("identity").asText()) || !frame.imageHash().equals(stored.path("imageHash").asText())
                    || !model.equals(stored.path("model").asText()) || !digest(json.writeValueAsBytes(words)).equals(stored.path("wordsHash").asText())) return null;
            return parseWords(words, frame, true);
        } catch (Exception corrupt) { return null; }
    }
    private void writeCache(Path path, String imageHash, JsonNode words) {
        Path temporary = null;
        try {
            // Store only the recognized source lines and their original rectangles, not provider metadata.
            List<Map<String,Object>> rows = new ArrayList<>();
            for (JsonNode word : words) rows.add(Map.of("text", word.path("text").textValue(), "rotate_rect", word.path("rotate_rect")));
            JsonNode canonicalWords = json.valueToTree(rows);
            byte[] bytes = json.writeValueAsBytes(Map.of("identity", identity, "imageHash", imageHash, "model", model,
                    "words", canonicalWords, "wordsHash", digest(json.writeValueAsBytes(canonicalWords))));
            if (bytes.length > MAX_CACHE) return;
            Files.createDirectories(cache); temporary = Files.createTempFile(cache, "advanced-", ".tmp"); Files.write(temporary, bytes);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { /* Optional cache failures do not discard verified OCR. */ }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
    private static OcrLayout.Frame frame(byte[] image, String mime) throws IOException {
        if (image == null || image.length < 8 || image.length > MAX_IMAGE || mime == null || !Set.of("image/png", "image/jpeg").contains(mime)) throw failure("INVALID_IMAGE");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(image))) {
            var readers = ImageIO.getImageReaders(input); if (!readers.hasNext()) throw failure("INVALID_IMAGE"); var reader = readers.next();
            try {
                reader.setInput(input, true, true); String format = reader.getFormatName();
                if (!("image/png".equals(mime) && "png".equalsIgnoreCase(format)) && !("image/jpeg".equals(mime) && "jpeg".equalsIgnoreCase(format))) throw failure("INVALID_IMAGE");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 32768 || height > 32768 || (long) width * height > 16_000_000) throw failure("IMAGE_PIXEL_LIMIT");
                return new OcrLayout.Frame("image", width, height, digest(image), "SUBMITTED_IMAGE", 0, null, null, null);
            } finally { reader.dispose(); }
        } catch (AdvancedOcrException invalid) { throw invalid; }
        catch (IOException | RuntimeException invalid) { throw failure("INVALID_IMAGE"); }
    }
    private static void interrupted() throws InterruptedIOException { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("原生高精 OCR 已中断。"); }
    private static String digest(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); } }
    private static AdvancedOcrException failure(String code) { return new AdvancedOcrException(code); }
    public static final class AdvancedOcrException extends OcrFailure {
        AdvancedOcrException(String code) { super("原生高精 OCR 未完成（" + code + "），请重试或核对原图。", code, null, false); }
        AdvancedOcrException(OcrFailure failure) { super("原生高精 OCR 未完成（" + failure.code() + "），请重试或核对原图。", failure.code(), failure.httpStatus(), failure.temporary()); }
    }
}
