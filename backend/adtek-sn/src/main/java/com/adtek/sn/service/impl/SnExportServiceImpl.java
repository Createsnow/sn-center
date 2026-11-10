package com.adtek.sn.service.impl;

import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.mapper.SnExportMapper;
import com.adtek.sn.query.SnBillQuery;
import com.adtek.sn.service.SnExportService;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.support.R;
import com.adtek.sn.support.SnCodec;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Util;
import com.adtek.sn.support.Xlsx;
import com.adtek.sn.vo.ExportFileVO;
import com.adtek.sn.vo.PageVO;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SN 导出（原系统 sn_export.py，F-14，页面在 SN 分配页的「已分配」页签）：已分配的号按「生产组织 + PI + 单据」查看与下载，不看推送、拉取状态；
 * 只读，全部页面账户可用（按授权的生产组织）。下载文件的列与 SN 导入模板相同。
 */
@Service
public class SnExportServiceImpl implements SnExportService {
    private static final Pattern ATTACH_NUM = Pattern.compile("^\\d+(\\.\\d+)?$");

    private final SnExportMapper mapper;
    private final BillStatsServiceImpl billStats;
    private final RuleServiceImpl rules;
    private final SnImportServiceImpl imports;
    private final SnSettings settings;

    public SnExportServiceImpl(
            SnExportMapper mapper,
            BillStatsServiceImpl billStats,
            RuleServiceImpl rules,
            SnImportServiceImpl imports,
            SnSettings settings) {
        this.mapper = mapper;
        this.billStats = billStats;
        this.rules = rules;
        this.imports = imports;
        this.settings = settings;
    }

    private CurrentUser cu() {
        return CurrentUser.current(settings.initAdminEmpNo());
    }

