package com.adtek.sn.dto;

import java.util.List;

/** 转单、转组织共用。来源：PI → 单据 →（整单 / 一个或多个物料编码 + 附件项次，各自全部、号段或指定数量）；目标 PI、单据、生产组织必填，物料行按同物料编码、 同附件项次匹配。 */
public class TransferDTO {
    public String factoryCode;
    public String piNo;
    public String billNo;
    public String scope;
    public String materialCode;
    public String attachNo;
    public String startSn;
    public String endSn;
    public Integer qty;
    public List<TransferLineDTO> lines;
    public String toPi;
    public String toBill;
    public String toFactory;
    public String toMaterial;
    public Integer toSeq;
    public String reason;
}
