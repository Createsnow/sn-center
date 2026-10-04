package com.adtek.sn.service.support;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 单事务资源准入阈值，与页面可调建议量及来源业务额度独立；变更须重新量测事务预算。 */
@Component
@ConfigurationProperties(prefix = "adtek.sn.work")
public class SnWorkSettings {
    private int maxBatchQuantity = 100000, maxImportBytes = 8388608, previewSeconds = 600;
    private int maxIncomingScan = 1000000;

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

    public int getMaxImportBytes() {
        return maxImportBytes;
    }

    public void setMaxImportBytes(int v) {
        if (v < 1024 || v > 67108864) throw new IllegalArgumentException("Invalid SN byte limit");
        maxImportBytes = v;
    }

    public int getPreviewSeconds() {
        return previewSeconds;
    }

    public void setPreviewSeconds(int v) {
        if (v < 10 || v > 3600) throw new IllegalArgumentException("Invalid preview lifetime");
        previewSeconds = v;
    }
}
