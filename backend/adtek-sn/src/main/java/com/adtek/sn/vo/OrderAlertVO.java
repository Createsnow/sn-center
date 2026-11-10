package com.adtek.sn.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 订单变更提醒。 */
public class OrderAlertVO {
    public long id;
    public String billNo;
    public String alertType;
    public Integer seq;
    public String beforeValue;
    public String afterValue;
    public String status;
    public LocalDateTime createdAt;
    public String closedBy;
    public LocalDateTime closedAt;
    public String closeAction;
    public String note;
    public String pi;
    public String orgCode;
    public String orgName;
    public String suspend;
    public String leaveState;
    public long suspendedQty;
    public long deliveredQty;
    public long inOrgQty;
    public List<String> actions;
}
