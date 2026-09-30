package com.adtek.sn.domain;

import java.time.Instant;
import com.baomidou.mybatisplus.annotation.*;

/** 同步尝试记录；失败或进程中断不能改变已发布快照，错误只保存受控码。 */
@TableName("sn_order_sync")
public class OrderSyncEntity {
    @TableId(type=IdType.INPUT)
    private String id;
    public String getId() { return id; }
    public void setId(String value) { this.id=value; }
    private Long actorId;
    public Long getActorId() { return actorId; }
    public void setActorId(Long value) { this.actorId=value; }
    private String mode;
    public String getMode() { return mode; }
    public void setMode(String value) { this.mode=value; }
    private String billNo;
    public String getBillNo() { return billNo; }
    public void setBillNo(String value) { this.billNo=value; }
    private String state;
    public String getState() { return state; }
    public void setState(String value) { this.state=value; }
    private Long baseVersion;
    public Long getBaseVersion() { return baseVersion; }
    public void setBaseVersion(Long value) { this.baseVersion=value; }
    private Long publishedVersion;
    public Long getPublishedVersion() { return publishedVersion; }
    public void setPublishedVersion(Long value) { this.publishedVersion=value; }
    private Long rowsCount;
    public Long getRowsCount() { return rowsCount; }
    public void setRowsCount(Long value) { this.rowsCount=value; }
    private Long billsCount;
    public Long getBillsCount() { return billsCount; }
    public void setBillsCount(Long value) { this.billsCount=value; }
    private Integer errorCode;
    public Integer getErrorCode() { return errorCode; }
    public void setErrorCode(Integer value) { this.errorCode=value; }
    private Boolean orgSyncFailed;
    public Boolean getOrgSyncFailed() { return orgSyncFailed; }
    public void setOrgSyncFailed(Boolean value) { this.orgSyncFailed=value; }
    private Instant startedAt;
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant value) { this.startedAt=value; }
    private Instant finishedAt;
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant value) { this.finishedAt=value; }
}

