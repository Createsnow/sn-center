package com.adtek.sn.vo;

import java.time.LocalDateTime;

/** 痕迹摘要（最近一次连接测试、保存历史）。 */
public class AuditBriefVO {
    public LocalDateTime createdAt;
    public String operator;
    public String operatorName;
    public String result;
    public String errorMsg;
    public String detail;
}
