package com.adtek.framework.task.service.impl;

import static com.adtek.framework.constant.ErrorCodeConstants.*;

import com.adtek.common.annotation.Idempotent;
import com.adtek.common.core.*;
import com.adtek.framework.security.CurrentActor;
import com.adtek.framework.task.*;
import com.adtek.framework.task.convert.TaskConvert;
import com.adtek.framework.task.domain.*;
import com.adtek.framework.task.mapper.*;
import com.adtek.framework.task.service.DurableTaskService;
import com.adtek.framework.task.vo.TaskVO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

@Service
public class DurableTaskServiceImpl implements DurableTaskService {
    private final DurableTaskMapper tasks;
    private final DurableFileMapper files;
    private final CurrentActor current;
    private final TaskAccess access;
    private final TaskHandlers handlers;
    private final TaskSettings settings;
    private final TaskConvert convert;

    public DurableTaskServiceImpl(
            DurableTaskMapper tasks,
            DurableFileMapper files,
            CurrentActor current,
            TaskAccess access,
            TaskHandlers handlers,
            TaskSettings settings,
            TaskConvert convert) {
        this.tasks = tasks;
        this.files = files;
        this.current = current;
        this.access = access;
        this.handlers = handlers;
        this.settings = settings;
        this.convert = convert;
    }

    @Override
    @Idempotent
    @Transactional(
            rollbackFor = Exception.class,
            isolation = Isolation.READ_COMMITTED,
            timeout = 30)
    public TaskVO submit(
            String type,
            String operationId,
            String input,
            String scope,
            Consumer<TaskEntity> snapshot) {
        String operation = TaskAccess.uuid(operationId);
        var actor = access.fresh(current.requireAuthenticated().userId());
        if (input == null
                || input.length() > 8192
                || scope == null
                || !scope.matches("[0-9a-f]{64}")) {
            throw new BusinessException(
                    com.adtek.common.constant.ErrorCodeConstants.INVALID_ARGUMENT);
        }
        var handler = handlers.require(type);
        String digest = digest("v1\n" + input);
        // 容量与同号竞争用同一短事务控制行序列化；唯一键仍是数据库最终护栏。
        if (tasks.lockSubmission() == null) {
            throw new IllegalStateException("Missing task submission control");
        }
        var existing =
                tasks.operationTyped(
                        actor.userId(), handler.action(), operation, actor.principalType());
        if (existing != null) {
            var task = tasks.find(existing.taskId());
            access.require(task, actor.userId());
            if (!digest.equals(existing.inputDigest())) {
                throw new BusinessException(OPERATION_CONFLICT);
            }
            return view(task);
        }
        if (tasks.activeCount() >= settings.queueLimit()
                || tasks.userActiveCount(actor.userId()) >= settings.perUserLimit()) {
            throw new BusinessException(TASK_CAPACITY);
        }
        String id = UUID.randomUUID().toString();
        tasks.insertOperationTyped(
                id,
                actor.userId(),
                handler.action(),
                operation,
                digest,
                scope,
                actor.principalType());
        tasks.insertTask(
                id,
                actor.userId(),
                type,
                digest,
                input,
                actor.securityVersion(),
                settings.maxAttempts(),
                settings.budgetSeconds());
        var task = tasks.find(id);
        snapshot.accept(task);
        access.require(task, actor.userId());
        return view(task);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskVO operation(String type, String operationId) {
        long actor = current.requireAuthenticated().userId();
        var operation =
                tasks.operationTyped(
                        actor,
                        handlers.require(type).action(),
                        TaskAccess.uuid(operationId),
                        current.requireAuthenticated().principalType());
        if (operation == null) {
            throw new BusinessException(com.adtek.common.constant.ErrorCodeConstants.NOT_FOUND);
        }
        return get(operation.taskId());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskVO get(String id) {
        var task = tasks.find(TaskAccess.uuid(id));
        access.require(task);
        return view(task);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public TaskVO cancel(String id) {
        var task = tasks.lock(TaskAccess.uuid(id));
        access.require(task);
        if (tasks.operationForTask(task.id()).state() == OperationState.SUCCEEDED
                || tasks.cancel(task.id()) != 1) {
            throw new BusinessException(TASK_NOT_CANCELLABLE);
        }
        tasks.operationEnded(task.id(), "CANCELLED", null);
        files.state(task.id(), "FAILED");
        handlers.require(task.taskType()).cancelled(task);
        return view(tasks.find(task.id()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public TaskVO recoverFile(String id) {
        if (tasks.lockSubmission() == null) {
            throw new IllegalStateException("Missing submission control");
        }
        var task = tasks.lock(TaskAccess.uuid(id));
        access.require(task);
        var operation = tasks.operationForTask(task.id());
        if (operation.state() != OperationState.SUCCEEDED || files.find(task.id()) == null) {
            throw new BusinessException(FILE_NOT_READY);
        }
        // 恢复只派生已完成事实；HTTP重试不会再创建任务或改写操作原始结果。
        if (task.state() == TaskState.SUCCESS || task.state() == TaskState.FAILED) {
            if (tasks.activeCount() >= settings.queueLimit()
                    || tasks.userActiveCount(task.userId()) >= settings.perUserLimit()) {
                throw new BusinessException(TASK_CAPACITY);
            }
            if (tasks.recover(task.id(), settings.budgetSeconds()) != 1) {
                throw new BusinessException(TASK_LEASE_LOST);
            }
            files.state(task.id(), "RECOVERING");
        }
        return view(tasks.find(task.id()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmBusiness(String taskId, String resultRef) {
        var task = tasks.find(TaskAccess.uuid(taskId));
        access.require(task);
        if (task.state() != TaskState.QUEUED
                || task.attempt() != 0
                || files.find(task.id()) == null
                || resultRef == null
                || resultRef.length() > 128
                || tasks.operationSucceeded(task.id(), resultRef) != 1)
            throw new BusinessException(OPERATION_CONFLICT);
    }

    private TaskVO view(TaskEntity task) {
        return convert.view(
                task,
                tasks.operationForTask(task.id()),
                files.find(task.id()),
                tasks.progress(task.id()));
    }

    public static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
