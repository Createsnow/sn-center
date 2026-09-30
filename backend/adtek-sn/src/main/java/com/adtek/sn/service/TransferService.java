package com.adtek.sn.service;

import com.adtek.common.core.*;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.sn.dto.*;
import com.adtek.sn.query.TransferQuery;
import com.adtek.sn.vo.*;

/** 原页面与统一任务的转厂用例；所有授权在此服务复核，控制器不作为安全边界。 */
public interface TransferService {
    /** 读取源厂/PI/范围计数；不预留成员，正式命令在执行时固定完整可转集合。 */
    TransferCandidateVO candidates(
            String factory, String pi, String scope, String material, String sn);

    /** 最小跨厂交接目录，须单独targets权限；不授予对应工厂库存查询或写资格。 */
    PageResult<TransferTargetVO> targets(boolean factories, String query, int page, int size);

    /** APPLY冻结或DIRECT转移，持久操作号重放同一任务；只允许人员主体。 */
    TaskVO create(String mode, TransferCreateDTO command);

    /** APPROVE/REJECT/WITHDRAW针对固定版本和成员，撤回不要求是原申请人。 */
    TaskVO decide(String id, String action, TransferDecisionDTO command);

    /** 原主体以原动作/操作号恢复结果；不新建写意图。 */
    TaskVO operation(String kind, String operation);

    /** 源或目标厂当前view范围内的固定历史；选定厂决定转入/转出方向。 */
    PageResult<TransferVO> list(TransferQuery query);

    /** 固定转厂头；当前工厂改名不重写历史。 */
    TransferVO get(String id);

    /** 固定ordinal完整明细和独立当前投影；游标绑定当前主体/授权版本。 */
    CursorResult<TransferItemVO> items(String id, String cursor, int size);
}
