package cn.bidcheck.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.UploadObjectArgs;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConfiguredArtifactStorageTest {
    @TempDir Path directory;

    @Test void largerOriginalsRemainStreamedAndIntegrityChecked() throws Exception {
        var storage = new ConfiguredArtifactStorage(directory.toString(), "local", null, "bidcheck");
        String id = UUID.randomUUID().toString();
        Path stage = storage.stagePath(id);
        try (RandomAccessFile file = new RandomAccessFile(stage.toFile(), "rw")) { file.setLength(32L * 1024 * 1024); }
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var in = new java.security.DigestInputStream(Files.newInputStream(stage), digest)) { in.transferTo(java.io.OutputStream.nullOutputStream()); }
        String hash = java.util.HexFormat.of().formatHex(digest.digest());
        storage.commit(id);
        assertEquals(stage, storage.materialize(id, hash));
        try (RandomAccessFile file = new RandomAccessFile(stage.toFile(), "rw")) { file.seek(file.length()-1); file.write(1); }
        assertThrows(IOException.class, () -> storage.materialize(id, hash));
    }

    @Test void localModeCommitsWithoutCopyingAndNeverDeletesCommittedOriginals() throws Exception {
        var storage = new ConfiguredArtifactStorage(directory.toString(), "local", "invalid endpoint", "", "", "ignored");
        String id = UUID.randomUUID().toString();
        Path stage = storage.stagePath(id);
        Files.writeString(stage, "原始证据");
        assertThrows(IOException.class, () -> storage.materialize(id));
        storage.commit(id);
        assertEquals(stage, storage.materialize(id));
        try (var input = storage.open(id)) { assertEquals("原始证据", new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
        storage.discardStaging(id);
        assertTrue(Files.exists(stage));
        assertThrows(IOException.class, () -> storage.stagePath(id));
        assertEquals("local", storage.mode());
        assertTrue(storage.available());
    }

    @Test void discardsOnlyUncommittedUploadsAndRejectsArbitraryPaths() throws Exception {
        var storage = new ConfiguredArtifactStorage(directory.toString(), "local", null, "bidcheck");
        String id = UUID.randomUUID().toString();
        Path stage = storage.stagePath(id);
        Files.writeString(stage, "duplicate");
        storage.discardStaging(id);
        assertFalse(Files.exists(stage));
        assertFalse(Files.exists(directory.resolve("files").resolve(id + ".pending")));
        for (String invalid : new String[]{"../outside", "../../a.bin", "1-1-1-1-1", "------------------------------------", "C:\\private", id + "/x", ""}) {
            assertThrows(IOException.class, () -> storage.stagePath(invalid));
            assertThrows(IOException.class, () -> storage.materialize(invalid));
            assertThrows(IOException.class, () -> storage.discardStaging(invalid));
        }
    }

    @Test void rejectsEmptyAndOversizedFilesBeforeAnyRemoteWrite() throws Exception {
        MinioClient client = mock(MinioClient.class);
        var storage = new ConfiguredArtifactStorage(directory.toString(), "minio", client, "bidcheck");
        String id = UUID.randomUUID().toString();
        Path stage = storage.stagePath(id);
        Files.createFile(stage);
        assertThrows(IOException.class, () -> storage.commit(id));
        try (RandomAccessFile file = new RandomAccessFile(stage.toFile(), "rw")) { file.setLength(ConfiguredArtifactStorage.MAX_BYTES + 1); }
        assertThrows(IOException.class, () -> storage.commit(id));
        verifyNoInteractions(client);
    }

    @Test void uploadsToDedicatedBucketBeforeMakingDocumentReadable() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        var storage = new ConfiguredArtifactStorage(directory.toString(), "minio", client, "bidcheck");
        String id = UUID.randomUUID().toString();
        Path stage = storage.stagePath(id);
        Files.writeString(stage, "proof");
        assertFalse(storage.available());
        storage.commit(id);
        assertTrue(storage.available());
        var sequence = inOrder(client);
        sequence.verify(client).bucketExists(any(BucketExistsArgs.class));
        sequence.verify(client).makeBucket(any(MakeBucketArgs.class));
        ArgumentCaptor<UploadObjectArgs> upload = ArgumentCaptor.forClass(UploadObjectArgs.class);
        sequence.verify(client).uploadObject(upload.capture());
        assertEquals("bidcheck", upload.getValue().bucket());
        assertEquals("documents/" + id + "/original.bin", upload.getValue().object());
        assertEquals("application/octet-stream", upload.getValue().contentType());
        assertEquals(stage, storage.materialize(id));
        storage.discardStaging(id);
        assertTrue(Files.exists(stage));
        verifyNoMoreInteractions(client);
    }

    @Test void failedUploadStaysUncommittedAndDoesNotExposeCredentials() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(client.uploadObject(any(UploadObjectArgs.class))).thenThrow(new IOException("private-secret-in-sdk-error"));
        var storage = new ConfiguredArtifactStorage(directory.toString(), "minio", client, "bidcheck");
        String id = UUID.randomUUID().toString();
        Path stage = storage.stagePath(id);
        Files.writeString(stage, "proof");
        IOException failure = assertThrows(IOException.class, () -> storage.commit(id));
        assertFalse(failure.toString().contains("private-secret"));
        assertNull(failure.getCause());
        assertFalse(storage.available());
        assertThrows(IOException.class, () -> storage.materialize(id));
        assertTrue(Files.exists(stage));
        storage.discardStaging(id);
        assertFalse(Files.exists(stage));
    }

    @Test void restoresMissingCacheFromMinioAndSubsequentReadsStayLocal() throws Exception {
        MinioClient client = mock(MinioClient.class);
        String id = UUID.randomUUID().toString();
        byte[] bytes = "recovered proof".getBytes(StandardCharsets.UTF_8);
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(new GetObjectResponse(
                new Headers.Builder().add("Content-Length", Integer.toString(bytes.length)).build(),
                "bidcheck", "", "documents/" + id + "/original.bin", new ByteArrayInputStream(bytes)));
        var storage = new ConfiguredArtifactStorage(directory.toString(), "minio", client, "bidcheck");
        Path cached = storage.materialize(id);
        assertArrayEquals(bytes, Files.readAllBytes(cached));
        assertEquals(cached, storage.materialize(id));
        assertTrue(storage.available());
        ArgumentCaptor<GetObjectArgs> args = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(client).getObject(args.capture());
        assertEquals("documents/" + id + "/original.bin", args.getValue().object());
        verifyNoMoreInteractions(client);
        try (var remaining = Files.list(directory.resolve("files"))) {
            assertEquals(1, remaining.count());
        }
    }

    @Test void failedOrOversizedDownloadNeverPublishesAPartialCacheFile() throws Exception {
        MinioClient client = mock(MinioClient.class);
        String id = UUID.randomUUID().toString();
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(new GetObjectResponse(
                new Headers.Builder().add("Content-Length", Long.toString(ConfiguredArtifactStorage.MAX_BYTES + 1)).build(),
                "bidcheck", "", "documents/" + id + "/original.bin", new ByteArrayInputStream(new byte[]{1})));
        var storage = new ConfiguredArtifactStorage(directory.toString(), "minio", client, "bidcheck");
        assertThrows(IOException.class, () -> storage.materialize(id));
        assertFalse(Files.exists(directory.resolve("files").resolve(id + ".bin")));
        try (var remaining = Files.list(directory.resolve("files"))) { assertEquals(0, remaining.count()); }
        assertFalse(storage.available());
        assertThrows(IOException.class, () -> new ConfiguredArtifactStorage(directory.toString(), "minio", client, "external-bucket"));
    }

    @Test void verifiesRegisteredHashEvenIfACachedFileHasTheSameSizeAndTimestamp() throws Exception {
        var storage = new ConfiguredArtifactStorage(directory.toString(), "local", null, "bidcheck");
        String id = UUID.randomUUID().toString();
        Path stage = storage.stagePath(id);
        byte[] original = "proof-one".getBytes(StandardCharsets.UTF_8);
        Files.write(stage, original);
        storage.commit(id);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
        assertEquals(stage, storage.materialize(id, hash));
        var modified = Files.getLastModifiedTime(stage);
        Files.writeString(stage, "proof-two");
        Files.setLastModifiedTime(stage, modified);
        assertTrue(assertThrows(IOException.class, () -> storage.materialize(id, hash)).getMessage().contains("SHA-256"));
        assertThrows(IOException.class, () -> storage.materialize(id, ""));
        assertTrue(Files.exists(stage));
    }

    @Test void truncatedDownloadWithAnOtherwiseValidLengthDoesNotBecomeAnOriginal() throws Exception {
        MinioClient client = mock(MinioClient.class);
        String id = UUID.randomUUID().toString();
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(new GetObjectResponse(
                new Headers.Builder().add("Content-Length", "100").build(), "bidcheck", "", "documents/" + id + "/original.bin",
                new ByteArrayInputStream(new byte[]{1, 2, 3})));
        var storage = new ConfiguredArtifactStorage(directory.toString(), "minio", client, "bidcheck");
        assertThrows(IOException.class, () -> storage.materialize(id));
        try (var remaining = Files.list(directory.resolve("files"))) { assertEquals(0, remaining.count()); }
    }
}
