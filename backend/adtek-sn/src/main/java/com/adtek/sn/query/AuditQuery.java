package com.adtek.sn.query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 痕迹查询条件；from / to 为账户显示时区的自然日，换算后的 UTC 区间与授权范围由服务填写。 */
public class AuditQuery {
    public LocalDate dateFrom;
    public LocalDate dateTo;
    public String action;
    public String operator;
    public String factoryCode;
    public String pi;
    public String source;
    public String result;
    public String batchNo;
    public String keyword;
    public String billNo;

    public LocalDateTime start;
    public LocalDateTime end;
    public List<String> scope;
    public boolean bound;
    public String like;
}
