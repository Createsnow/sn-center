package com.adtek.sn.service.impl;

import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.mapper.AuditQueryMapper;
import com.adtek.sn.query.AuditQuery;
import com.adtek.sn.service.AuditQueryService;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.Camel;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.support.R;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Util;
import com.adtek.sn.support.Xlsx;
import com.adtek.sn.vo.ExportFileVO;
import com.adtek.sn.vo.PageVO;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 痕迹查询与导出（原系统 audit_query.py，F-21）：绑定生产组织的账户只看本组织相关的痕迹；时间范围默认最近 30 天（按账户显示时区的自然日）；覆盖到已归档的
 * 痕迹时同时查归档表，结果标 archived。
 */
@Service
public class AuditQueryServiceImpl implements AuditQueryService {
    public static final List<String> HEADERS =
            List.of(
                    "时间",
                    "工号",
                    "姓名",
                    "角色",
                    "来源",
                    "动作",
                    "结果",
                    "生产组织",
                    "PI",
                    "客户",
                    "物料",
                    "单据编号",
                    "起始SN",
                    "结束SN",
                    "数量",
                    "操作前状态",
                    "操作后状态",
                    "批次号",
                    "请求号",
                    "转单 / 转组织单号",
                    "原因",
                    "失败原因",
                    "请求ID",
                    "详情");
    static final List<String> ROW_FIELDS =
            List.of(
                    "id",
                    "created_at",
                    "operator",
                    "operator_name",
                    "role",
                    "source",
                    "action",
                    "result",
                    "factory_code",
                    "pi_no",
                    "customer_code",
                    "material_code",
                    "bill_no",
                    "start_sn",
                    "end_sn",
                    "qty",
                    "before_status",
                    "after_status",
                    "batch_no",
                    "request_no",
                    "transfer_no",
                    "reason",
                    "error_msg",
                    "trace_id",
                    "detail");

    private final AuditQueryMapper mapper;
    private final FactoryServiceImpl factories;
    private final SnSettings settings;

    public AuditQueryServiceImpl(
            AuditQueryMapper mapper, FactoryServiceImpl factories, SnSettings settings) {
        this.mapper = mapper;
        this.factories = factories;
        this.settings = settings;
    }

    private CurrentUser cu() {
        return CurrentUser.current(settings.initAdminEmpNo()).requireAudit();
    }

    @Override
    public Map<String, Object> meta() {
        cu();
        int d = settings.auditRetentionDays();
        Map<String, Object> a = mapper.archiveRange();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("retentionDays", d);
        out.put("permanent", d == 0);
        out.put("archiveRetentionDays", settings.auditArchiveRetentionDays());
        out.put("archiveFrom", a == null ? null : R.t(a, "lo"));
        out.put("archiveTo", a == null ? null : R.t(a, "hi"));
        out.put("exportLimit", settings.auditExportLimit());
        return out;
    }

    private static String nb(String v) {
        return Util.blank(v) ? null : v.strip();
    }

    private AuditQuery prepare(CurrentUser cu, AuditQuery in) {
        int off = factories.tzOffset(cu.factories());
        LocalDate to = in.dateTo != null ? in.dateTo : Util.now().plusMinutes(off).toLocalDate();
        LocalDate from = in.dateFrom != null ? in.dateFrom : to.minusDays(30);
        if (from.isAfter(to)) {
            throw Biz.err(SnErrorCode.VALIDATION, "detail", "from > to");
        }
        AuditQuery f = new AuditQuery();
        f.start = from.atStartOfDay().minusMinutes(off);
        f.end = to.plusDays(1).atStartOfDay().minusMinutes(off);
        f.scope = cu.readFactories(in.factoryCode);
        f.bound = cu.bound();
        f.action = nb(in.action);
        f.operator = nb(in.operator);
        f.pi = nb(in.pi);
        f.source = nb(in.source);
        f.result = nb(in.result);
        f.batchNo = nb(in.batchNo);
        f.billNo = nb(in.billNo);
        f.like = Util.blank(in.keyword) ? null : Util.likeAny(in.keyword.strip());
        return f;
    }

    /** 查询起点早于归档表里最新一条痕迹时，归档表里可能有命中的痕迹，要一起查。 */
    private boolean withArchive(AuditQuery f) {
        Map<String, Object> a = mapper.archiveRange();
        LocalDateTime hi = a == null ? null : R.t(a, "hi");
        return hi != null && !hi.isBefore(f.start);
    }

    private long count(AuditQuery f) {
        long n = mapper.countLive(f, null);
        if (withArchive(f)) {
            n += mapper.countArchive(f, null);
        }
        return n;
    }

    @Override
    public PageVO<Map<String, Object>> list(AuditQuery in, PageReq page) {
        AuditQuery f = prepare(cu(), in);
        long total = count(f);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r :
                mapper.page(f, withArchive(f), null, false, page.offset(), page.size())) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : ROW_FIELDS) {
                m.put(Camel.key(k), r.get(k));
            }
            m.put("archived", R.b(r, "archived"));
            items.add(m);
        }
        return PageVO.of(items, total, page.page(), page.size());
    }

    @Override
    public Map<String, Object> exportCheck(AuditQuery in) {
        AuditQuery f = prepare(cu(), in);
        long n = count(f);
        long limit = settings.auditExportLimit();
        return R.of("limit", limit, "truncated", n > limit, "rows", Math.min(n, limit));
    }

    @Override
    public ExportFileVO export(AuditQuery in) {
        AuditQuery f = prepare(cu(), in);
        boolean archive = withArchive(f);
        return new ExportFileVO(
                "audit_export",
                out -> {
                    try (Xlsx.Writer w = new Xlsx.Writer("audit", HEADERS)) {
                        long lastId = Long.MAX_VALUE;
                        long left = settings.auditExportLimit();
                        while (left > 0) {
                            List<Map<String, Object>> page =
                                    mapper.page(f, archive, lastId, true, 0, 5000);
                            if (page.isEmpty()) {
                                break;
                            }
                            for (Map<String, Object> r : page) {
                                if (left <= 0) {
                                    break;
                                }
                                left--;
                                List<Object> row = new ArrayList<>();
                                row.add(Util.fmt(R.t(r, "created_at")));
                                for (String k : ROW_FIELDS.subList(2, ROW_FIELDS.size())) {
                                    Object v = r.get(k);
                                    row.add(v instanceof Number n ? n.longValue() : v);
                                }
                                w.add(row);
                                lastId = R.l(r, "id");
                            }
                        }
                        w.write(out);
                    }
                });
    }
}
