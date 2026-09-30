package com.adtek.sn.query;

import jakarta.validation.constraints.*;

import java.time.LocalDate;

/** 日期按上海自然日；storage仅ALL/ONLINE/ARCHIVE，不能输入SQL或表名。 */
public record SnAuditQuery(
        LocalDate dateFrom,
        LocalDate dateTo,
        @Size(max = 80) String action,
        @Size(max = 64) String operator,
        @Size(max = 19) String factoryId,
        @Size(max = 128) String pi,
        @Size(max = 16) String source,
        @Size(max = 16) String result,
        @Size(max = 36) String batchId,
        @Size(max = 200) String keyword,
        @Pattern(regexp = "ALL|ONLINE|ARCHIVE") String storage) {
    public SnAuditQuery {
        if (storage == null) storage = "ALL";
    }
}
