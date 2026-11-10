package com.adtek.sn.service.impl;

import com.adtek.sn.mapper.DashboardMapper;
import com.adtek.sn.service.DashboardService;
import com.adtek.sn.support.Camel;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.R;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Util;
import com.adtek.sn.vo.BatchVO;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 首页看板（原系统 dashboard.py，F-20）：在途、推送中断、拉取未回执、交付告警、订单变更提醒、今日数据、生成任务。只读。 */
@Service
public class DashboardServiceImpl implements DashboardService {
    private final DashboardMapper mapper;
    private final FactoryServiceImpl factories;
    private final ChangeServiceImpl changes;
    private final SnSettings settings;

    public DashboardServiceImpl(
            DashboardMapper mapper,
            FactoryServiceImpl factories,
            ChangeServiceImpl changes,
            SnSettings settings) {
        this.mapper = mapper;
        this.factories = factories;
        this.changes = changes;
        this.settings = settings;
    }

    private static boolean mine(List<String> facs, String planJson) {
        return GenerateServiceImpl.items(GenerateServiceImpl.loads(planJson)).stream()
                .anyMatch(x -> GenerateServiceImpl.inScope(facs, x));
    }

    private static Map<String, Object> job(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        m.remove("plan_json");
        return Camel.map(m);
    }

    @Override
    public Map<String, Object> load() {
        CurrentUser cu = CurrentUser.current(settings.initAdminEmpNo());
        List<String> facs = cu.readFactories(null);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("PENDING_ALLOC", 0L);
        counts.put("TO_PUSH", 0L);
        counts.put("ALLOCATED", 0L);
        for (Map<String, Object> r : mapper.statusCounts(facs)) {
            counts.put(R.s(r, "status"), R.l(r, "c"));
        }
        if (cu.isAuditor()) {
            counts.remove("PENDING_ALLOC");
        }
        LocalDateTime cutoff = Util.now().minusMinutes(settings.batchUnconfirmedAlertMinutes());
        Map<String, Long> alerts = new LinkedHashMap<>();
        for (Map<String, Object> r : mapper.alerts(facs)) {
            alerts.put(R.s(r, "alert_type"), R.l(r, "n"));
        }
        int off = factories.tzOffset(cu.factories());
        LocalDateTime start = Util.localDayStart(off, null);
        Map<String, Long> today = new LinkedHashMap<>();
        for (Map<String, Object> r : mapper.todayAudit(facs, start)) {
            today.put(R.s(r, "action"), R.l(r, "q"));
        }
        for (Map<String, Object> r : mapper.todayConfirm(facs, start)) {
            today.put("CONFIRM_" + R.s(r, "confirm_method"), R.l(r, "q"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statusCounts", counts);
        out.put("suspended", mapper.suspended(facs));
        out.put("pushInterrupted", mapper.interrupted(facs));
        out.put("pullUnconfirmed", mapper.stalePull(facs, cutoff));
        out.put("deliveryAlerts", alerts);
        out.put("orderAlerts", changes.openAlertCount(cu));
        out.put("today", today);
        out.put("todayStart", start);
        out.put("tzOffsetMin", off);
        out.put("totalGenerated", cu.bound() ? null : mapper.totalGenerated());
        if (cu.isAdmin()) {
            out.put("runningJobs", mapper.runningJobs());
            out.put(
                    "recentJobs",
                    mapper.recentJobs(8).stream().map(DashboardServiceImpl::job).toList());
        } else if (cu.isOperator()) {
            out.put(
                    "runningJobs",
                    mapper.runningPlans().stream().filter(p -> mine(facs, p)).count());
            List<Map<String, Object>> jobs = new ArrayList<>();
            for (Map<String, Object> j : mapper.recentJobs(200)) {
                if (mine(facs, R.s(j, "plan_json"))) {
                    jobs.add(job(j));
                    if (jobs.size() >= 8) {
                        break;
                    }
                }
            }
            out.put("recentJobs", jobs);
        }
        if (!cu.isAuditor()) {
            out.put(
                    "recentFailures",
                    mapper.recentFailures(facs, cutoff).stream().map(BatchVO::of).toList());
            out.put(
                    "openDeliveryAlerts",
                    mapper.openAlerts(facs).stream().map(Camel::map).toList());
        }
        return out;
    }
}
