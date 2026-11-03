package cn.bidcheck.storage;

import cn.bidcheck.document.DocumentLimits;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.UploadObjectArgs;
import io.minio.errors.ErrorResponseException;
import javax.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;

@Service
public class ConfiguredArtifactStorage implements ArtifactStorage {
    static final long MAX_BYTES = DocumentLimits.MAX_FILE_BYTES;
    private static final String APP_BUCKET = "bidcheck";
    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private final Path files;
    private final String mode;
    private final String bucket;
    private final MinioClient client;
    private final Object[] locks = new Object[64];
    private final Object bucketLock = new Object();
    private volatile boolean bucketReady;
    private volatile boolean remoteAvailable;

    @Autowired
    public ConfiguredArtifactStorage(@Value("${bidcheck.data}") String directory,
                                     @Value("${bidcheck.storage.type:local}") String type,
                                     @Value("${bidcheck.storage.endpoint:}") String endpoint,
                                     @Value("${bidcheck.storage.access-key:}") String accessKey,
                                     @Value("${bidcheck.storage.secret-key:}") String secretKey,
                                     @Value("${bidcheck.storage.bucket:bidcheck}") String bucket) throws IOException {
        this(directory, normalizedMode(type), createClient(type, endpoint, accessKey, secretKey), bucket);
    }

    /** Injection seam for deterministic storage tests; no external service is required by local mode. */
    ConfiguredArtifactStorage(String directory, String mode, MinioClient client, String bucket) throws IOException {
        this.mode = normalizedMode(mode);
        this.bucket = bucket == null || cn.bidcheck.compat.Java8Api.isBlank(bucket) ? APP_BUCKET : cn.bidcheck.compat.Java8Api.strip(bucket);
        if ("minio".equals(this.mode) && !APP_BUCKET.equals(this.bucket)) {
            throw new IOException("MinIO 仅允许使用本应用专用的 bidcheck 存储桶。");
        }
        if ("minio".equals(this.mode) && client == null) throw new IOException("MinIO 存储配置不完整。");
        this.client = "minio".equals(this.mode) ? client : null;
        Path root = java.nio.file.Paths.get(directory).toAbsolutePath().normalize().resolve("files");
        Files.createDirectories(root);
        this.files = root.toRealPath();
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }

    @Override public Path stagePath(String documentId) throws IOException {
        String id = id(documentId);
        synchronized (lock(id)) {
            Path target = file(id);
            Path pending = pending(id);
            rejectSymbolicLink(target);
            rejectSymbolicLink(pending);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("文档编号已持久化，不能覆盖原始文件。");
            }
            if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
                cn.bidcheck.compat.Java8Api.writeString(pending, "pending", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            return target;
        }
    }

