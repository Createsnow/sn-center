package com.adtek.sn.service.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * PRD_MO只读来源端口；integration实现。名称、显示行号都不能代替稳定内码。 所有文本原样校验长度不截断；qty为非负精确计划量，小数保留并阻止所属订单生成，非法数据拒绝整次拉取。
 */
public interface ErpProductionOrderPort {
    /** 返回受控配置的来源命名空间；未配置明确失败，不能回退成空订单。 */
    Source source();

    /**
     * 完整分页拉取计划/计划确认分录；billNo为空表示全量。 任一页、身份、数量或协议失败抛受控错误，不返回部分成功；明确不存在的单据可以返回合法空集。
     * 调用必须在发布事务外；有有限页数/行数/超时/总预算，无自动业务重试。
     */
    List<Row> orders(String billNo);

    /** 完整分页拉取启用组织，稳定ID/编码/名称都必需；失败不修改本地映射。 */
    List<Organization> organizations();

    /** sourceAccount为数据身份，不是租户；只允许配置中的一个K3账套。 */
    record Source(String system, String account) {}

    /**
     * @param id ERP组织内码，保持十进制原字符，不经浮点转换
     * @param code ERP组织编码，改名不改变身份
     * @param name 展示名；冲突不能推断为同一工厂
     */
    record Organization(String id, String code, String name) {}

    /**
     * @param billId 稳定单据内码
     * @param entryId 稳定分录内码；显示顺序改变不改变本字段
     * @param ordinal 本次快照分配顺序，正整数
     * @param qty 精确非负计划量，最多4位小数，单位沿用ERP生产数量，不隐式取整或换算
     * @param status 仅1计划/2计划确认
     */
    record Row(
            String billId,
            String entryId,
            String billNo,
            String customer,
            String po,
            String pi,
            String orgId,
            String orgCode,
            String orgName,
            long ordinal,
            String material,
            String materialName,
            BigDecimal qty,
            String status,
            String demandBillNo) {}
}
