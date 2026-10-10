package com.adtek.sn.service;

import com.adtek.common.core.*;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.sn.dto.*;
import com.adtek.sn.query.AcquireItemsQuery;
import com.adtek.sn.query.AcquireQuery;
import com.adtek.sn.vo.*;

/** 页面与MES共用SN用例；可信Actor来自认证上下文，HTTP不能传入角色或来源标记。 */
public interface AcquireService {
    /** 当前view工厂范围的工厂+PI汇总，分页完整读取，合法空为零项。 */
    PageResult<AcquireSummaryVO> summaries(AcquireQuery query);

    /** 按状态/物料/流水归属拼连续段，未知流水逐枚；显式同PI跨厂只读需本厂归属资格。 */
    PageResult<AcquireSegmentVO> segments(AcquireQuery query);

    /**
     * 整厂+PI异步领取全部合格待领取号，逐来源校验；分类/固定成员/状态/审计/成功同事务。
     * 主体+动作+operationId唯一，异载荷409，零可领持久成功但无空批次；重放当前授权，不另领新成员。
     */
    TaskVO take(AcquisitionDTO command);

    /** 固定批次清单或同归属连续范围回调；PRINTED唯一目标，去重并保存每项结果。 事务复核当前工厂/PI/有效批次/状态，旧批次与转走/申请中明确跳过；超资源拒绝、失败整批回滚。 */
    TaskVO confirm(PrintConfirmationDTO command);

    /** 只查询本主体原操作；当前对应动作工厂权限撤销后拒绝，不泄露跨主体操作。 */
    TaskVO operation(String kind, String operationId);

    /** 本人任务结果及完整分类计数，仍检查当前对应动作权限。 */
    AcquireWorkVO result(String taskId);

    /** 固定逐项结果，签名ordinal游标绑定主体/授权版本/集合；空nextCursor表示结束。 */
    CursorResult<AcquireResultVO> results(String taskId, String cursor, int size);

    /** 历史批次按原所属厂当前view范围分页，不根据SN今日归属扩大访问。 */
    PageResult<AcquireBatchVO> batches(AcquireQuery query);

    /** 固定领取展示快照与另列当前状态；全部ordinal可读，转厂/重领不改旧成员。 */
    CursorResult<AcquireItemVO> items(String batchId, String cursor, int size);

    /** 页面详情按原批次工厂授权，返回仍指向该批次的当前成员；转出后不再显示。 */
    PageResult<SnReadItemVO> currentItems(String batchId, AcquireItemsQuery query);

    /** 当前范围实时只读，不改变状态；游标绑定筛选/主体/版本，跨页不承诺固定导出快照。 */
    CursorResult<SnReadItemVO> readItems(
            String factoryId,
            String pi,
            String customer,
            String materialMode,
            String material,
            String cursor,
            int size);
}
