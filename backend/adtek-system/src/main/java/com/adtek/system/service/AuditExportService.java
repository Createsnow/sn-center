package com.adtek.system.service;
import com.adtek.system.dto.AuditExportCommandDTO;
import com.adtek.framework.task.vo.TaskVO;
/** 在线操作日志快照CSV；不提供SN/归档导出或XLSX。 */
public interface AuditExportService {
    /** 当前账号须有audit:list和audit:export，导出范围为两者交集；显式时间窗和规范化筛选固定输入。
     * 持久机制按主体+动作+operationId复用；同号异载荷409。单查询一致快照，超行/字节上限整体拒绝，
     * 快照/操作/QUEUED任务/提交审计原子提交；返回任务不代表文件READY。超时查询原操作，禁止自动换号。
     */
    TaskVO submit(AuditExportCommandDTO command);
    /** 按当前主体和原operationId恢复，重新核验完整快照范围；未发现404，不读取他人结果。 */
    TaskVO operation(String operationId);
}
