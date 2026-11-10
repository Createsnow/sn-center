package com.adtek.sn.service.impl;

import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.mapper.DeliveryMapper;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.R;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 每日交付自检（原系统 delivery/self_check.py，5.2.5 第 5 条）：发现的问题记为交付告警（CHECK），列入首页并留痕。 */
@Service
public class DeliveryCheckServiceImpl {
    private static final Logger log = LoggerFactory.getLogger(DeliveryCheckServiceImpl.class);

    private final DeliveryMapper mapper;
    private final AuditServiceImpl audit;
    private final SnSettings settings;

    public DeliveryCheckServiceImpl(
            DeliveryMapper mapper, AuditServiceImpl audit, SnSettings settings) {
        this.mapper = mapper;
        this.audit = audit;
        this.settings = settings;
    }

    private record Found(String org, String batchNo, String sn, String detail) {}

    /** 成功批次记为接收的枚数与批次枚数是否一致；同一枚号（号池 id + rev）是否在两个成功交付批次中；未确认超过告警时长的批次。人工确认的批次单列。 */
    public Map<String, Object> run() {
        LocalDateTime now = Util.now();
        LocalDateTime since = now.minusDays(2);
        List<Found> found = new ArrayList<>();
        for (Map<String, Object> b : mapper.checkAccepted(since)) {
            if (R.l(b, "n") != R.l(b, "qty")) {
                found.add(
                        new Found(
                                R.s(b, "factory_code"),
                                R.s(b, "batch_no"),
                                null,
                                "回执枚数 " + R.l(b, "n") + " 与批次枚数 " + R.l(b, "qty") + " 不一致"));
            }
        }
        for (Map<String, Object> r : mapper.checkDuplicates(since)) {
            String nos = R.s(r, "nos");
            found.add(
                    new Found(
                            R.s(r, "f"),
                            nos.length() > 40 ? nos.substring(0, 40) : nos,
                            R.s(r, "sn"),
                            "同一 SN（rev " + R.l(r, "rev") + "）出现在多个成功交付批次：" + nos));
        }
        LocalDateTime cutoff = now.minusMinutes(settings.batchUnconfirmedAlertMinutes());
        for (Map<String, Object> b : mapper.checkUnconfirmed(cutoff)) {
            found.add(
                    new Found(
                            R.s(b, "factory_code"),
                            R.s(b, "batch_no"),
                            null,
                            "批次建立于 " + Util.fmt(R.t(b, "created_at")) + "，超过告警时长仍未确认"));
        }
        long manual = mapper.manualCount(since);
        int created = 0;
        for (Found x : found) {
            if (mapper.openCheckAlert(x.batchNo(), x.sn())) {
                continue;
            }
            mapper.insertCheckAlert(
                    x.org() == null ? "" : x.org(),
                    x.batchNo(),
                    x.sn(),
                    Util.cut(x.detail(), 1000),
                    now);
            created++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("problems", found.size());
        result.put("new_alerts", created);
        result.put("manual_confirmed", manual);
        audit.recordIsolated(
                CurrentUser.system(),
                AuditEntryDTO.of(AuditAction.SELF_CHECK)
                        .count(found.size())
                        .info(
                                result.entrySet().stream()
                                        .map(e -> e.getKey() + "=" + e.getValue())
                                        .collect(Collectors.joining(" "))));
        log.info("delivery_self_check {}", result);
        return result;
    }
}
