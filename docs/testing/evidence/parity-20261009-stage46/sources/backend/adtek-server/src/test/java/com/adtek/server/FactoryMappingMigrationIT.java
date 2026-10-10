package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 从真实V39升级，核对旧显式绑定、自动名称重算、额度与迁移重跑；只使用一次性库。 */
@Testcontainers
class FactoryMappingMigrationIT {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_0900_as_cs");

    @Test
    void upgradesOldBindingsWithoutChangingConsumedQuotaOrUnchangedSnapshots() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("39")
                .load()
                .migrate();
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update(
                "INSERT INTO sn_factory(id,code,name,source) VALUES(701,'F-A','旧厂新名','K3'),"
                        + "(702,'F-B','显式厂','MANUAL'),(703,'F-C','订单旧名','MANUAL')");
        jdbc.update(
                "INSERT INTO sn_org_mapping(source_system,source_account,org_id,org_code,org_name,factory_id)"
                        + " VALUES('K3','T','1','F-A','旧厂新名',701),('K3','T','2','F-B','显式厂',702)");
        jdbc.update(
                "INSERT INTO sys_oper_log(action,object_id,success,changes)"
                        + " VALUES('sn:factory:map','702',TRUE,'{}'),('sn:factory:map','701',FALSE,'{}')");
        for (int id = 1; id <= 3; id++) {
            jdbc.update(
                    "INSERT INTO sn_order_bill(id,source_system,source_account,source_bill_id,bill_no,"
                            + "customer,po,pi,org_id,org_code,org_name,factory_id,factory_code,total_qty,"
                            + "line_count,statuses,snapshot_version,synced_at)"
                            + " VALUES(?,'K3','T',?,?,'C','P','PI',?,'F-A',?,?,'F-A',5,1,'1',8,UTC_TIMESTAMP(6))",
                    id,
                    Integer.toString(id),
                    "B" + id,
                    id == 2 ? "2" : "1",
                    id == 1 ? "订单旧名" : id == 2 ? "快照异名" : "旧厂新名",
                    id == 2 ? 702 : 701);
            jdbc.update(
                    "INSERT INTO sn_order_entry(id,bill_id,source_entry_id,ordinal,material,material_name,"
                            + "qty,status,demand_bill_no,factory_id,snapshot_version)"
                            + " VALUES(?,?,'1',1,'M','物料',5,'1','D',?,8)",
                    id,
                    id,
                    id == 2 ? 702 : 701);
            jdbc.update("INSERT INTO sn_source_entry_quota VALUES(?,'1',3,2)", id);
        }
        jdbc.update("UPDATE sn_order_bill SET factory_code='F-B' WHERE id=2");
        jdbc.update("UPDATE sn_snapshot_control SET revision=8");
        var quota = jdbc.queryForList("SELECT * FROM sn_source_entry_quota ORDER BY bill_id");
        var preserved =
                jdbc.queryForList("SELECT * FROM sn_order_bill WHERE id IN (2,3) ORDER BY id");
        var flyway =
                Flyway.configure()
                        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                        .target("40")
                        .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(
                        jdbc.queryForList(
                                "SELECT binding_kind FROM sn_org_mapping ORDER BY org_id",
                                String.class))
                .containsExactly("AUTO", "EXPLICIT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT factory_id FROM sn_order_bill WHERE id=1", Long.class))
                .isEqualTo(703L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT factory_id FROM sn_order_entry WHERE id=1", Long.class))
                .isEqualTo(703L);
        assertThat(jdbc.queryForObject("SELECT revision FROM sn_snapshot_control", Long.class))
                .isEqualTo(9L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT snapshot_version FROM sn_order_bill WHERE id=1",
                                Long.class))
                .isEqualTo(9L);
        assertThat(jdbc.queryForList("SELECT * FROM sn_source_entry_quota ORDER BY bill_id"))
                .isEqualTo(quota);
        assertThat(jdbc.queryForList("SELECT * FROM sn_order_bill WHERE id IN (2,3) ORDER BY id"))
                .isEqualTo(preserved);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT revision FROM sn_snapshot_control", Long.class))
                .isEqualTo(9L);
    }
}
