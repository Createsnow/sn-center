package com.adtek.sn.dto;

import java.time.YearMonth;
import java.util.UUID;

/** 运维输入：month为UTC月，apply缺省false；runId绑定完整输入，不接收任意表名或SQL。 */
public record MaintenanceCommandDTO(Action action, String runId, boolean apply, YearMonth month,
                                    int monthsAhead, int rowLimit, String proofDirectory) {
    public enum Action { PARTITIONS, CLEAN_PREVIEWS, ARCHIVE_COPY, ARCHIVE_VERIFY, ARCHIVE_FINALIZE }
    public MaintenanceCommandDTO(Action action,String runId,boolean apply,YearMonth month,int monthsAhead,int rowLimit) {
        this(action,runId,apply,month,monthsAhead,rowLimit,null);
    }
    public MaintenanceCommandDTO {
        if (action == null || runId == null || !UUID.fromString(runId).toString().equals(runId)
                || month == null || month.getYear() < 1970 || month.getYear() > 9997
                || monthsAhead < 1 || monthsAhead > 12 || rowLimit < 1 || rowLimit > 1000000) {
            throw new IllegalArgumentException("MAINTENANCE_INPUT");
        }
        if(action != Action.ARCHIVE_FINALIZE && proofDirectory != null)throw new IllegalArgumentException("MAINTENANCE_INPUT");
    }
}
