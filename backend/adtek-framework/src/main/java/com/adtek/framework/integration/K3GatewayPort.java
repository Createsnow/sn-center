package com.adtek.framework.integration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 金蝶 K3 Cloud WebAPI（只读）：LoginByAppSecret 登录，批量用 ExecuteBillQuery 分页，单张用 View。
 *
 * <p>权限：不做授权检查（调用方用例已检查）。事务：不参与数据库事务，调用方不得在持有数据库锁的事务内调用。连接参数由调用方传入（每次调用即读即用）。 失败：登录失败抛 {@link
 * K3Exception}（{@link K3Exception.Kind#LOGIN}），查询失败（含分页重试用尽）抛 {@link
 * K3Exception.Kind#QUERY}；任何一页失败即整体失败，调用方不改快照。文本一律去首尾空格，基础资料为 null 视为空。
 */
public interface K3GatewayPort {
    /** 比对同步：已审核单据的编号与修改时间（本地去重）。 */
    List<Head> fetchApprovedHeads(Connection connection);

    /** 按单据编号批量拉已审核单据的全部物料行；每批后回调已拉完的单据数（可为 null）。 */
    List<Row> fetchBills(Connection connection, List<String> billNos, IntConsumer onBills);

    /** 全量重建：按已审核条件完整拉取；每页后回调已拉到的行数（可为 null）。 */
    List<Row> fetchAll(Connection connection, IntConsumer onRows);

    /** 按单据编号查询（不带单据状态条件）；查无此单返回 found = false。 */
    BillView viewBill(Connection connection, String billNo);

    /** 组织机构（含已禁用的）。 */
    List<Org> fetchOrganizations(Connection connection);

    /** 连接参数（应用密钥为明文，只在内存中使用）。 */
    record Connection(
            String serverUrl,
            String acctId,
            String appId,
            String appSecret,
            String username,
            int lcid,
            int loginTimeout,
            int queryTimeout,
            int viewTimeout,
            int pageRetries,
            double pageRetrySeconds) {}

    /** 快照的一行（一条物料行）。 */
    record Row(
            String billNo,
            String docStatus,
            String customerCode,
            String pi,
            String po,
            String orgCode,
            String orgName,
            int seq,
            String attachNo,
            String materialCode,
            String materialName,
            String lengthRaw,
            BigDecimal qty,
            LocalDateTime modifyTime,
            String unit,
            String customerPn,
            String customerDesc,
            String specDesc,
            String materialSpec,
            String deptCode,
            String deptName) {}

    /** 已审核单据的编号与修改时间。 */
    record Head(String billNo, LocalDateTime modifyTime) {}

    /** 单张查询结果：found = false 为查无此单（已删除）。 */
    record BillView(boolean found, String docStatus, List<Row> rows) {}

    /** 组织机构。 */
    record Org(String number, String name, boolean enabled) {}
}
