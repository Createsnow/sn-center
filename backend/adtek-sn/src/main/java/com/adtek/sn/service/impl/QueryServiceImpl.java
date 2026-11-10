package com.adtek.sn.service.impl;

import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.mapper.QueryMapper;
import com.adtek.sn.query.SnItemQuery;
import com.adtek.sn.service.QueryService;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.support.R;
import com.adtek.sn.support.SnCodec;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Util;
import com.adtek.sn.support.Xlsx;
import com.adtek.sn.vo.ExportFileVO;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * SN 查询与导出（原系统 query.py，F-18）：只读；按账户可看范围过滤；按条件分页读取。列表总数超过 10 万时只统计到 10 万并注明；导出默认最多 100
 * 万行（SN_EXPORT_LIMIT）。
 */
@Service
public class QueryServiceImpl implements QueryService {
    public static final long COUNT_CAP = 100_000;
    public static final List<String> EXPORT_HEADERS =
            List.of(
                    "序号", "完整SN", "进制流水", "十进制流水", "组织编号", "生产组织名称", "客户编码", "PI", "单据编号", "附件项次",
                    "物料编码", "ERP行号", "米数", "状态", "交付方式", "交付批次");

    private final QueryMapper mapper;
    private final RuleServiceImpl rules;
    private final SnImportServiceImpl imports;
    private final SnSettings settings;

    public QueryServiceImpl(
            QueryMapper mapper,
            RuleServiceImpl rules,
            SnImportServiceImpl imports,
            SnSettings settings) {
        this.mapper = mapper;
        this.rules = rules;
        this.imports = imports;
        this.settings = settings;
    }

    private CurrentUser cu() {
        return CurrentUser.current(settings.initAdminEmpNo());
    }

    private static String nb(String v) {
        return Util.blank(v) ? null : v.strip();
    }

    /** 授权范围、流水范围换算；返回规范化的条件。 */
    private SnItemQuery prepare(CurrentUser cu, SnItemQuery in) {
        SnItemQuery f = new SnItemQuery();
        f.factoryCode = in.factoryCode;
        f.customerCode = nb(in.customerCode);
        f.pi = nb(in.pi);
        f.billNo = nb(in.billNo);
        f.length = nb(in.length);
        f.sn = nb(in.sn);
        f.materialCode = in.materialCode == null ? null : in.materialCode.strip();
        String st = nb(in.status);
        f.status =
                "SUSPENDED".equals(st) || (st != null && Util.SN_STATUSES.contains(st)) ? st : null;
        f.piAll = in.piAll;
        f.seqFrom = in.seqFrom;
        f.seqTo = in.seqTo;
        if (in.piAll && cu.isOperator()) {
            if (Util.blank(in.pi)) {
                throw Biz.err(SnErrorCode.QUERY_PI_REQUIRED);
            }
            if (!mapper.piOwned(in.pi.strip(), cu.factories())) {
                throw Biz.err(SnErrorCode.QUERY_PI_NOT_OWNED, "pi", in.pi.strip());
            }
            f.allOfPi = true;
        } else {
            f.scope = cu.readFactories(in.factoryCode);
        }
        boolean hasRange = !(Util.blank(in.seqFrom) && Util.blank(in.seqTo));
        if (hasRange) {
            if (Util.blank(in.pi)) {
                throw Biz.err(SnErrorCode.QUERY_RANGE_PI_REQUIRED);
            }
            String pi = in.pi.strip();
            List<SnCodec.Spec> specs = new ArrayList<>();
            for (Long v : mapper.piVersions(pi)) {
                SnCodec.Spec s = rules.cachedSpec(v);
                if (s != null) {
                    specs.add(s);
                }
            }
            Long[] bounds = new Long[2];
            String[] vals = {in.seqFrom, in.seqTo};
            for (int i = 0; i < 2; i++) {
                if (Util.blank(vals[i])) {
                    continue;
                }
                Long n = seqOf(vals[i].strip(), specs);
                if (n == null) {
                    throw Biz.err(
                            SnErrorCode.QUERY_RANGE_INVALID, "value", vals[i].strip(), "pi", pi);
                }
                bounds[i] = n;
            }
            if (bounds[0] != null && bounds[1] != null && bounds[0] > bounds[1]) {
                throw Biz.err(
                        SnErrorCode.QUERY_RANGE_REVERSED,
                        "start",
                        in.seqFrom.strip(),
                        "end",
                        in.seqTo.strip());
            }
            f.rangePi = pi;
            f.rangeLo = bounds[0];
            f.rangeHi = bounds[1];
        }
        return f;
    }

