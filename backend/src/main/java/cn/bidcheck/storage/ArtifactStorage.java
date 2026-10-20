package cn.bidcheck.storage;

import cn.bidcheck.document.DocumentLimits;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Original-document storage. Call commit successfully before creating a persistent parse task. */
public interface ArtifactStorage {
    Path stagePath(String documentId) throws IOException;
    void commit(String documentId) throws IOException;
    Path materialize(String documentId) throws IOException;
    /** Verify against the SHA-256 registered in the database, including already cached local files. */
    default Path materialize(String documentId, String expectedSha256) throws IOException {
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}")) throw new IOException("原始文件缺少有效的完整性校验值，无法读取。");
        Path path = materialize(documentId);
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream input = java.nio.file.Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int length;
                while ((length = input.read(buffer)) != -1) {
                    total += length;
                    if (total > DocumentLimits.MAX_FILE_BYTES) throw new IOException("原始文件超过 1024 MB 限制。");
                    digest.update(buffer, 0, length);
                }
            }
            byte[] expected = java.util.HexFormat.of().parseHex(expectedSha256);
            if (!java.security.MessageDigest.isEqual(expected, digest.digest())) throw new IOException("原始文件 SHA-256 与已登记证据不一致，已拒绝解析和下载，请核对存储数据。");
            return path;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("文件完整性校验不可用。");
        }
    }
    default InputStream open(String documentId) throws IOException {
        return java.nio.file.Files.newInputStream(materialize(documentId));
    }
    /** Removes only an uncommitted local upload; never deletes a committed file or a remote object. */
    void discardStaging(String documentId) throws IOException;
    String mode();
    /** Cached/local status only. This method never makes a network request. */
    boolean available();
}
