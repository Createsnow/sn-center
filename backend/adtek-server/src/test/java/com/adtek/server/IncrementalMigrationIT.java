package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.UUID;

/** 隔离空库先停在V17，验证增量升级只沿用最新成功规则并保留既有历史及水位。 */
@Testcontainers
class IncrementalMigrationIT {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("adtek_incremental_upgrade_it")
                    .withUsername("upgrade_it")
                    .withPassword(UUID.randomUUID().toString());

    @Test
    void upgradeFrom17PreservesHistoryAndBackfillsOnlyLatestSuccessfulChoice() {
        var source =
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(source).target("17").load().migrate();
        var jdbc = new JdbcTemplate(source);
        var before =
                jdbc.queryForList(
                        "SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        jdbc.update(
                "INSERT INTO sn_pi_counter(pi,last_seq,generated_qty,start_locked)"
                        + " VALUES('Case',9007199254740993,3,TRUE)");
        work(jdbc, "Case", "GENERATE", "SUCCESS", "11", "2026-10-01 01:00:00");
        work(jdbc, "Case", "GENERATE", "SUCCESS", "9007199254740993", "2026-10-02 01:00:00");
        work(jdbc, "Case", "GENERATE", "FAILED", "22", "2026-10-03 01:00:00");
        work(jdbc, "case", "GENERATE", "SUCCESS", "33", "2026-10-02 01:00:00");
        work(jdbc, "Pending", "GENERATE", "PENDING", "44", null);
        work(jdbc, "Init", "PI_INIT", "SUCCESS", "55", "2026-10-03 01:00:00");
        work(jdbc, "Missing", "GENERATE", "SUCCESS", null, "2026-10-03 01:00:00");
        var works = jdbc.queryForList("SELECT * FROM sn_work ORDER BY task_id");
        var counters = jdbc.queryForList("SELECT * FROM sn_pi_counter ORDER BY pi");

        var upgrade = Flyway.configure().dataSource(source).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT version,checksum,success FROM flyway_schema_history"
                                        + " WHERE installed_rank<=17 ORDER BY installed_rank"))
                .isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM sn_work ORDER BY task_id")).isEqualTo(works);
        assertThat(jdbc.queryForList("SELECT * FROM sn_pi_counter ORDER BY pi"))
                .isEqualTo(counters);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_pi_rule_choice", Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT rule_id FROM sn_pi_rule_choice WHERE pi='Case'",
                                Long.class))
                .isEqualTo(9007199254740993L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT rule_id FROM sn_pi_rule_choice WHERE pi='case'",
                                Long.class))
                .isEqualTo(33L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT enabled FROM sn_order_sync_schedule WHERE id=1",
                                Boolean.class))
                .isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT interval_minutes FROM sn_order_sync_schedule WHERE id=1",
                                Integer.class))
                .isEqualTo(1440);
        assertThat(
                        jdbc.queryForMap(
                                        "SELECT owner_id,next_run_at FROM sn_order_sync_schedule WHERE id=1")
                                .values())
                .containsOnlyNulls();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }

    private void work(
            JdbcTemplate jdbc, String pi, String kind, String state, String rule, String finished) {
        jdbc.update(
                "INSERT INTO sn_work(task_id,kind,pi,actor_id,payload,state,quantity,finished_at)"
                        + " VALUES(?,?,?,1,?,?,1,?)",
                UUID.randomUUID().toString(),
                kind,
                pi,
                rule == null ? "{}" : "{\"ruleId\":" + rule + "}",
                state,
                finished);
    }
}
