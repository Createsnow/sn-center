package com.adtek.sn.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.domain.RuleEntity;
import com.adtek.sn.domain.RuleVersionEntity;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.dto.CustomRuleDTO;
import com.adtek.sn.dto.LineRefDTO;
import com.adtek.sn.dto.LineSelectDTO;
import com.adtek.sn.dto.PiBindDTO;
import com.adtek.sn.dto.PiLinesDTO;
import com.adtek.sn.dto.RuleCreateDTO;
import com.adtek.sn.dto.RulePreviewDTO;
import com.adtek.sn.dto.RuleSpecDTO;
import com.adtek.sn.dto.RuleUpdateDTO;
import com.adtek.sn.mapper.RuleMapper;
import com.adtek.sn.service.RuleService;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.PyJson;
import com.adtek.sn.support.R;
import com.adtek.sn.support.SnCodec;
import com.adtek.sn.support.SnLocks;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;
import com.adtek.sn.vo.CustomRuleVO;
import com.adtek.sn.vo.RuleActionVO;
import com.adtek.sn.vo.RuleInfoVO;
import com.adtek.sn.vo.RuleOptionVO;
import com.adtek.sn.vo.RulePreviewVO;
import com.adtek.sn.vo.RuleVersionVO;
import com.adtek.sn.vo.RuleViewVO;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * SN 规则库与规则选取（原系统 app/services/rules.py）。
 *
 * <p>全局不撞号：前缀归属 PI + PI 流水唯一。一个前缀只归属一张 PI（版本首次用于生成时归属）；别的 PI 要用同一条规则须为它填新前缀，另出属于它的
 * 版本。同一前缀下进制、流水位数、字符表锁定；任何两个不同的前缀都不能一个是另一个的开头。规则按物料行生效：物料行已选 ＞ PI 绑定。 自定义规则生成成功后存入规则库并归属本 PI。
 */
@Service
public class RuleServiceImpl implements RuleService {
    public static final String NONE = "NONE";
    public static final String CUSTOMER = "CUSTOMER";
    public static final String PI = "PI";
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern CODE_UNSAFE = Pattern.compile("[^A-Za-z0-9_-]");
    public static final int CUSTOM_SEQ_LEN = 4;
    public static final String SRC_LINE = "LINE";
    public static final String SRC_PI = "PI";
    public static final String MODE_LIBRARY = "LIBRARY";
    public static final String MODE_CUSTOM = "CUSTOM";
    public static final String PLAN_USE = "USE";
    public static final String PLAN_CLAIM = "CLAIM";
    public static final String PLAN_NEW = "NEW";
    public static final String PLAN_PREFIX = "PREFIX";
    static final String SAMPLE_LENGTH = "100";
    static final String SAMPLE_UNIT = "M";

    private final RuleMapper mapper;
    private final FactoryServiceImpl factories;
    private final OrderServiceImpl orders;
    private final AuditServiceImpl audit;
    private final SnLocks locks;
    private final SnSettings settings;
    private final Tx tx;

    /** 已被号引用的版本参数不可变，可放心缓存。 */
    private final Map<Long, SnCodec.Spec> specCache = new ConcurrentHashMap<>();

    public RuleServiceImpl(
            RuleMapper mapper,
            FactoryServiceImpl factories,
            @Lazy OrderServiceImpl orders,
            AuditServiceImpl audit,
            SnLocks locks,
            SnSettings settings,
            Tx tx) {
        this.mapper = mapper;
        this.factories = factories;
        this.orders = orders;
        this.audit = audit;
        this.locks = locks;
        this.settings = settings;
        this.tx = tx;
    }

    private CurrentUser operator() {
        return CurrentUser.current(settings.initAdminEmpNo()).requireOperator();
    }

    // ------------------------------------------------------------------ 规格

    public static SnCodec.Spec specOf(Map<String, Object> v) {
        return new SnCodec.Spec(
                R.s(v, "prefix"),
                R.s(v, "suffix"),
                R.i(v, "base"),
                R.i(v, "seq_len"),
                R.s(v, "charset"),
                R.b(v, "with_length"),
                R.i(v, "length_pad"),
                R.sz(v, "length_sep_before"),
                R.sz(v, "length_sep_after"),
                R.b(v, "with_unit"));
    }

    /** 格式说明：HX + 6 位 10 进制流水 + - + 米数（补足 5 位）+ M + 单位 + A。 */
    public static String formatText(SnCodec.Spec s) {
        List<String> parts = new ArrayList<>();
        parts.add(s.prefix());
        parts.add(s.seqLen() + " 位 " + s.base() + " 进制流水");
        if (s.withLength()) {
            if (!s.sepBefore().isEmpty()) {
                parts.add(s.sepBefore());
            }
            parts.add(s.lengthPad() > 0 ? "米数（补足 " + s.lengthPad() + " 位）" : "米数");
            if (!s.sepAfter().isEmpty()) {
                parts.add(s.sepAfter());
            }
        }
        if (s.withUnit()) {
            parts.add("单位");
        }
        if (!s.suffix().isEmpty()) {
            parts.add(s.suffix());
        }
        return String.join(" + ", parts);
    }

    /** 规范化并校验编码参数；字符表留空用该进制默认字符表。 */
    static SnCodec.Spec normalize(
            String prefix,
            String suffix,
            Integer base,
            Integer seqLen,
            String charset,
            Boolean withLength,
            Integer lengthPad,
            String sepBefore,
            String sepAfter,
            Boolean withUnit) {
        if (base == null || !SnCodec.DEFAULT_CHARSETS.containsKey(base)) {
            throw Biz.err(SnErrorCode.RULE_BASE_INVALID);
        }
        if (seqLen == null) {
            throw Biz.err(SnErrorCode.RULE_SEQ_LEN_INVALID);
        }
        String cs = Util.trim(charset);
        if (cs.isEmpty()) {
            cs = SnCodec.DEFAULT_CHARSETS.get(base);
        }
        boolean wl = withLength == null || withLength;
        SnCodec.Spec spec =
                new SnCodec.Spec(
                        Util.trim(prefix),
                        Util.trim(suffix),
                        base,
                        seqLen,
                        cs,
                        wl,
                        wl ? (lengthPad == null ? 0 : lengthPad) : 0,
                        wl ? Util.trim(sepBefore) : "",
                        wl ? Util.trim(sepAfter) : "",
                        withUnit != null && withUnit);
        SnErrorCode err = SnCodec.validate(spec);
        if (err != null) {
            throw Biz.err(err);
        }
        return spec;
    }

    static SnCodec.Spec normalize(RuleSpecDTO s) {
        return normalize(
                s.prefix,
                s.suffix,
                s.base,
                s.seqLen,
                s.charset,
                s.withLength,
                s.lengthPad,
                s.sepBefore,
                s.sepAfter,
                s.withUnit);
    }

    static boolean sameSeqFormat(SnCodec.Spec a, SnCodec.Spec b) {
        return a.base() == b.base()
                && a.seqLen() == b.seqLen()
                && Objects.equals(a.charset(), b.charset());
    }

    // ------------------------------------------------------------------ 前缀

