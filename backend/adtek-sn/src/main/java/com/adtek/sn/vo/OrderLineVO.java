package com.adtek.sn.vo;

import java.time.LocalDateTime;

/** 订单物料行（含生效的附件项次、米数、产品单位与计数、可生成额度、生效规则摘要）。 */
public class OrderLineVO {
    public String billNo;
    public int seq;
    public String customerCode;
    public String pi;
    public String po;
    public String orgCode;
    public String orgName;
    public String attachNo;
    public String attachSource;
    public String attachK3;
    public String attachManual;
    public boolean attachK3Conflict;
    public String materialCode;
    public String materialName;
    public String lengthRaw;
    public String length;
    public String lengthSource;
    public String lengthManual;
    public String unitRaw;
    public String unit;
    public String unitSource;
    public String unitManual;
    public long qty;
    public String customerPn;
    public String customerDesc;
    public String specDesc;
    public String materialSpec;
    public String deptCode;
    public String deptName;
    public long generatedQty;
    public long ownGeneratedQty;
    public long allocatedQty;
    public String editLock;
    public long quota;
    public Object rule;
    public String modifyTime;
    public LocalDateTime syncedAt;
}
