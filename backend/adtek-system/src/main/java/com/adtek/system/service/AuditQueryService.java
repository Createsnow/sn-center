package com.adtek.system.service;

import com.adtek.common.core.PageResult;
import com.adtek.system.query.AuditQuery;
import com.adtek.system.vo.*;

/** 在线操作日志查询，不包含未来SN业务归档；不能由审计可见性反向获得业务对象写权限。 */
public interface AuditQueryService {
    /** 仅供导出提交：单次一致性SELECT固定list/export范围交集，超行上限整体拒绝；不分页截断。 */
    java.util.List<AuditLogVO> exportSnapshot(AuditQuery query);

    /** 要求当前system:audit:list；只读事务按事件操作者部门快照/本人ID过滤并分页；无范围为空，参数非法400。 */
    PageResult<AuditLogVO> list(AuditQuery query);

    /** 与列表同一当前范围，越范围与不存在都404；对原始JSON再次白名单脱敏，不返回秘密或未经解释的历史字段。 */
    AuditDetailVO detail(String id);

    /** 与列表同一时间窗和范围读取候选动作/模块；每类上限200，超限明确拒绝，不截断误导筛选。 */
    AuditOptionsVO options(AuditQuery query);
}
