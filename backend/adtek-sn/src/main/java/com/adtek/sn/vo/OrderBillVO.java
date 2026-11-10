package com.adtek.sn.vo;

import java.util.List;

/** 订单页：左表一张单据，或右表单据头与物料行。 */
public class OrderBillVO {
    public String billNo;
    public String customerCode;
    public String po;
    public String pi;
    public String orgCode;
    public String orgName;
    public String deptName;
    public String docStatus;
    public String modifyTime;
    public long totalQty;
    public long lineCount;
    public long generatedQty;
    public long quota;
    public String suspend;
    public String leaveState;
    public boolean piFrozen;
    public long openAlerts;
    public boolean issued;
    public BillStateVO state;
    public List<OrderLineVO> lines;
}
