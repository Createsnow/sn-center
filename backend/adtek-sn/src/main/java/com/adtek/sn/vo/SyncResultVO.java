package com.adtek.sn.vo;

import java.time.LocalDateTime;

/** 订单同步结果（比对 / 按单据 / 全量重建；比对被跳过时 skipped = true）。 */
public class SyncResultVO {
    public String mode;
    public Boolean skipped;
    public Boolean started;
    public String billNo;
    public String outcome;
    public String docStatus;
    public Long bills;
    public Long replaced;
    public Long removed;
    public Long rows;
    public Long attachK3Filled;
    public LocalDateTime syncedAt;
}
