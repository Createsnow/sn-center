package com.adtek.sn.service.support;

import com.adtek.sn.vo.SnQueryItemVO;

import java.util.*;

/** 固定查询文件保留旧11列及不可变身份/当前投影扩展；编码字段用文本，不推断数值或公式。 */
public final class SnQueryFiles {
    private SnQueryFiles() {}

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
