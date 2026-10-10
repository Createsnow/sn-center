package com.adtek.sn.service.support;

import com.adtek.sn.vo.SnQueryItemVO;

import java.util.*;

/** 新查询文件使用原型十一列；旧二十二列仅用于恢复已有固定产物。 */
public final class SnQueryFiles {
    private SnQueryFiles() {}

    public static List<String> prototypeCells(long ordinal, SnQueryItemVO v) {
        return Arrays.asList(
                Long.toString(ordinal),
                v.sn(),
                v.seqText(),
                v.seqDec(),
                v.pi(),
                v.material(),
                v.customer(),
                v.factoryCode(),
                v.status(),
                v.currentBatchNo(),
                v.billNo());
    }

    public static final List<String> HEADER =
            List.of(
                    "ordinal",
                    "sn",
                    "seq_text",
                    "seq_dec",
                    "pi",
                    "material",
                    "customer",
                    "factory_code",
                    "status",
                    "batch_id",
                    "source_bill_no",
                    "seq_pi",
                    "source",
                    "print_id",
                    "transfer_id",
                    "created_at",
                    "acquired_at",
                    "printed_at",
                    "source_system",
                    "source_account",
                    "source_bill_id",
                    "source_entry_id");

    public static List<String> cells(long ordinal, SnQueryItemVO v) {
        return Arrays.asList(
                Long.toString(ordinal),
                v.sn(),
                v.seqText(),
                v.seqDec(),
                v.pi(),
                v.material(),
                v.customer(),
                v.factoryCode(),
                v.status(),
                v.currentBatchId(),
                v.billNo(),
                v.seqPi(),
                v.source(),
                v.currentPrintId(),
                v.currentTransferId(),
                v.createdAt().toString(),
                v.acquiredAt() == null ? null : v.acquiredAt().toString(),
                v.printedAt() == null ? null : v.printedAt().toString(),
                v.sourceSystem(),
                v.sourceAccount(),
                v.sourceBillId(),
                v.sourceEntryId());
    }
}
