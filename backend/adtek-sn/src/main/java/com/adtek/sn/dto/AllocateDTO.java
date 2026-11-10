package com.adtek.sn.dto;

import java.util.List;

/** 分配 / 分配面板：PI 必填；范围为物料行（lines）、单据（bills），都不填为整张 PI。 */
public class AllocateDTO {
    public String piNo;
    public List<String> bills;
    public List<LineRefDTO> lines;
}
