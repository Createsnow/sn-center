package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import com.adtek.integration.legacy.LegacySnapshotFactory;
import com.adtek.sn.dto.LegacySnapshotCommandDTO;
import com.adtek.sn.vo.LegacySnapshotManifestVO;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 已知仓库V1复制到一次性MySQL；源读取账号只有SELECT，绝不加载真实旧库配置。 */
@Testcontainers
class LegacySnapshotIT {
    static final String PASSWORD = UUID.randomUUID().toString().replace("-", "");

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("adtek_legacy_source_it")
                    .withUsername("fixture_writer")
                    .withPassword(PASSWORD)
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_bin",
                            "--default-time-zone=+00:00");

    static DriverManagerDataSource source;
    static JdbcTemplate jdbc;
    @TempDir Path root;

    @BeforeAll
    static void schema() throws Exception {
        var writer = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), PASSWORD);
        jdbc = new JdbcTemplate(writer);
        try (var c = writer.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("legacy/repository-v1.sql"));
        }
        var grant =
                MYSQL.execInContainer(
                        "sh",
                        "-c",
                        "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot -e \"CREATE USER"
                                + " 'legacy_reader'@'%' IDENTIFIED BY '"
                                + PASSWORD
                                + "'; GRANT SELECT ON adtek_legacy_source_it.* TO"
                                + " 'legacy_reader'@'%';\"");
        assertThat(grant.getExitCode()).isZero();
        source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), "legacy_reader", PASSWORD);
        // 已知夹具元数据单独核对，使结构失败可定位；不输出任何业务行或凭据。
        var expected =
                new ObjectMapper()
                        .readTree(
                                new ClassPathResource("legacy/repository-v1.json")
                                        .getInputStream());
        for (var table : expected.path("tables"))
            for (var col : table.path("columns")) {
                var actual =
                        jdbc.queryForMap(
                                "SELECT column_type,is_nullable,collation_name FROM"
                                        + " information_schema.columns WHERE table_schema=DATABASE()"
                                        + " AND table_name=? AND column_name=?",
                                table.path("name").asString(),
                                col.path("name").asString());
                assertThat(actual.get("column_type"))
                        .as(table.path("name").asString() + "." + col.path("name").asString())
                        .isEqualTo(col.path("type").asString());
                assertThat(actual.get("is_nullable"))
                        .as(table.path("name").asString() + "." + col.path("name").asString())
                        .isEqualTo(col.path("nullable").asBoolean() ? "YES" : "NO");
                assertThat(actual.get("collation_name"))
                        .isEqualTo(col.path("collation").asString(null));
            }
    }

    @BeforeEach
    void cleanFixture() {
        for (String table :
                jdbc.queryForList(
                        "SELECT table_name FROM information_schema.tables WHERE"
                                + " table_schema=DATABASE()",
                        String.class)) jdbc.update("DELETE FROM " + table);
        jdbc.update(
                "INSERT INTO sn_factory(factory_code,factory_name,source,created_at,updated_at)"
                        + " VALUES('F-1','测试工厂','MANUAL','2026-01-01','2026-01-01')");
    }

    private LegacySnapshotCommandDTO command(Path output) {
        return new LegacySnapshotCommandDTO(
                UUID.randomUUID().toString(),
                MYSQL.getDatabaseName(),
                "sn-python-repository-v1",
                "Asia/Shanghai",
                1000,
                1048576,
                output);
    }

    private LegacySnapshotManifestVO capture(Path output) {
        return LegacySnapshotFactory.create(source).capture(command(output));
    }

    @Test
    void exportsAllKnownTablesWithSelectOnlyAccountAndStableFingerprint() throws Exception {
        assertThatThrownBy(
                        () ->
                                new JdbcTemplate(source)
                                        .update("UPDATE sn_factory SET enabled=FALSE"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var first = capture(root.resolve("one"));
        var second = capture(root.resolve("two"));
        assertThat(first.tables()).hasSize(19);
        assertThat(first.schemaMetadata()).isNotEmpty();
        assertThat(first.sourceReadOnly()).isTrue();
        assertThat(first.readyForPromotion()).isFalse();
        assertThat(first.sourceFingerprint()).isEqualTo(second.sourceFingerprint());
        assertThat(first.issues()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT enabled FROM sn_factory", Boolean.class)).isTrue();
        var row =
                new ObjectMapper()
                        .readTree(Files.readString(root.resolve("one/sn_factory.ndjson")));
        assertThat(row.path("factory_name").asString()).isEqualTo("测试工厂");
        assertThat(row.path("created_at").asString()).isEqualTo("2026-01-01 00:00:00");
        assertThatThrownBy(() -> capture(root.resolve("one"))).hasMessage("LEGACY_OUTPUT_EXISTS");
    }

    @Test
    void unknownColumnOrUniqueKeyIsRefusedBeforeExport() {
        jdbc.execute("ALTER TABLE sn_factory ADD unsupported VARCHAR(8)");
        try {
            assertThatThrownBy(() -> capture(root.resolve("bad")))
                    .hasMessage("LEGACY_UNSUPPORTED_SCHEMA");
            assertThat(root.resolve("bad")).doesNotExist();
        } finally {
            jdbc.execute("ALTER TABLE sn_factory DROP COLUMN unsupported");
        }
        jdbc.execute("ALTER TABLE sn_factory DROP INDEX ux_factory_name");
        try {
            assertThatThrownBy(() -> capture(root.resolve("key")))
                    .hasMessage("LEGACY_UNSUPPORTED_SCHEMA");
        } finally {
            jdbc.execute("ALTER TABLE sn_factory ADD UNIQUE KEY ux_factory_name(factory_name)");
        }
    }

    @Test
    void preservesHighPrecisionAndExposesIntegrityAndMissingHistoryBlockers() throws Exception {
        jdbc.update(
                "INSERT INTO"
                        + " sn_item(gen_month,sn,pi_no,seq_pi_no,seq_dec,status,source,created_at,updated_at)"
                        + " VALUES(202608,'原文0001','P','P',9007199254740993,'PRINTED','IMPORT','2026-08-01','2026-08-01')");
        jdbc.update(
                "INSERT INTO"
                        + " sn_print(print_no,factory_code,pi_no,request_no,qty,created_by,created_at)"
                        + " VALUES('PRINT-OLD','F-1','P','legacy-request',1,'historical','2026-08-01')");
        var result = capture(root.resolve("snapshot"));
        assertThat(result.issues())
                .extracting(LegacySnapshotManifestVO.Issue::code)
                .contains(
                        "SN_KEY_MISSING",
                        "SN_WATERMARK_MISSING_OR_LOW",
                        "PRINT_MEMBERS_AND_FILES_REQUIRED");
        var row =
                new ObjectMapper()
                        .readTree(Files.readString(root.resolve("snapshot/sn_item.ndjson")));
        assertThat(row.path("seq_dec").asString()).isEqualTo("9007199254740993");
        assertThat(row.path("gen_month").asString()).isEqualTo("202608");
        assertThat(row.path("factory_code").isNull()).isTrue();
    }

    @Test
    void resourceOverflowDoesNotPublishAUsableManifest() {
        jdbc.update(
                "INSERT INTO sn_factory(factory_code,factory_name,source,created_at,updated_at)"
                        + " VALUES('F-2','第二工厂','MANUAL','2026-01-01','2026-01-01')");
        var cmd =
                new LegacySnapshotCommandDTO(
                        UUID.randomUUID().toString(),
                        MYSQL.getDatabaseName(),
                        "sn-python-repository-v1",
                        "Asia/Shanghai",
                        1,
                        1048576,
                        root.resolve("limited"));
        assertThatThrownBy(() -> LegacySnapshotFactory.create(source).capture(cmd))
                .hasMessage("LEGACY_ROW_LIMIT");
        assertThat(root.resolve("limited/manifest.json")).doesNotExist();
    }

    @Test
    void wrongSourceSchemaAndAmbiguousTimezoneAreRefused() {
        var cmd =
                new LegacySnapshotCommandDTO(
                        UUID.randomUUID().toString(),
                        "wrong_schema",
                        "sn-python-repository-v1",
                        "UTC",
                        1000,
                        1048576,
                        root.resolve("wrong"));
        assertThatThrownBy(() -> LegacySnapshotFactory.create(source).capture(cmd))
                .hasMessage("LEGACY_SCHEMA_MISMATCH");
        jdbc.update("UPDATE sn_factory SET created_at='2026-11-01 01:30:00'");
        var ambiguous =
                new LegacySnapshotCommandDTO(
                        UUID.randomUUID().toString(),
                        MYSQL.getDatabaseName(),
                        "sn-python-repository-v1",
                        "America/New_York",
                        1000,
                        1048576,
                        root.resolve("time"));
        assertThatThrownBy(() -> LegacySnapshotFactory.create(source).capture(ambiguous))
                .hasMessage("LEGACY_TIME_AMBIGUOUS");
        assertThat(root.resolve("time/manifest.json")).doesNotExist();
    }
}
