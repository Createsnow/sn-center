package com.adtek.sn.domain;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 单据汇总：每个（PI，单据，号上的生产组织）一行（sn_bill_stat）。 */
@TableName("sn_bill_stat")
public class BillStatEntity {
    public String piNo;
    public String billNo;
    public String orgKey;
    public String factoryCode;
    public long qty;
    public long pending;
    public long toPush;
    public long suspended;
    public LocalDateTime handledAt;
    public long allocQty;
    public long allocSuspended;
    public String allocCustomer;
    public LocalDateTime refreshedAt;

    /** 对账比较用的值（不含 PI、单据与重算时间）。 */
    public String compareKey() {
        return String.join(
                "|",
                String.valueOf(orgKey),
                String.valueOf(factoryCode),
                Long.toString(qty),
                Long.toString(pending),
                Long.toString(toPush),
                Long.toString(suspended),
                String.valueOf(handledAt),
                Long.toString(allocQty),
                Long.toString(allocSuspended),
                String.valueOf(allocCustomer));
    }
}
