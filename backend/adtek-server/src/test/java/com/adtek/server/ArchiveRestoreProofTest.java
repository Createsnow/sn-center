package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import com.adtek.sn.mapper.MaintenanceMapper;
import com.adtek.sn.service.support.ArchiveRestoreProof;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.YearMonth;
import java.util.*;

class ArchiveRestoreProofTest {
    @TempDir Path root;
    private final YearMonth month = YearMonth.of(2026, 1);

    private RestoreProofFixture fixture() throws Exception {
        return new RestoreProofFixture(
                root,
                "fixture",
                UUID.randomUUID().toString(),
                month,
                new MaintenanceMapper.Digest(1, "a".repeat(64)));
    }

    private void refused(RestoreProofFixture f) {
        assertThatThrownBy(() -> new ArchiveRestoreProof(root).verify(f.directory, month))
                .hasMessage("ARCHIVE_RESTORE_PROOF_INVALID");
    }

    @Test
    void validatesArtifactsAndEquivalentMysqlDdl() throws Exception {
        var f = fixture();
        var result = new ArchiveRestoreProof(root).verify(f.directory, month);
        assertThat(result.rows()).isEqualTo(1);
        assertThat(result.proofDigest())
                .isEqualTo(RestoreProofFixture.hash(f.restored.resolve("restore-proof.json")));
    }

    @Test
    void rejectsMissingRootAndTraversal() throws Exception {
        var f = fixture();
        assertThatThrownBy(() -> new ArchiveRestoreProof(null).verify(f.directory, month))
                .hasMessage("ARCHIVE_RESTORE_PROOF_INVALID");
        assertThatThrownBy(() -> new ArchiveRestoreProof(root).verify("../" + f.directory, month))
                .hasMessage("ARCHIVE_RESTORE_PROOF_INVALID");
        Files.delete(f.restored.resolve("browser.json"));
        refused(f);
    }

    @Test
    void rawDataChangeFailsEvenWhenRawHashIsReplaced() throws Exception {
        var f = fixture();
        Path sql = f.restored.resolve("restored.sql");
        Files.writeString(sql, Files.readString(sql).replace("中文", "changed"));
        f.proof.put("restoredDumpSha256", RestoreProofFixture.hash(sql));
        f.save();
        refused(f);
    }

    @Test
    void fileByteChangeRefusesProof() throws Exception {
        var f = fixture();
        byte[] bytes = Files.readAllBytes(f.restored.resolve("restored-files.tar"));
        bytes[512] ^= 1;
        Files.write(f.restored.resolve("restored-files.tar"), bytes);
        refused(f);
    }

    @Test
    void failedBrowserSameServerAndWrongMonthRefused() throws Exception {
        var f = fixture();
        f.proof.put("targetIdentity", f.manifest.get("sourceIdentity"));
        f.save();
        refused(f);
        f = fixture();
        Files.writeString(
                f.restored.resolve("browser.json"),
                "{\"stats\":{\"expected\":1,\"unexpected\":1,\"skipped\":0,\"flaky\":0}}");
        f.proof.put(
                "browser",
                Map.of(
                        "reportSha256",
                        RestoreProofFixture.hash(f.restored.resolve("browser.json"))));
        f.save();
        refused(f);
        var clean = fixture();
        assertThatThrownBy(
                        () ->
                                new ArchiveRestoreProof(root)
                                        .verify(clean.directory, month.plusMonths(1)))
                .hasMessage("ARCHIVE_RESTORE_PROOF_INVALID");
    }
}