    @Override public void commit(String documentId) throws IOException {
        String id = id(documentId);
        synchronized (lock(id)) {
            Path target = file(id);
            Path pending = pending(id);
            validateOriginal(target);
            rejectSymbolicLink(pending);
            // An existing original with no pending marker is already committed (including legacy local files).
            if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) return;
            force(target);
            if ("minio".equals(mode)) {
                try {
                    ensureBucket();
                    client.uploadObject(UploadObjectArgs.builder().bucket(bucket).object(objectKey(id))
                            .filename(target.toString()).contentType("application/octet-stream").build());
                    remoteAvailable = true;
                } catch (Exception failure) {
                    remoteAvailable = false;
                    bucketReady = false;
                    // Do not attach the SDK cause: exception details may carry request credentials or document data.
                    if (failure instanceof ErrorResponseException && "RequestTimeTooSkewed".equals(((ErrorResponseException) (failure)).errorResponse().code())) {
                        throw new StorageUnavailableException("文件存储服务器与本机时间不同步，请校准虚拟机时间后重试上传。");
                    }
                    throw new StorageUnavailableException("原始文件写入 MinIO 失败；未创建解析任务，请检查对象存储及虚拟机时间后重试。");
                }
            }
            // A pending marker is removed only after the configured durable write succeeds.
            Files.delete(pending);
        }
    }

    @Override public Path materialize(String documentId) throws IOException {
        String id = id(documentId);
        synchronized (lock(id)) {
            Path target = file(id);
            Path pending = pending(id);
            rejectSymbolicLink(target);
            rejectSymbolicLink(pending);
            if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) throw new IOException("原始文件尚未持久化，不能开始解析。");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                validateOriginal(target);
                return target;
            }
            if ("local".equals(mode)) throw new IOException("原始文件不存在，请重新上传。");
            Path temporary = Files.createTempFile(files, "cache-" + id + "-", ".tmp");
            try {
                try (GetObjectResponse input = client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey(id)).build());
                     OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    String length = input.headers().get("Content-Length");
                    long expected = length == null ? -1 : Long.parseLong(length);
                    if (expected > MAX_BYTES || expected < -1) throw new IOException("原始文件长度不合法或超过 1024 MB 限制。");
                    long actual = copyBounded(input, output);
                    if (expected >= 0 && actual != expected) throw new IOException("MinIO 返回的原始文件被截断或长度不一致。");
                }
                validateOriginal(temporary);
                force(temporary);
                // Same-directory rename publishes only a completely downloaded, size-checked file.
                Files.move(temporary, target);
                remoteAvailable = true;
                return target;
            } catch (Exception ignored) {
                remoteAvailable = false;
                throw new IOException("无法从 MinIO 读取原始文件，或文件为空、损坏、超过 1024 MB，请检查存储后重试。");
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    @Override public void discardStaging(String documentId) throws IOException {
        String id = id(documentId);
        synchronized (lock(id)) {
            Path pending = pending(id);
            Path target = file(id);
            rejectSymbolicLink(pending);
            rejectSymbolicLink(target);
            if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) return;
            // Only caller-created, uncommitted local uploads are removed. There is no remote-delete operation.
            Files.deleteIfExists(target);
            Files.delete(pending);
        }
    }

    @Override public String mode() { return mode; }

    @Override public boolean available() {
        return "local".equals(mode) ? Files.isDirectory(files) && Files.isWritable(files) : remoteAvailable;
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try { client.close(); } catch (Exception ignored) { /* No sensitive SDK diagnostics are logged. */ }
        }
    }

    private void ensureBucket() throws Exception {
        if (bucketReady) return;
        synchronized (bucketLock) {
            if (bucketReady) return;
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                try {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                } catch (ErrorResponseException error) {
                    if (!"BucketAlreadyOwnedByYou".equals(error.errorResponse().code())) throw error;
                }
            }
            bucketReady = true;
        }
    }

    private static MinioClient createClient(String type, String endpoint, String accessKey, String secretKey) throws IOException {
        if ("local".equals(normalizedMode(type))) return null;
        if (endpoint == null || cn.bidcheck.compat.Java8Api.isBlank(endpoint) || accessKey == null || cn.bidcheck.compat.Java8Api.isBlank(accessKey) || secretKey == null || cn.bidcheck.compat.Java8Api.isBlank(secretKey)) {
            throw new IOException("MinIO 存储需要配置地址、访问标识和密钥。");
        }
        try {
            URI uri = URI.create(cn.bidcheck.compat.Java8Api.strip(endpoint));
            if (!cn.bidcheck.compat.Java8Api.setOf("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            MinioClient client = MinioClient.builder().endpoint(cn.bidcheck.compat.Java8Api.strip(endpoint)).credentials(accessKey, secretKey).build();
            client.setTimeout(3000, 15000, 15000);
            return client;
        } catch (IllegalArgumentException ignored) {
            throw new IOException("MinIO 存储配置无效，请检查服务地址和访问凭据。");
        }
    }

    private static String normalizedMode(String type) throws IOException {
        String mode = type == null || cn.bidcheck.compat.Java8Api.isBlank(type) ? "local" : cn.bidcheck.compat.Java8Api.strip(type).toLowerCase(Locale.ROOT);
        if (!cn.bidcheck.compat.Java8Api.setOf("local", "minio").contains(mode)) throw new IOException("文件存储类型仅支持 local 或 minio。");
        return mode;
    }

    private static String id(String value) throws IOException {
        if (value == null || !value.matches(UUID_PATTERN)) throw new IOException("文件编号必须是有效的 UUID。");
        return UUID.fromString(value).toString();
    }

    private Path file(String id) { return files.resolve(id + ".bin"); }
    private Path pending(String id) { return files.resolve(id + ".pending"); }
    private Object lock(String id) { return locks[(id.hashCode() & 0x7fffffff) % locks.length]; }
    private static String objectKey(String id) { return "documents/" + id + "/original.bin"; }

    private static void rejectSymbolicLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("文件路径不允许使用符号链接。");
    }

    private static void validateOriginal(Path file) throws IOException {
        rejectSymbolicLink(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("原始文件不存在或不可读取。");
        long size = Files.size(file);
        if (size < 1 || size > MAX_BYTES) throw new IOException("原始文件不能为空，且不能超过 1024 MB。");
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    private static long copyBounded(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int length;
        while ((length = input.read(buffer)) != -1) {
            total += length;
            if (total > MAX_BYTES) throw new IOException("原始文件超过 1024 MB 限制。");
            output.write(buffer, 0, length);
        }
        return total;
    }
}
