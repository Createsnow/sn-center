package com.adtek.sn.vo;

import java.time.LocalDateTime;

/** 订单同步进度：running = false 为没有同步在运行；别的实例在同步时 mode 为空串。 */
public class SyncProgressVO {
    public boolean running;
    public String mode;
    public String trigger;
    public String operator;
    public String stage;
    public long done;
    public long total;
    public String billNo;
    public LocalDateTime startedAt;
    public Long elapsedSec;
}
