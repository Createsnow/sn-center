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

/** V37升级复制已有凭据代数，保留人员/服务主体的全部原字段与旧迁移摘要。 */
@Testcontainers
class CredentialVersionMigrationIT {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("credential_upgrade_it")
                    .withUsername("upgrade_it")
                    .withPassword(UUID.randomUUID().toString());

    @Test
    void upgradeCopiesExistingRevisionWithoutChangingOriginalColumns() {
        var source =
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(source).target("37").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update(
                "INSERT INTO sys_user(id,principal_type,username,password_hash,display_name,security_version,enabled,must_change_password) VALUES (41,'USER','upgrade_person','opaque-test-hash','保留账号',17,TRUE,FALSE),(42,'SERVICE','upgrade_service','opaque-service-hash','服务主体',23,FALSE,FALSE)");
        var original = jdbc.queryForList("SELECT * FROM sys_user ORDER BY id");
        var history =
                jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        var flyway = Flyway.configure().dataSource(source).target("38").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        var current = jdbc.queryForList("SELECT * FROM sys_user ORDER BY id");
        for (int i = 0; i < original.size(); i++) {
            assertThat(current.get(i).remove("credential_version"))
                    .isEqualTo(original.get(i).get("security_version"));
        }
        assertThat(current).isEqualTo(original);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM flyway_schema_history WHERE version<>'38' ORDER BY installed_rank"))
                .isEqualTo(history);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
}
