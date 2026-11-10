package com.adtek.sn.vo;

import java.time.LocalDateTime;

/** 订单页顶部统计。 */
public class OrderMetaVO {
    public LocalDateTime syncedAt;
    public long bills;
    public long rows;
    public long emptyCustomerBills;
    public long attachPending;
    public Compare compare;
    public long openAlerts;

    /** 比对同步状态。 */
    public static class Compare {
        public LocalDateTime lastRunAt;
        public Boolean lastOk;
        public String lastMessage;
        public Long lastBills;
        public LocalDateTime lastFullAt;
    }
}
