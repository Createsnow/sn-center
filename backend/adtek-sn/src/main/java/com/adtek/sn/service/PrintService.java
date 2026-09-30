package com.adtek.sn.service;

import com.adtek.common.core.PageResult;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.sn.dto.AcquisitionDTO;
import com.adtek.sn.query.AcquireQuery;
import com.adtek.sn.vo.PrintVO;

/** 页面整厂+PI打印事实与文件生成明确分离，不声称感知物理打印机完成。 */
public interface PrintService {
    /**
     * 当前人员print与download权限；整PI合法待打印成员、PRINTED、固定快照、成功操作/审计及CSV/XLSX任务同事务。
     * 返回operationState=SUCCEEDED和PREPARING文件；文件失败只恢复原任务，不重新确认SN。零可打印409无空打印单。
     * 同号同载荷返回原结果，异载荷409；同PI写互斥，任一成员当前批次失配整批拒绝。
     */
    TaskVO confirm(AcquisitionDTO command);

    /** 当前本人同动作操作查询，重新检查工厂权限；用于刷新/超时恢复。 */
    TaskVO operation(String operationId);

    /** 原所属厂当前view范围，分页完整打印历史，分别返回两种格式的文件状态。 */
    PageResult<PrintVO> history(AcquireQuery query);

    /** 原所属厂当前view范围读取固定打印头及文件状态；不根据今日SN归属更换授权对象。 */
    PrintVO get(String id);
}
