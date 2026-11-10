package com.adtek.sn.vo;

import java.util.List;

/** 生成详情里的物料行：订单物料行 + 不能生成的原因 + 现有号的起止 SN。 */
public class GenLineVO extends OrderLineVO {
    public List<String> issues;
    public String startSn;
    public String endSn;
}
