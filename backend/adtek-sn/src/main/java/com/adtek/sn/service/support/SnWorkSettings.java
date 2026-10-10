package com.adtek.sn.service.support;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 单事务资源准入阈值，与页面可调建议量及来源业务额度独立；变更须重新量测事务预算。 */
@Component
@ConfigurationProperties(prefix = "adtek.sn.work")
public class SnWorkSettings {
    private int maxBatchQuantity = 100000, maxImportQuantity = 500000, previewSeconds = 1800;
    private int maxAuditExportQuantity = 200000;

    public int getMaxAuditExportQuantity() {
        return maxAuditExportQuantity;
    }

    public void setMaxAuditExportQuantity(int value) {
        if (value < 1 || value > 1000000)
            throw new IllegalArgumentException("Invalid audit export limit");
        maxAuditExportQuantity = value;
    }

    private int importExecutionSeconds = 600;
    private int maxIncomingScan = 1000000;
    private int inlineGenerationQuantity = 5000;

    public int getInlineGenerationQuantity() {
        return inlineGenerationQuantity;
    }

    public void setInlineGenerationQuantity(int value) {
        if (value < 0) throw new IllegalArgumentException("Invalid inline generation threshold");
        inlineGenerationQuantity = value;
    }

    public int getMaxIncomingScan() {
        return maxIncomingScan;
    }

    public void setMaxIncomingScan(int value) {
        if (value < 1000 || value > 10000000)
            throw new IllegalArgumentException("Invalid SN scan limit");
        maxIncomingScan = value;
    }

    public int getMaxBatchQuantity() {
        return maxBatchQuantity;
    }

    public void setMaxBatchQuantity(int v) {
        if (v < 1 || v > 1000000) throw new IllegalArgumentException("Invalid SN resource limit");
        maxBatchQuantity = v;
    }

    public int getMaxImportQuantity() {
        return maxImportQuantity;
    }

    public void setMaxImportQuantity(int v) {
        if (v < 1 || v > 1000000) throw new IllegalArgumentException("Invalid import quantity");
        maxImportQuantity = v;
    }

    public int getImportExecutionSeconds() {
        return importExecutionSeconds;
    }

    public void setImportExecutionSeconds(int v) {
        if (v < 30 || v > 3540)
            throw new IllegalArgumentException("Invalid import execution budget");
        importExecutionSeconds = v;
    }

    public int getPreviewSeconds() {
        return previewSeconds;
    }

    public void setPreviewSeconds(int v) {
        if (v < 10 || v > 3600) throw new IllegalArgumentException("Invalid preview lifetime");
        previewSeconds = v;
    }
}
