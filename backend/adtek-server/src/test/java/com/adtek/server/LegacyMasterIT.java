package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import com.adtek.integration.legacy.LegacySnapshotFactory;
import com.adtek.sn.config.*;
import com.adtek.sn.dto.*;
import com.adtek.sn.service.LegacyMasterService;
import com.adtek.system.config.LegacyIdentityFactory;

import org.flywaydb.core.Flyway;
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
import java.util.*;

/** 两实例真实V1→V15主数据转换；合成身份不启用，源数据不改写，保留高位ID/时间/规则版本及未知创建者。 */
@Testcontainers
class LegacyMasterIT {
    static final String PASSWORD = UUID.randomUUID().toString();
    // 公开合成Python PBKDF2向量，不是实际账号秘密。
    static final String HASH =
            "000102030405060708090a0b0c0d0e0f94bce3685d06306a31519b6aeca32ca5d2200f846369f29f551563c979f4638f";

    @Container
    static final MySQLContainer SOURCE =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("legacy_master_source")
                    .withUsername("source_it")
                    .withPassword(PASSWORD);

    @Container
    static final MySQLContainer TARGET =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("legacy_master_target")
                    .withUsername("target_it")
                    .withPassword(PASSWORD);

    static DriverManagerDataSource source, target;
    static JdbcTemplate old, jdbc;
    @TempDir Path root;
    String snapshot, run;
    LegacyMasterService service;

    @BeforeAll
    static void schema() throws Exception {
        source = new DriverManagerDataSource(SOURCE.getJdbcUrl(), SOURCE.getUsername(), PASSWORD);
        target = new DriverManagerDataSource(TARGET.getJdbcUrl(), TARGET.getUsername(), PASSWORD);
        old = new JdbcTemplate(source);
        jdbc = new JdbcTemplate(target);
        try (var c = source.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("legacy/repository-v1.sql"));
        }
        Flyway.configure().dataSource(target).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void prepare() throws Exception {
        for (String table :
                List.of(
                        "sn_legacy_master_run",
                        "sn_legacy_id_map",
                        "sn_legacy_row",
                        "sn_legacy_migration_table",
                        "sn_legacy_migration_run",
                        "sn_user_factory",
                        "sn_rule_version",
                        "sn_rule",
                        "sn_factory",
                        "sys_user_role",
                        "sys_user")) jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE sys_security_control SET initialized=FALSE WHERE id=1");
        var descriptor =
                new ObjectMapper()
                        .readTree(
                                new ClassPathResource("legacy/repository-v1.json")
                                        .getInputStream());
        for (var table : descriptor.path("tables"))
            old.update("DELETE FROM " + table.path("name").asString());
        old.update(
                "INSERT INTO"
                        + " sn_factory(factory_code,factory_name,enabled,source,created_at,updated_at)"
                        + " VALUES('F1','合成甲厂',1,'MANUAL','2026-09-01 00:01:02','2026-09-01"
                        + " 01:02:03'),('F2','合成乙厂',0,'K3','2026-09-01','2026-09-01')");
        user(9007199254740993L, "legacy_admin", "admin", null, "ACTIVE", null, 1);
        user(12, "legacy_operator", "factory_operator", "F1", "ACTIVE", "vi", 0);
        user(13, "legacy_query_all", "query", null, "DISABLED", "en", 0);
        user(14, "legacy_query_own", "query", "F2", "ACTIVE", "zh-CN", 1);
        old.update(
                "INSERT INTO"
                        + " sn_rule(id,rule_code,rule_name,bind_scope,bind_value,current_version,created_by,created_at,updated_at)"
                        + " VALUES(101,'LEGACY_GENERAL','旧通用规则','GENERAL','',2,'deleted-person','2026-09-01"
                        + " 00:01:02','2026-09-01 00:01:02')");
        old.update(
                "INSERT INTO"
                        + " sn_rule_version(id,rule_id,version,prefix,suffix,base,seq_len,charset,used,created_by,created_at,updated_at)"
                        + " VALUES(201,101,1,'旧','',10,6,'0123456789',1,'legacy_admin','2026-09-01','2026-09-01'),(202,101,2,'新','',16,7,'0123456789ABCDEF',0,NULL,'2026-09-01','2026-09-01')");
        capture();
        service =
                LegacyMasterFactory.create(
                        target, TARGET.getDatabaseName(), root, LegacyIdentityFactory.create());
    }

