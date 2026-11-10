package com.adtek.sn.support;

import java.util.List;
import java.util.Map;

/**
 * MES 接口（原系统 /api/mes/v1）的错误：应答 {"schemaVersion", "code", "message", "batchNo", "timestamp",
 * "details"}，字段固定齐全，出错不返回 200。程序只按 code 判断，message 给人看。
 */
public class MesException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public final int status;
    public final String code;
    public final String batchNo;
    public final transient List<Map<String, Object>> details;
    public final Integer retryAfter;

    public MesException(
            int status,
            String code,
            String message,
            String batchNo,
            List<Map<String, Object>> details,
            Integer retryAfter) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.batchNo = batchNo;
        this.details = details == null ? List.of() : details;
        this.retryAfter = retryAfter;
    }

    public MesException(int status, String code, String message) {
        this(status, code, message, null, null, null);
    }

    public MesException(int status, String code, String message, String batchNo) {
        this(status, code, message, batchNo, null, null);
    }

    public static MesException bad(String field, String reason) {
        return new MesException(
                400,
                "INVALID_REQUEST",
                "参数不合法：" + field,
                null,
                List.of(Map.of("field", field, "reason", reason)),
                null);
    }
}
