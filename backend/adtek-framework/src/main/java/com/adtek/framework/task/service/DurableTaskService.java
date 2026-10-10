package com.adtek.framework.task.service;

import com.adtek.framework.task.domain.TaskEntity;
import com.adtek.framework.task.vo.TaskProgressVO;
import com.adtek.framework.task.vo.TaskVO;

import java.util.function.Consumer;

/** 持久操作的唯一实现入口；业务模块负责规范化及固定快照，禁止在回调中执行外部写副作用。 */
public interface DurableTaskService {
    /**
     * 当前可信主体类型+稳定主体ID+登记动作+operationId唯一；事务内登记操作/任务并调用snapshot一次。
     * 同号重放先复核当前完整范围，再比较规范化摘要；异载荷409，同载荷返回原任务/终态。 新提交超容量拒绝；快照/审计/登记任一异常整笔回滚，返回持久任务而非业务已完成。
     */
    TaskVO submit(
            String type,
            String operationId,
            String canonicalInput,
            String scopeDigest,
            Consumer<TaskEntity> snapshot);

    /** 仅在submit快照回调的当前事务中使用：数据库业务事实已固定，文件仍QUEUED。 将操作终态与业务同事务提交；文件任务失败/恢复不得再改原业务结果，已确认任务不可取消。 */
    void confirmBusiness(String taskId, String resultRef);

    /** 只读当前主体原操作，未发现404；重查当前业务权限，不能跨主体猜号读取。 */
    TaskVO operation(String type, String operationId);

    /** 只读本人的持久任务；当前完整快照范围不满足则拒绝，不返回历史结果。 */
    TaskVO get(String id);

    /** 内部业务看板调用：重新验证当前主体与任务处理器完整业务授权，可读跨提交人的进度，不返回任务或文件内容。 */
    TaskProgressVO businessProgress(String id);

    /** 当前本人且完整快照授权通过后，事务锁定任务，仅取消从未领取的QUEUED； 与领取互斥，其余409，不声称撤销已提交业务。 */
    TaskVO cancel(String id);

    /** 当前本人及完整快照授权下，只为已成功操作恢复文件；保持原结果和固定快照。 新执行周期有界且检查容量，进行中的恢复返回原任务；原文件正常也只校验复用同一产物。 */
    TaskVO recoverFile(String id);
}
