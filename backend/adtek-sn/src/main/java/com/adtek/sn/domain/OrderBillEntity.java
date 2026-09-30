package com.adtek.sn.domain;

import java.time.Instant;
import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.*;

/** 来源系统/账套/单据内码共同固定身份；active=false仍保留历史引用，factoryId空表示未映射。 */
@TableName("sn_order_bill")
public class OrderBillEntity {
    @TableId(type=IdType.AUTO)
    private Long id;
    public Long getId() { return id; }
    public void setId(Long value) { this.id=value; }
    private String sourceSystem;
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String value) { this.sourceSystem=value; }
    private String sourceAccount;
    public String getSourceAccount() { return sourceAccount; }
    public void setSourceAccount(String value) { this.sourceAccount=value; }
    private String sourceBillId;
    public String getSourceBillId() { return sourceBillId; }
    public void setSourceBillId(String value) { this.sourceBillId=value; }
    private String billNo;
    public String getBillNo() { return billNo; }
    public void setBillNo(String value) { this.billNo=value; }
    private String customer;
    public String getCustomer() { return customer; }
    public void setCustomer(String value) { this.customer=value; }
    private String po;
    public String getPo() { return po; }
    public void setPo(String value) { this.po=value; }
    private String pi;
    public String getPi() { return pi; }
    public void setPi(String value) { this.pi=value; }
    private String orgId;
    public String getOrgId() { return orgId; }
    public void setOrgId(String value) { this.orgId=value; }
    private String orgCode;
    public String getOrgCode() { return orgCode; }
    public void setOrgCode(String value) { this.orgCode=value; }
    private String orgName;
    public String getOrgName() { return orgName; }
    public void setOrgName(String value) { this.orgName=value; }
    @TableField(updateStrategy=FieldStrategy.ALWAYS)
    private Long factoryId;
    public Long getFactoryId() { return factoryId; }
    public void setFactoryId(Long value) { this.factoryId=value; }
    @TableField(updateStrategy=FieldStrategy.ALWAYS)
    private String factoryCode;
    public String getFactoryCode() { return factoryCode; }
    public void setFactoryCode(String value) { this.factoryCode=value; }
    private Boolean active;
    public Boolean getActive() { return active; }
    public void setActive(Boolean value) { this.active=value; }
    private BigDecimal totalQty;
    public BigDecimal getTotalQty() { return totalQty; }
    public void setTotalQty(BigDecimal value) { this.totalQty=value; }
    private Boolean quantityReady;
    public Boolean getQuantityReady() { return quantityReady; }
    public void setQuantityReady(Boolean value) { this.quantityReady=value; }
    private Long lineCount;
    public Long getLineCount() { return lineCount; }
    public void setLineCount(Long value) { this.lineCount=value; }
    private String statuses;
    public String getStatuses() { return statuses; }
    public void setStatuses(String value) { this.statuses=value; }
    private Long snapshotVersion;
    public Long getSnapshotVersion() { return snapshotVersion; }
    public void setSnapshotVersion(Long value) { this.snapshotVersion=value; }
    private Instant syncedAt;
    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant value) { this.syncedAt=value; }
}
