package com.adtek.framework.task.service.impl;
import com.adtek.common.core.*;
import com.adtek.framework.task.*;
import com.adtek.framework.task.domain.TaskEntity;
import com.adtek.framework.task.mapper.*;
import com.adtek.framework.task.service.TaskExecutionService;
import java.io.IOException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.adtek.framework.constant.ErrorCodeConstants.*;
@Service
public class TaskExecutionServiceImpl implements TaskExecutionService {
    private final DurableTaskMapper tasks;private final DurableFileMapper files;private final TaskAccess access;private final TaskHandlers handlers;private final TaskSettings settings;
    public TaskExecutionServiceImpl(DurableTaskMapper tasks,DurableFileMapper files,TaskAccess access,TaskHandlers handlers,TaskSettings settings) {this.tasks=tasks;this.files=files;this.access=access;this.handlers=handlers;this.settings=settings;}
    @Override @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public TaskEntity claim(String owner) {
        TaskAccess.uuid(owner);var task=tasks.candidate();if(task==null) {return null;}
        if(task.attempt()>=task.maxAttempts() || !tasks.now().isBefore(task.deadline())) {end(task,TASK_BUDGET.code());return null;}
        if(tasks.claim(task.id(),task.executionEpoch(),owner,settings.leaseSeconds())!=1) {throw new BusinessException(TASK_LEASE_LOST);}return tasks.find(task.id());
    }
    @Override @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED,timeout=30)
    public void execute(TaskEntity lease) throws Exception {
        // 这是执行事务的第一把锁。接管者必须等该事务结束，不能仅凭过期时间越锁提交。
        var task=tasks.lock(lease.id());requireLease(task,lease);
        if(!tasks.now().isBefore(task.leaseUntil())) {throw new BusinessException(TASK_LEASE_LOST);}
        if(!tasks.now().isBefore(task.deadline())) {throw new BusinessException(TASK_BUDGET);}
        var actor=access.require(task,task.userId());String result=handlers.require(task.taskType()).execute(task,actor);
        if(!tasks.now().isBefore(task.deadline())) {throw new BusinessException(TASK_BUDGET);}
        if(tasks.succeed(task.id(),task.executionEpoch(),task.owner(),result)!=1) {throw new BusinessException(TASK_LEASE_LOST);}
        // 文件恢复时操作已成功，保留原引用；此条件更新不把恢复伪装成第二次业务操作。
        tasks.operationSucceeded(task.id(),result);
    }
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void failed(TaskEntity lease,Exception failure) {
        var task=tasks.lock(lease.id());
        if(task==null || task.state()!=TaskState.RUNNING || task.executionEpoch()!=lease.executionEpoch() || !java.util.Objects.equals(task.owner(),lease.owner())) {return;}
        // 提交结果未知时先读持久终态；SUCCESS不会被迟到的异常覆盖。
        boolean retry=failure instanceof IOException || failure instanceof TransientDataAccessException
            || (failure instanceof BusinessException business && java.util.Set.of(DEPENDENCY_UNAVAILABLE.code(),TASK_LEASE_LOST.code()).contains(business.getError().code()));
        int code=failure instanceof BusinessException business?business.getError().code():DEPENDENCY_UNAVAILABLE.code();
        if(retry && task.attempt()<task.maxAttempts() && tasks.now().isBefore(task.deadline())) {
            tasks.fail(task.id(),task.executionEpoch(),"RETRY_WAIT",code,settings.retrySeconds());
        } else {end(task,code);}
    }
    private void end(TaskEntity task,int code) {tasks.fail(task.id(),task.executionEpoch(),"FAILED",code,0);tasks.operationEnded(task.id(),"FAILED",code);files.state(task.id(),"FAILED");handlers.require(task.taskType()).failed(task,code);}
    private void requireLease(TaskEntity task,TaskEntity lease) {
        if(task==null || task.state()!=TaskState.RUNNING || task.executionEpoch()!=lease.executionEpoch() || !java.util.Objects.equals(task.owner(),lease.owner())) {throw new BusinessException(TASK_LEASE_LOST);}
    }
}
