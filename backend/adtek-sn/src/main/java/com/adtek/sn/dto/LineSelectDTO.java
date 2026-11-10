package com.adtek.sn.dto;

import java.util.List;

/** 物料行选定规则：规则库 ruleId（必要时 newPrefix）或自定义 custom，二选一。 */
public class LineSelectDTO {
    public String piNo;
    public List<LineRefDTO> lines;
    public Long ruleId;
    public CustomRuleDTO custom;
    public String newPrefix;
}
