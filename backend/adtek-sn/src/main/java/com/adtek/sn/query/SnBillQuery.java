package com.adtek.sn.query;

import java.util.List;

/** 一张单据（生产组织 + PI + 单据）已分配号的筛选：物料行或物料编码、SN 起止（换算为流水范围）、SN 模糊、附件项次。 */
public class SnBillQuery {
    public List<String> scope;
    public String pi;
    public String bill;
    public Integer billSeq;
    public String material;
    public String fromPi;
    public Long fromDec;
    public String toPi;
    public Long toDec;
    public String q;
    public boolean attachSet;
    public String attach;

    public SnBillQuery copy() {
        SnBillQuery c = new SnBillQuery();
        c.scope = scope;
        c.pi = pi;
        c.bill = bill;
        c.billSeq = billSeq;
        c.material = material;
        c.fromPi = fromPi;
        c.fromDec = fromDec;
        c.toPi = toPi;
        c.toDec = toDec;
        c.q = q;
        c.attachSet = attachSet;
        c.attach = attach;
        return c;
    }
}
