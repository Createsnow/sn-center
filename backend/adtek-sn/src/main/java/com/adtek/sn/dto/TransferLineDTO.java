package com.adtek.sn.dto;

/** 按物料编码转移时选中的一个物料编码 + 附件项次及其转移方式（ALL / RANGE / QTY）。 */
public class TransferLineDTO {
    public String materialCode;
    public String attachNo;
    public String mode;
    public String startSn;
    public String endSn;
    public Integer qty;
    public Integer toSeq;
}
