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
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConfiguredArtifactStorageTest {
    @TempDir Path directory;

    @Test void largerOriginalsRemainStreamedAndIntegrityChecked() throws Exception {
        cn.bidcheck.storage.ConfiguredArtifactStorage storage = new ConfiguredArtifactStorage(directory.toString(), "local", null, "bidcheck");
        String id = UUID.randomUUID().toString();
        Path stage = storage.stagePath(id);
        try (RandomAccessFile file = new RandomAccessFile(stage.toFile(), "rw")) { file.setLength(32L * 1024 * 1024); }
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.security.DigestInputStream in = new java.security.DigestInputStream(Files.newInputStream(stage), digest)) { cn.bidcheck.compat.Java8Api.transferTo(in, cn.bidcheck.compat.Java8Extras.nullOutputStream()); }
        String hash = cn.bidcheck.compat.HexFormat.of().formatHex(digest.digest());
        storage.commit(id);
        assertEquals(stage, storage.materialize(id, hash));
        try (RandomAccessFile file = new RandomAccessFile(stage.toFile(), "rw")) { file.seek(file.length()-1); file.write(1); }
        assertThrows(IOException.class, () -> storage.materialize(id, hash));
    }

    @Test void localModeCommitsWithoutCopyingAndNeverDeletesCommittedOriginals() throws Exception {
        cn.bidcheck.storage.ConfiguredArtifactStorage storage = new ConfiguredArtifactStorage(directory.toString(), "local", "invalid endpoint", "", "", "ignored");
        String id = UUID.randomUUID().toString();
        Path stage = storage.stagePath(id);
        cn.bidcheck.compat.Java8Api.writeString(stage, "原始证据");
        assertThrows(IOException.class, () -> storage.materialize(id));
        storage.commit(id);
        assertEquals(stage, storage.materialize(id));
        try (java.io.InputStream input = storage.open(id)) { assertEquals("原始证据", new String(cn.bidcheck.compat.Java8Api.readAllBytes(input), StandardCharsets.UTF_8)); }
        storage.discardStaging(id);
        assertTrue(Files.exists(stage));
        assertThrows(IOException.class, () -> storage.stagePath(id));
        assertEquals("local", storage.mode());
        assertTrue(storage.available());
    }

    @Test void discardsOnlyUncommittedUploadsAndRejectsArbitraryPaths() throws Exception {
        cn.bidcheck.storage.ConfiguredArtifactStorage storage = new ConfiguredArtifactStorage(directory.toString(), "local", null, "bidcheck");
        String id = UUID.randomUUID().toString();
        Path stage = storage.stagePath(id);
        cn.bidcheck.compat.Java8Api.writeString(stage, "duplicate");
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
        cn.bidcheck.storage.ConfiguredArtifactStorage storage = new ConfiguredArtifactStorage(directory.toString(), "minio", client, "bidcheck");
        String id = UUID.randomUUID().toString();
        Path stage = storage.stagePath(id);
        Files.createFile(stage);
        assertThrows(IOException.class, () -> storage.commit(id));
        Files.delete(stage);
        try (java.nio.channels.SeekableByteChannel file = Files.newByteChannel(stage,
                java.util.EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE))) {
            file.position(ConfiguredArtifactStorage.MAX_BYTES);
            file.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }
        assertEquals(ConfiguredArtifactStorage.MAX_BYTES + 1, Files.size(stage));
        assertThrows(IOException.class, () -> storage.commit(id));
        verifyNoInteractions(client);
    }

    @Test void uploadsToDedicatedBucketBeforeMakingDocumentReadable() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        cn.bidcheck.storage.ConfiguredArtifactStorage storage = new ConfiguredArtifactStorage(directory.toString(), "minio", client, "bidcheck");
        String id = UUID.randomUUID().toString();
        Path stage = storage.stagePath(id);
        cn.bidcheck.compat.Java8Api.writeString(stage, "proof");
        assertFalse(storage.available());
        storage.commit(id);
        assertTrue(storage.available());
        org.mockito.InOrder sequence = inOrder(client);
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
        cn.bidcheck.storage.ConfiguredArtifactStorage storage = new ConfiguredArtifactStorage(directory.toString(), "minio", client, "bidcheck");
        String id = UUID.randomUUID().toString();
        Path stage = storage.stagePath(id);
        cn.bidcheck.compat.Java8Api.writeString(stage, "proof");
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
        cn.bidcheck.storage.ConfiguredArtifactStorage storage = new ConfiguredArtifactStorage(directory.toString(), "minio", client, "bidcheck");
        Path cached = storage.materialize(id);
        assertArrayEquals(bytes, Files.readAllBytes(cached));
        assertEquals(cached, storage.materialize(id));
        assertTrue(storage.available());
        ArgumentCaptor<GetObjectArgs> args = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(client).getObject(args.capture());
        assertEquals("documents/" + id + "/original.bin", args.getValue().object());
        verifyNoMoreInteractions(client);
        try (java.util.stream.Stream<java.nio.file.Path> remaining = Files.list(directory.resolve("files"))) {
            assertEquals(1, remaining.count());
        }
    }

    @Test void failedOrOversizedDownloadNeverPublishesAPartialCacheFile() throws Exception {
        MinioClient client = mock(MinioClient.class);
        String id = UUID.randomUUID().toString();
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(new GetObjectResponse(
                new Headers.Builder().add("Content-Length", Long.toString(ConfiguredArtifactStorage.MAX_BYTES + 1)).build(),
                "bidcheck", "", "documents/" + id + "/original.bin", new ByteArrayInputStream(new byte[]{1})));
        cn.bidcheck.storage.ConfiguredArtifactStorage storage = new ConfiguredArtifactStorage(directory.toString(), "minio", client, "bidcheck");
        assertThrows(IOException.class, () -> storage.materialize(id));
        assertFalse(Files.exists(directory.resolve("files").resolve(id + ".bin")));
        try (java.util.stream.Stream<java.nio.file.Path> remaining = Files.list(directory.resolve("files"))) { assertEquals(0, remaining.count()); }
        assertFalse(storage.available());
        assertThrows(IOException.class, () -> new ConfiguredArtifactStorage(directory.toString(), "minio", client, "external-bucket"));
    }

    @Test void verifiesRegisteredHashEvenIfACachedFileHasTheSameSizeAndTimestamp() throws Exception {
        cn.bidcheck.storage.ConfiguredArtifactStorage storage = new ConfiguredArtifactStorage(directory.toString(), "local", null, "bidcheck");
        String id = UUID.randomUUID().toString();
        Path stage = storage.stagePath(id);
        byte[] original = "proof-one".getBytes(StandardCharsets.UTF_8);
        Files.write(stage, original);
        storage.commit(id);
        String hash = cn.bidcheck.compat.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
        assertEquals(stage, storage.materialize(id, hash));
        java.nio.file.attribute.FileTime modified = Files.getLastModifiedTime(stage);
        cn.bidcheck.compat.Java8Api.writeString(stage, "proof-two");
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
        cn.bidcheck.storage.ConfiguredArtifactStorage storage = new ConfiguredArtifactStorage(directory.toString(), "minio", client, "bidcheck");
        assertThrows(IOException.class, () -> storage.materialize(id));
        try (java.util.stream.Stream<java.nio.file.Path> remaining = Files.list(directory.resolve("files"))) { assertEquals(0, remaining.count()); }
    }
}
