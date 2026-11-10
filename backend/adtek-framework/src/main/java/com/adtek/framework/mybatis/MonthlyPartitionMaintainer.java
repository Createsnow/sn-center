package com.adtek.framework.mybatis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 按月 RANGE COLUMNS 分区的 DDL（业务 Mapper 不能拼接标识符，分区名只能由这里在白名单格式内生成）：
 *
 * <ul>
 *   <li>{@link #partitions}：读 information_schema 的分区名与上界；
 *   <li>{@link #addMonths}：从兜底分区 pmax 拆出新月分区（pmax 始终为空，拆分几乎零成本）；
 *   <li>{@link #dropPartition}：删除一个整月分区（调用方先把数据拷入归档表）。
 * </ul>
 *
 * <p>表名与分区名都按固定格式校验；运行账号只对分区表本身有 ALTER / DROP 权限（见运维手册）。
 */
@Service
public class MonthlyPartitionMaintainer {
    private static final Logger log = LoggerFactory.getLogger(MonthlyPartitionMaintainer.class);
    private static final Pattern TABLE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern PARTITION = Pattern.compile("p\\d{6}");
    private static final Pattern BOUND = Pattern.compile("\\d{4}-\\d{2}-01 00:00:00");

    private final JdbcTemplate jdbc;

    public MonthlyPartitionMaintainer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 分区与上界（上界为 'yyyy-MM-01 00:00:00'，pmax 为 null），按分区顺序。 */
    public record Partition(String name, String bound) {}

    public List<Partition> partitions(String table) {
        check(TABLE, table);
        return jdbc.query(
                "SELECT PARTITION_NAME AS n, PARTITION_DESCRIPTION AS d FROM information_schema.PARTITIONS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND PARTITION_NAME IS NOT NULL"
                        + " ORDER BY PARTITION_ORDINAL_POSITION",
                (rs, i) -> {
                    String d = rs.getString("d");
                    return new Partition(
                            rs.getString("n"),
                            d == null || "MAXVALUE".equals(d) ? null : d.replace("'", ""));
                },
                table);
    }

    /** 月分区定义：分区名 p{yyyyMM}、上界为下月 1 日 0 点。 */
    public record MonthDef(String name, String bound) {}

    /** 从 pmax 拆出给定的月分区（已存在上界不晚于最高上界的由调用方先过滤）；没有要加的直接返回。 */
    public void addMonths(String table, List<MonthDef> months) {
        check(TABLE, table);
        if (months.isEmpty()) {
            return;
        }
        List<String> defs = new ArrayList<>();
        for (MonthDef m : months) {
            check(PARTITION, m.name());
            check(BOUND, m.bound());
            defs.add("PARTITION " + m.name() + " VALUES LESS THAN ('" + m.bound() + "')");
        }
        jdbc.execute(
                "ALTER TABLE "
                        + table
                        + " REORGANIZE PARTITION pmax INTO ("
                        + String.join(", ", defs)
                        + ", PARTITION pmax VALUES LESS THAN (MAXVALUE))");
        log.info("Added {} partitions to {}", defs.size(), table);
    }

    public void dropPartition(String table, String partition) {
        check(TABLE, table);
        check(PARTITION, partition);
        jdbc.execute("ALTER TABLE " + table + " DROP PARTITION " + partition);
    }

    private static void check(Pattern pattern, String value) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("illegal identifier: " + value);
        }
    }
}
