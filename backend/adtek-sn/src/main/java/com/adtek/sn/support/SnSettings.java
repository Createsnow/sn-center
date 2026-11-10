package com.adtek.sn.support;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** 运行参数：沿用原系统的环境变量名（见 deploy/app.env.example），每次读取都看当前环境；默认值与原系统相同。 */
@Component
public class SnSettings {
    public static final String DEFAULT_ADMIN_EMP_NO = "admin";
    public static final String DEFAULT_ADMIN_PASSWORD = "Admin@123";
    public static final String DEFAULT_SECRET = "change-me-sn-center-secret";

    private final Environment env;

    public SnSettings(Environment env) {
        this.env = env;
    }

    public String get(String key, String fallback) {
        String v = env.getProperty(key);
        return v == null ? fallback : v.strip();
    }

    public int getInt(String key, int fallback) {
        String v = env.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getBool(String key, boolean fallback) {
        String v = env.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        return switch (v.strip().toLowerCase()) {
            case "1", "true", "yes", "on", "y" -> true;
            case "0", "false", "no", "off", "n" -> false;
            default -> fallback;
        };
    }

    public boolean production() {
        return "production".equals(get("ENVIRONMENT", "local"));
    }

    public String secretKey() {
        String v = get("SN_SECRET", "");
        return v.isEmpty() ? DEFAULT_SECRET : v;
    }

    public String initAdminEmpNo() {
        String v = get("SN_INIT_ADMIN_EMP_NO", "");
        return v.isEmpty() ? DEFAULT_ADMIN_EMP_NO : v;
    }

    public String initAdminPassword() {
        return get("SN_INIT_ADMIN_PASSWORD", DEFAULT_ADMIN_PASSWORD);
    }

    public String adminPasswordProblem() {
        String v = initAdminPassword();
        if (v.isEmpty()) {
            return "SN_INIT_ADMIN_PASSWORD 未设置";
        }
        if (DEFAULT_ADMIN_PASSWORD.equals(v)) {
            return "SN_INIT_ADMIN_PASSWORD 仍是默认值";
        }
        return null;
    }

    public boolean demoSeed() {
        return getBool("SN_DEMO_SEED", false);
    }

    public int auditRetentionDays() {
        return Math.max(0, getInt("SN_AUDIT_RETENTION_DAYS", 1095));
    }

    public int auditArchiveRetentionDays() {
        return Math.max(0, getInt("SN_AUDIT_ARCHIVE_RETENTION_DAYS", 1825));
    }

    public int auditExportLimit() {
        return getInt("AUDIT_EXPORT_LIMIT", 200_000);
    }

    public int snExportLimit() {
        return getInt("SN_EXPORT_LIMIT", 1_000_000);
    }

    public int genSyncThreshold() {
        return getInt("SN_GEN_SYNC_THRESHOLD", 5000);
    }

    public int genConcurrency() {
        return Math.max(1, getInt("SN_GEN_CONCURRENCY", 4));
    }

    public int genDefaultLimit() {
        return Math.max(1, getInt("SN_GEN_DEFAULT_LIMIT", 10_000));
    }

    public int genMaxPerRun() {
        return Math.max(1, getInt("SN_GEN_MAX_PER_RUN", 500_000));
    }

    public int previewTtlMinutes() {
        return getInt("SN_PREVIEW_TTL_MINUTES", 30);
    }

    public int syncIntervalMinutes() {
        return Math.max(1, getInt("SN_SYNC_INTERVAL_MINUTES", 10));
    }

    public List<Integer> pushRetryIntervals() {
        return minutes("SN_PUSH_RETRY_INTERVALS_MINUTES", "1,5,15");
    }

    public List<Integer> pushReceiptWaits() {
        List<Integer> v = minutes("SN_PUSH_RECEIPT_WAIT_MINUTES", "1,5,10");
        return v.isEmpty() ? List.of(1, 5, 10) : v;
    }

    private List<Integer> minutes(String key, String def) {
        List<Integer> out = new ArrayList<>();
        for (String part : get(key, def).split(",")) {
            try {
                int v = Integer.parseInt(part.strip());
                if (v >= 0) {
                    out.add(v);
                }
            } catch (NumberFormatException ignored) {
                // 跳过不合法的项
            }
        }
        return out;
    }

    public int batchUnconfirmedAlertMinutes() {
        return Math.max(1, getInt("SN_BATCH_UNCONFIRMED_ALERT_MINUTES", 30));
    }

    public int mesAuthMaxFailures() {
        return Math.max(1, getInt("SN_MES_AUTH_MAX_FAILURES", 10));
    }

    public int mesAuthLockMinutes() {
        return Math.max(1, getInt("SN_MES_AUTH_LOCK_MINUTES", 15));
    }

    public int mesPullRatePerMinute() {
        return Math.max(1, getInt("SN_MES_PULL_RATE_PER_MINUTE", 60));
    }

    public int pushPollSeconds() {
        return Math.max(1, getInt("SN_PUSH_POLL_SECONDS", 5));
    }

    public boolean pushEnabled() {
        return getBool("SN_PUSH_ENABLED", true);
    }

    public int partitionAheadMonths() {
        return Math.max(1, getInt("SN_PARTITION_AHEAD_MONTHS", 3));
    }

    public String partitionCron() {
        return get("SN_PARTITION_CRON", "0 10 2 * * *");
    }

    public boolean maintainEnabled() {
        return getBool("SN_MAINTAIN_ENABLED", true);
    }

    public boolean syncEnabled() {
        return getBool("SN_SYNC_ENABLED", true);
    }

    public ZoneId zone() {
        try {
            return ZoneId.of(get("TZ", "Asia/Shanghai"));
        } catch (RuntimeException e) {
            return ZoneId.of("Asia/Shanghai");
        }
    }
}