    private void user(
            long id,
            String username,
            String role,
            String factory,
            String status,
            String language,
            int change) {
        old.update(
                "INSERT INTO"
                        + " sn_user(id,emp_no,name,role,factory_code,status,password_hash,must_change_pwd,lang,created_by,created_at,updated_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,'legacy_admin','2026-09-01 00:01:02','2026-09-01"
                        + " 01:02:03')",
                id,
                username,
                "合成用户",
                role,
                factory,
                status,
                HASH,
                change,
                language);
    }

    private void capture() {
        snapshot = UUID.randomUUID().toString();
        run = UUID.randomUUID().toString();
        LegacySnapshotFactory.create(source)
                .capture(
                        new LegacySnapshotCommandDTO(
                                snapshot,
                                SOURCE.getDatabaseName(),
                                "sn-python-repository-v1",
                                "Asia/Shanghai",
                                1000,
                                1048576,
                                root.resolve(snapshot)));
        LegacyStageFactory.create(target, TARGET.getDatabaseName(), root)
                .stage(new LegacyStageCommandDTO(run, snapshot, "legacy-v1-staging-v1", true, 2));
    }

    private LegacyMasterCommandDTO command(boolean apply) {
        return new LegacyMasterCommandDTO(run, snapshot, apply);
    }