    /** 流水号（可省略左侧补位）或完整 SN → 十进制流水；按 PI 用过的规则版本逐个试。 */
    static Long seqOf(String text, List<SnCodec.Spec> specs) {
        for (SnCodec.Spec s : specs) {
            if (text.length() <= s.seqLen()
                    && text.chars().allMatch(c -> s.charset().indexOf(c) >= 0)) {
                Long n = SnCodec.decodeSeq(text, s);
                if (n != null) {
                    return n;
                }
            }
            Long hit = SnCodec.parse(text, s);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private record BillInfo(Map<String, String> pi, Map<String, String> org) {}

    private BillInfo billInfo(List<String> bills) {
        Map<String, String> pis = new HashMap<>();
        Map<String, String> orgs = new HashMap<>();
        if (bills.isEmpty()) {
            return new BillInfo(pis, orgs);
        }
        TreeSet<String> ub = new TreeSet<>(bills);
        for (Map<String, Object> r : mapper.ledger(ub)) {
            pis.put(R.s(r, "bill_no"), R.s(r, "pi"));
            if (!R.sz(r, "org_code").isEmpty()) {
                orgs.put(R.s(r, "bill_no"), R.s(r, "org_code"));
            }
        }
        for (Map<String, Object> r : mapper.snapshotPis(ub)) {
            pis.put(R.s(r, "bill_no"), R.s(r, "pi"));
        }
        return new BillInfo(pis, orgs);
    }

    private Map<String, Object> view(Map<String, Object> r, BillInfo bills) {
        SnCodec.Spec s = rules.cachedSpec(R.lo(r, "rule_version_id"));
        String bill = R.s(r, "bill_no");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", R.l(r, "id"));
        m.put("sn", R.s(r, "sn"));
        m.put("seqLen", s == null ? null : s.seqLen());
        m.put("base", s == null ? null : s.base());
        String unit = s == null ? null : SnCodec.unitOf(R.s(r, "sn"), s, R.s(r, "length_text"));
        m.put("unit", unit == null ? "" : unit);
        m.put("seqText", rules.seqText(R.lo(r, "rule_version_id"), R.lo(r, "seq_dec")));
        m.put("seqDec", Util.seqDecText(R.s(r, "seq_pi_no"), R.lo(r, "seq_dec")));
        m.put("seqPiNo", R.s(r, "seq_pi_no"));
        m.put("piNo", R.s(r, "pi_no"));
        m.put("billNo", bill);
        m.put("billSeq", R.l(r, "bill_seq"));
        m.put("materialCode", R.s(r, "material_code"));
        m.put("attachNo", R.s(r, "attach_no"));
        m.put("length", R.s(r, "length_text"));
        m.put("customerCode", R.s(r, "customer_code"));
        String f = R.s(r, "factory_code");
        m.put("factoryCode", f != null && !f.isEmpty() ? f : bills.org().get(bill));
        m.put("status", R.s(r, "status"));
        m.put("suspend", R.s(r, "suspend"));
        m.put(
                "piChanged",
                !Objects.equals(bills.pi().getOrDefault(bill, R.s(r, "pi_no")), R.s(r, "pi_no")));
        m.put("transferredIn", Util.transferredIn(R.s(r, "seq_pi_no"), R.s(r, "pi_no")));
        m.put("createdAt", R.t(r, "created_at"));
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) r.get("import");
        if (d != null) {
            m.put("seqLen", R.l(d, "seq_len"));
            m.put("base", R.l(d, "seq_base"));
            m.put("unit", R.sz(d, "unit"));
            m.put("seqText", R.s(d, "serial_text"));
            m.put("seqDec", String.valueOf(R.l(d, "seq_dec")));
            m.put("billSeq", R.l(d, "cur_bill_seq"));
        }
        return m;
    }

    @Override
    public Map<String, Object> list(SnItemQuery in, PageReq page) {
        CurrentUser cu = cu();
        SnItemQuery f = prepare(cu, in);
        long total = mapper.count(f, COUNT_CAP + 1);
        List<Map<String, Object>> rows =
                imports.withDetails(mapper.page(f, page.offset(), page.size()));
        BillInfo bi = billInfo(rows.stream().map(r -> R.s(r, "bill_no")).toList());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            items.add(view(r, bi));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", Math.min(total, COUNT_CAP));
        out.put("page", page.page());
        out.put("pageSize", page.size());
        out.put("totalCapped", total > COUNT_CAP);
        if (f.rangePi != null) {
            Map<String, Long> byStatus = new LinkedHashMap<>();
            long found = 0;
            for (Map<String, Object> r : mapper.rangeSummary(f)) {
                byStatus.put(R.s(r, "status"), R.l(r, "n"));
                found += R.l(r, "n");
            }
            Long expected =
                    f.rangeLo == null || f.rangeHi == null ? null : f.rangeHi - f.rangeLo + 1;
            Map<String, Object> rg = new LinkedHashMap<>();
            rg.put("found", found);
            rg.put("expected", expected);
            rg.put("missing", expected == null ? null : expected - found);
            rg.put("byStatus", byStatus);
            out.put("range", rg);
        }
        return out;
    }

    @Override
    public Map<String, Object> exportCheck(SnItemQuery in) {
        SnItemQuery f = prepare(cu(), in);
        long limit = settings.snExportLimit();
        long total = mapper.count(f, limit + 1);
        return R.of("limit", limit, "truncated", total > limit, "rows", Math.min(total, limit));
    }

    private List<Object> row(long no, Map<String, Object> r) {
        @SuppressWarnings("unchecked")
        Map<String, Object> imp = (Map<String, Object>) r.get("import");
        return Arrays.asList(
                no,
                R.s(r, "sn"),
                imp != null
                        ? R.s(imp, "serial_text")
                        : rules.seqText(R.lo(r, "rule_version_id"), R.lo(r, "seq_dec")),
                imp != null
                        ? String.valueOf(R.l(imp, "seq_dec"))
                        : Util.seqDecText(R.s(r, "seq_pi_no"), R.lo(r, "seq_dec")),
                R.sz(r, "factory_code"),
                R.sz(r, "factory_name"),
                R.s(r, "customer_code"),
                R.s(r, "pi_no"),
                R.s(r, "bill_no"),
                R.sz(r, "attach_no"),
                R.s(r, "material_code"),
                imp != null ? R.lo(imp, "cur_bill_seq") : R.lo(r, "bill_seq"),
                R.sz(r, "length_text"),
                R.s(r, "status"),
                R.sz(r, "delivery_mode"),
                R.sz(r, "delivery_batch_no"));
    }

    @Override
    public ExportFileVO export(SnItemQuery in) {
        SnItemQuery f = prepare(cu(), in);
        int limit = settings.snExportLimit();
        return new ExportFileVO(
                "sn_export",
                out -> {
                    try (Xlsx.Writer w = new Xlsx.Writer("SN", EXPORT_HEADERS)) {
                        long last = 0;
                        long no = 0;
                        while (no < limit) {
                            List<Map<String, Object>> page =
                                    mapper.exportChunk(f, last, (int) Math.min(5000, limit - no));
                            if (page.isEmpty()) {
                                break;
                            }
                            for (Map<String, Object> r : imports.withDetails(page)) {
                                no++;
                                last = R.l(r, "id");
                                w.add(row(no, r));
                            }
                        }
                        w.write(out);
                    }
                });
    }
}
