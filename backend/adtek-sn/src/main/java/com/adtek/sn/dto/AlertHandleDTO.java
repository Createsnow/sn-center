package com.adtek.sn.dto;

/** 处理订单变更提醒：CONFIRM / MAP（targetSeq）/ UNRELATED / VOID（note 必填）/ ACK。 */
public class AlertHandleDTO {
    public String action;
    public String note;
    public Integer targetSeq;
}
