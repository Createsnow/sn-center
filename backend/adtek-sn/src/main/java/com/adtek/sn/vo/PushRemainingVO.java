package com.adtek.sn.vo;

import java.util.Map;

/** 剩余推送次数：未入批且未暂停的枚数、未确认或失败的批次数、折算的推送次数；查询时附推送任务状态。 */
public class PushRemainingVO {
    public String factoryCode;
    public String key;
    public long unbatched;
    public long batches;
    public long pushes;
    public Map<String, Object> task;
}
