package com.adtek.sn.service;

import com.adtek.common.core.*;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.sn.dto.*;
import com.adtek.sn.query.TransferQuery;
import com.adtek.sn.vo.*;

/** 原页面与统一任务的转厂用例；所有授权在此服务复核，控制器不作为安全边界。 */
public interface TransferService {
    /** 页面固定成员按流水/创建顺序分页，来源单厂已转出标识仅影响展示。 */
    PageResult<TransferDetailVO> itemPage(String id, int page, int size);

    /** 只读当前可转集合及目标起号提示；先校验来源权限，全厂view才返回目标水位，候选不保留执行权。 */
    TransferImpactVO hint(TransferHintDTO command);

    /** 读取待审批固定成员在当前规则/目标状态下的影响；非待审批无测算，全厂view才返回目标水位。 */
    TransferImpactVO pendingHint(String transferId);

    /** 读取来源计数、整源 PI 物料及可转号在目标 PI 的重复提示；不预留成员。 */
    TransferCandidateVO candidates(
            String factory, String pi, String scope, String material, String sn, String toPi);

    /** 最小跨厂交接目录，须单独targets权限；不授予对应工厂库存查询或写资格。 */
    PageResult<TransferSnapshotVO> targets(String query, int page, int size);

    /** 可手工选择的启用工厂；独立于可能包含未映射组织的订单快照。 */
    PageResult<TransferTargetVO> factories(String query, int page, int size);

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
