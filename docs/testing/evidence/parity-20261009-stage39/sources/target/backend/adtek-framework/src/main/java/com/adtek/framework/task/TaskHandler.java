package com.adtek.framework.task;

import com.adtek.common.core.Actor;
import com.adtek.framework.task.domain.TaskEntity;

/** 业务注册的任务处理端口；framework不引用业务表。类型必须在启动时唯一登记。 */
public interface TaskHandler {
    /** 稳定任务类型编码；必须与持久task_type一致，启动时重复登记即拒绝。 */
    String type();

    /** 该类型的稳定动作编码，组成持久操作唯一键；具体资格仍由authorize检查。 */
    String action();

    /** 类型专属的完整业务事务预算；默认保留30秒，长批次须显式登记且仍原子提交。 */
    default int executionSeconds() {
        return 30;
    }

    /** 对当前可信主体和完整固定输入检查权限；覆盖重放、工作器、取消、恢复及每个下载读取块。 */
    void authorize(Actor actor, TaskEntity task);

    /** 业务内只读进度可使用独立查看资格；默认仍要求完整命令授权，不开放任务输入或操作重放。 */
    default void authorizeProgress(Actor actor, TaskEntity task) {
        authorize(actor, task);
    }

    /** 默认文件仍仅本人；业务固定历史文件可显式按原对象范围开放，不因此开放任务输入/操作查询。 */
    default boolean sharedFileAccess() {
        return false;
    }

    /** 与命令授权分开，可让有下载权限的查询员读历史文件；每个读取块重新调用。 */
    default void authorizeFile(Actor actor, TaskEntity task) {
        authorize(actor, task);
    }

    /** 已持任务行锁并复核代次；业务变更/成功审计必须加入该事务，不在此开启第二个业务事务。 */
    String execute(TaskEntity task, Actor actor) throws Exception;

    /** 原执行已回滚后的终态失败事实，与任务FAILED同事务；只记录受控错误码，禁止异常原文。 */
    void failed(TaskEntity task, int errorCode);

    /** 终态可携带 BusinessException 的公开参数；默认处理器仅保留错误码，业务模块须筛选再持久化。 */
    default void failed(TaskEntity task, int errorCode, java.util.Map<String, String> params) {
        failed(task, errorCode);
    }

    /** 从未领取任务的取消事实，与取消状态同事务，不代表业务回滚。 */
    void cancelled(TaskEntity task);
}
