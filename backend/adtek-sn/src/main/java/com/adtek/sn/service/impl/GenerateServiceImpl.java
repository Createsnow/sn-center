package com.adtek.sn.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.lock.NamedLockService;
import com.adtek.framework.task.BackgroundTasks;
import com.adtek.framework.task.RecurringTask;
import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.domain.GenJobEntity;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.dto.LineRefDTO;
import com.adtek.sn.mapper.GenerateMapper;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.DbErrors;
import com.adtek.sn.support.R;
import com.adtek.sn.support.SnCodec;
import com.adtek.sn.support.SnLocks;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * SN 生成（F-09，原系统 app/services/generate.py）。删号见 DeletionServiceImpl，分配见 AllocationServiceImpl。
 *
 * <p>在一张 PI 下整单（勾选单据）或按物料行生成；最小单位是物料行（整行额度一次生成）。排号顺序：单据按勾选先后，单据内按附件项次（按数字比）、 项次相同按行号。流水来源：MAX 从 PI
 * 最大号 + 1 起；RANGE 用手动起止号内的空闲流水，不够时从 max(最大号, 止号) + 1 接着编。必须先预演 （只对发起人有效、默认 30
 * 分钟、只能用一次，条件变化即失效）；一次生成在一个事务里完成，撞号整次回滚；数量不大时当场完成，否则后台执行 （可取消）。生成、删号与分配共用 PI 操作锁；一个实例同时写号池的生成不超过
 * SN_GEN_CONCURRENCY；死锁整笔重做。
 *
 * <p>预演与任务的计划以 JSON 文档保存（snake_case 键，与原系统相同，新旧版本可互读），返回给前端时转为 camelCase。
 */
@Service
public class GenerateServiceImpl implements RecurringTask {
    private static final Logger log = LoggerFactory.getLogger(GenerateServiceImpl.class);
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final int CHUNK = 2000;
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String REVOKED = "REVOKED";
    public static final String CANCELED = "CANCELED";
    public static final String SCOPE_BILLS = "BILLS";
    public static final String SCOPE_LINES = "LINES";
    public static final String SEQ_MAX = "MAX";
    public static final String SEQ_RANGE = "RANGE";
    static final int GAP_LIST_MAX = 500;
    static final int SEG_LIST_MAX = 500;
    static final int SN_PAGE_MAX = 200;
    static final int SLOT_WAIT_SECONDS = 60;
    public static final long RECOVER_INTERVAL_SECONDS = 60;
    static final List<String> JOB_FIELDS =
            List.of(
                    "id",
                    "gen_batch",
                    "pi_no",
                    "scope",
                    "qty",
                    "start_seq_dec",
                    "end_seq_dec",
                    "start_sn",
                    "end_sn",
                    "status",
                    "done_qty",
                    "current_sn",
                    "error_code",
                    "error_msg",
                    "created_by",
                    "cancel_by",
                    "cancel_at",
                    "revoke_reason",
                    "created_at",
                    "started_at",
                    "finished_at");
    static final List<String> LINE_FIELDS =
            List.of(
                    "bill_no",
                    "seq",
                    "material_code",
                    "attach",
                    "length",
                    "unit",
                    "qty",
                    "start_sn",
                    "end_sn",
                    "rule_label");
    static final List<String> KEY_FIELDS =
            List.of(
                    "bill_no",
                    "seq",
                    "qty",
                    "start_seq",
                    "end_seq",
                    "start_sn",
                    "end_sn",
                    "rule_key",
                    "rule_plan",
                    "line_qty",
                    "own_before",
                    "segs");
    private static final Pattern ATTACH_NUM = Pattern.compile("^\\d+(\\.\\d+)?$");

    private final GenerateMapper mapper;
    private final OrderServiceImpl orders;
    private final RuleServiceImpl rules;
    private final ChangeServiceImpl changes;
    private final BillStatsServiceImpl billStats;
    private final AuditServiceImpl audit;
    private final SnLocks locks;
    private final SnSettings settings;
    private final BackgroundTasks background;
    private final Tx tx;
    private volatile Semaphore slots;
    private volatile int slotCount;

    public GenerateServiceImpl(
            GenerateMapper mapper,
            OrderServiceImpl orders,
            RuleServiceImpl rules,
            ChangeServiceImpl changes,
            BillStatsServiceImpl billStats,
            AuditServiceImpl audit,
            SnLocks locks,
            SnSettings settings,
            BackgroundTasks background,
            Tx tx) {
        this.mapper = mapper;
        this.orders = orders;
        this.rules = rules;
        this.changes = changes;
        this.billStats = billStats;
        this.audit = audit;
        this.locks = locks;
        this.settings = settings;
        this.background = background;
        this.tx = tx;
    }

    private synchronized Semaphore genSlots() {
        int n = settings.genConcurrency();
        if (slots == null || slotCount != n) {
            slots = new Semaphore(n);
            slotCount = n;
        }
        return slots;
    }

    // ------------------------------------------------------------------ JSON

