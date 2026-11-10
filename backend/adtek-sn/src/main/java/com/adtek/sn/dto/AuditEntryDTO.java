package com.adtek.sn.dto;

/** 一条操作痕迹的业务字段（操作人 / 时间 / 来源由审计服务填写）；链式设置，与原系统 audit.Entry 一致。 */
public class AuditEntryDTO {
    public String action;
    public String result = "OK";
    public String factoryCode;
    public String piNo;
    public String customerCode;
    public String materialCode;
    public String billNo;
    public String startSn;
    public String endSn;
    public Long qty;
    public String beforeStatus;
    public String afterStatus;
    public String batchNo;
    public String requestNo;
    public String transferNo;
    public String reason;
    public String errorMsg;
    public String detail;

    public static AuditEntryDTO of(String action) {
        AuditEntryDTO e = new AuditEntryDTO();
        e.action = action;
        return e;
    }

    private static String cut(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }

    public AuditEntryDTO factory(String v) {
        factoryCode = v;
        return this;
    }

    public AuditEntryDTO pi(String v) {
        piNo = v;
        return this;
    }

    public AuditEntryDTO customer(String v) {
        customerCode = v;
        return this;
    }

    public AuditEntryDTO material(String v) {
        materialCode = v;
        return this;
    }

    public AuditEntryDTO bill(String v) {
        billNo = v;
        return this;
    }

    public AuditEntryDTO range(String start, String end) {
        startSn = start;
        endSn = end;
        return this;
    }

    public AuditEntryDTO count(long v) {
        qty = v;
        return this;
    }

    public AuditEntryDTO status(String before, String after) {
        beforeStatus = before;
        afterStatus = after;
        return this;
    }

    public AuditEntryDTO batch(String v) {
        batchNo = v;
        return this;
    }

    public AuditEntryDTO request(String v) {
        requestNo = v;
        return this;
    }

    public AuditEntryDTO transfer(String v) {
        transferNo = v;
        return this;
    }

    public AuditEntryDTO why(String v) {
        reason = cut(v, 500);
        return this;
    }

    public AuditEntryDTO info(String v) {
        detail = cut(v, 2000);
        return this;
    }

    public AuditEntryDTO fail(String message) {
        result = "FAIL";
        errorMsg = cut(message, 1000);
        return this;
    }
}
