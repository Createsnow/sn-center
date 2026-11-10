package com.adtek.sn.support;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Set;

/** 小工具：状态 / 角色常量、字符串、业务时间、单号（与原系统 app/core/utils.py 一致）。 */
public final class Util {
    private Util() {}

    // ---------------------------------------------------------------- SN 状态 / 角色
    public static final String PENDING_ALLOC = "PENDING_ALLOC";
    public static final String TO_PUSH = "TO_PUSH";
    public static final String PUSHED = "PUSHED";
    public static final String ALLOCATED = "ALLOCATED";
    public static final Set<String> SN_STATUSES = Set.of(PENDING_ALLOC, TO_PUSH, PUSHED, ALLOCATED);
    public static final String MODE_PUSH = "PUSH";
    public static final String MODE_PULL = "PULL";
    public static final String MODE_PAGE = "PAGE";
    public static final String PUSH_NEW = "NEW";
    public static final String PUSH_REASSIGN = "REASSIGN";
    public static final String PUSH_TRANSFER_OUT = "TRANSFER_OUT";
    public static final String ORIGIN_GEN = "GEN";
    public static final String ORIGIN_IMPORT = "IMPORT";
    public static final String SUSPEND_REVERSAL = "REVERSAL";
    public static final String SUSPEND_CHANGE = "CHANGE";

    public static final String ADMIN = "admin";
    public static final String OPERATOR = "operator";
    public static final String AUDITOR = "auditor";
    public static final String MES = "mes";
    public static final Set<String> ROLES = Set.of(ADMIN, OPERATOR, AUDITOR, MES);
    public static final Set<String> SCOPED_ROLES = Set.of(OPERATOR, AUDITOR);

    /** 账户不绑或绑多个生产组织时的显示时区（UTC+8）。 */
    public static final int DEFAULT_TZ_OFFSET_MIN = 480;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    private static final DateTimeFormatter MES_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter ID_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 十进制流水的显示值：导入的号（没有流水归属 PI）没有流水，为空串。 */
    public static String seqDecText(String seqPiNo, Long seqDec) {
        return seqPiNo != null && !seqPiNo.isEmpty() && seqDec != null ? seqDec.toString() : "";
    }

    /** 转入号：流水归属别的 PI；导入的号没有流水，不算转入。 */
    public static boolean transferredIn(String seqPiNo, String piNo) {
        return seqPiNo != null && !seqPiNo.isEmpty() && !seqPiNo.equals(piNo);
    }

    // ---------------------------------------------------------------- 字符串
    public static String trim(String s) {
        return s == null ? "" : s.strip();
    }

    public static String trimOrNull(String s) {
        String t = trim(s);
        return t.isEmpty() ? null : t;
    }

    public static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** LIKE 前缀匹配，转义通配符。 */
    public static String likePrefix(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public static String likeAny(String s) {
        return "%" + likePrefix(s);
    }

    public static String cut(String s, int n) {
        if (s == null || s.length() <= n) {
            return s;
        }
        return s.substring(0, n);
    }

    public static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 与 Java String.valueOf / 原系统 java_str 一致：null → 空串。 */
    public static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    // ---------------------------------------------------------------- 时间
    /** 当前 UTC 时间，精确到秒。 */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    public static LocalDateTime nowMillis() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    /** 按给定时区（相对 UTC 的分钟数）的当天 0 点，换算回 UTC。 */
    public static LocalDateTime localDayStart(int offsetMin, LocalDateTime t) {
        LocalDateTime base = (t == null ? now() : t).plusMinutes(offsetMin);
        return base.toLocalDate().atStartOfDay().minusMinutes(offsetMin);
    }

    /** 业务日（按 TZ，默认 Asia/Shanghai）。 */
    public static LocalDate today(ZoneId zone) {
        return LocalDate.now(zone);
    }

    public static String fmt(LocalDateTime t) {
        return t == null ? null : TS.format(t);
    }

    /** UTC 换成 UTC+8；null 为当前时间（毫秒）。 */
    public static LocalDateTime mesLocal(LocalDateTime t) {
        return (t == null ? nowMillis() : t).plusHours(8);
    }

    /** MES 接口的时间文本：UTC+8，yyyy-MM-dd HH:mm:ss.SSS。 */
    public static String mesFmt(LocalDateTime t) {
        return t == null ? null : MES_TS.format(mesLocal(t));
    }

    public static String mesNow() {
        return MES_TS.format(mesLocal(null));
    }

    // ---------------------------------------------------------------- 单号
    /** 业务单号：前缀 + 秒级时间（UTC）+ 4 位随机数；唯一键兜底。 */
    public static String nextId(String prefix) {
        return prefix + ID_TS.format(now()) + String.format("%04d", RANDOM.nextInt(10_000));
    }

    public static String tokenHex() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public static boolean isSuperAdmin(String empNo, String role, String adminEmpNo) {
        return ADMIN.equals(role) && empNo != null && empNo.equals(adminEmpNo);
    }
}
