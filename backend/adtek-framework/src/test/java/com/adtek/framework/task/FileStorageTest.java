package com.adtek.framework.task;

import static org.assertj.core.api.Assertions.*;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.task.domain.FileEntity;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;

class FileStorageTest {
    @TempDir Path root;

    private FileStorage storage() {
        return new FileStorage(
                new TaskSettings(false, 3, 30, 300, 3, 3, 1000, 10000, 1024, root.toString()));
    }

    @Test
    void rejectsPathsBeforeWritingOutsideRoot() {
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> storage().publish(id, "../escape.csv", out -> out.write(1)))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> storage().publish("../escape", id + ".csv", out -> out.write(1)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void writerFailureAndSizeLimitCleanPartsWithoutPublishing() throws Exception {
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(
                        () ->
                                storage()
                                        .publish(
                                                id,
                                                id + ".csv",
                                                out -> {
                                                    out.write(1);
                                                    throw new IOException("injected");
                                                }))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(
                        () -> storage().publish(id, id + ".csv", out -> out.write(new byte[1025])))
                .isInstanceOf(BusinessException.class);
        try (var parts = Files.list(root.resolve("parts"))) {
            assertThat(parts.count()).isZero();
        }
        assertThat(Files.exists(root.resolve("objects").resolve(id + ".csv"))).isFalse();
    }

    @Test
    void publishedBytesAreReusedAndInterruptedPartsAreCleanedOnlyForSameTask() throws Exception {
        String id = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();
        var store = storage();
        var first =
                store.publish(
                        id,
                        id + ".csv",
                        out ->
                                out.write(
                                        "stable"
                                                .getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)));
        var again =
                store.publish(
                        id,
                        id + ".csv",
                        out ->
                                out.write(
                                        "stable"
                                                .getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(again).isEqualTo(first);
        Path stale = root.resolve("parts").resolve(id + "-old.part"),
                unrelated = root.resolve("parts").resolve(other + "-old.part");
        Files.writeString(stale, "partial");
        Files.writeString(unrelated, "keep");
        store.cleanInterrupted(id);
        assertThat(Files.exists(stale)).isFalse();
        assertThat(Files.readString(unrelated)).isEqualTo("keep");
    }

    @Test
    void corruptContentAndAuthorizationAreRejectedBeforeExposingBytes() throws Exception {
        String id = UUID.randomUUID().toString();
        var store = storage();
        var metadata = store.publish(id, id + ".csv", out -> out.write(42));
        var file =
                new FileEntity(
                        id,
                        id,
                        FileState.READY,
                        id + ".csv",
                        metadata.digest(),
                        metadata.bytes(),
                        1,
                        Instant.now());
        assertThatThrownBy(
                        () ->
                                store.open(
                                        file,
                                        () -> {
                                            throw new IllegalStateException("revoked");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        Files.write(root.resolve("objects").resolve(id + ".csv"), new byte[] {43});
        assertThatThrownBy(() -> store.open(file, () -> {})).isInstanceOf(BusinessException.class);
    }

    @Test
    void changedSnapshotCannotReplaceCommittedObject() throws Exception {
        String id = UUID.randomUUID().toString();
        var store = storage();
        var original = store.publish(id, id + ".csv", out -> out.write(42));
        assertThatThrownBy(
                        () ->
                                store.publish(
                                        id, id + ".csv", original.digest(), out -> out.write(43)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(root.resolve("objects").resolve(id + ".csv")))
                .containsExactly((byte) 42);
    }

    @Test
    void linkedStorageDirectoryCannotEscapeControlledRoot(@TempDir Path outside) throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            // Windows用普通账号可创建的真实NTFS目录联接；不更改主机符号链接权限，不跳过路径防护验证。
            var process =
                    new ProcessBuilder(
                                    "cmd.exe",
                                    "/d",
                                    "/c",
                                    "mklink",
                                    "/J",
                                    root.resolve("objects").toString(),
                                    outside.toString())
                            .redirectErrorStream(true)
                            .start();
            assertThat(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        } else {
            Files.createSymbolicLink(root.resolve("objects"), outside);
        }
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> storage().publish(id, id + ".csv", out -> out.write(42)))
                .isInstanceOf(IOException.class);
        try (var files = Files.list(outside)) {
            assertThat(files.count()).isZero();
        }
    }
}
