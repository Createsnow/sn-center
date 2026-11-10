package com.adtek.sn.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.adtek.sn.query.AuditQuery;
import com.adtek.sn.query.SnItemQuery;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * sn 模块在真实 MySQL 上：初始迁移与原 Python 版 V1__init.sql 校验和相同（生产库可直接接管），全部 Mapper XML 可解析并能在空库上执行主要查询，
 * 交付批次的序号、认领与回执 SQL 按预期改动行数（JDBC 连接带 useAffectedRows=true，与原系统 PyMySQL 语义一致）。
 */
@Testcontainers
class SnSchemaIT {
    /** 原系统 tests/test_unit.py::test_migration_flyway_checksum 固定的校验和。 */
    static final int PYTHON_V1_CHECKSUM = -2062353459;

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(
                            DockerImageName.parse(
                                            "mysql:8.4.11@sha256:0744ee5ef89ce6ccfa13de3e579fe6b9e27f93dd70da9c06d2c908b1b193fb8d")
                                    .asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName("sn_center");

    static SqlSessionFactory factory;
    static JdbcTemplate jdbc;
    static Flyway flyway;

    @BeforeAll
    static void setUp() throws Exception {
        DriverManagerDataSource ds =
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl()
                                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useAffectedRows=true",
                        MYSQL.getUsername(),
                        MYSQL.getPassword());
        flyway =
                Flyway.configure()
                        .dataSource(ds)
                        .locations("classpath:db/migration/sn", "classpath:db/migration/framework")
                        .load();
        flyway.migrate();
        jdbc = new JdbcTemplate(ds);
        Configuration cfg =
                new Configuration(new Environment("it", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.setJdbcTypeForNull(JdbcType.NULL);
        for (Resource r :
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:/mapper/sn/*.xml")) {
            try (InputStream in = r.getInputStream()) {
                new XMLMapperBuilder(in, cfg, r.getURI().toString(), cfg.getSqlFragments()).parse();
            }
        }
        factory = new SqlSessionFactoryBuilder().build(cfg);
    }

    @Test
    void initialMigrationIsTheOriginalSchema() {
        MigrationInfo init =
                List.of(flyway.info().all()).stream()
                        .filter(
                                m ->
                                        m.getVersion() != null
                                                && "202611100000"
                                                        .equals(m.getVersion().getVersion()))
                        .findFirst()
                        .orElseThrow();
        assertThat(init.getChecksum()).isEqualTo(PYTHON_V1_CHECKSUM);
        Integer tables =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()"
                                + " AND TABLE_NAME LIKE 'sn\\_%'",
                        Integer.class);
        assertThat(tables).isEqualTo(38);
    }

    @Test
    void readQueriesRunOnAnEmptySchema() {
        try (SqlSession s = factory.openSession(true)) {
            List<String> scope = List.of("110");
            AllocationMapper a = s.getMapper(AllocationMapper.class);
            assertThat(a.countBills(null, null, "TO_ALLOCATE")).isZero();
            assertThat(a.countBills(scope, "%X%", "ALLOCATED")).isZero();
            assertThat(a.contextRows("PI-1", scope)).isEmpty();
            DeliveryMapper d = s.getMapper(DeliveryMapper.class);
            assertThat(d.countBatches(scope, true, "PI-1", null, null, null, null, null)).isZero();
            assertThat(d.firstInScope("110", true, "PI-1", "B1", "PENDING_SCOPED", null, false))
                    .isNull();
            assertThat(d.firstInScope("110", false, null, null, "FAILED_PULLED", 1L, true))
                    .isNull();
            assertThat(d.qtyInScope("110", true, null, null, "PENDING", null)).isZero();
            assertThat(d.unbatchedCount("110", null, null, 0)).isZero();
            assertThat(d.runnableTasks(LocalDateTime.now())).isEmpty();
            assertThat(d.segmentsOf(1)).isEmpty();
            QueryMapper q = s.getMapper(QueryMapper.class);
            SnItemQuery f = new SnItemQuery();
            f.scope = scope;
            f.status = "SUSPENDED";
            assertThat(q.count(f, 100_001)).isZero();
            assertThat(q.page(f, 0, 20)).isEmpty();
            AuditQuery af = new AuditQuery();
            af.start = LocalDateTime.now().minusDays(30);
            af.end = LocalDateTime.now().plusDays(1);
            af.scope = scope;
            af.bound = true;
            af.like = "%x%";
            AuditQueryMapper aq = s.getMapper(AuditQueryMapper.class);
            assertThat(aq.countLive(af, null)).isZero();
            assertThat(aq.page(af, true, Long.MAX_VALUE, true, 0, 10)).isEmpty();
            TransferMapper t = s.getMapper(TransferMapper.class);
            assertThat(t.countAll(true, true, "PI-1", "B1", scope)).isZero();
            assertThat(t.sources("PI-1", null, scope)).isEmpty();
            DashboardMapper db = s.getMapper(DashboardMapper.class);
            assertThat(db.statusCounts(scope)).isEmpty();
        }
    }

    @Test
    void deliverySequenceClaimAndConfirmTouchTheExpectedRows() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 8, 0);
        jdbc.update(
                "INSERT INTO sn_factory(factory_code, factory_name, enabled, mes_enabled, created_at, updated_at)"
                        + " VALUES ('110', '一厂', 1, 1, ?, ?)",
                now,
                now);
        jdbc.update(
                "INSERT INTO sn_item(sn, origin, pi_no, customer_code, bill_no, bill_seq, material_code, gen_org_code,"
                        + " gen_bill_no, gen_bill_seq, seq_pi_no, seq_dec, rule_version_id, gen_batch, status,"
                        + " factory_code, created_at, updated_at) VALUES ('HX1', 'GEN', 'PI-1', 'C', 'B1', 1, 'M',"
                        + " '110', 'B1', 1, 'PI-1', 1, 0, 'G1', 'TO_PUSH', '110', ?, ?)",
                now,
                now);
        try (SqlSession s = factory.openSession(true)) {
            DeliveryMapper d = s.getMapper(DeliveryMapper.class);
            assertThat(d.seqExists("PS-110-20261110")).isFalse();
            d.seqInsert("PS-110-20261110");
            assertThat(d.seqBump("PS-110-20261110")).isEqualTo(1);
            assertThat(d.seqValue("PS-110-20261110")).isEqualTo(1L);
            List<Map<String, Object>> groups = d.poolGroups("110", "PI-1", null);
            assertThat(groups).hasSize(1);
            assertThat(groups.get(0).get("t")).isEqualTo("NEW");
            List<Map<String, Object>> claimed = d.claim("110", "PI-1", 10, null, "NEW", false);
            assertThat(claimed).hasSize(1);
            long id = ((Number) claimed.get(0).get("id")).longValue();
            assertThat(d.assignBatch("PS-110-20261110-000001", now, List.of(id))).isEqualTo(1);
            assertThat(d.assignBatch("PS-110-20261110-000001", now, List.of(id)))
                    .as("已入批的号不能再认领（useAffectedRows）")
                    .isZero();
            assertThat(d.itemsPushed("PS-110-20261110-000001", "110", "PUSH", now)).isEqualTo(1);
            assertThat(d.readDelivered("110", true, "PI-1", 0, 10)).hasSize(1);
        }
    }
}
