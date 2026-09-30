package com.adtek.framework.task.service;
import com.adtek.framework.task.domain.TaskEntity;
/** 仅后台工作器/隔离恢复测试使用，不作为HTTP写入口。 */
public interface TaskExecutionService {
    /** 内部工作器以自身唯一owner领取；短事务SKIP LOCKED并增加代次/尝试数。
     * 过期预算转失败，空队列返回null；领取不授予业务权限，执行时另行核验持久主体。
     */
    TaskEntity claim(String owner);
    /** 先锁任务复核owner/代次/租约，再重载持久主体并检查完整业务快照权限。
     * 业务+审计+最终结果原子提交；失效代次409、当前授权不符拒绝，异常整笔回滚。
     */
    void execute(TaskEntity lease) throws Exception;
    /** 执行事务结束后独立读取真实状态，已成功不覆盖；仅相同代次可记录终态失败或有界重试。 */
    void failed(TaskEntity lease,Exception failure);
}