    @Test
    void preservesIdentityScopeRuleVersionsUtcAndRawUnknownActorsWithoutActivating() {
        assertThat(service.promote(command(false)).state()).isEqualTo("DRY_RUN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT initialized FROM sys_security_control", Boolean.class))
                .isFalse();
        var result = service.promote(command(true));
        assertThat(result.accountsActive()).isFalse();
        assertThat(result.readyForPromotion()).isFalse();
        assertThat(result.unknownActorReferences()).isEqualTo("1");
        assertThat(service.promote(command(true))).isEqualTo(result);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_user WHERE enabled=TRUE", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT initialized FROM sys_security_control", Boolean.class))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT CAST(id AS CHAR) FROM sys_user WHERE"
                                        + " username='legacy_admin'",
                                String.class))
                .isEqualTo("9007199254740993");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT password_hash FROM sys_user WHERE id=12", String.class))
                .isEqualTo("{pbkdf2-sha256-120k}" + HASH);
        assertThat(jdbc.queryForObject("SELECT language FROM sys_user WHERE id=12", String.class))
                .isEqualTo("vi");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT language FROM sys_user WHERE username='legacy_admin'",
                                String.class))
                .isEqualTo("zh-CN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT must_change_password FROM sys_user WHERE id=12",
                                Boolean.class))
                .isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT must_change_password FROM sys_user WHERE id=14",
                                Boolean.class))
                .isTrue();
        assertThat(
                        jdbc.queryForList(
                                "SELECT role_id FROM sys_user_role WHERE user_id=9007199254740993"
                                        + " ORDER BY role_id",
                                Long.class))
                .containsExactly(1L, 2L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT role_id FROM sys_user_role WHERE user_id=13", Long.class))
                .isEqualTo(4L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT role_id FROM sys_user_role WHERE user_id=14", Long.class))
                .isEqualTo(5L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_user_factory", Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT DATE_FORMAT(create_time,'%Y-%m-%d %H:%i:%s') FROM sys_user"
                                        + " WHERE id=12",
                                String.class))
                .isEqualTo("2026-08-31 16:01:02");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') FROM sn_factory"
                                        + " WHERE code='F1'",
                                String.class))
                .isEqualTo("2026-08-31 16:01:02");
        assertThat(jdbc.queryForObject("SELECT created_by FROM sn_rule WHERE id=101", Long.class))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT created_by FROM sn_rule_version WHERE id=201", Long.class))
                .isEqualTo(9007199254740993L);
        assertThat(jdbc.queryForObject("SELECT current_version FROM sn_rule", Long.class))
                .isEqualTo(2L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT used FROM sn_rule_version WHERE id=201", Boolean.class))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.created_by')) FROM"
                                        + " sn_legacy_row WHERE table_name='sn_rule'",
                                String.class))
                .isEqualTo("deleted-person");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_legacy_id_map", Integer.class))
                .isEqualTo(11);
        assertThat(
                        old.queryForObject(
                                "SELECT COUNT(*) FROM sn_user WHERE status='ACTIVE'",
                                Integer.class))
                .isEqualTo(3);
    }

    @Test
    void laterTableFailureRollsBackIdentityFactoriesMapsAndBootstrapClosure() {
        jdbc.execute(
                "ALTER TABLE sn_rule ADD CONSTRAINT it_block_master CHECK(code<>'LEGACY_GENERAL')");
        try {
            assertThatThrownBy(() -> service.promote(command(true)))
                    .hasMessageStartingWith("LEGACY_MASTER_SQL_");
        } finally {
            jdbc.execute("ALTER TABLE sn_rule DROP CHECK it_block_master");
        }
        for (String table :
                List.of(
                        "sys_user",
                        "sys_user_role",
                        "sn_factory",
                        "sn_rule",
                        "sn_rule_version",
                        "sn_user_factory",
                        "sn_legacy_id_map",
                        "sn_legacy_master_run"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                    .as(table)
                    .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT initialized FROM sys_security_control", Boolean.class))
                .isFalse();
        assertThat(service.promote(command(true)).state()).isEqualTo("MASTER_STAGED");
    }

    @Test
    void changedStagingOrExistingTargetNeverGetsOverwritten() {
        jdbc.update(
                "UPDATE sn_legacy_row SET payload=JSON_SET(payload,'$.name','tampered') WHERE"
                        + " table_name='sn_user' AND ordinal=1");
        assertThatThrownBy(() -> service.promote(command(true)))
                .hasMessage("LEGACY_STAGE_ROW_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isZero();
    }

    @Test
    void replayDetectsActivatedUserRuleChangeOrMissingMap() {
        service.promote(command(true));
        jdbc.update("UPDATE sys_user SET enabled=TRUE WHERE id=12");
        assertThatThrownBy(() -> service.promote(command(true)))
                .hasMessage("LEGACY_IDENTITY_TARGET_CHANGED");
        jdbc.update("UPDATE sys_user SET enabled=FALSE WHERE id=12");
        jdbc.update("UPDATE sn_rule_version SET prefix='改' WHERE id=201");
        assertThatThrownBy(() -> service.promote(command(true)))
                .hasMessage("LEGACY_MASTER_TARGET_CHANGED");
        jdbc.update("UPDATE sn_rule_version SET prefix='旧' WHERE id=201");
        jdbc.update("DELETE FROM sn_legacy_id_map WHERE target_table='sys_user' LIMIT 1");
        assertThatThrownBy(() -> service.promote(command(true)))
                .hasMessage("LEGACY_MASTER_TARGET_CHANGED");
    }

    @Test
    void initializedTargetAndUnsupportedSourceRoleAreBlockedBeforeWrites() {
        jdbc.update("UPDATE sys_security_control SET initialized=TRUE");
        assertThatThrownBy(() -> service.promote(command(true)))
                .hasMessage("LEGACY_TARGET_NOT_EMPTY");
        jdbc.update("UPDATE sys_security_control SET initialized=FALSE");
        old.update("UPDATE sn_user SET role='unknown' WHERE id=12");
        capture();
        assertThatThrownBy(() -> service.promote(command(true)))
                .hasMessage("LEGACY_MASTER_SOURCE_BLOCKED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isZero();
    }
}
