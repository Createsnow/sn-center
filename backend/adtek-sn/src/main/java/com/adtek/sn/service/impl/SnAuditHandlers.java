package com.adtek.sn.service.impl;

import com.adtek.common.core.Actor;
import com.adtek.framework.audit.ManagementAuditPort;
import com.adtek.framework.task.*;
import com.adtek.framework.task.domain.TaskEntity;
import com.adtek.sn.service.support.*;

import org.springframework.context.annotation.*;

import java.io.*;
import java.util.*;

/** 从固定审计行流式写文件；恢复保留同ID/摘要，下载不能借原厂范围变化扩大原范围。 */
@Configuration
public class SnAuditHandlers {
    private final SnAuditAccess service;
    private final FileStorage storage;
    private final TaskSettings settings;

    public SnAuditHandlers(SnAuditAccess service, FileStorage storage, TaskSettings settings) {
        this.service = service;
        this.storage = storage;
        this.settings = settings;
    }

    @Bean
    TaskHandler snAuditCsv() {
        return handler("CSV");
    }

    @Bean
    TaskHandler snAuditXlsx() {
        return handler("XLSX");
    }

    private TaskHandler handler(String format) {
        return new TaskHandler() {
            public String type() {
                return SnAuditServiceImpl.type(format);
            }

            public String action() {
                return SnAuditAccess.EXPORT + ":" + format.toLowerCase(Locale.ROOT);
            }

            public void authorize(Actor actor, TaskEntity task) {
                service.authorize(actor, task.id());
            }

            public String execute(TaskEntity task, Actor actor) throws Exception {
                var fresh = service.authorization.lockCurrent(actor);
                service.authorize(fresh, task.id());
                var export = service.rows.exportById(task.id());
                if (export.formatVersion() < 1 || export.formatVersion() > 7)
                    throw new IllegalStateException("Unsupported audit export format");
                boolean prototype = export.formatVersion() >= 2;
                var file = service.files.find(task.id());
                if (file == null) throw new IllegalStateException("Missing export file");
                storage.cleanInterrupted(task.id());
                PrintFiles.Rows rows =
                        consumer -> {
                            long ordinal = 0;
                            while (true) {
                                var batch = service.rows.exportRows(task.id(), ordinal, 500);
                                if (batch.isEmpty()) break;
                                for (var row : batch) {
                                    if (row.ordinal() != ++ordinal)
                                        throw new IllegalStateException("Broken export ordinal");
                                    var cells =
                                            Arrays.asList(
                                                    service.json.readValue(
                                                            row.cells(), String[].class));
                                    if (prototype) {
                                        var roles =
                                                new HashSet<>(
                                                        Arrays.asList(
                                                                service.json.readValue(
                                                                        cells.get(3),
                                                                        String[].class)));
                                        cells =
                                                export.formatVersion() == 7
                                                        ? SnAuditFiles.prototypeCellsV7(
                                                                cells, roles)
                                                        : export.formatVersion() == 6
                                                                ? SnAuditFiles.prototypeCellsV6(
                                                                        cells, roles)
                                                                : export.formatVersion() == 5
                                                                        ? SnAuditFiles
                                                                                .prototypeCellsV5(
                                                                                        cells,
                                                                                        roles)
                                                                        : SnAuditFiles
                                                                                .prototypeCells(
                                                                                        cells,
                                                                                        roles);
                                    }
                                    consumer.accept(cells);
                                }
                                service.progress.record(
                                        task.id(), task.executionEpoch(), ordinal, "WRITING");
                            }
                            if (ordinal != export.quantity())
                                throw new IllegalStateException("Incomplete fixed export");
                        };
                var published =
                        storage.publish(
                                task.id(),
                                file.storageKey(),
                                file.sha256(),
                                output -> {
                                    if (format.equals("XLSX")) {
                                        if (export.formatVersion() >= 3)
                                            PrintFiles.auditXlsx(
                                                    output,
                                                    export.createdAt(),
                                                    settings.fileBytes(),
                                                    export.formatVersion(),
                                                    rows);
                                        else if (prototype)
                                            PrintFiles.typedXlsx(
                                                    output,
                                                    export.createdAt(),
                                                    SnAuditFiles.PROTOTYPE_HEADER,
                                                    "audit",
                                                    Set.of(14),
                                                    settings.fileBytes(),
                                                    rows);
                                        else
                                            PrintFiles.xlsx(
                                                    output,
                                                    export.createdAt(),
                                                    SnAuditFiles.HEADER,
                                                    settings.fileBytes(),
                                                    rows);
                                    } else {
                                        output.write(
                                                new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
                                        if (prototype)
                                            SnAuditFiles.prototypeCsvRow(
                                                    output, SnAuditFiles.PROTOTYPE_HEADER);
                                        else PrintFiles.csvRow(output, SnAuditFiles.HEADER);
                                        rows.each(
                                                values -> {
                                                    try {
                                                        if (prototype)
                                                            SnAuditFiles.prototypeCsvRow(
                                                                    output, values);
                                                        else
                                                            PrintFiles.csvRow(
                                                                    output,
                                                                    SnAuditFiles.csv(values));
                                                    } catch (IOException error) {
                                                        throw new UncheckedIOException(error);
                                                    }
                                                });
                                    }
                                });
                if (file.sha256() != null
                        && (!file.sha256().equals(published.digest())
                                || file.byteSize() != published.bytes()))
                    throw new IllegalStateException("Immutable SN export changed");
                service.files.ready(task.id(), published.digest(), published.bytes());
                service.progress.record(
                        task.id(), task.executionEpoch(), export.quantity(), "PUBLISHED");
                if (file.sha256() == null)
                    service.audit.success(
                            new ManagementAuditPort.Event(
                                    actor.userId(),
                                    "SN操作痕迹",
                                    SnAuditAccess.EXPORT,
                                    "OTHER",
                                    task.id(),
                                    Map.of(
                                            "quantity",
                                            Long.toString(export.quantity()),
                                            "format",
                                            format),
                                    org.slf4j.MDC.get("traceId")));
                return file.id();
            }

            public void failed(TaskEntity task, int code) {
                failure(task, "FAILED", Integer.toString(code));
            }

            public void cancelled(TaskEntity task) {
                failure(task, "CANCELLED", "");
            }

            private void failure(TaskEntity task, String state, String code) {
                try {
                    service.audit.failure(
                            new ManagementAuditPort.Event(
                                    task.userId(),
                                    "SN操作痕迹",
                                    SnAuditAccess.EXPORT + ":failed",
                                    "OTHER",
                                    task.id(),
                                    Map.of("state", state, "errorCode", code),
                                    org.slf4j.MDC.get("traceId")));
                } catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(SnAuditHandlers.class)
                            .warn("SN export failure audit unavailable; durable result retained");
                }
            }
        };
    }
}
