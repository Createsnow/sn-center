package com.adtek.sn.domain;

import java.time.Instant;
import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.*;

/** 来源分录身份由billId+sourceEntryId决定；ordinal仅排序，qty保留ERP精确小数，active不改变历史额度。 */
@TableName("sn_order_entry")
public class OrderEntryEntity {
    @TableId(type=IdType.AUTO)
    private Long id;
    public Long getId() { return id; }
    public void setId(Long value) { this.id=value; }
    private Long billId;
    public Long getBillId() { return billId; }
    public void setBillId(Long value) { this.billId=value; }
    private String sourceEntryId;
    public String getSourceEntryId() { return sourceEntryId; }
    public void setSourceEntryId(String value) { this.sourceEntryId=value; }
    private Long ordinal;
    public Long getOrdinal() { return ordinal; }
    public void setOrdinal(Long value) { this.ordinal=value; }
    private String material;
    public String getMaterial() { return material; }
    public void setMaterial(String value) { this.material=value; }
    private String materialName;
    public String getMaterialName() { return materialName; }
    public void setMaterialName(String value) { this.materialName=value; }
    private BigDecimal qty;
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal value) { this.qty=value; }
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status=value; }
    private String demandBillNo;
    public String getDemandBillNo() { return demandBillNo; }
    public void setDemandBillNo(String value) { this.demandBillNo=value; }
    @TableField(updateStrategy=FieldStrategy.ALWAYS)
    private Long factoryId;
    public Long getFactoryId() { return factoryId; }
    public void setFactoryId(Long value) { this.factoryId=value; }
    private Boolean active;
    public Boolean getActive() { return active; }
    public void setActive(Boolean value) { this.active=value; }
    private Long snapshotVersion;
    public Long getSnapshotVersion() { return snapshotVersion; }
    public void setSnapshotVersion(Long value) { this.snapshotVersion=value; }
}
