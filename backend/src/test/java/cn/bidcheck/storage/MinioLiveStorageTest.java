package cn.bidcheck.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Optional, explicit live smoke test. Set BIDCHECK_MINIO_LIVE_TEST=true together with
 * BIDCHECK_STORAGE_ENDPOINT, BIDCHECK_STORAGE_ACCESS_KEY and BIDCHECK_STORAGE_SECRET_KEY.
 * Creates only a new UUID object in bidcheck; deliberately does not delete any remote object.
 */
@EnabledIfEnvironmentVariable(named = "BIDCHECK_MINIO_LIVE_TEST", matches = "(?i)true")
class MinioLiveStorageTest {
    @TempDir Path temporary;

    @Test void newlyUploadedObjectCanBeRecoveredIntoAnIndependentEmptyCache() throws Exception {
        String endpoint = required("BIDCHECK_STORAGE_ENDPOINT");
        String accessKey = required("BIDCHECK_STORAGE_ACCESS_KEY");
        String secretKey = required("BIDCHECK_STORAGE_SECRET_KEY");
        String id = UUID.randomUUID().toString();
        byte[] original = ("BidCheck storage smoke test\nDocument: " + id + "\nSynthetic evidence only.\n")
                .getBytes(StandardCharsets.UTF_8);

        ConfiguredArtifactStorage upload = new ConfiguredArtifactStorage(temporary.resolve("upload").toString(),
                "minio", endpoint, accessKey, secretKey, "bidcheck");
        ConfiguredArtifactStorage download = null;
        try {
            Path staged = upload.stagePath(id);
            Files.write(staged, original);
            assertThrows(java.io.IOException.class, () -> upload.materialize(id));
            upload.commit(id);
            assertTrue(upload.available());
            assertArrayEquals(original, Files.readAllBytes(upload.materialize(id)));

            // A separate cache ensures that materialize performs an actual S3 GET.
            download = new ConfiguredArtifactStorage(temporary.resolve("download").toString(),
                    "minio", endpoint, accessKey, secretKey, "bidcheck");
            Path recovered = download.materialize(id);
            assertArrayEquals(original, Files.readAllBytes(recovered));
            assertTrue(download.available());

            upload.discardStaging(id);
            assertTrue(Files.exists(staged), "Discard must preserve an already committed original");
            try (var input = download.open(id)) { assertArrayEquals(original, input.readAllBytes()); }
        } finally {
            upload.close();
            if (download != null) download.close();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Live MinIO test requires environment variable " + name);
        return value;
    }
}