    /** 前缀能否给一个（归属 pi 的；pi = null 为尚未归属）版本用（见原系统 check_prefix）。 */
    public void checkPrefix(
            String prefix, Long excludeVersion, SnCodec.Spec spec, String pi, boolean gen) {
        locks.holdPrefix(prefix);
        if (!gen) {
            prefixRules(prefix, spec, pi, excludeVersion, true);
            return;
        }
        prefixRules(prefix, spec, pi, excludeVersion, false);
        tx.execIsolated(() -> prefixRules(prefix, spec, pi, excludeVersion, false));
    }

    private void prefixRules(
            String prefix, SnCodec.Spec spec, String pi, Long exclude, boolean lock) {
        List<Map<String, Object>> same =
                lock
                        ? mapper.prefixRulesLock(prefix, exclude)
                        : mapper.prefixRules(prefix, exclude);
        for (Map<String, Object> v : same) {
            if (pi == null || !pi.equals(R.s(v, "claimed_pi"))) {
                throw Biz.err(SnErrorCode.RULE_PREFIX_CONFLICT, "prefix", prefix);
            }
            if (spec != null && !sameSeqFormat(specOf(v), spec)) {
                throw Biz.err(
                        SnErrorCode.RULE_PREFIX_FORMAT_LOCKED,
                        "prefix",
                        prefix,
                        "base",
                        R.i(v, "base"),
                        "seq_len",
                        R.i(v, "seq_len"));
            }
        }
        for (Map<String, Object> v : overlapping(prefix)) {
            String claimed = R.s(v, "claimed_pi");
            throw Biz.err(
                    SnErrorCode.RULE_PREFIX_OVERLAP,
                    "prefix",
                    prefix,
                    "other",
                    R.s(v, "prefix"),
                    "pi",
                    Util.blank(claimed) ? "-" : claimed);
        }
    }

    private List<Map<String, Object>> overlapping(String prefix) {
        List<String> heads = new ArrayList<>();
        int n = prefix.codePointCount(0, prefix.length());
        for (int k = 1; k < n; k++) {
            heads.add(prefix.substring(0, prefix.offsetByCodePoints(0, k)));
        }
        return mapper.overlapping(Util.likePrefix(prefix), prefix, heads);
    }

