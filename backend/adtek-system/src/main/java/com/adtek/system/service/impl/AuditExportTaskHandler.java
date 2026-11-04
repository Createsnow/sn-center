package com.adtek.system.service.impl;

import static com.adtek.framework.constant.ErrorCodeConstants.*;

import com.adtek.common.core.*;
import com.adtek.framework.mybatis.ScopeContext;
import com.adtek.framework.task.*;
import com.adtek.framework.task.domain.TaskEntity;
import com.adtek.framework.task.mapper.DurableFileMapper;
import com.adtek.framework.task.service.TaskProgressService;
import com.adtek.system.mapper.*;
import com.adtek.system.service.support.AuditCsv;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 只从固定成员生成；业务成功审计与READY/任务/操作结果一起提交，文件发布本身在事务外部资源上发生。 */
@Component
public class AuditExportTaskHandler implements TaskHandler {
    public static final String TYPE = "SYSTEM_AUDIT_CSV";
    public static final String ACTION = "system:audit:export";
    private final AuditExportMapper rows;
    private final DurableFileMapper files;
    private final FileStorage storage;
    private final TaskProgressService progress;
    private final OperLogMapper audit;

    public AuditExportTaskHandler(
            AuditExportMapper rows,
            DurableFileMapper files,
            FileStorage storage,
            TaskProgressService progress,
            OperLogMapper audit) {
        this.rows = rows;
        this.files = files;
        this.storage = storage;
        this.progress = progress;
        this.audit = audit;
    }

    public String type() {
        return TYPE;
    }

    public String action() {
        return ACTION;
    }

    @Override
    public void failed(TaskEntity task, int code) {
        fact(task, "system:audit:export:failed", false, "{\"errorCode\":\"" + code + "\"}");
    }

    @Override
    public void cancelled(TaskEntity task) {
        fact(task, "system:audit:export:cancel", true, "{}");
    }

    private void fact(TaskEntity task, String action, boolean success, String changes) {
        var snapshot = audit.actorSnapshot(task.userId());
        audit.management(
                task.userId(),
                "操作日志导出",
                "OTHER",
                action,
                task.id(),
                success,
                changes,
                org.slf4j.MDC.get("traceId"),
                snapshot == null ? null : snapshot.departmentId(),
                snapshot == null ? null : snapshot.username());
    }

    @Override
    public void authorize(Actor actor, TaskEntity task) {
        if (actor.mustChangePassword()
                || !actor.permissions()
                        .containsAll(java.util.Set.of("system:audit:list", ACTION))) {
            throw new BusinessException(FORBIDDEN);
        }
        long total = rows.count(task.id());
        // 两项权限都不能扩大另一项的范围；使用同一显式SQL策略分别核验完整成员。
        for (String permission : java.util.List.of("system:audit:list", ACTION)) {
            try (var ignored = ScopeContext.open("system:audit-export", permission, actor)) {
                if (rows.visibleCount(task.id()) != total) {
                    throw new BusinessException(FORBIDDEN);
                }
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    @Override
    public String execute(TaskEntity task, Actor actor) throws Exception {
        var file = files.find(task.id());
        if (file == null) {
            throw new IllegalStateException("Missing fixed file metadata");
        }
        storage.cleanInterrupted(task.id());
        progress.record(task.id(), task.executionEpoch(), 0, "WRITING");
        var published =
                storage.publish(
                        task.id(),
                        file.storageKey(),
                        file.sha256(),
                        output -> {
                            output.write(AuditCsv.HEADER.getBytes(StandardCharsets.UTF_8));
                            int ordinal = 0;
                            while (true) {
                                var batch = rows.page(task.id(), ordinal, 200);
                                if (batch.isEmpty()) {
                                    break;
                                }
                                for (var row : batch) {
                                    if (row.ordinal() != ++ordinal) {
                                        throw new IllegalStateException("Broken export ordinal");
                                    }
                                    output.write(row.csvLine().getBytes(StandardCharsets.UTF_8));
                                }
                                progress.record(
                                        task.id(), task.executionEpoch(), ordinal, "WRITING");
                            }
                            if (ordinal != file.rowCount()) {
                                throw new IllegalStateException("Incomplete export snapshot");
                            }
                        });
        if (file.sha256() != null
                && (!file.sha256().equals(published.digest())
                        || file.byteSize() != published.bytes())) {
            throw new IllegalStateException("Immutable export changed");
        }
        progress.record(task.id(), task.executionEpoch(), file.rowCount(), "PUBLISHED");
        if (files.ready(task.id(), published.digest(), published.bytes()) != 1) {
            throw new IllegalStateException("Missing file record");
        }
        if (file.sha256() == null) {
            var snapshot = audit.actorSnapshot(actor.userId());
            audit.management(
                    actor.userId(),
                    "操作日志导出",
                    "EXPORT",
                    ACTION,
                    task.id(),
                    true,
                    "{}",
                    org.slf4j.MDC.get("traceId"),
                    snapshot == null ? null : snapshot.departmentId(),
                    snapshot == null ? null : snapshot.username());
        }
        return file.id();
    }
}