    @Override
    public PageVO<Map<String, Object>> bills(
            String factory, String q, Boolean mes, String pi, PageReq page) {
        CurrentUser cu = cu();
        billStats.refresh();
        List<String> scope = cu.readFactories(factory);
        String p = Util.blank(pi) ? null : pi.strip();
        String like = Util.blank(q) ? null : Util.likePrefix(q.strip());
        Integer m = mes == null ? null : (mes ? 1 : 0);
        long total = mapper.countBills(scope, p, like, m);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r :
                mapper.pageBills(scope, p, like, m, page.offset(), page.size())) {
            items.add(
                    R.of(
                            "factoryCode", R.s(r, "factory_code"),
                            "piNo", R.s(r, "pi_no"),
                            "billNo", R.s(r, "bill_no"),
                            "customerCode", R.s(r, "customer_code"),
                            "mesEnabled", R.b(r, "mes"),
                            "qty", R.l(r, "qty"),
                            "suspended", R.l(r, "suspended")));
        }
        return PageVO.of(items, total, page.page(), page.size());
    }

    private SnBillQuery billQuery(CurrentUser cu, String factory, String pi, String bill) {
        String p = Util.trim(pi);
        String b = Util.trim(bill);
        if (p.isEmpty()) {
            throw Biz.err(SnErrorCode.PI_REQUIRED);
        }
        if (b.isEmpty()) {
            throw Biz.err(SnErrorCode.VALIDATION, "detail", "bill_no");
        }
        SnBillQuery f = new SnBillQuery();
        f.scope = cu.readFactories(factory);
        f.pi = p;
        f.bill = b;
        return f;
    }

    /** 物料行 / 物料编码、SN 起止号筛选；起止号不是该单据已分配的号时返回 null（没有结果）。 */
    private SnBillQuery filter(
            SnBillQuery base, Integer billSeq, String material, String snFrom, String snTo) {
        SnBillQuery f = base.copy();
        if (billSeq != null) {
            f.billSeq = billSeq;
            f.material = Util.trim(material);
        } else if (!Util.blank(material)) {
            f.material = Util.trim(material);
        }
        if (!Util.blank(snFrom)) {
            Map<String, Object> at = mapper.snAt(f, snFrom.strip());
            if (at == null) {
                return null;
            }
            f.fromPi = R.s(at, "seq_pi_no");
            f.fromDec = R.l(at, "seq_dec");
        }
        if (!Util.blank(snTo)) {
            Map<String, Object> at = mapper.snAt(f, snTo.strip());
            if (at == null) {
                return null;
            }
            f.toPi = R.s(at, "seq_pi_no");
            f.toDec = R.l(at, "seq_dec");
        }
        return f;
    }

    @Override
    public List<Map<String, Object>> lines(String factory, String pi, String bill) {
        SnBillQuery f = billQuery(cu(), factory, pi, bill);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : mapper.lines(f)) {
            out.add(
                    R.of(
                            "billSeq", R.l(r, "bill_seq"),
                            "materialCode", R.s(r, "material_code"),
                            "attachNo", R.sz(r, "attach_no"),
                            "length", R.sz(r, "length_text"),
                            "qty", R.l(r, "qty"),
                            "suspended", R.l(r, "suspended")));
        }
        return out;
    }

    @Override
    public PageVO<Map<String, Object>> segments(
            String factory, String pi, String bill, PageReq page) {
        SnBillQuery f = billQuery(cu(), factory, pi, bill);
        long total = mapper.countSegments(f);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : mapper.pageSegments(f, page.offset(), page.size())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("factoryCode", R.s(r, "factory_code"));
            m.put("piNo", f.pi);
            m.put("billNo", f.bill);
            m.put("billSeq", R.l(r, "bill_seq"));
            m.put("materialCode", R.s(r, "material_code"));
            m.put("attachNo", R.sz(r, "attach_no"));
            m.put("length", R.sz(r, "length_text"));
            m.put("seqPiNo", R.s(r, "seq_pi_no"));
            m.put("transferredIn", Util.transferredIn(R.s(r, "seq_pi_no"), f.pi));
            m.put("startSeq", R.l(r, "s"));
            m.put("endSeq", R.l(r, "e"));
            m.put("startSn", R.s(r, "start_sn"));
            m.put("endSn", R.s(r, "end_sn"));
            m.put("qty", R.l(r, "qty"));
            m.put("suspend", R.s(r, "suspend"));
            items.add(m);
        }
        return PageVO.of(items, total, page.page(), page.size());
    }

    /** 附件项次排序：按数字比，不是数字的排在数字之后、按文字比，空的排最后。 */
    static final Comparator<String> ATTACH_ORDER =
            (a, b) -> {
                String x = a == null ? "" : a.strip();
                String y = b == null ? "" : b.strip();
                if (x.isEmpty() != y.isEmpty()) {
                    return x.isEmpty() ? 1 : -1;
                }
                boolean nx = ATTACH_NUM.matcher(x).matches();
                boolean ny = ATTACH_NUM.matcher(y).matches();
                if (nx != ny) {
                    return nx ? -1 : 1;
                }
                if (nx) {
                    int c = new BigDecimal(x).compareTo(new BigDecimal(y));
                    if (c != 0) {
                        return c;
                    }
                    return x.compareTo(y);
                }
                return x.compareTo(y);
            };

    private List<String> attaches(SnBillQuery f) {
        List<String> got = new ArrayList<>(mapper.attaches(f));
        got.sort(ATTACH_ORDER);
        return got;
    }

    private SnCodec.Spec spec(Map<String, Object> r) {
        return rules.cachedSpec(R.lo(r, "rule_version_id"));
    }

    private String unit(Map<String, Object> r) {
        SnCodec.Spec s = spec(r);
        String u = s == null ? null : SnCodec.unitOf(R.s(r, "sn"), s, R.s(r, "length_text"));
        return u == null ? "" : u;
    }

    @Override
    public PageVO<Map<String, Object>> items(
            String factory,
            String pi,
            String bill,
            PageReq page,
            Integer billSeq,
            String material,
            String q,
            String snFrom,
            String snTo) {
        SnBillQuery base = billQuery(cu(), factory, pi, bill);
        SnBillQuery f = filter(base, billSeq, material, snFrom, snTo);
        if (f == null) {
            return PageVO.of(List.of(), 0, page.page(), page.size());
        }
        if (!Util.blank(q)) {
            f.q = Util.likeAny(q.strip());
        }
        long total = mapper.countItems(f);
        List<String> rank = attaches(f);
        List<Map<String, Object>> rows =
                imports.withDetails(mapper.pageItems(f, rank, page.offset(), page.size()));
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        for (Map<String, Object> r : rows) {
            SnCodec.Spec s = spec(r);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineNo", page.offset() + (++n));
            m.put("piNo", R.s(r, "pi_no"));
            m.put("factoryCode", R.sz(r, "factory_code"));
            m.put("factoryName", R.sz(r, "factory_name"));
            m.put("sn", R.s(r, "sn"));
            m.put("billSeq", R.l(r, "bill_seq"));
            m.put("materialCode", R.s(r, "material_code"));
            m.put("attachNo", R.sz(r, "attach_no"));
            m.put("length", R.sz(r, "length_text"));
            m.put("unit", unit(r));
            m.put("seqPiNo", R.s(r, "seq_pi_no"));
            m.put("transferredIn", Util.transferredIn(R.s(r, "seq_pi_no"), f.pi));
            m.put("base", s == null ? null : s.base());
            m.put("seqLen", s == null ? null : s.seqLen());
            m.put("seqText", rules.seqText(R.lo(r, "rule_version_id"), R.lo(r, "seq_dec")));
            m.put("seqDec", R.sz(r, "seq_pi_no").isEmpty() ? null : R.l(r, "seq_dec"));
            m.put("status", R.s(r, "status"));
            m.put("deliveryMode", R.sz(r, "delivery_mode"));
            m.put("deliveryBatchNo", R.sz(r, "delivery_batch_no"));
            m.put("suspend", R.s(r, "suspend"));
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) r.get("import");
            if (d != null) {
                m.put("billSeq", R.l(d, "cur_bill_seq"));
                m.put("seqLen", R.l(d, "seq_len"));
                m.put("base", R.l(d, "seq_base"));
                m.put("seqText", R.s(d, "serial_text"));
                m.put("seqDec", R.l(d, "seq_dec"));
                m.put("unit", R.sz(d, "unit"));
            }
            out.add(m);
        }
        return PageVO.of(out, total, page.page(), page.size());
    }

    private List<Object> fileRow(Map<String, Object> r) {
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) r.get("import");
        SnCodec.Spec s = spec(r);
        return Arrays.asList(
                R.s(r, "pi_no"),
                R.s(r, "bill_no"),
                R.sz(r, "attach_no"),
                R.s(r, "material_code"),
                d != null ? R.lo(d, "cur_bill_seq") : R.lo(r, "bill_seq"),
                R.s(r, "sn"),
                d != null ? R.lo(d, "seq_len") : (s == null ? null : (Object) s.seqLen()),
                d != null ? R.lo(d, "seq_base") : (s == null ? null : (Object) s.base()),
                d != null
                        ? R.s(d, "serial_text")
                        : rules.seqText(R.lo(r, "rule_version_id"), R.lo(r, "seq_dec")),
                d != null
                        ? String.valueOf(R.l(d, "seq_dec"))
                        : Util.seqDecText(R.s(r, "seq_pi_no"), R.lo(r, "seq_dec")),
                R.sz(r, "length_text"),
                d != null ? R.sz(d, "unit") : unit(r),
                R.sz(r, "factory_code"),
                R.sz(r, "factory_name"));
    }

    @Override
    public ExportFileVO file(
            String factory, String pi, String bill, String material, String snFrom, String snTo) {
        SnBillQuery base = billQuery(cu(), factory, pi, bill);
        SnBillQuery f = filter(base, null, material, snFrom, snTo);
        if (f == null || !mapper.exists(f)) {
            throw Biz.err(SnErrorCode.EXPORT_NOTHING, "pi", base.pi, "bill_no", base.bill);
        }
        return new ExportFileVO(
                "SN_" + base.pi + "_" + base.bill,
                out -> {
                    try (Xlsx.Writer w = new Xlsx.Writer("SN", SnImportServiceImpl.HEADERS)) {
                        for (String attach : attaches(f)) {
                            SnBillQuery fa = f.copy();
                            fa.attachSet = true;
                            fa.attach = attach;
                            Long afterSeq = null;
                            String afterPi = null;
                            Long afterDec = null;
                            while (true) {
                                List<Map<String, Object>> page =
                                        mapper.fileChunk(fa, afterSeq, afterPi, afterDec);
                                if (page.isEmpty()) {
                                    break;
                                }
                                for (Map<String, Object> r : imports.withDetails(page)) {
                                    w.add(fileRow(r));
                                }
                                Map<String, Object> last = page.get(page.size() - 1);
                                afterSeq = R.l(last, "bill_seq");
                                afterPi = R.s(last, "seq_pi_no");
                                afterDec = R.l(last, "seq_dec");
                            }
                        }
                        w.write(out);
                    }
                });
    }
}
