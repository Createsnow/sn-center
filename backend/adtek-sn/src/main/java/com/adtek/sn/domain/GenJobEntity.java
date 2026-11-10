package com.adtek.sn.domain;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 生成任务（sn_gen_job）：一次生成一行（= 生成批次）；running_pi 唯一保证同一 PI 同一时刻只有一个生成。 */
@TableName("sn_gen_job")
public class GenJobEntity {
    public Long id;
    public String genBatch;
    public String piNo;
    public String scope;
    public long qty;
    public long startSeqDec;
    public long endSeqDec;
    public String startSn;
    public String endSn;
    public String previewToken;
    public String planJson;
    public String createdBy;
    public LocalDateTime createdAt;
}
