package com.adtek.framework.task.service.impl;

import static com.adtek.framework.constant.ErrorCodeConstants.*;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.task.*;
import com.adtek.framework.task.mapper.*;
import com.adtek.framework.task.service.TaskFileService;

import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class TaskFileServiceImpl implements TaskFileService {
    private final DurableTaskMapper tasks;
    private final DurableFileMapper files;
    private final TaskAccess access;
    private final FileStorage storage;
    private final org.springframework.transaction.support.TransactionTemplate transaction;

    public TaskFileServiceImpl(
            DurableTaskMapper tasks,
            DurableFileMapper files,
            TaskAccess access,
            FileStorage storage,
            org.springframework.transaction.PlatformTransactionManager manager) {
        this.tasks = tasks;
        this.files = files;
        this.access = access;
        this.storage = storage;
        this.transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    @Override
    public Download open(String id) throws IOException {
        var task = tasks.find(TaskAccess.uuid(id));
        var actor = access.file(task);
        var file = files.find(task.id());
        if (file == null || file.state() != FileState.READY) {
            throw new BusinessException(FILE_NOT_READY);
        }
        try {
            return new Download(
                    file.filename() == null ? "audit-" + file.id() + ".csv" : file.filename(),
                    file.byteSize(),
                    storage.open(file, () -> access.file(task, actor)),
                    file.mediaType() == null ? "text/csv;charset=UTF-8" : file.mediaType());
        } catch (BusinessException error) {
            if (error.getError().code() == FILE_RECOVERY_REQUIRED.code()) {
                transaction.executeWithoutResult(
                        status -> {
                            var locked = tasks.lock(task.id());
                            if (locked.executionEpoch() == task.executionEpoch()
                                    && locked.state() == TaskState.SUCCESS) {
                                files.state(task.id(), "RECOVERING");
                            }
                        });
            }
            throw error;
        }
    }
}
