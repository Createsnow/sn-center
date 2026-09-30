package com.adtek.sn.service;

import com.adtek.common.core.PageResult;
import com.adtek.sn.dto.OrderSyncDTO;
import com.adtek.sn.query.SnPageQuery;
import com.adtek.sn.vo.*;

/** 生产订单页读用例和完整同步入口；所有可见信息按同一order:list工厂范围。 */
public interface OrderService {
    /** 客户非空的当前有效单据稳定分页；明细搜索只筛选单据，不减少单据汇总量。 */
    PageResult<OrderBillVO> list(SnPageQuery query);

    /** 本人当前可见有效单据的分录完整分页；未知/跨厂/空客户单据404，不泄漏成员。 */
    PageResult<OrderLineVO> lines(String billId, SnPageQuery query);

    /** 同范围统计和未映射提示；过多未映射提示明确资源拒绝，不静默截断。 */
    OrderMetaVO meta();

    /** 同范围完整分页客户候选，空范围为空，不返回跨厂客户。 */
    PageResult<String> customers(SnPageQuery query);

    /** 当前order:sync全部工厂权限；外部完整拉取后原子发布，失败记录且保留旧版本。 同步不生成SN、不消费/退回历史额度；并发发布版本变更409，调用方可重新发起新的完整读取。 */
    OrderSyncVO synchronize(OrderSyncDTO command);

    /** 当前order:list全部工厂资格才能查看全局同步历史；按开始时刻/ID倒序分页。 */
    PageResult<OrderSyncVO> syncs(SnPageQuery query);
}