    static String dumps(Object v) {
        return JSON.writeValueAsString(v);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> loads(String s) {
        return JSON.readValue(s, LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(Map<String, Object> plan) {
        return (List<Map<String, Object>>) plan.get("items");
    }

    /** 计划中一个物料行的号段（十进制流水起止，升序）；早先的计划只有一段。 */
    @SuppressWarnings("unchecked")
    static List<long[]> itemSegs(Map<String, Object> x) {
        List<long[]> out = new ArrayList<>();
        Object segs = x.get("segs");
        if (segs instanceof List<?> l && !l.isEmpty()) {
            for (Object s : l) {
                List<Object> p = (List<Object>) s;
                out.add(
                        new long[] {
                            ((Number) p.get(0)).longValue(), ((Number) p.get(1)).longValue()
                        });
            }
            return out;
        }
        out.add(new long[] {R.l(x, "start_seq"), R.l(x, "end_seq")});
        return out;
    }

    static List<Map<String, Object>> oneSeg(Map<String, Object> x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("start_seq", x.get("start_seq"));
        m.put("end_seq", x.get("end_seq"));
        m.put("qty", x.get("qty"));
        m.put("start_sn", x.get("start_sn"));
        m.put("end_sn", x.get("end_sn"));
        return List.of(m);
    }

    /** 任务的视图（snake_case，调用方转 camelCase）。 */
    @SuppressWarnings("unchecked")
    Map<String, Object> jobJson(Map<String, Object> j) {
        if (j == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String f : JOB_FIELDS) {
            out.put(f, j.get(f));
        }
        Map<String, Object> plan = loads(R.s(j, "plan_json"));
        List<Map<String, Object>> lines = new ArrayList<>();
        TreeSet<String> bills = new TreeSet<>();
        long revoked = 0;
        for (Map<String, Object> x : items(plan)) {
            Map<String, Object> l = new LinkedHashMap<>();
            for (String k : LINE_FIELDS) {
                l.put(k, x.get(k));
            }
            l.put("revoked", Boolean.TRUE.equals(x.get("revoked")));
            Object segList = x.get("seg_list");
            l.put("seg_list", segList instanceof List<?> sl && !sl.isEmpty() ? segList : oneSeg(x));
            lines.add(l);
            bills.add(R.s(x, "bill_no"));
            if (Boolean.TRUE.equals(x.get("revoked"))) {
                revoked += R.l(x, "qty");
            }
        }
        out.put("lines", lines);
        out.put("bills", new ArrayList<>(bills));
        out.put("revoked_qty", revoked);
        return out;
    }

    public Map<String, Object> limits() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("default", settings.genDefaultLimit());
        m.put("max", settings.genMaxPerRun());
        m.put("sync_threshold", settings.genSyncThreshold());
        return m;
    }

    /** PI 最大流水号（只增不减：删号不退回）。 */
    public long lastSeq(String pi) {
        return mapper.lastSeq(pi);
    }

    // ================================================================== 空号段

    /** PI 流水 1..最大号之间没有号占用的连续段（升序）；按流水归属 PI 算。 */
    public List<long[]> gapSegments(String pi, Long last) {
        long top = last == null ? lastSeq(pi) : last;
        List<long[]> out = new ArrayList<>();
        if (top <= 0) {
            return out;
        }
        for (Map<String, Object> r : mapper.gapRows(pi, top)) {
            out.add(new long[] {R.l(r, "s"), R.l(r, "e")});
        }
        long usedTop = mapper.usedTop(pi, top);
        if (usedTop < top) {
            out.add(new long[] {usedTop + 1, top});
        }
        return out;
    }

    public Map<String, Object> gaps(CurrentUser cu, String piIn) {
        cu.requireOperator();
        String pi = Util.trim(piIn);
        if (pi.isEmpty()) {
            throw Biz.err(SnErrorCode.PI_REQUIRED);
        }
        Set<String> orgs = orders.billOrgs(mapper.billsOfPi(pi));
        cu.checkRead(orgs.isEmpty() ? new String[] {"-"} : orgs.toArray(String[]::new));
        return gapsOf(pi);
    }

    /** 空号段按来源单据拆段（升序）：来源单据 = 流水上最后一次删掉的号的生成单据。 */
    public List<Map<String, Object>> freeSegments(String pi, Long last) {
        long top = last == null ? lastSeq(pi) : last;
        List<long[]> raw = gapSegments(pi, top);
        List<Map<String, Object>> result = new ArrayList<>();
        if (raw.isEmpty()) {
            return result;
        }
        List<Object[]> marked = new ArrayList<>();
        for (Map<String, Object> r : mapper.deletedMarks(pi, top)) {
            marked.add(new Object[] {R.l(r, "s"), R.l(r, "e"), R.s(r, "bill_no")});
        }
        List<Object[]> out = new ArrayList<>();
        int i = 0;
        for (long[] se : raw) {
            long s = se[0];
            long e = se[1];
            long pos = s;
            while (i < marked.size() && (long) marked.get(i)[0] <= e) {
                long a = (long) marked.get(i)[0];
                long b = (long) marked.get(i)[1];
                String bill = (String) marked.get(i)[2];
                if (a > pos) {
                    out.add(new Object[] {pos, a - 1, ""});
                }
                out.add(new Object[] {a, b, bill});
                pos = b + 1;
                i++;
            }
            if (pos <= e) {
                out.add(new Object[] {pos, e, ""});
            }
        }
        for (Object[] o : out) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start_seq", o[0]);
            m.put("end_seq", o[1]);
            m.put("qty", (long) o[1] - (long) o[0] + 1);
            m.put("bill_no", o[2]);
            result.add(m);
        }
        return result;
    }

    public Map<String, Object> gapsOf(String pi) {
        long last = lastSeq(pi);
        List<Map<String, Object>> segs = freeSegments(pi, last);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pi_no", pi);
        m.put("last_seq", last);
        m.put("next_seq", last + 1);
        m.put("count", segs.size());
        m.put("total", segs.stream().mapToLong(x -> R.l(x, "qty")).sum());
        m.put("segments", segs.subList(0, Math.min(segs.size(), GAP_LIST_MAX)));
        m.put("truncated", segs.size() > GAP_LIST_MAX);
        return m;
    }

    // ================================================================== 单据号段

    /** 各单据现有号的号段数与枚数（生成详情的单据头用）。 */
    public Map<String, long[]> billSegmentCounts(
            String pi, List<String> billNos, List<String> scope) {
        Map<String, long[]> out = new HashMap<>();
        if (billNos.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : mapper.billSegmentCounts(pi, billNos, scope)) {
            out.put(R.s(r, "bill_no"), new long[] {R.l(r, "segs"), R.l(r, "qty")});
        }
        return out;
    }

    private String[] billKey(CurrentUser cu, String piIn, String billIn) {
        String pi = Util.trim(piIn);
        String bill = Util.trim(billIn);
        if (pi.isEmpty()) {
            throw Biz.err(SnErrorCode.PI_REQUIRED);
        }
        if (bill.isEmpty()) {
            throw Biz.err(SnErrorCode.ORDER_BILL_REQUIRED);
        }
        cu.checkRead(orders.billOrgs(List.of(bill)).toArray(String[]::new));
        return new String[] {pi, bill};
    }

    public Map<String, Object> billSegments(CurrentUser cu, String piIn, String billIn) {
        cu.requireOperator();
        String[] k = billKey(cu, piIn, billIn);
        String pi = k[0];
        String bill = k[1];
        List<String> scope = cu.readFactories(null);
        List<Map<String, Object>> use = new ArrayList<>();
        for (Map<String, Object> r : mapper.billSegments(pi, bill, scope)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bill_seq", R.i(r, "bill_seq"));
            m.put("material_code", R.sz(r, "material_code"));
            m.put("attach_no", R.sz(r, "attach_no"));
            m.put("seq_pi_no", R.s(r, "seq_pi_no"));
            m.put("start_seq", R.l(r, "s"));
            m.put("end_seq", R.l(r, "e"));
            m.put("start_sn", R.s(r, "start_sn"));
            m.put("end_sn", R.s(r, "end_sn"));
            m.put("qty", R.l(r, "n"));
            m.put("pending", R.l(r, "pending"));
            m.put("allocated", R.l(r, "n") - R.l(r, "pending"));
            m.put("transferred_in", !Objects.equals(R.s(r, "seq_pi_no"), pi));
            use.add(m);
        }
        use.sort(
                Comparator.comparing((Map<String, Object> x) -> R.s(x, "attach_no").isBlank())
                        .thenComparing(x -> R.s(x, "attach_no"), ATTACH_ORDER)
                        .thenComparingInt(x -> R.i(x, "bill_seq")));
        long last = lastSeq(pi);
        List<Map<String, Object>> free = new ArrayList<>();
        for (Map<String, Object> x : freeSegments(pi, last)) {
            if (bill.equals(R.s(x, "bill_no"))) {
                free.add(x);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pi_no", pi);
        m.put("bill_no", bill);
        m.put("last_seq", last);
        m.put("next_seq", last + 1);
        m.put("use", use.subList(0, Math.min(use.size(), SEG_LIST_MAX)));
        m.put("use_count", use.size());
        m.put("use_qty", use.stream().mapToLong(x -> R.l(x, "qty")).sum());
        m.put("use_truncated", use.size() > SEG_LIST_MAX);
        m.put("free", free.subList(0, Math.min(free.size(), SEG_LIST_MAX)));
        m.put("free_count", free.size());
        m.put("free_qty", free.stream().mapToLong(x -> R.l(x, "qty")).sum());
        m.put("free_truncated", free.size() > SEG_LIST_MAX);
        return m;
    }

    public Map<String, Object> segmentSns(
            CurrentUser cu,
            String piIn,
            String billIn,
            String seqPiIn,
            Long start,
            Long end,
            Integer page,
            Integer size,
            Integer billSeq) {
        cu.requireOperator();
        String[] k = billKey(cu, piIn, billIn);
        String seqPi = Util.trim(seqPiIn);
        if (seqPi.isEmpty()) {
            seqPi = k[0];
        }
        if (start == null || end == null || start < 1 || end < start) {
            throw Biz.err(SnErrorCode.GEN_SEQ_RANGE_INVALID);
        }
        int pg = Math.max(1, page == null ? 1 : page);
        int sz = Math.min(Math.max(1, size == null ? 20 : size), SN_PAGE_MAX);
        List<String> scope = cu.readFactories(null);
        long total = mapper.segmentSnCount(k[0], k[1], seqPi, start, end, billSeq, scope);
        List<String> items =
                mapper.segmentSns(k[0], k[1], seqPi, start, end, billSeq, scope, sz, (pg - 1) * sz);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("page", pg);
        m.put("size", sz);
        m.put("items", items);
        return m;
    }

    /** 规范化流水来源：MAX（默认）/ RANGE（只填起号按单个号）。 */
    static Map<String, Object> seqChoice(String mode, Long start, Long end) {
        String m = Util.trim(mode);
        if (m.isEmpty()) {
            m = SEQ_MAX;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (SEQ_MAX.equals(m)) {
            out.put("mode", SEQ_MAX);
            return out;
        }
        if (!SEQ_RANGE.equals(m)) {
            throw Biz.err(SnErrorCode.GEN_SEQ_RANGE_INVALID);
        }
        Long e = end == null ? start : end;
        if (start == null || start < 1 || e < start) {
            throw Biz.err(SnErrorCode.GEN_SEQ_RANGE_INVALID);
        }
        out.put("mode", SEQ_RANGE);
        out.put("start", start);
        out.put("end", e);
        return out;
    }

    /** 按流水来源依次切号：先用 free（升序的空闲段），用完从 overflow 起接着编。 */
    static final class Taker {
        final List<long[]> free;
        int i;
        long pos;
        long overflow;
        long inRange;
        long over;

        Taker(List<long[]> free, long overflow) {
            this.free = free;
            this.pos = free.isEmpty() ? 0 : free.getFirst()[0];
            this.overflow = overflow;
        }

        List<long[]> take(long n, Long floor) {
            List<long[]> out = new ArrayList<>();
            while (n > 0) {
                long a;
                long b;
                if (i < free.size()) {
                    long s = free.get(i)[0];
                    long e = free.get(i)[1];
                    a = Math.max(s, pos);
                    b = Math.min(e, a + n - 1);
                    inRange += b - a + 1;
                    if (b == e) {
                        i++;
                        pos = i < free.size() ? free.get(i)[0] : 0;
                    } else {
                        pos = b + 1;
                    }
                } else {
                    a = Math.max(overflow, floor == null ? 0 : floor);
                    b = a + n - 1;
                    over += n;
                    overflow = b + 1;
                }
                n -= b - a + 1;
                if (!out.isEmpty() && out.getLast()[1] + 1 == a) {
                    out.getLast()[1] = b;
                } else {
                    out.add(new long[] {a, b});
                }
            }
            return out;
        }
    }

    private Taker taker(String pi, long base, Map<String, Object> seq) {
        if (!SEQ_RANGE.equals(seq.get("mode"))) {
            return new Taker(new ArrayList<>(), base + 1);
        }
        long start = R.l(seq, "start");
        long end = R.l(seq, "end");
        List<long[]> free = new ArrayList<>();
        for (long[] se : gapSegments(pi, base)) {
            long a = Math.max(se[0], start);
            long b = Math.min(se[1], end);
            if (a <= b) {
                free.add(new long[] {a, b});
            }
        }
        if (end > base) {
            free.add(new long[] {Math.max(start, base + 1), end});
        }
        return new Taker(free, Math.max(base, end) + 1);
    }

    // ================================================================== 物料行与生成条件

    /** 一条参与生成的物料行。 */
    static final class GenLine {
        String billNo;
        int seq;
        String materialCode;
        String customerCode;
        String orgCode;
        String pi;
        long qty;
        long own;
        String length;
        String lengthSource;
        String unit;
        String unitSource;
        String attach;
        RuleServiceImpl.Resolved res;
        String block;
        List<String> issues = new ArrayList<>();

        long quota() {
            return Math.max(0, qty - own);
        }
    }

    List<GenLine> loadLines(String pi, List<String[]> keys) {
        if (keys.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> bills = new TreeSet<>();
        for (String[] k : keys) {
            bills.add(k[0]);
        }
        Map<String, Map<String, Object>> snap = new HashMap<>();
        for (Map<String, Object> r : mapper.prdLinesOf(bills)) {
            snap.put(R.key(r), r);
        }
        OrderServiceImpl.Extras extras = orders.lineExtrasOfBills(bills);
        Map<String, Map<String, Object>> sels = rules.selectionsOfBills(bills);
        RuleServiceImpl.Resolved def = rules.piDefault(pi);
        Map<Long, RuleServiceImpl.Resolved> memo = new HashMap<>();
        Map<String, Boolean> taken = new HashMap<>();
        Map<String, String> blocks = new HashMap<>();
        for (String b : bills) {
            blocks.put(b, changes.generationBlock(b));
        }
        List<GenLine> out = new ArrayList<>();
        for (String[] k : keys) {
            String key = R.key(k[0], Integer.parseInt(k[1]));
            Map<String, Object> r = snap.get(key);
            if (r == null) {
                throw Biz.err(SnErrorCode.ORDER_LINE_NOT_FOUND, "bill_no", k[0], "seq", k[1]);
            }
            if (!Objects.equals(R.s(r, "pi"), pi)) {
                throw Biz.err(SnErrorCode.GEN_SCOPE_INVALID);
            }
            Map<String, Object> c = extras.counts().get(key);
            GenLine line = new GenLine();
            line.billNo = R.s(r, "bill_no");
            line.seq = R.i(r, "seq");
            line.materialCode = R.s(r, "material_code");
            line.customerCode = R.s(r, "customer_code");
            line.orgCode = R.s(r, "org_code");
            line.pi = R.s(r, "pi");
            line.qty = OrderServiceImpl.snQty(r);
            line.own = c == null ? 0 : R.l(c, "own_generated");
            OrderServiceImpl.Effective len =
                    OrderServiceImpl.effectiveLength(r, extras.lengths().get(key));
            OrderServiceImpl.Effective unit =
                    OrderServiceImpl.effectiveUnit(r, extras.units().get(key));
            line.length = len.value();
            line.lengthSource = len.source();
            line.unit = unit.value();
            line.unitSource = unit.source();
            line.attach = OrderServiceImpl.effectiveAttach(r, extras.attaches().get(key)).value();
            line.res = rules.effective(pi, sels.get(key), def, true, memo);
            line.block = blocks.get(line.billNo);
            check(line, taken);
            out.add(line);
        }
        return out;
    }

    private void check(GenLine line, Map<String, Boolean> taken) {
        if (line.quota() <= 0) {
            return;
        }
        if (line.block != null) {
            line.issues.add(line.block);
        }
        if (line.res == null) {
            line.issues.add("NO_RULE");
        } else if (!line.res.ready()) {
            line.issues.add("RULE_PREFIX");
        } else if ((RuleServiceImpl.PLAN_NEW.equals(line.res.plan())
                        || RuleServiceImpl.PLAN_CLAIM.equals(line.res.plan()))
                && !prefixOk(line, taken)) {
            line.issues.add("RULE_PREFIX");
        }
        SnCodec.Spec spec = line.res != null && line.res.ready() ? line.res.spec() : null;
        if ("EMPTY".equals(line.lengthSource) && (spec == null || spec.withLength())) {
            line.issues.add("LENGTH_EMPTY");
        }
        if (spec != null && spec.withUnit() && "EMPTY".equals(line.unitSource)) {
            line.issues.add("UNIT_EMPTY");
        }
        if (line.attach == null || line.attach.isEmpty()) {
            line.issues.add("ATTACH_EMPTY");
        }
    }

    private boolean prefixOk(GenLine line, Map<String, Boolean> memo) {
        SnCodec.Spec spec = line.res.spec();
        Long own =
                RuleServiceImpl.PLAN_CLAIM.equals(line.res.plan())
                        ? R.lo(line.res.version(), "id")
                        : null;
        String k =
                spec.prefix()
                        + "\u0000"
                        + spec.base()
                        + "\u0000"
                        + spec.seqLen()
                        + "\u0000"
                        + spec.charset()
                        + "\u0000"
                        + line.pi
                        + "\u0000"
                        + own;
        return memo.computeIfAbsent(k, x -> rules.prefixUsable(spec.prefix(), spec, line.pi, own));
    }

    /** 附件项次的排号次序：按数字比大小；不是数字的排在数字之后、按文字比。 */
    static final Comparator<String> ATTACH_ORDER =
            (a, b) -> {
                String x = a == null ? "" : a.strip();
                String y = b == null ? "" : b.strip();
                boolean nx = ATTACH_NUM.matcher(x).matches();
                boolean ny = ATTACH_NUM.matcher(y).matches();
                if (nx && ny) {
                    return new BigDecimal(x).compareTo(new BigDecimal(y));
                }
                if (nx != ny) {
                    return nx ? -1 : 1;
                }
                if (!nx) {
                    return x.compareTo(y);
                }
                return 0;
            };

    private static List<GenLine> genOrder(List<GenLine> ls) {
        Map<String, Integer> rank = new LinkedHashMap<>();
        for (GenLine x : ls) {
            rank.putIfAbsent(x.billNo, rank.size());
        }
        List<GenLine> out = new ArrayList<>(ls);
        out.sort(
                Comparator.comparingInt((GenLine x) -> rank.get(x.billNo))
                        .thenComparing(x -> x.attach, ATTACH_ORDER)
                        .thenComparingInt(x -> x.seq));
        return out;
    }

    private List<String[]> scopeKeys(
            String pi, String scope, List<String> billsIn, List<LineRefDTO> linesIn) {
        if (SCOPE_BILLS.equals(scope)) {
            LinkedHashSet<String> nos = new LinkedHashSet<>();
            if (billsIn != null) {
                for (String b : billsIn) {
                    if (!Util.trim(b).isEmpty()) {
                        nos.add(Util.trim(b));
                    }
                }
            }
            if (nos.isEmpty()) {
                throw Biz.err(SnErrorCode.GEN_SCOPE_INVALID);
            }
            List<String[]> keys = new ArrayList<>();
            for (String no : nos) {
                List<Map<String, Object>> ls = orders.lines(no);
                if (ls.isEmpty()) {
                    throw Biz.err(SnErrorCode.ORDER_NOT_FOUND, "bill_no", no);
                }
                if (!Objects.equals(R.s(ls.getFirst(), "pi"), pi)) {
                    throw Biz.err(SnErrorCode.GEN_SCOPE_INVALID);
                }
                for (Map<String, Object> x : ls) {
                    keys.add(new String[] {no, Integer.toString(R.i(x, "seq"))});
                }
            }
            return keys;
        }
        if (SCOPE_LINES.equals(scope)) {
            LinkedHashMap<String, String[]> keys = new LinkedHashMap<>();
            if (linesIn != null) {
                for (LineRefDTO x : linesIn) {
                    String b = Util.trim(x.billNo);
                    int s = x.seq == null ? 0 : x.seq;
                    keys.putIfAbsent(R.key(b, s), new String[] {b, Integer.toString(s)});
                }
            }
            if (keys.isEmpty()) {
                throw Biz.err(SnErrorCode.GEN_SCOPE_INVALID);
            }
            return new ArrayList<>(keys.values());
        }
        throw Biz.err(SnErrorCode.GEN_SCOPE_INVALID);
    }

    // ================================================================== 预演

    private Map<String, Object> plan(
            String pi, String scope, List<String[]> keys, long base, Map<String, Object> seqIn) {
        Map<String, Object> seq = seqIn == null ? Map.of("mode", SEQ_MAX) : seqIn;
        List<GenLine> ls = genOrder(loadLines(pi, keys));
        List<GenLine> todo = ls.stream().filter(x -> x.quota() > 0).toList();
        long quota = todo.stream().mapToLong(GenLine::quota).sum();
        List<Map<String, Object>> issues = new ArrayList<>();
        for (GenLine x : todo) {
            if (!x.issues.isEmpty()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("bill_no", x.billNo);
                m.put("seq", x.seq);
                m.put("material_code", x.materialCode);
                m.put("issues", x.issues);
                m.put(
                        "prefix",
                        x.issues.contains("RULE_PREFIX") && x.res != null && x.res.ready()
                                ? R.s(x.res.version(), "prefix")
                                : null);
                issues.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pi_no", pi);
        out.put("scope", scope);
        out.put("quota", quota);
        out.put("issues", issues);
        out.put("items", new ArrayList<Map<String, Object>>());
        out.put("base_last_seq", base);
        out.put("seq", seq);
        if (todo.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bill_no", null);
            m.put("seq", null);
            m.put("material_code", null);
            m.put("issues", List.of("QUOTA_EMPTY"));
            out.put("issues", List.of(m));
            return out;
        }
        if (!issues.isEmpty()) {
            return out;
        }
        if (quota > settings.genMaxPerRun()) {
            throw Biz.err(
                    SnErrorCode.GEN_QTY_OVER_MAX, "qty", quota, "max", settings.genMaxPerRun());
        }
        Taker taker = taker(pi, base, seq);
        List<Map<String, Object>> items = items(out);
        for (GenLine x : todo) {
            long n = x.quota();
            SnCodec.Spec spec = x.res.spec();
            List<long[]> segs = taker.take(n, x.res.seqStart());
            long end = segs.getLast()[1];
            long mx = SnCodec.maxSeq(spec);
            if (end > mx) {
                throw Biz.err(SnErrorCode.GEN_RULE_EXHAUSTED, "max", mx, "end", end);
            }
            String length = spec.withLength() ? x.length : null;
            String unit = spec.withUnit() ? x.unit : null;
            String startSn = SnCodec.formatSn(segs.getFirst()[0], spec, length, unit);
            if (SnCodec.len(startSn) > SnCodec.SN_MAX) {
                throw Biz.err(SnErrorCode.RULE_SN_TOO_LONG);
            }
            RuleServiceImpl.Resolved res = x.res;
            List<Map<String, Object>> segList = new ArrayList<>();
            List<List<Long>> segPairs = new ArrayList<>();
            for (long[] ab : segs) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("start_seq", ab[0]);
                g.put("end_seq", ab[1]);
                g.put("qty", ab[1] - ab[0] + 1);
                g.put("start_sn", SnCodec.formatSn(ab[0], spec, length, unit));
                g.put("end_sn", SnCodec.formatSn(ab[1], spec, length, unit));
                g.put("start_seq_text", SnCodec.encodeSeq(ab[0], spec));
                g.put("end_seq_text", SnCodec.encodeSeq(ab[1], spec));
                segList.add(g);
                segPairs.add(List.of(ab[0], ab[1]));
            }
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("bill_no", x.billNo);
            it.put("seq", x.seq);
            it.put("material_code", x.materialCode);
            it.put("customer_code", x.customerCode);
            it.put("org_code", x.orgCode);
            it.put("length", length);
            it.put("unit", unit);
            it.put("attach", x.attach);
            it.put("prefix", spec.prefix());
            it.put("base", spec.base());
            it.put("seq_len", spec.seqLen());
            it.put("start_seq_text", segList.getFirst().get("start_seq_text"));
            it.put("end_seq_text", segList.getLast().get("end_seq_text"));
            it.put("line_qty", x.qty);
            it.put("own_before", x.own);
            it.put("qty", n);
            it.put("segs", segPairs);
            it.put("seg_list", segList);
            it.put("start_seq", segs.getFirst()[0]);
            it.put("end_seq", end);
            it.put("start_sn", startSn);
            it.put("end_sn", segList.getLast().get("end_sn"));
            it.put("rule_key", res.key());
            it.put("rule_plan", res.plan());
            it.put("rule_id", res.rule() == null ? null : R.l(res.rule(), "id"));
            it.put("version_id", R.lo(res.version(), "id"));
            it.put("custom", res.custom());
            it.put(
                    "rule_label",
                    (res.rule() != null
                                    ? R.s(res.rule(), "rule_code")
                                            + " v"
                                            + R.i(res.version(), "version")
                                    : "自定义")
                            + "（"
                            + RuleServiceImpl.formatText(spec)
                            + "）");
            items.add(it);
        }
        out.put("qty", quota);
        long startSeq = items.stream().mapToLong(x -> R.l(x, "start_seq")).min().orElse(0);
        long endSeq = items.stream().mapToLong(x -> R.l(x, "end_seq")).max().orElse(0);
        out.put("start_seq", startSeq);
        out.put("end_seq", endSeq);
        out.put("new_last", Math.max(base, endSeq));
        out.put("range_qty", taker.inRange);
        out.put("overflow_qty", SEQ_RANGE.equals(seq.get("mode")) ? taker.over : 0L);
        out.put("start_sn", items.getFirst().get("start_sn"));
        out.put("end_sn", items.getLast().get("end_sn"));
        return out;
    }

    public Map<String, Object> preview(
            CurrentUser cu,
            String piIn,
            String scopeIn,
            List<String> bills,
            List<LineRefDTO> lines,
            String seqMode,
            Long seqStart,
            Long seqEnd) {
        cu.requireOperator();
        String pi = Util.trim(piIn);
        if (pi.isEmpty()) {
            throw Biz.err(SnErrorCode.PI_REQUIRED);
        }
        String sc = Util.trim(scopeIn);
        if (sc.isEmpty()) {
            sc = SCOPE_BILLS;
        }
        Map<String, Object> seq = seqChoice(seqMode, seqStart, seqEnd);
        List<String[]> keys = scopeKeys(pi, sc, bills, lines);
        cu.checkWrite(
                orders.billOrgs(keys.stream().map(k -> k[0]).toList()).toArray(String[]::new));
        Map<String, Object> plan = plan(pi, sc, keys, lastSeq(pi), seq);
        Map<String, Object> gap = gapsOf(pi);
        Map<String, Object> out = new LinkedHashMap<>(plan);
        if (!((List<?>) plan.get("issues")).isEmpty()) {
            out.put("generatable", false);
            out.put("limits", limits());
            out.put("gaps", gap);
            return out;
        }
        LocalDateTime now = Util.now();
        String token = Util.tokenHex();
        LocalDateTime expires = now.plusMinutes(settings.previewTtlMinutes());
        Map<String, Object> stored = new LinkedHashMap<>(plan);
        List<List<Object>> keyList = new ArrayList<>();
        for (String[] k : keys) {
            keyList.add(List.of(k[0], Integer.parseInt(k[1])));
        }
        stored.put("keys", keyList);
        mapper.insertPreview(
                token,
                pi,
                R.l(plan, "qty"),
                R.l(plan, "base_last_seq"),
                dumps(stored),
                cu.empNo(),
                now,
                expires);
        out.put("generatable", true);
        out.put("token", token);
        out.put("expires_at", expires);
        out.put("limits", limits());
        out.put("gaps", gap);
        return out;
    }

    private static boolean samePlan(Map<String, Object> a, Map<String, Object> b) {
        List<Map<String, Object>> x = items(a);
        List<Map<String, Object>> y = items(b);
        if (x.size() != y.size()) {
            return false;
        }
        for (int i = 0; i < x.size(); i++) {
            for (String k : KEY_FIELDS) {
                if (!Objects.equals(dumps(x.get(i).get(k)), dumps(y.get(i).get(k)))) {
                    return false;
                }
            }
        }
        return true;
    }

    // ================================================================== 生成

    @SuppressWarnings("unchecked")
    public Map<String, Object> generate(CurrentUser cu, String token) {
        cu.requireOperator();
        Map<String, Object> p = Util.blank(token) ? null : mapper.preview(Util.trim(token));
        if (p == null || !Objects.equals(R.s(p, "created_by"), cu.empNo())) {
            throw Biz.err(SnErrorCode.GEN_PREVIEW_REQUIRED);
        }
        if (mapper.previewUsed(R.s(p, "token"))) {
            throw Biz.err(SnErrorCode.GEN_PREVIEW_USED);
        }
        if (Util.now().isAfter(R.t(p, "expires_at"))) {
            throw Biz.err(SnErrorCode.GEN_PREVIEW_EXPIRED);
        }
        String pi = R.s(p, "pi_no");
        NamedLockService.Held held = locks.piLock(pi);
        long jobId;
        Map<String, Object> fresh;
        try {
            Map<String, Object> stored = loads(R.s(p, "plan_json"));
            long actual = lastSeq(pi);
            if (actual != R.l(p, "base_last_seq")) {
                throw Biz.err(
                        SnErrorCode.GEN_PREVIEW_STALE,
                        "pi",
                        pi,
                        "expected",
                        R.l(p, "base_last_seq"),
                        "actual",
                        actual);
            }
            List<String[]> keys = new ArrayList<>();
            for (Object k : (List<Object>) stored.get("keys")) {
                List<Object> kk = (List<Object>) k;
                keys.add(new String[] {String.valueOf(kk.get(0)), String.valueOf(kk.get(1))});
            }
            cu.checkWrite(
                    orders.billOrgs(keys.stream().map(k -> k[0]).toList()).toArray(String[]::new));
            fresh =
                    plan(
                            pi,
                            R.s(stored, "scope"),
                            keys,
                            actual,
                            (Map<String, Object>) stored.get("seq"));
            for (Object o : (List<Object>) fresh.get("issues")) {
                Map<String, Object> i = (Map<String, Object>) o;
                if (i.get("prefix") != null) {
                    rules.checkPrefix(R.s(i, "prefix"), null, null, pi, false);
                }
            }
            if (!((List<?>) fresh.get("issues")).isEmpty() || !samePlan(stored, fresh)) {
                throw Biz.err(SnErrorCode.GEN_PREVIEW_CHANGED);
            }
            GenJobEntity e = new GenJobEntity();
            e.genBatch = Util.nextId("G") + Util.tokenHex().substring(0, 4);
            e.piNo = pi;
            e.scope = R.s(stored, "scope");
            e.qty = R.l(fresh, "qty");
            e.startSeqDec = R.l(fresh, "start_seq");
            e.endSeqDec = R.l(fresh, "end_seq");
            e.startSn = R.s(fresh, "start_sn");
            e.endSn = R.s(fresh, "end_sn");
            e.previewToken = R.s(p, "token");
            e.planJson = dumps(fresh);
            e.createdBy = cu.empNo();
            e.createdAt = Util.now();
            try {
                mapper.insertJob(e);
            } catch (RuntimeException ex) {
                if (!DbErrors.isDuplicate(ex)) {
                    throw ex;
                }
                if (DbErrors.mentions(ex, "ux_job_preview")) {
                    throw Biz.err(SnErrorCode.GEN_PREVIEW_USED);
                }
                throw Biz.err(SnErrorCode.GEN_BUSY, "pi", pi);
            }
            jobId = e.id;
        } catch (RuntimeException ex) {
            held.release();
            throw ex;
        }
        if (R.l(fresh, "qty") <= settings.genSyncThreshold()) {
            try {
                run(cu, jobId, true);
            } finally {
                held.release();
            }
        } else {
            long id = jobId;
            try {
                background.submit(
                        "sn-gen",
                        () -> {
                            try {
                                run(cu, id, false);
                            } finally {
                                held.release();
                            }
                        });
            } catch (RuntimeException ex) {
                held.release();
                throw ex;
            }
        }
        return jobJson(mapper.job(jobId));
    }

    private void run(CurrentUser cu, long jobId, boolean rethrow) {
        Map<String, Object> j = mapper.job(jobId);
        Map<String, Object> plan = loads(R.s(j, "plan_json"));
        Semaphore s = genSlots();
        boolean got = false;
        int[] attempt = {0};
        try {
            if (rethrow) {
                got = s.tryAcquire(SLOT_WAIT_SECONDS, TimeUnit.SECONDS);
            } else {
                s.acquire();
                got = true;
            }
            if (!got) {
                throw Biz.err(SnErrorCode.CONFLICT_RETRY);
            }
            mapper.jobStarted(jobId, Util.now());
            DbErrors.retryDeadlock(
                    () -> {
                        attempt[0]++;
                        if (attempt[0] > 1) {
                            log.warn(
                                    "gen_deadlock_retry job={} pi={} attempt={}",
                                    jobId,
                                    R.s(j, "pi_no"),
                                    attempt[0]);
                        }
                        tx.exec(
                                () -> {
                                    generateInTx(cu, j, plan);
                                    int n = mapper.jobSuccess(jobId, Util.now());
                                    if (n != 1) {
                                        if (cancelRequested(jobId)) {
                                            throw Biz.err(SnErrorCode.GEN_CANCELED);
                                        }
                                        throw Biz.err(SnErrorCode.CONFLICT_RETRY);
                                    }
                                });
                        return null;
                    },
                    DbErrors.DEADLOCK_ATTEMPTS,
                    false);
            log.info(
                    "gen_done job={} pi={} qty={} attempts={}",
                    jobId,
                    R.s(j, "pi_no"),
                    R.l(j, "qty"),
                    attempt[0]);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            failJob(cu, j, plan, Biz.err(SnErrorCode.CONFLICT_RETRY), rethrow);
        } catch (RuntimeException e) {
            failJob(cu, j, plan, e, rethrow);
            if (rethrow) {
                throw e;
            }
        } finally {
            if (got) {
                s.release();
            }
        }
    }

    private void failJob(
            CurrentUser cu,
            Map<String, Object> j,
            Map<String, Object> plan,
            RuntimeException e,
            boolean rethrow) {
        long jobId = R.l(j, "id");
        String code =
                e instanceof BusinessException be && be.errorCode() instanceof SnErrorCode sc
                        ? sc.name()
                        : "INTERNAL";
        boolean canceled = SnErrorCode.GEN_CANCELED.name().equals(code);
        String msg =
                Util.cut(
                        e instanceof BusinessException be
                                ? Biz.message(be)
                                : (e.getMessage() == null
                                        ? e.getClass().getSimpleName()
                                        : e.getMessage()),
                        1000);
        try {
            mapper.jobFailed(jobId, canceled ? CANCELED : FAILED, code, msg, Util.now());
        } catch (RuntimeException ex) {
            log.error("gen_job_status_update_failed job={}", jobId, ex);
        }
        if (canceled || !rethrow) {
            for (Map.Entry<String, List<Map<String, Object>>> en : byBill(items(plan)).entrySet()) {
                List<Map<String, Object>> its = en.getValue();
                audit.recordIsolated(
                        cu,
                        AuditEntryDTO.of(AuditAction.SN_GENERATE)
                                .fail(msg)
                                .pi(R.s(j, "pi_no"))
                                .bill(en.getKey())
                                .factory(R.s(its.getFirst(), "org_code"))
                                .count(its.stream().mapToLong(x -> R.l(x, "qty")).sum())
                                .range(
                                        R.s(its.getFirst(), "start_sn"),
                                        R.s(its.getLast(), "end_sn"))
                                .batch(R.s(j, "gen_batch"))
                                .info("job=" + jobId));
            }
        }
        if (!(e instanceof BusinessException)) {
            log.error(
                    "gen_failed job={} pi={} deadlock={}",
                    jobId,
                    R.s(j, "pi_no"),
                    DbErrors.isDeadlock(e),
                    e);
        }
    }

    static Map<String, List<Map<String, Object>>> byBill(List<Map<String, Object>> items) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map<String, Object> x : items) {
            out.computeIfAbsent(R.s(x, "bill_no"), k -> new ArrayList<>()).add(x);
        }
        return out;
    }

    private Map<String, Object> lockCounterRow(String pi, LocalDateTime now) {
        if (!mapper.counterExists(pi)) {
            mapper.insertCounter(pi, now);
        }
        return mapper.lockCounter(pi);
    }

    private Map<String, Object> lockLineCountRow(
            String bill, int seq, String material, LocalDateTime now) {
        if (!mapper.lineCountExists(bill, seq)) {
            mapper.insertLineCount(bill, seq, material, now);
        }
        return mapper.lockLineCount(bill, seq);
    }

    private void generateInTx(CurrentUser cu, Map<String, Object> j, Map<String, Object> plan) {
        LocalDateTime now = Util.now();
        String pi = R.s(j, "pi_no");
        long jobId = R.l(j, "id");
        Map<String, Object> c = lockCounterRow(pi, now);
        if (!RUNNING.equals(mapper.jobStatus(jobId))) {
            throw Biz.err(SnErrorCode.CONFLICT_RETRY);
        }
        if (cancelRequested(jobId)) {
            throw Biz.err(SnErrorCode.GEN_CANCELED);
        }
        if (R.l(c, "last_seq_dec") != R.l(plan, "base_last_seq")) {
            throw Biz.err(
                    SnErrorCode.GEN_PREVIEW_STALE,
                    "pi",
                    pi,
                    "expected",
                    R.l(plan, "base_last_seq"),
                    "actual",
                    R.l(c, "last_seq_dec"));
        }
        List<Map<String, Object>> items = items(plan);
        for (Map<String, Object> x : items) {
            Map<String, Object> lc =
                    lockLineCountRow(
                            R.s(x, "bill_no"), R.i(x, "seq"), R.s(x, "material_code"), now);
            Map<String, Object> line = mapper.lineQty(R.s(x, "bill_no"), R.i(x, "seq"));
            if (line == null
                    || OrderServiceImpl.snQty(line) - R.l(lc, "own_generated") < R.l(x, "qty")) {
                throw Biz.err(SnErrorCode.GEN_PREVIEW_CHANGED);
            }
        }
        Map<String, Map<String, Object>> versions = new HashMap<>();
        for (Map<String, Object> x : items) {
            String rk = R.s(x, "rule_key");
            if (versions.containsKey(rk)) {
                continue;
            }
            List<String[]> same = new ArrayList<>();
            for (Map<String, Object> y : items) {
                if (rk.equals(R.s(y, "rule_key"))) {
                    same.add(new String[] {R.s(y, "bill_no"), Integer.toString(R.i(y, "seq"))});
                }
            }
            versions.put(rk, rules.realize(cu, pi, resolvedOf(pi, x), same));
        }
        for (Map<String, Object> x : items) {
            mapper.clearLineStart(R.s(x, "bill_no"), R.i(x, "seq"));
        }
        for (String no : new TreeSet<>(byBill(items).keySet())) {
            changes.ensureLedger(no);
            changes.lockLedgerSuspend(no);
        }
        long done = 0;
        String batch = R.s(j, "gen_batch");
        for (Map<String, Object> x : items) {
            Map<String, Object> v = versions.get(R.s(x, "rule_key"));
            SnCodec.Spec spec = RuleServiceImpl.specOf(v);
            for (long[] se : chunks(itemSegs(x))) {
                List<String> sns = new ArrayList<>();
                List<Map<String, Object>> rows = new ArrayList<>();
                for (long q = se[0]; q <= se[1]; q++) {
                    String sn = SnCodec.formatSn(q, spec, R.s(x, "length"), R.s(x, "unit"));
                    sns.add(sn);
                    Map<String, Object> r = new HashMap<>();
                    r.put("sn", sn);
                    r.put("seqDec", q);
                    rows.add(r);
                }
                Map<String, Object> xp = new HashMap<>();
                xp.put("customerCode", R.s(x, "customer_code"));
                xp.put("billNo", R.s(x, "bill_no"));
                xp.put("seq", R.i(x, "seq"));
                xp.put("materialCode", R.s(x, "material_code"));
                xp.put("orgCode", R.s(x, "org_code"));
                xp.put("length", R.s(x, "length"));
                xp.put("attach", R.s(x, "attach"));
                try {
                    mapper.insertItems(rows, xp, pi, R.l(v, "id"), batch, now);
                } catch (RuntimeException ex) {
                    if (!DbErrors.isDuplicate(ex)) {
                        throw ex;
                    }
                    Map<String, Object> hit = mapper.duplicateHit(sns);
                    if (hit == null) {
                        throw Biz.err(SnErrorCode.CONFLICT_RETRY);
                    }
                    if (Util.ORIGIN_IMPORT.equals(R.s(hit, "origin"))) {
                        throw Biz.err(
                                SnErrorCode.GEN_IMPORTED_SN,
                                "sn",
                                R.s(hit, "sn"),
                                "pi",
                                R.s(hit, "pi_no"));
                    }
                    throw Biz.err(
                            SnErrorCode.GEN_DUPLICATE_SN,
                            "sn",
                            R.s(hit, "sn"),
                            "pi",
                            R.s(hit, "pi_no"));
                }
                continueDeletedRev(sns);
                billStats.touch(pi, R.s(x, "bill_no"));
                done += sns.size();
                if (progress(jobId, done, sns.getLast())) {
                    throw Biz.err(SnErrorCode.GEN_CANCELED);
                }
            }
            mapper.addLineCount(
                    R.s(x, "bill_no"), R.i(x, "seq"), R.s(x, "material_code"), R.l(x, "qty"), now);
        }
        long newLast = plan.containsKey("new_last") ? R.l(plan, "new_last") : R.l(plan, "end_seq");
        mapper.advanceCounter(pi, newLast, R.l(plan, "qty"), now);
        changes.refreshItems(byBill(items).keySet(), true);
        for (Map.Entry<String, List<Map<String, Object>>> en : byBill(items).entrySet()) {
            List<Map<String, Object>> its = en.getValue();
            List<String> parts = new ArrayList<>();
            for (Map<String, Object> x : its) {
                parts.add(
                        R.i(x, "seq")
                                + ":"
                                + R.l(x, "qty")
                                + "@"
                                + segsText(x)
                                + "["
                                + R.l(versions.get(R.s(x, "rule_key")), "id")
                                + "]");
            }
            audit.record(
                    cu,
                    AuditEntryDTO.of(AuditAction.SN_GENERATE)
                            .factory(R.s(its.getFirst(), "org_code"))
                            .pi(pi)
                            .customer(R.s(its.getFirst(), "customer_code"))
                            .bill(en.getKey())
                            .range(R.s(its.getFirst(), "start_sn"), R.s(its.getLast(), "end_sn"))
                            .count(its.stream().mapToLong(x -> R.l(x, "qty")).sum())
                            .status(null, Util.PENDING_ALLOC)
                            .batch(batch)
                            .info("job=" + jobId + " lines=" + String.join(",", parts)));
        }
    }

    /** 刚写入号池的号里以前删过同样 SN 的：rev 接着删号存档里最大的 rev 加 1。 */
    public void continueDeletedRev(List<String> sns) {
        if (!sns.isEmpty()) {
            mapper.continueDeletedRev(sns);
        }
    }

    static List<long[]> chunks(List<long[]> segs) {
        List<long[]> out = new ArrayList<>();
        for (long[] ab : segs) {
            long s = ab[0];
            while (s <= ab[1]) {
                long e = Math.min(ab[1], s + CHUNK - 1);
                out.add(new long[] {s, e});
                s = e + 1;
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static String segsText(Map<String, Object> x) {
        Object sl = x.get("seg_list");
        List<Map<String, Object>> segs =
                sl instanceof List<?> l && !l.isEmpty()
                        ? (List<Map<String, Object>>) sl
                        : oneSeg(x);
        List<String> out = new ArrayList<>();
        for (Map<String, Object> g : segs) {
            out.add(R.s(g, "start_sn") + "~" + R.s(g, "end_sn"));
        }
        return String.join(";", out);
    }

    @SuppressWarnings("unchecked")
    private RuleServiceImpl.Resolved resolvedOf(String pi, Map<String, Object> x) {
        if (RuleServiceImpl.PLAN_NEW.equals(R.s(x, "rule_plan"))) {
            return rules.customResolved(
                    RuleServiceImpl.SRC_LINE, (Map<String, Object>) x.get("custom"));
        }
        Map<String, Object> rule = mapper.ruleLock(R.l(x, "rule_id"));
        if (rule == null) {
            throw Biz.err(SnErrorCode.GEN_PREVIEW_CHANGED);
        }
        RuleServiceImpl.Resolved res = rules.resolve(rule, pi, RuleServiceImpl.SRC_LINE);
        if (res.version() == null || R.l(res.version(), "id") != R.l(x, "version_id")) {
            throw Biz.err(SnErrorCode.GEN_PREVIEW_CHANGED);
        }
        return res;
    }

    // ================================================================== 中断回收

    @Override
    public String name() {
        return "sn-gen-recover";
    }

    @Override
    public long intervalSeconds() {
        return RECOVER_INTERVAL_SECONDS;
    }

    @Override
    public void run() {
        recoverInterrupted();
    }

    /** 仍是 RUNNING、但执行它的进程已退出的任务标为失败并释放 running_pi（拿得到 PI 操作锁即说明没有实例在执行）。 */
    public int recoverInterrupted() {
        int recovered = 0;
        for (Map<String, Object> j : mapper.runningJobs()) {
            long id = R.l(j, "id");
            String pi = R.s(j, "pi_no");
            Optional<NamedLockService.Held> held = locks.tryAcquire("pi", List.of(pi), 0);
            if (held.isEmpty()) {
                continue;
            }
            try {
                Boolean ok =
                        tx.isolated(
                                () -> {
                                    Map<String, Object> row = mapper.jobStatusNowait(id);
                                    if (row == null || !RUNNING.equals(R.s(row, "status"))) {
                                        return false;
                                    }
                                    mapper.counterNowait(pi);
                                    mapper.markInterrupted(
                                            id,
                                            "执行任务的服务已退出（重启、下线或宕机），任务未完成，已整批回滚，请重新预演后生成",
                                            Util.now());
                                    return true;
                                });
                if (Boolean.TRUE.equals(ok)) {
                    recovered++;
                }
            } catch (RuntimeException e) {
                if (!DbErrors.isNowait(e)) {
                    throw e;
                }
                log.info("gen_job_still_running job={} pi={}", id, pi);
            } finally {
                held.get().release();
            }
        }
        if (recovered > 0) {
            log.warn("gen_jobs_recovered count={}", recovered);
        }
        return recovered;
    }

    private boolean progress(long jobId, long done, String current) {
        try {
            return tx.isolated(
                    () -> {
                        mapper.jobProgress(jobId, done, current);
                        return mapper.cancelAt(jobId) != null;
                    });
        } catch (RuntimeException e) {
            log.debug("progress_update_failed job={}", jobId);
            return false;
        }
    }

    private boolean cancelRequested(long jobId) {
        return Boolean.TRUE.equals(tx.isolated(() -> mapper.cancelAt(jobId) != null));
    }

    // ================================================================== 进度 / 取消

    private Map<String, Object> jobByBatch(String batch, boolean lock) {
        Map<String, Object> j =
                Util.blank(batch)
                        ? null
                        : (lock
                                ? mapper.jobByBatchLock(Util.trim(batch))
                                : mapper.jobByBatch(Util.trim(batch)));
        if (j == null) {
            throw Biz.err(SnErrorCode.GEN_JOB_NOT_FOUND);
        }
        return j;
    }

    private Set<String> orgsOf(List<Map<String, Object>> items) {
        Set<String> out = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> x : items) {
            if (!Util.blank(R.s(x, "org_code"))) {
                out.add(R.s(x, "org_code"));
            } else {
                missing.add(R.s(x, "bill_no"));
            }
        }
        if (!missing.isEmpty()) {
            out.addAll(orders.billOrgs(missing));
        }
        return out;
    }

    static boolean inScope(List<String> scope, Map<String, Object> x) {
        String org = R.s(x, "org_code");
        return scope == null || scope.contains(Util.blank(org) ? "-" : org);
    }

    public Map<String, Object> progress(CurrentUser cu, String batch) {
        if (cu != null) {
            cu.requireOperator();
        }
        Map<String, Object> j = jobByBatch(batch, false);
        if (cu != null) {
            cu.checkRead(orgsOf(items(loads(R.s(j, "plan_json")))).toArray(String[]::new));
        }
        Map<String, Object> out = jobJson(j);
        Long elapsed = null;
        LocalDateTime started = R.t(j, "started_at");
        if (started != null) {
            LocalDateTime end = R.t(j, "finished_at") != null ? R.t(j, "finished_at") : Util.now();
            elapsed = Math.max(1, Duration.between(started, end).getSeconds());
        }
        long doneQty = R.l(j, "done_qty");
        long qty = R.l(j, "qty");
        Double speed = elapsed == null || doneQty == 0 ? null : (double) doneQty / elapsed;
        out.put("percent", qty == 0 ? 0 : Math.min(100, (int) (doneQty * 100 / qty)));
        out.put("speed_per_sec", speed == null ? null : Math.round(speed * 10) / 10.0);
        out.put(
                "eta_seconds",
                speed == null || !RUNNING.equals(R.s(j, "status"))
                        ? null
                        : (long) (Math.max(0, qty - doneQty) / speed));
        return out;
    }

    public Map<String, Object> cancel(CurrentUser cu, String batch) {
        cu.requireOperator();
        String b =
                tx.run(
                        () -> {
                            Map<String, Object> j = jobByBatch(batch, true);
                            cu.checkWrite(
                                    orgsOf(items(loads(R.s(j, "plan_json"))))
                                            .toArray(String[]::new));
                            if (!RUNNING.equals(R.s(j, "status"))) {
                                throw Biz.err(SnErrorCode.GEN_CANCEL_NOT_RUNNING);
                            }
                            if (R.v(j, "cancel_at") == null) {
                                mapper.requestCancel(R.l(j, "id"), Util.now(), cu.empNo());
                                audit.record(
                                        cu,
                                        AuditEntryDTO.of(AuditAction.SN_GEN_CANCEL)
                                                .pi(R.s(j, "pi_no"))
                                                .range(R.s(j, "start_sn"), R.s(j, "end_sn"))
                                                .count(R.l(j, "qty"))
                                                .batch(R.s(j, "gen_batch"))
                                                .status(RUNNING, CANCELED)
                                                .info(
                                                        "job="
                                                                + R.l(j, "id")
                                                                + " done="
                                                                + R.l(j, "done_qty")
                                                                + "/"
                                                                + R.l(j, "qty")));
                            }
                            return R.s(j, "gen_batch");
                        });
        return progress(null, b);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> jobs(String piIn, int limit, List<String> scope) {
        String pi = Util.trim(piIn);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> j : mapper.jobsOfPi(pi, Math.min(Math.max(limit, 1), 200))) {
            List<Map<String, Object>> its = items(loads(R.s(j, "plan_json")));
            if (its.stream().noneMatch(x -> inScope(scope, x))) {
                continue;
            }
            Map<String, Object> v = jobJson(j);
            Set<String> keep = new LinkedHashSet<>();
            for (Map<String, Object> x : its) {
                if (inScope(scope, x)) {
                    keep.add(R.key(R.s(x, "bill_no"), R.i(x, "seq")));
                }
            }
            List<Map<String, Object>> lines = new ArrayList<>();
            for (Map<String, Object> l : (List<Map<String, Object>>) v.get("lines")) {
                if (keep.contains(R.key(R.s(l, "bill_no"), R.i(l, "seq")))) {
                    lines.add(l);
                }
            }
            v.put("lines", lines);
            out.add(v);
        }
        return out;
    }
}
