package com.adtek.sn.vo;

import java.util.List;

/** 示例号：前 5 个和最大号、最大流水、格式。 */
public class RulePreviewVO {
    public long maxSeq;
    public String format;
    public String sampleLength;
    public String sampleUnit;
    public List<Sample> samples;

    /** 一个示例号。 */
    public static class Sample {
        public long seqDec;
        public String seqText;
        public String sn;
    }
}
