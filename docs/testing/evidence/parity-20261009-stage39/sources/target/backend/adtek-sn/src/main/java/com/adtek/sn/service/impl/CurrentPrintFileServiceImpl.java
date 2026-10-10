package com.adtek.sn.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.task.TaskAccess;
import com.adtek.framework.task.TaskSettings;
import com.adtek.framework.task.service.TaskFileService.Download;
import com.adtek.sn.domain.*;
import com.adtek.sn.service.CurrentPrintFileService;
import com.adtek.sn.service.support.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.io.*;
import java.util.*;

/** 一个下载对应一个只读数据库快照；分页读成员，生成完成后才向客户端发送字节。 */
@Service
public class CurrentPrintFileServiceImpl implements CurrentPrintFileService {
    private final AcquireEngine e;
    private final TaskSettings settings;

    public CurrentPrintFileServiceImpl(AcquireEngine e, TaskSettings settings) {
        this.e = e;
        this.settings = settings;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Download open(String id, String format) throws IOException {
        if (!Set.of("csv", "xlsx").contains(format)) throw SnPolicy.invalid();
        var actor = e.policy.read(AcquirePolicy.DOWNLOAD);
        String printId = TaskAccess.uuid(id);
        var print = e.rows.printByTask(printId);
        if (print == null || !print.id().equals(printId)) throw SnPolicy.missing();
        e.policy.require(actor, AcquirePolicy.DOWNLOAD, print.factoryId());
        var output = new LimitedBytes(settings.fileBytes());
        PrintFiles.Rows rows =
                consumer -> {
                    long ordinal = 0;
                    String after = null;
                    Map<Long, RuleVersionEntity> rules = new HashMap<>();
                    Map<Long, FactoryEntity> factories = new HashMap<>();
                    Map<String, AcquireBatchEntity> batches = new HashMap<>();
                    while (true) {
                        var chunk = e.rows.currentPrintItems(print.id(), after, 500);
                        if (chunk.isEmpty()) break;
                        for (var row : chunk) {
                            var factory =
                                    factories.computeIfAbsent(
                                            row.factoryId(), e.work.factories::selectById);
                            if (factory == null)
                                throw new IllegalStateException("Missing current factory");
                            var batch =
                                    row.currentBatchId() == null
                                            ? null
                                            : batches.computeIfAbsent(
                                                    row.currentBatchId(), e.rows::batchById);
                            String batchNo =
                                    batch == null
                                            ? null
                                            : batch.batchNo() == null
                                                    ? batch.id()
                                                    : batch.batchNo();
                            consumer.accept(
                                    PrintFiles.prototypeCells(
                                            ++ordinal,
                                            e.snapshot(row, factory, rules),
                                            batchNo,
                                            row.status()));
                        }
                        after = chunk.getLast().id();
                    }
                };
        try {
            if (format.equals("xlsx"))
                PrintFiles.prototypeXlsx(output, print, settings.fileBytes(), rows);
            else {
                output.write(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
                PrintFiles.prototypeCsvRow(output, PrintFiles.PROTOTYPE_HEADER);
                rows.each(
                        cells -> {
                            try {
                                PrintFiles.prototypeCsvRow(output, cells);
                            } catch (IOException error) {
                                throw new UncheckedIOException(error);
                            }
                        });
            }
        } catch (IOException | RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Cannot prepare current print file", error);
        }
        byte[] bytes = output.toByteArray();
        // 读事务结束后再按当前授权复核每个响应块，撤权不沿用生成时的 Actor。
        InputStream input =
                new FilterInputStream(new ByteArrayInputStream(bytes)) {
                    private void authorize() {
                        e.policy.require(
                                e.policy.read(AcquirePolicy.DOWNLOAD),
                                AcquirePolicy.DOWNLOAD,
                                print.factoryId());
                    }

                    @Override
                    public int read() throws IOException {
                        authorize();
                        return super.read();
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        authorize();
                        return in.read(buffer, offset, length);
                    }
                };
        String filename =
                print.printNo() == null ? "print-" + print.id() : "print_" + print.printNo();
        return new Download(
                filename + "." + format,
                bytes.length,
                input,
                format.equals("xlsx") ? PrintFiles.XLSX : "text/csv;charset=UTF-8");
    }

    /** 字节上限沿用部署配置；超限拒绝完整下载，不交付被截断的文件。 */
    private static final class LimitedBytes extends ByteArrayOutputStream {
        private final long limit;

        LimitedBytes(long limit) {
            this.limit = limit;
        }

        private void room(int length) {
            if ((long) count + length > limit)
                throw new BusinessException(
                        com.adtek.framework.constant.ErrorCodeConstants.RESOURCE_LIMIT);
        }

        @Override
        public synchronized void write(int value) {
            room(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            room(length);
            super.write(bytes, offset, length);
        }
    }
}
