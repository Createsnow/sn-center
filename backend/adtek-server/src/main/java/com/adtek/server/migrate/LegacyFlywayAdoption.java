package com.adtek.server.migrate;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * 接管原 Python 版生产库（migrate profile，一次性）：原系统用自带迁移器写了 Flyway 兼容的 flyway_schema_history，只有一行
 * V1__init.sql。 本工程的 sn 模块初始迁移 V202611100000__init.sql 与它逐字节相同（校验和相同）。检测到这种库时：
 *
 * <ol>
 *   <li>核对原记录的校验和与 V202611100000 的校验和相同（不同即拒绝，防止接管结构不一致的库）；
 *   <li>原历史表改名为 flyway_schema_history_python（保留备查）；
 *   <li>按版本 202611100000 建立基线（库里已有的结构视为已执行），再正常执行之后的迁移（如 framework_session）。
 * </ol>
 *
 * 新库（没有历史表）与已接管的库按 Flyway 常规流程执行。
 */
@Configuration(proxyBeanMethods = false)
@Profile("migrate")
public class LegacyFlywayAdoption {
    private static final Logger log = LoggerFactory.getLogger(LegacyFlywayAdoption.class);
    static final String HISTORY = "flyway_schema_history";
    static final String LEGACY = "flyway_schema_history_python";
    static final String LEGACY_SCRIPT = "V1__init.sql";
    static final String SN_INIT = "202611100000";

    @Bean
    FlywayMigrationStrategy legacyAwareMigration(DataSource dataSource) {
        return flyway -> {
            adoptIfLegacy(flyway, new JdbcTemplate(dataSource));
            flyway.migrate();
        };
    }

    static void adoptIfLegacy(Flyway flyway, JdbcTemplate jdbc) {
        Integer exists =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                        Integer.class,
                        HISTORY);
        if (exists == null || exists == 0) {
            return;
        }
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT version, script, checksum, success FROM "
                                + HISTORY
                                + " ORDER BY installed_rank");
        boolean legacy = rows.stream().anyMatch(r -> LEGACY_SCRIPT.equals(r.get("script")));
        boolean adopted =
                rows.stream().anyMatch(r -> SN_INIT.equals(String.valueOf(r.get("version"))));
        if (!legacy || adopted) {
            return;
        }
        if (rows.size() != 1) {
            throw new IllegalStateException("原系统迁移历史不止一条（" + rows.size() + " 条），需人工核对后再接管");
        }
        Map<String, Object> old = rows.get(0);
        Integer resolved = null;
        for (MigrationInfo m : flyway.info().all()) {
            if (m.getVersion() != null
                    && m.getVersion().equals(MigrationVersion.fromVersion(SN_INIT))) {
                resolved = m.getChecksum();
            }
        }
        Object checksum = old.get("checksum");
        if (resolved == null
                || checksum == null
                || resolved.intValue() != ((Number) checksum).intValue()) {
            throw new IllegalStateException(
                    "原系统 V1__init.sql 校验和（"
                            + checksum
                            + "）与 V"
                            + SN_INIT
                            + " 不一致（"
                            + resolved
                            + "），拒绝接管");
        }
        jdbc.execute("RENAME TABLE " + HISTORY + " TO " + LEGACY);
        Flyway.configure()
                .configuration(flyway.getConfiguration())
                .baselineVersion(MigrationVersion.fromVersion(SN_INIT))
                .baselineDescription("adopted from Python V1__init.sql")
                .load()
                .baseline();
        log.warn(
                "Adopted legacy schema: {} renamed to {}, baseline at {}",
                HISTORY,
                LEGACY,
                SN_INIT);
    }
}
