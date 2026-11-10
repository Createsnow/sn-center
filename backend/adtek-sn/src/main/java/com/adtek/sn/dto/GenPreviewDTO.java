package com.adtek.sn.dto;

import java.util.List;

/** 生成预演：范围 BILLS 整单 / LINES 按物料行（同一张 PI 内）；流水来源 MAX / RANGE（seqStart～seqEnd）。 */
public class GenPreviewDTO {
    public String piNo;
    public String scope;
    public List<String> bills;
    public List<LineRefDTO> lines;
    public String seqMode;
    public Long seqStart;
    public Long seqEnd;
}