    /** check_prefix 的判断版（不加锁、不抛错）。 */
    public boolean prefixUsable(String prefix, SnCodec.Spec spec, String pi, Long excludeVersion) {
        try {
            prefixRules(prefix, spec, pi, excludeVersion, false);
        } catch (BusinessException e) {
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ 解析

    /** 某物料行（或 PI 默认）将用的规则；version 为 null 表示须为本 PI 换前缀（plan = PREFIX）。 */
    public record Resolved(
            String source,
            String mode,
            String plan,
            Map<String, Object> rule,
            Map<String, Object> version,
            Map<String, Object> custom,
            String otherPi,
            Long seqStart) {
        public boolean ready() {
            return version != null;
        }

        public SnCodec.Spec spec() {
            return specOf(version);
        }

        /** 同一次生成里规则相同的物料行共用同一版：自定义规则按参数、规则库按版本。 */
        public String key() {
            if (PLAN_NEW.equals(plan)) {
                return "C:" + PyJson.dumps(custom, true);
            }
            return "V:" + (version == null ? "None" : String.valueOf(R.l(version, "id")));
        }

        public Resolved withSeqStart(Long start) {
            return new Resolved(source, mode, plan, rule, version, custom, otherPi, start);
        }
    }

    private Map<String, Object> currentOf(Map<String, Object> rule, List<Map<String, Object>> vs) {
        int cur = R.i(rule, "current_version");
        for (Map<String, Object> v : vs) {
            if (R.i(v, "version") == cur) {
                return v;
            }
        }
        return null;
    }

    public Map<String, Object> currentVersion(Map<String, Object> r) {
        return mapper.currentVersion(R.l(r, "id"), R.i(r, "current_version"));
    }

    public Resolved resolve(Map<String, Object> rule, String pi, String source) {
        return resolveIn(rule, mapper.versionsOf(R.l(rule, "id")), pi, source);
    }

    private Resolved resolveIn(
            Map<String, Object> rule, List<Map<String, Object>> vs, String pi, String source) {
        Map<String, Object> t = currentOf(rule, vs);
        if (t == null) {
            throw Biz.err(SnErrorCode.RULE_VERSION_NOT_FOUND);
        }
        Map<String, Object> own = null;
        for (Map<String, Object> v : vs) {
            if (Objects.equals(R.lo(v, "derived_from"), R.lo(t, "id"))
                    && Objects.equals(R.s(v, "claimed_pi"), pi)) {
                own = v;
                break;
            }
        }
        String claimed = R.s(t, "claimed_pi");
        if (Objects.equals(claimed, pi)) {
            return new Resolved(source, MODE_LIBRARY, PLAN_USE, rule, t, null, null, null);
        }
        if (own != null) {
            return new Resolved(source, MODE_LIBRARY, PLAN_USE, rule, own, null, null, null);
        }
        if (claimed == null) {
            return new Resolved(source, MODE_LIBRARY, PLAN_CLAIM, rule, t, null, null, null);
        }
        return new Resolved(source, MODE_LIBRARY, PLAN_PREFIX, rule, null, null, claimed, null);
    }

    static Map<String, Object> customDict(SnCodec.Spec spec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prefix", spec.prefix());
        m.put("base", spec.base());
        m.put("seq_len", spec.seqLen());
        m.put("with_length", spec.withLength());
        m.put("length_pad", spec.lengthPad());
        m.put("sep_before", spec.sepBefore());
        m.put("sep_after", spec.sepAfter());
        m.put("with_unit", spec.withUnit());
        m.put("suffix", spec.suffix());
        return m;
    }

    private static Integer intOf(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static Boolean boolOf(Object v) {
        return v instanceof Boolean b ? b : null;
    }

    private static String strOf(Object v) {
        return v == null ? null : v.toString();
    }

    /** 自定义规则参数（库里 custom_json 的 snake_case 键）→ 规格。 */
    static SnCodec.Spec customSpec(Map<String, Object> c) {
        Integer base = intOf(c.get("base"));
        Integer len = intOf(c.get("seq_len"));
        Integer pad = intOf(c.get("length_pad"));
        Boolean wl = c.containsKey("with_length") ? boolOf(c.get("with_length")) : Boolean.FALSE;
        Boolean wu = c.containsKey("with_unit") ? boolOf(c.get("with_unit")) : Boolean.FALSE;
        return normalize(
                strOf(c.get("prefix")),
                strOf(c.get("suffix")),
                base == null || base == 0 ? 10 : base,
                len == null || len == 0 ? CUSTOM_SEQ_LEN : len,
                null,
                wl,
                pad == null ? 0 : pad,
                c.get("sep_before") == null ? "" : strOf(c.get("sep_before")),
                c.get("sep_after") == null ? "" : strOf(c.get("sep_after")),
                wu);
    }

    static Map<String, Object> customOf(CustomRuleDTO d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prefix", d.prefix);
        m.put("base", d.base);
        m.put("seq_len", d.seqLen);
        m.put("with_length", d.withLength == null ? Boolean.FALSE : d.withLength);
        m.put("length_pad", d.lengthPad == null ? 0 : d.lengthPad);
        m.put("sep_before", d.sepBefore == null ? "" : d.sepBefore);
        m.put("sep_after", d.sepAfter == null ? "" : d.sepAfter);
        m.put("with_unit", d.withUnit == null ? Boolean.FALSE : d.withUnit);
        m.put("suffix", d.suffix);
        if (d.seqStart != null) {
            m.put("seq_start", d.seqStart);
        }
        return m;
    }

    /** 自定义规则的起始号：不填为 null；须为 1..规则最大流水。 */
    public static Long checkSeqStart(Long seqStart, SnCodec.Spec spec) {
        if (seqStart == null) {
            return null;
        }
        long mx = SnCodec.maxSeq(spec);
        if (seqStart < 1 || seqStart > mx) {
            throw Biz.err(SnErrorCode.RULE_SEQ_START_INVALID, "max", mx);
        }
        return seqStart;
    }

    private static Long longOf(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    Resolved customResolved(String source, Map<String, Object> c) {
        SnCodec.Spec spec = customSpec(c);
        Long start = checkSeqStart(longOf(c.get("seq_start")), spec);
        Map<String, Object> draft = new HashMap<>();
        draft.put("id", null);
        draft.put("rule_id", null);
        draft.put("version", 1);
        draft.put("prefix", spec.prefix());
        draft.put("suffix", spec.suffix());
        draft.put("base", spec.base());
        draft.put("seq_len", spec.seqLen());
        draft.put("charset", spec.charset());
        draft.put("with_length", spec.withLength() ? 1 : 0);
        draft.put("length_pad", spec.lengthPad());
        draft.put("length_sep_before", spec.sepBefore());
        draft.put("length_sep_after", spec.sepAfter());
        draft.put("with_unit", spec.withUnit() ? 1 : 0);
        draft.put("used", 0);
        draft.put("claimed_pi", null);
        draft.put("derived_from", null);
        return new Resolved(
                source, MODE_CUSTOM, PLAN_NEW, null, draft, customDict(spec), null, start);
    }

    static String customJson(Resolved res) {
        Map<String, Object> c = new LinkedHashMap<>(res.custom());
        if (res.seqStart() != null) {
            c.put("seq_start", res.seqStart());
        }
        return PyJson.dumps(c, true);
    }

    static String startJson(Long seqStart) {
        return seqStart == null ? null : PyJson.dumps(Map.of("seq_start", seqStart), false);
    }

    static Long startOf(Map<String, Object> sel) {
        if (R.v(sel, "rule_id") == null || Util.blank(R.s(sel, "custom_json"))) {
            return null;
        }
        Object v = PyJson.parse(R.s(sel, "custom_json")).get("seq_start");
        return v instanceof Number n && !(v instanceof Double) && n.longValue() > 0
                ? n.longValue()
                : null;
    }

    static boolean sameCustom(String customJson, Map<String, Object> custom) {
        Map<String, Object> c = PyJson.parse(customJson);
        c.remove("seq_start");
        return PyJson.dumps(c, true).equals(PyJson.dumps(custom, true));
    }

    /** 自定义规则与本 PI 已有的版本完全同格式，且规则在本 PI 下正用这一版：返回该解析；否则 null。 */
    public Resolved ownVersion(String pi, SnCodec.Spec spec) {
        for (Map<String, Object> v : mapper.versionsOfPrefixPi(spec.prefix(), pi)) {
            if (!specOf(v).equals(spec)) {
                continue;
            }
            Map<String, Object> r = mapper.rule(R.l(v, "rule_id"));
            if (r == null) {
                continue;
            }
            Resolved res = resolve(r, pi, SRC_LINE);
            if (res.version() != null && R.l(res.version(), "id") == R.l(v, "id")) {
                return res;
            }
        }
        return null;
    }

    /** 生成页「选规则」的默认前缀：本 PI 已生成的号还在时沿用已用前缀（custom 为其格式），否则 PI 第一张单据编码后 7 位。 */
    public Map<String, Object> piPrefix(String pi) {
        Map<String, Object> used = mapper.usedVersionOfPi(pi);
        Map<String, Object> out = new LinkedHashMap<>();
        if (used == null) {
            String first = mapper.minBillOfPi(pi);
            first = first == null ? "" : first;
            out.put("prefix", first.length() > 7 ? first.substring(first.length() - 7) : first);
            out.put("custom", null);
            return out;
        }
        Map<String, Object> v = mapper.latestVersionOfPrefixPi(R.s(used, "prefix"), pi);
        if (v == null) {
            v = used;
        }
        out.put("prefix", R.s(used, "prefix"));
        out.put("custom", customVO(customDict(specOf(v))));
        return out;
    }

    private void adopt(CurrentUser cu, Map<String, Object> rule, String pi, Set<String> orgs) {
        String fc = R.s(rule, "factory_code");
        if (fc == null || (orgs.size() <= 1 && (orgs.isEmpty() || orgs.contains(fc)))) {
            return;
        }
        if (PI.equals(R.s(rule, "category_scope")) && pi.equals(R.s(rule, "category_value"))) {
            mapper.clearRuleFactory(R.l(rule, "id"));
        } else {
            checkVisible(cu, rule);
        }
    }

    @Override
    public RulePreviewVO preview(RulePreviewDTO s) {
        operator();
        SnCodec.Spec spec = normalize(s);
        String sampleLen = SnCodec.cleanLength(s.length);
        if (sampleLen == null) {
            sampleLen = SAMPLE_LENGTH;
        }
        String sampleUnit = Util.trim(s.unit);
        if (sampleUnit.isEmpty()) {
            sampleUnit = SAMPLE_UNIT;
        }
        long mx = SnCodec.maxSeq(spec);
        Long start = checkSeqStart(s.seqStart, spec);
        long first = start == null ? 1 : start;
        List<Long> seqs = new ArrayList<>();
        for (long i = first; i <= Math.min(first + 4, mx); i++) {
            seqs.add(i);
        }
        if (mx > first + 4) {
            seqs.add(mx);
        }
        RulePreviewVO v = new RulePreviewVO();
        v.maxSeq = mx;
        v.format = formatText(spec);
        v.sampleLength = sampleLen;
        v.sampleUnit = sampleUnit;
        v.samples = new ArrayList<>();
        for (long i : seqs) {
            RulePreviewVO.Sample x = new RulePreviewVO.Sample();
            x.seqDec = i;
            x.seqText = SnCodec.encodeSeq(i, spec);
            x.sn = SnCodec.formatSn(i, spec, sampleLen, sampleUnit);
            v.samples.add(x);
        }
        return v;
    }

    static String describe(SnCodec.Spec s) {
        return "prefix="
                + s.prefix()
                + " suffix="
                + s.suffix()
                + " base="
                + s.base()
                + " len="
                + s.seqLen()
                + " with_length="
                + (s.withLength() ? 1 : 0)
                + " length_pad="
                + s.lengthPad()
                + " sep_before="
                + s.sepBefore()
                + " sep_after="
                + s.sepAfter()
                + " with_unit="
                + (s.withUnit() ? 1 : 0)
                + " charset="
                + s.charset();
    }

    // ================================================================== 规则库

    private Map<Long, List<Map<String, Object>>> versionsByRule(Collection<Long> ids) {
        Map<Long, List<Map<String, Object>>> out = new HashMap<>();
        if (ids != null && ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> v : mapper.versionsByRules(ids)) {
            out.computeIfAbsent(R.l(v, "rule_id"), k -> new ArrayList<>()).add(v);
        }
        return out;
    }

    static RuleVersionVO versionVO(Map<String, Object> v) {
        if (v == null) {
            return null;
        }
        RuleVersionVO o = new RuleVersionVO();
        o.id = R.l(v, "id");
        o.ruleId = R.l(v, "rule_id");
        o.version = R.i(v, "version");
        o.prefix = R.s(v, "prefix");
        o.suffix = R.s(v, "suffix");
        o.base = R.i(v, "base");
        o.seqLen = R.i(v, "seq_len");
        o.charset = R.s(v, "charset");
        o.withLength = R.b(v, "with_length");
        o.lengthPad = R.i(v, "length_pad");
        o.lengthSepBefore = R.s(v, "length_sep_before");
        o.lengthSepAfter = R.s(v, "length_sep_after");
        o.withUnit = R.b(v, "with_unit");
        o.used = R.b(v, "used");
        o.claimedPi = R.s(v, "claimed_pi");
        o.derivedFrom = R.lo(v, "derived_from");
        o.createdBy = R.s(v, "created_by");
        o.createdAt = R.t(v, "created_at");
        o.updatedAt = R.t(v, "updated_at");
        o.format = formatText(specOf(v));
        return o;
    }

    static RuleViewVO.Rule ruleVO(Map<String, Object> r) {
        RuleViewVO.Rule o = new RuleViewVO.Rule();
        o.id = R.l(r, "id");
        o.ruleCode = R.s(r, "rule_code");
        o.ruleName = R.s(r, "rule_name");
        o.categoryScope = R.s(r, "category_scope");
        o.categoryValue = R.s(r, "category_value");
        o.factoryCode = R.s(r, "factory_code");
        o.currentVersion = R.i(r, "current_version");
        o.createdBy = R.s(r, "created_by");
        o.createdAt = R.t(r, "created_at");
        o.updatedBy = R.s(r, "updated_by");
        o.updatedAt = R.t(r, "updated_at");
        return o;
    }

    private List<RuleViewVO> views(List<Map<String, Object>> rs) {
        List<Long> ids = rs.stream().map(r -> R.l(r, "id")).toList();
        Map<Long, List<Map<String, Object>>> versions = versionsByRule(ids);
        Map<Long, List<String>> piBind = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Map<String, Object> row : mapper.piBindingsOf(ids)) {
                piBind.computeIfAbsent(R.l(row, "rule_id"), k -> new ArrayList<>())
                        .add(R.s(row, "v"));
            }
        }
        List<RuleViewVO> out = new ArrayList<>();
        for (Map<String, Object> r : rs) {
            List<Map<String, Object>> vs = versions.getOrDefault(R.l(r, "id"), List.of());
            RuleViewVO v = new RuleViewVO();
            v.rule = ruleVO(r);
            v.current = versionVO(currentOf(r, vs));
            v.versions = vs.stream().map(RuleServiceImpl::versionVO).toList();
            v.used = vs.stream().anyMatch(x -> R.b(x, "used"));
            Set<String> pis = new TreeSet<>();
            for (Map<String, Object> x : vs) {
                if (!Util.blank(R.s(x, "claimed_pi"))) {
                    pis.add(R.s(x, "claimed_pi"));
                }
            }
            v.pis = new ArrayList<>(pis);
            v.bindings = Map.of("pi", piBind.getOrDefault(R.l(r, "id"), List.of()));
            out.add(v);
        }
        return out;
    }

    public RuleViewVO view(Map<String, Object> r) {
        return views(List.of(r)).getFirst();
    }

    /** 选用规则：全局规则或授权组织的规则。 */
    public void checkVisible(CurrentUser cu, Map<String, Object> r) {
        String fc = R.s(r, "factory_code");
        if (fc != null && !cu.canRead(fc)) {
            throw Biz.err(SnErrorCode.FACTORY_FORBIDDEN, "factory", cu.scopeText());
        }
    }

    /** 修改、删除规则：系统管理员不限；普通操作员只能改授权组织的规则，全局规则只能选用。 */
    void checkEditable(CurrentUser cu, Map<String, Object> r) {
        if (cu.isAdmin()) {
            return;
        }
        if (R.s(r, "factory_code") == null) {
            throw Biz.err(SnErrorCode.RULE_GLOBAL_READONLY, "rule_code", R.s(r, "rule_code"));
        }
        cu.checkWrite(R.s(r, "factory_code"));
    }

    private String owner(CurrentUser cu, String factoryCode) {
        String f = Util.trimOrNull(factoryCode);
        if (f != null) {
            cu.checkWrite(f);
            return factories.mustGet(f).factoryCode;
        }
        if (cu.isAdmin()) {
            return null;
        }
        if (cu.factories().size() == 1) {
            return cu.factories().getFirst();
        }
        throw Biz.err(SnErrorCode.RULE_FACTORY_REQUIRED);
    }

    @Override
    public List<RuleViewVO> list(String q, String category, String categoryValue) {
        CurrentUser cu = operator();
        String text = Util.trim(q);
        return views(
                mapper.listRules(
                        cu.readFactories(null),
                        Util.trimOrNull(category),
                        Util.trimOrNull(categoryValue),
                        text.isEmpty() ? null : Util.likeAny(text)));
    }

    public Map<String, Object> getRule(Long ruleId, boolean lock) {
        Map<String, Object> r =
                ruleId == null ? null : (lock ? mapper.ruleLock(ruleId) : mapper.rule(ruleId));
        if (r == null) {
            throw Biz.err(SnErrorCode.RULE_NOT_FOUND);
        }
        return r;
    }

    private record Category(String scope, String value) {}

    private static Category checkCategory(String category, String value) {
        String cs = Util.trim(category);
        if (cs.isEmpty()) {
            cs = PI;
        }
        if (!PI.equals(cs)) {
            throw Biz.err(SnErrorCode.RULE_CATEGORY_INVALID);
        }
        String cv = Util.trim(value);
        if (cv.isEmpty() || cv.length() > 64) {
            throw Biz.err(SnErrorCode.RULE_CATEGORY_REQUIRED);
        }
        return new Category(cs, cv);
    }

    private long insertVersion(
            CurrentUser cu,
            long ruleId,
            int version,
            SnCodec.Spec spec,
            LocalDateTime now,
            boolean used,
            String claimedPi,
            Long derivedFrom) {
        RuleVersionEntity e = new RuleVersionEntity();
        e.ruleId = ruleId;
        e.version = version;
        e.prefix = spec.prefix();
        e.suffix = spec.suffix();
        e.base = spec.base();
        e.seqLen = spec.seqLen();
        e.charset = spec.charset();
        e.withLength = spec.withLength();
        e.lengthPad = spec.lengthPad();
        e.lengthSepBefore = spec.sepBefore();
        e.lengthSepAfter = spec.sepAfter();
        e.withUnit = spec.withUnit();
        e.used = used;
        e.claimedPi = claimedPi;
        e.derivedFrom = derivedFrom;
        e.createdBy = cu.empNo();
        e.createdAt = now;
        e.updatedAt = now;
        mapper.insertVersion(e);
        return e.id;
    }

    private long insertRule(
            CurrentUser cu,
            String code,
            String name,
            SnCodec.Spec spec,
            Category cat,
            String note,
            String claimedPi,
            String factory) {
        LocalDateTime now = Util.now();
        RuleEntity e = new RuleEntity();
        e.ruleCode = code;
        e.ruleName = name;
        e.categoryScope = cat.scope();
        e.categoryValue = cat.value();
        e.factoryCode = factory;
        e.createdBy = cu.empNo();
        e.createdAt = now;
        e.updatedBy = cu.empNo();
        e.updatedAt = now;
        mapper.insertRule(e);
        insertVersion(cu, e.id, 1, spec, now, claimedPi != null, claimedPi, null);
        audit.record(
                cu,
                AuditEntryDTO.of(AuditAction.RULE_CREATE)
                        .pi(PI.equals(cat.scope()) ? cat.value() : claimedPi)
                        .customer(CUSTOMER.equals(cat.scope()) ? cat.value() : null)
                        .factory(factory)
                        .info(code + " v1 " + describe(spec) + note));
        return e.id;
    }

    @Override
    public RuleViewVO create(RuleCreateDTO b) {
        CurrentUser cu = operator();
        return tx.run(
                () -> {
                    String own = owner(cu, b.factoryCode);
                    String c = Util.trim(b.ruleCode);
                    if (!CODE.matcher(c).matches()) {
                        throw Biz.err(SnErrorCode.RULE_CODE_INVALID);
                    }
                    String n = Util.trim(b.ruleName);
                    if (n.isEmpty() || n.length() > 128) {
                        throw Biz.err(SnErrorCode.RULE_NAME_REQUIRED);
                    }
                    Category cat = checkCategory(b.categoryScope, b.categoryValue);
                    SnCodec.Spec spec = normalize(b);
                    checkPrefix(spec.prefix(), null, spec, null, false);
                    if (mapper.existsCode(c)) {
                        throw Biz.err(SnErrorCode.RULE_CODE_EXISTS, "rule_code", c);
                    }
                    long rid = insertRule(cu, c, n, spec, cat, "", null, own);
                    return view(getRule(rid, false));
                });
    }

    @Override
    public RuleActionVO update(long ruleId, RuleUpdateDTO b) {
        CurrentUser cu = operator();
        return tx.run(
                () -> {
                    Map<String, Object> r = getRule(ruleId, true);
                    checkEditable(cu, r);
                    String n = Util.trim(b.ruleName);
                    if (n.isEmpty() || n.length() > 128) {
                        throw Biz.err(SnErrorCode.RULE_NAME_REQUIRED);
                    }
                    Map<String, Object> cur =
                            mapper.currentVersionLock(R.l(r, "id"), R.i(r, "current_version"));
                    SnCodec.Spec spec = normalize(b);
                    Category cat =
                            b.categoryScope == null && b.categoryValue == null
                                    ? new Category(
                                            R.s(r, "category_scope"), R.s(r, "category_value"))
                                    : checkCategory(b.categoryScope, b.categoryValue);
                    SnCodec.Spec before = specOf(cur);
                    String action = "UNCHANGED";
                    int version = R.i(r, "current_version");
                    LocalDateTime now = Util.now();
                    String code = R.s(r, "rule_code");
                    if (!spec.equals(before)) {
                        if (R.b(cur, "used")) {
                            boolean samePrefix = spec.prefix().equals(before.prefix());
                            String ownerPi = samePrefix ? R.s(cur, "claimed_pi") : null;
                            checkPrefix(spec.prefix(), null, spec, ownerPi, false);
                            version = mapper.nextVersion(R.l(r, "id"));
                            insertVersion(
                                    cu, R.l(r, "id"), version, spec, now, false, ownerPi, null);
                            action = "NEW_VERSION";
                            audit.record(
                                    cu,
                                    AuditEntryDTO.of(AuditAction.RULE_VERSION)
                                            .info(
                                                    code
                                                            + " v"
                                                            + version
                                                            + " "
                                                            + describe(spec)
                                                            + " (was "
                                                            + describe(before)
                                                            + ")"));
                        } else {
                            if (!spec.prefix().equals(before.prefix())
                                    || !sameSeqFormat(spec, before)) {
                                checkPrefix(
                                        spec.prefix(),
                                        R.l(cur, "id"),
                                        spec,
                                        R.s(cur, "claimed_pi"),
                                        false);
                            }
                            RuleVersionEntity e = new RuleVersionEntity();
                            e.id = R.l(cur, "id");
                            e.prefix = spec.prefix();
                            e.suffix = spec.suffix();
                            e.base = spec.base();
                            e.seqLen = spec.seqLen();
                            e.charset = spec.charset();
                            e.withLength = spec.withLength();
                            e.lengthPad = spec.lengthPad();
                            e.lengthSepBefore = spec.sepBefore();
                            e.lengthSepAfter = spec.sepAfter();
                            e.withUnit = spec.withUnit();
                            e.updatedAt = now;
                            mapper.updateVersionSpec(e);
                            specCache.remove(R.l(cur, "id"));
                            action = "UPDATED";
                            audit.record(
                                    cu,
                                    AuditEntryDTO.of(AuditAction.RULE_UPDATE)
                                            .info(
                                                    code
                                                            + " v"
                                                            + R.i(cur, "version")
                                                            + " "
                                                            + describe(spec)
                                                            + " (was "
                                                            + describe(before)
                                                            + ")"));
                        }
                    }
                    if (!Objects.equals(cat.scope(), R.s(r, "category_scope"))
                            || !Objects.equals(cat.value(), R.s(r, "category_value"))) {
                        audit.record(
                                cu,
                                AuditEntryDTO.of(AuditAction.RULE_CATEGORY)
                                        .pi(PI.equals(cat.scope()) ? cat.value() : null)
                                        .customer(CUSTOMER.equals(cat.scope()) ? cat.value() : null)
                                        .info(
                                                code
                                                        + " "
                                                        + R.s(r, "category_scope")
                                                        + ":"
                                                        + R.s(r, "category_value")
                                                        + " -> "
                                                        + cat.scope()
                                                        + ":"
                                                        + cat.value()));
                        if ("UNCHANGED".equals(action)) {
                            action = "RECATEGORIZED";
                        }
                    }
                    if (!n.equals(R.s(r, "rule_name"))) {
                        audit.record(
                                cu,
                                AuditEntryDTO.of(AuditAction.RULE_RENAME)
                                        .info(code + " " + R.s(r, "rule_name") + " -> " + n));
                        if ("UNCHANGED".equals(action)) {
                            action = "RENAMED";
                        }
                    }
                    mapper.updateRule(
                            R.l(r, "id"), n, cat.scope(), cat.value(), version, cu.empNo(), now);
                    RuleActionVO out = new RuleActionVO();
                    out.action = action;
                    out.rule = view(getRule(R.l(r, "id"), false));
                    return out;
                });
    }

    @Override
    public RuleActionVO delete(long ruleId) {
        CurrentUser cu = operator();
        return tx.run(
                () -> {
                    Map<String, Object> r = getRule(ruleId, true);
                    checkEditable(cu, r);
                    long id = R.l(r, "id");
                    List<Map<String, Object>> vs = mapper.versionsLock(id);
                    if (vs.stream().anyMatch(v -> R.b(v, "used"))) {
                        throw Biz.err(SnErrorCode.RULE_IN_USE, "rule_code", R.s(r, "rule_code"));
                    }
                    String pi = mapper.piOfBinding(id);
                    if (Util.blank(pi)) {
                        pi = mapper.piOfLineRule(id);
                    }
                    if (Util.blank(pi)) {
                        pi =
                                vs.stream()
                                        .map(v -> R.s(v, "claimed_pi"))
                                        .filter(x -> !Util.blank(x))
                                        .findFirst()
                                        .orElse(null);
                    }
                    if (pi != null) {
                        throw Biz.err(
                                SnErrorCode.RULE_ASSIGNED,
                                "rule_code",
                                R.s(r, "rule_code"),
                                "pi",
                                pi);
                    }
                    mapper.deleteVersions(id);
                    mapper.deleteRule(id);
                    for (Map<String, Object> v : vs) {
                        specCache.remove(R.l(v, "id"));
                    }
                    Map<String, Object> cur = currentOf(r, vs);
                    audit.record(
                            cu,
                            AuditEntryDTO.of(AuditAction.RULE_DELETE)
                                    .info(
                                            R.s(r, "rule_code")
                                                    + " "
                                                    + R.s(r, "rule_name")
                                                    + " v"
                                                    + R.i(r, "current_version")
                                                    + (cur != null
                                                            ? " " + describe(specOf(cur))
                                                            : "")));
                    RuleActionVO out = new RuleActionVO();
                    out.id = id;
                    out.ruleCode = R.s(r, "rule_code");
                    return out;
                });
    }

    // ================================================================== 生效规则

    /** PI 绑定的规则（各物料行的默认）；没绑时 null。 */
    public Resolved piDefault(String pi) {
        Map<String, Object> b = mapper.piRule(pi);
        if (b != null) {
            Map<String, Object> r = mapper.rule(R.l(b, "rule_id"));
            if (r != null) {
                return resolve(r, pi, SRC_PI);
            }
        }
        return null;
    }

    /** 物料行生效的规则：物料行已选 ＞ PI 绑定；都没有返回 null。 */
    public Resolved effective(
            String pi,
            Map<String, Object> sel,
            Resolved def,
            boolean useDefault,
            Map<Long, Resolved> memo) {
        if (sel != null) {
            if (!Util.blank(R.s(sel, "custom_json")) && R.v(sel, "rule_id") == null) {
                return customResolved(SRC_LINE, PyJson.parse(R.s(sel, "custom_json")));
            }
            Long rid = R.lo(sel, "rule_id");
            if (rid != null) {
                Resolved res;
                if (memo != null && memo.containsKey(rid)) {
                    res = memo.get(rid);
                } else {
                    Map<String, Object> r = mapper.rule(rid);
                    res = r == null ? null : resolve(r, pi, SRC_LINE);
                    if (memo != null) {
                        memo.put(rid, res);
                    }
                }
                if (res != null) {
                    Long start = startOf(sel);
                    return start == null || !PLAN_USE.equals(res.plan())
                            ? res
                            : res.withSeqStart(start);
                }
            }
        }
        if (!useDefault) {
            return null;
        }
        return def != null ? def : piDefault(pi);
    }

    public Map<String, Map<String, Object>> selections(Collection<Map<String, Object>> lines) {
        Set<String> bills = new TreeSet<>();
        for (Map<String, Object> l : lines) {
            bills.add(R.s(l, "bill_no"));
        }
        return selectionsOfBills(bills);
    }

    public Map<String, Map<String, Object>> selectionsOfBills(Collection<String> bills) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (bills.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : mapper.selectionsOf(new TreeSet<>(bills))) {
            out.put(R.key(r), r);
        }
        return out;
    }

    static CustomRuleVO customVO(Map<String, Object> c) {
        if (c == null) {
            return null;
        }
        CustomRuleVO v = new CustomRuleVO();
        v.prefix = strOf(c.get("prefix"));
        v.base = intOf(c.get("base")) == null ? 0 : intOf(c.get("base"));
        v.seqLen = intOf(c.get("seq_len")) == null ? 0 : intOf(c.get("seq_len"));
        v.withLength = Boolean.TRUE.equals(c.get("with_length"));
        v.lengthPad = intOf(c.get("length_pad")) == null ? 0 : intOf(c.get("length_pad"));
        v.sepBefore = strOf(c.get("sep_before"));
        v.sepAfter = strOf(c.get("sep_after"));
        v.withUnit = Boolean.TRUE.equals(c.get("with_unit"));
        v.suffix = strOf(c.get("suffix"));
        return v;
    }

    /** 规则说明：来源、选法、规则、本 PI 用的版本、格式、第一个号（按本行米数、单位）。 */
    public RuleInfoVO info(Resolved res, String length, long startSeq, String unit) {
        if (res == null) {
            return null;
        }
        RuleInfoVO o = new RuleInfoVO();
        o.source = res.source();
        o.mode = res.mode();
        o.plan = res.plan();
        o.ruleId = res.rule() == null ? null : R.l(res.rule(), "id");
        o.ruleCode = res.rule() == null ? null : R.s(res.rule(), "rule_code");
        o.ruleName = res.rule() == null ? null : R.s(res.rule(), "rule_name");
        o.versionId = res.version() == null ? null : R.lo(res.version(), "id");
        o.version = res.version() == null ? null : R.io(res.version(), "version");
        o.otherPi = res.otherPi();
        o.custom = customVO(res.custom());
        o.seqStart = res.seqStart();
        if (res.version() != null) {
            SnCodec.Spec s = res.spec();
            o.format = formatText(s);
            o.prefix = s.prefix();
            o.base = s.base();
            o.seqLen = s.seqLen();
            o.withLength = s.withLength();
            o.lengthPad = s.lengthPad();
            o.sepBefore = s.sepBefore();
            o.sepAfter = s.sepAfter();
            o.withUnit = s.withUnit();
            o.suffix = s.suffix();
            o.maxSeq = SnCodec.maxSeq(s);
            long start = startSeq;
            if (res.seqStart() != null) {
                start = Math.max(start, res.seqStart());
            }
            if ((length != null || !s.withLength()) && 0 < start && start <= o.maxSeq) {
                o.firstSn = SnCodec.formatSn(start, s, length, unit);
            }
        } else if (res.rule() != null) {
            Map<String, Object> t = currentVersion(res.rule());
            if (t != null) {
                o.format = formatText(specOf(t));
                o.templatePrefix = R.s(t, "prefix");
            }
        }
        return o;
    }

    public RuleInfoVO info(Resolved res) {
        return info(res, null, 1, null);
    }

    /** 订单页右表的「SN 规则」列：(bill, seq) → 规则说明。 */
    public Map<String, Object> lineRulesBrief(String pi, List<Map<String, Object>> lines) {
        Map<String, Object> out = new HashMap<>();
        if (lines.isEmpty() || Util.blank(pi)) {
            return out;
        }
        Map<String, Map<String, Object>> sels = selections(lines);
        Resolved def = piDefault(pi);
        Map<Long, Resolved> memo = new HashMap<>();
        for (Map<String, Object> l : lines) {
            String k = R.key(l);
            out.put(k, info(effective(pi, sels.get(k), def, true, memo)));
        }
        return out;
    }

    // ================================================================== 选取 / 绑定

    private Map<String, Object> derive(
            CurrentUser cu, Map<String, Object> rule, String pi, String newPrefix) {
        Map<String, Object> t = currentVersion(rule);
        String p = Util.trim(newPrefix);
        SnCodec.Spec base = specOf(t);
        SnCodec.Spec spec =
                new SnCodec.Spec(
                        p,
                        base.suffix(),
                        base.base(),
                        base.seqLen(),
                        base.charset(),
                        base.withLength(),
                        base.lengthPad(),
                        base.sepBefore(),
                        base.sepAfter(),
                        base.withUnit());
        SnErrorCode err = SnCodec.validate(spec);
        if (err != null) {
            throw Biz.err(err);
        }
        checkPrefix(p, null, spec, pi, false);
        int n = mapper.nextVersion(R.l(rule, "id"));
        long vid = insertVersion(cu, R.l(rule, "id"), n, spec, Util.now(), false, pi, R.l(t, "id"));
        audit.record(
                cu,
                AuditEntryDTO.of(AuditAction.RULE_PREFIX_VERSION)
                        .pi(pi)
                        .info(
                                R.s(rule, "rule_code")
                                        + " v"
                                        + n
                                        + " "
                                        + describe(spec)
                                        + " (由 v"
                                        + R.i(t, "version")
                                        + " 换前缀，归属 "
                                        + pi
                                        + ")"));
        return mapper.version(vid);
    }

    private Resolved resolveForSelect(
            CurrentUser cu, Map<String, Object> rule, String pi, String source, String newPrefix) {
        Resolved res = resolve(rule, pi, source);
        if (PLAN_PREFIX.equals(res.plan())) {
            if (Util.blank(newPrefix)) {
                Map<String, Object> t = currentVersion(rule);
                throw Biz.err(
                        SnErrorCode.RULE_PREFIX_CHANGE_REQUIRED,
                        "rule_code",
                        R.s(rule, "rule_code"),
                        "version",
                        R.i(t, "version"),
                        "other_pi",
                        res.otherPi());
            }
            derive(cu, rule, pi, newPrefix);
            res = resolve(rule, pi, source);
        }
        return res;
    }

    private List<Map<String, Object>> lineKeys(String pi, List<LineRefDTO> lines) {
        List<Map<String, Object>> keys = new ArrayList<>();
        if (lines != null) {
            for (LineRefDTO x : lines) {
                String no = Util.trim(x.billNo);
                Map<String, Object> line = mapper.prdLine(no, x.seq);
                if (line == null) {
                    throw Biz.err(SnErrorCode.ORDER_LINE_NOT_FOUND, "bill_no", no, "seq", x.seq);
                }
                if (!Objects.equals(R.s(line, "pi"), pi)) {
                    throw Biz.err(SnErrorCode.GEN_SCOPE_INVALID);
                }
                keys.add(line);
            }
        }
        if (keys.isEmpty()) {
            throw Biz.err(SnErrorCode.RULE_LINE_REQUIRED);
        }
        return keys;
    }

    private static Set<String> orgsOf(List<Map<String, Object>> lines) {
        Set<String> s = new LinkedHashSet<>();
        for (Map<String, Object> l : lines) {
            s.add(R.s(l, "org_code"));
        }
        return s;
    }

    @Override
    public RuleActionVO selectForLines(LineSelectDTO body) {
        CurrentUser cu = operator();
        String pi = Util.trim(body.piNo);
        if (pi.isEmpty()) {
            throw Biz.err(SnErrorCode.PI_REQUIRED);
        }
        return tx.run(
                () -> {
                    List<Map<String, Object>> keys = lineKeys(pi, body.lines);
                    cu.checkWrite(orgsOf(keys).toArray(String[]::new));
                    Resolved res = null;
                    Resolved own = null;
                    if (body.custom != null) {
                        res = customResolved(SRC_LINE, customOf(body.custom));
                        own = ownVersion(pi, res.spec());
                    }
                    Long rid;
                    String cj;
                    String label;
                    if (own != null) {
                        adopt(cu, own.rule(), pi, orgsOf(keys));
                        Long start = res.seqStart();
                        res = own.withSeqStart(start);
                        rid = R.l(own.rule(), "id");
                        cj = startJson(start);
                        label =
                                R.s(own.rule(), "rule_code")
                                        + " v"
                                        + R.i(own.version(), "version")
                                        + (start == null
                                                ? ""
                                                : " start=" + SnCodec.encodeSeq(start, res.spec()));
                    } else if (body.custom != null) {
                        checkPrefix(R.s(res.version(), "prefix"), null, res.spec(), pi, false);
                        rid = null;
                        cj = customJson(res);
                        label =
                                "custom "
                                        + formatText(res.spec())
                                        + (res.seqStart() == null
                                                ? ""
                                                : " start="
                                                        + SnCodec.encodeSeq(
                                                                res.seqStart(), res.spec()));
                    } else {
                        Map<String, Object> rule = getRule(body.ruleId, false);
                        checkVisible(cu, rule);
                        res = resolveForSelect(cu, rule, pi, SRC_LINE, body.newPrefix);
                        rid = R.l(rule, "id");
                        cj = null;
                        label = R.s(rule, "rule_code") + " v" + R.i(res.version(), "version");
                    }
                    LocalDateTime now = Util.now();
                    Map<String, Map<String, Object>> prev = selections(keys);
                    for (Map<String, Object> line : keys) {
                        String no = R.s(line, "bill_no");
                        int seq = R.i(line, "seq");
                        mapper.upsertLineRule(no, seq, pi, rid, cj, cu.empNo(), now);
                        long generated = mapper.ownGenerated(no, seq);
                        audit.record(
                                cu,
                                AuditEntryDTO.of(AuditAction.RULE_LINE_SELECT)
                                        .pi(pi)
                                        .bill(no)
                                        .material(R.s(line, "material_code"))
                                        .info(
                                                "seq="
                                                        + seq
                                                        + " "
                                                        + label
                                                        + (prev.containsKey(R.key(no, seq))
                                                                        && generated > 0
                                                                ? " (改选，已生成过号)"
                                                                : "")));
                    }
                    RuleActionVO out = new RuleActionVO();
                    out.piNo = pi;
                    out.info = info(res);
                    return out;
                });
    }

    @Override
    public RuleActionVO clearLines(PiLinesDTO body) {
        CurrentUser cu = operator();
        String pi = Util.trim(body.piNo);
        tx.exec(
                () -> {
                    List<Map<String, Object>> keys = lineKeys(pi, body.lines);
                    cu.checkWrite(orgsOf(keys).toArray(String[]::new));
                    for (Map<String, Object> line : keys) {
                        String no = R.s(line, "bill_no");
                        int seq = R.i(line, "seq");
                        if (mapper.deleteLineRule(no, seq) > 0) {
                            audit.record(
                                    cu,
                                    AuditEntryDTO.of(AuditAction.RULE_LINE_SELECT)
                                            .pi(pi)
                                            .bill(no)
                                            .material(R.s(line, "material_code"))
                                            .info("seq=" + seq + " cleared"));
                        }
                    }
                });
        RuleActionVO out = new RuleActionVO();
        out.piNo = pi;
        return out;
    }

    private void checkPiScope(CurrentUser cu, String pi) {
        if (cu.isAdmin()) {
            return;
        }
        Set<String> orgs = new HashSet<>(mapper.orgsOfPi(pi));
        cu.checkWrite(orgs.isEmpty() ? new String[] {"-"} : orgs.toArray(String[]::new));
    }

    @Override
    public RuleActionVO bindPi(PiBindDTO body) {
        CurrentUser cu = operator();
        String pi = Util.trim(body.piNo);
        if (pi.isEmpty()) {
            throw Biz.err(SnErrorCode.PI_REQUIRED);
        }
        checkPiScope(cu, pi);
        return tx.run(
                () -> {
                    Map<String, Object> rule = getRule(body.ruleId, false);
                    checkVisible(cu, rule);
                    Resolved res = resolveForSelect(cu, rule, pi, SRC_PI, body.newPrefix);
                    mapper.upsertPiRule(pi, R.l(rule, "id"), cu.empNo(), Util.now());
                    audit.record(
                            cu,
                            AuditEntryDTO.of(AuditAction.RULE_PI_BIND)
                                    .pi(pi)
                                    .info(
                                            R.s(rule, "rule_code")
                                                    + " v"
                                                    + R.i(res.version(), "version")));
                    RuleActionVO out = new RuleActionVO();
                    out.piNo = pi;
                    out.info = info(res);
                    return out;
                });
    }

    @Override
    public RuleActionVO unbindPi(String piIn) {
        CurrentUser cu = operator();
        String pi = Util.trim(piIn);
        checkPiScope(cu, pi);
        tx.exec(
                () -> {
                    Map<String, Object> b = mapper.piRuleLock(pi);
                    if (b != null) {
                        mapper.deletePiRule(pi);
                        Map<String, Object> r = mapper.rule(R.l(b, "rule_id"));
                        audit.record(
                                cu,
                                AuditEntryDTO.of(AuditAction.RULE_PI_UNBIND)
                                        .pi(pi)
                                        .info(
                                                r != null
                                                        ? R.s(r, "rule_code")
                                                        : String.valueOf(R.l(b, "rule_id"))));
                    }
                });
        RuleActionVO out = new RuleActionVO();
        out.piNo = pi;
        return out;
    }

    @Override
    public List<RuleOptionVO> options(String pi, String customer) {
        CurrentUser cu = operator();
        List<RuleOptionVO> out = new ArrayList<>();
        Map<Long, List<Map<String, Object>>> versions = versionsByRule(null);
        String p = Util.blank(pi) ? null : pi;
        for (Map<String, Object> r : mapper.visibleRules(cu.readFactories(null))) {
            List<Map<String, Object>> vs = versions.getOrDefault(R.l(r, "id"), List.of());
            Resolved res = p != null ? resolveIn(r, vs, p, SRC_LINE) : null;
            boolean recommended =
                    (PI.equals(R.s(r, "category_scope"))
                                    && Objects.equals(R.s(r, "category_value"), p))
                            || (CUSTOMER.equals(R.s(r, "category_scope"))
                                    && !Util.blank(customer)
                                    && Objects.equals(R.s(r, "category_value"), customer));
            Map<String, Object> t = currentOf(r, vs);
            RuleOptionVO o = new RuleOptionVO();
            o.ruleId = R.l(r, "id");
            o.ruleCode = R.s(r, "rule_code");
            o.ruleName = R.s(r, "rule_name");
            o.categoryScope = R.s(r, "category_scope");
            o.categoryValue = R.s(r, "category_value");
            o.factoryCode = R.s(r, "factory_code");
            o.recommended = recommended;
            o.format = t == null ? null : formatText(specOf(t));
            Integer rv =
                    res != null && res.version() != null ? R.io(res.version(), "version") : null;
            o.version = rv != null && rv != 0 ? rv : (t == null ? null : R.io(t, "version"));
            o.currentPrefix = t == null ? null : R.s(t, "prefix");
            Set<String> pis = new HashSet<>();
            for (Map<String, Object> v : vs) {
                if (R.s(v, "claimed_pi") != null) {
                    pis.add(R.s(v, "claimed_pi"));
                }
            }
            o.usedPis = pis.size();
            o.plan = res == null ? null : res.plan();
            o.otherPi = res == null ? null : res.otherPi();
            out.add(o);
        }
        out.sort(
                Comparator.comparing((RuleOptionVO x) -> !x.recommended)
                        .thenComparing(x -> x.ruleCode));
        return out;
    }

    // ================================================================== 生成事务内落库

    private String[] customNames(String pi) {
        String safe = CODE_UNSAFE.matcher(pi).replaceAll("_");
        String stem = "PI-" + (safe.length() > 50 ? safe.substring(0, 50) : safe) + "-";
        Set<String> taken = new HashSet<>(mapper.ruleCodesLike(Util.likePrefix(stem)));
        int k = 1;
        while (taken.contains(stem + k)) {
            k++;
        }
        return new String[] {stem + k, Util.cut(pi + "_v" + k, 128)};
    }

    /** 生成事务内：把本次要用的版本落库、标记已使用并归属本 PI，返回版本行；与预演时不一致报预演已失效。 */
    public Map<String, Object> realize(
            CurrentUser cu, String pi, Resolved res, List<String[]> lines) {
        if (PLAN_NEW.equals(res.plan())) {
            SnCodec.Spec spec = res.spec();
            SnErrorCode err = SnCodec.validate(spec);
            if (err != null) {
                throw Biz.err(err);
            }
            checkPrefix(spec.prefix(), null, spec, pi, true);
            String[] names = customNames(pi);
            LinkedHashSet<String> same = new LinkedHashSet<>();
            for (String[] l : lines) {
                same.add(l[0] + "\u0000" + l[1]);
            }
            for (Map<String, Object> r : mapper.customLineRules(pi)) {
                if (sameCustom(R.s(r, "custom_json"), res.custom())) {
                    same.add(R.s(r, "bill_no") + "\u0000" + R.i(r, "seq"));
                }
            }
            List<String> bills = new ArrayList<>();
            for (String k : same) {
                bills.add(k.substring(0, k.indexOf('\u0000')));
            }
            Set<String> orgs = orders.billOrgs(bills);
            orgs.remove("-");
            String owner = orgs.size() == 1 ? orgs.iterator().next() : null;
            long rid =
                    insertRule(
                            cu,
                            names[0],
                            names[1],
                            spec,
                            new Category(PI, pi),
                            " (自定义规则，生成成功后存入规则库)",
                            pi,
                            owner);
            for (String k : same) {
                int sep = k.indexOf('\u0000');
                mapper.setLineRuleRule(
                        rid, k.substring(0, sep), Integer.parseInt(k.substring(sep + 1)));
            }
            return mapper.currentVersion(rid, 1);
        }
        if (res.version() == null) {
            throw Biz.err(SnErrorCode.GEN_PREVIEW_CHANGED);
        }
        long vid = R.l(res.version(), "id");
        if (R.s(res.version(), "claimed_pi") == null) {
            checkPrefix(R.s(res.version(), "prefix"), vid, specOf(res.version()), pi, true);
        }
        int n = mapper.claimVersion(vid, pi);
        if (n == 0 && !mapper.versionClaimedBy(vid, pi)) {
            throw Biz.err(SnErrorCode.GEN_PREVIEW_CHANGED);
        }
        return mapper.version(vid);
    }

    // ================================================================== 进制流水显示

    public SnCodec.Spec cachedSpec(Long versionId) {
        if (versionId == null || versionId == 0) {
            return null;
        }
        SnCodec.Spec s = specCache.get(versionId);
        if (s != null) {
            return s;
        }
        Map<String, Object> v = mapper.version(versionId);
        s = v == null ? null : specOf(v);
        if (s != null) {
            specCache.put(versionId, s);
        }
        return s;
    }

    public String seqText(Long versionId, Long seqDec) {
        SnCodec.Spec s = cachedSpec(versionId);
        if (s == null || seqDec == null) {
            return "";
        }
        try {
            return SnCodec.encodeSeq(seqDec, s);
        } catch (RuntimeException e) {
            return "";
        }
    }
}
