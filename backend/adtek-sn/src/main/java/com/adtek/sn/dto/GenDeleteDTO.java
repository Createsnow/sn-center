package com.adtek.sn.dto;

import java.util.List;

/** 删号范围（单据 / 物料行，或 SN 起止号）与原因（选填）。 */
public class GenDeleteDTO {
    public String piNo;
    public List<String> bills;
    public List<LineRefDTO> lines;
    public String startSn;
    public String endSn;
    public String reason;
}
