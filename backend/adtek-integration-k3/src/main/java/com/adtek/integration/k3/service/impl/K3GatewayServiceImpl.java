package com.adtek.integration.k3.service.impl;

import com.adtek.framework.integration.K3Exception;
import com.adtek.framework.integration.K3GatewayPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 金蝶 K3 Cloud WebAPI（只读，原系统 app/services/k3.py）：LoginByAppSecret 登录；批量查询用 ExecuteBillQuery 分页，单张用
 * View。
 *
 * <p>比对同步只取已审核（FDocumentStatus = 'C'）单据的单据编号与修改时间；变动的单据按单据编号批量拉明细；全量重建按已审核条件完整拉取。 文本一律去首尾空格；基础资料为
 * null 视为空。只显示的明细列（客户 P/N 等）金蝶不认时改用基本列，一小时后再试。直连金蝶，不走系统代理。
 */
@Service
public class K3GatewayServiceImpl implements K3GatewayPort {
    private static final Logger log = LoggerFactory.getLogger(K3GatewayServiceImpl.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String FORM_PRD_MO = "PRD_MO";
    static final String FORM_ORG = "ORG_Organizations";
    static final String APPROVED_FILTER = "FDocumentStatus='C'";
    static final String ORDER_BY = "FBillNo ASC, FTreeEntity_FEntryId ASC";
    static final List<String> DETAIL_KEYS =
            List.of(
                    "FBillNo",
                    "FDocumentStatus",
                    "F_ora_kh.FNumber",
                    "F_HX_PI",
                    "F_HX_PO",
                    "FPrdOrgId.FNumber",
                    "FPrdOrgId.FName",
                    "FTERM",
                    "FMaterialId.FNumber",
                    "FMaterialId.FName",
                    "Flength",
                    "FQty",
                    "FTreeEntity_FSeq",
                    "FModifyDate",
                    "FProductUnit");
    static final List<String> DISPLAY_KEYS =
            List.of(
                    "FPN",
                    "FCD",
                    "FAdtek",
                    "FMaterialId.FSpecification",
                    "FWorkShopID.FNumber",
                    "FWorkShopID.FName");
    static final long DISPLAY_RETRY_SECONDS = 3600;
    static final String ORG_FIELDS = "FNumber,FName,FForbidStatus";
    static final int PAGE_SIZE = 2000;
    static final int BILLS_PER_QUERY = 100;
    static final int LCID_ZH = 2052;
    static final String LOGIN_SVC =
            "Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret.common.kdsvc";
    static final String VIEW_SVC =
            "Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.View.common.kdsvc";
    static final String QUERY_SVC =
            "Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc";
    private static final Pattern DATE_MS = Pattern.compile("/Date\\((-?\\d+)");
    private static final DateTimeFormatter INVOKE_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 金蝶不认只显示列时记下的时间（毫秒）；0 = 没有失败过。 */
    private volatile long displayFailedAt;

    // ------------------------------------------------------------------ 字段归一

    static String javaStr(Object v) {
        if (v instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            return pyFloat(d);
        }
        return String.valueOf(v);
    }

    /** 与 Python repr(float) 一致（整数值带 .0）。 */
    static String pyFloat(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e16) {
            return BigDecimal.valueOf(d).setScale(1).toPlainString();
        }
        return Double.toString(d);
    }

    static String text(Object v) {
        return v == null ? "" : javaStr(v).strip();
    }

    static String numberText(Object v) {
        if ((v instanceof Double || v instanceof Float)) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return BigDecimal.valueOf(d).toBigInteger().toString();
            }
        }
        return text(v);
    }

    static BigDecimal qty(Object v) {
        if (v == null || "".equals(v)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(v instanceof Number ? javaStr(v) : text(v));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    static int seqOf(Object v) {
        try {
            return (int) Double.parseDouble(text(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String localeName(Object v) {
        if (v instanceof String s) {
            return s.strip();
        }
        if (v instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m
                        && m.get("Key") instanceof Number k
                        && !(m.get("Key") instanceof Boolean)
                        && k.intValue() == LCID_ZH) {
                    return text(m.get("Value"));
                }
            }
            return "";
        }
        if (v instanceof Map<?, ?> m) {
            if (m.containsKey("Name")) {
                return localeName(m.get("Name"));
            }
            return text(m.get("Value"));
        }
        return text(v);
    }

    static String numberOf(Object v) {
        if (v instanceof Map<?, ?> m) {
            return text(m.get("Number"));
        }
        return text(v);
    }

    static LocalDateTime modifyTime(Object v) {
        String t = text(v);
        if (t.isEmpty()) {
            return null;
        }
        Matcher m = DATE_MS.matcher(t);
        if (m.find()) {
            long ms = Long.parseLong(m.group(1));
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC).withNano(0);
        }
        String iso = t.replace("Z", "");
        iso = (iso.length() > 19 ? iso.substring(0, 19) : iso).replace(" ", "T");
        try {
            return LocalDateTime.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Row rowOfQuery(List<?> o) {
        int n = DETAIL_KEYS.size() + DISPLAY_KEYS.size();
        Object[] p = new Object[n];
        for (int i = 0; i < n; i++) {
            p[i] = i < o.size() ? o.get(i) : null;
        }
        String bill = text(p[0]);
        if (bill.isEmpty()) {
            return null;
        }
        return new Row(
                bill,
                text(p[1]),
                text(p[2]),
                text(p[3]),
                text(p[4]),
                text(p[5]),
                localeName(p[6]),
                seqOf(p[12]),
                text(p[7]),
                text(p[8]),
                localeName(p[9]),
                numberText(p[10]),
                qty(p[11]),
                modifyTime(p[13]),
                text(p[14]),
                text(p[15]),
                text(p[16]),
                text(p[17]),
                localeName(p[18]),
                text(p[19]),
                localeName(p[20]));
    }

    static Map<?, ?> map(Object v) {
        return v instanceof Map<?, ?> m ? m : Map.of();
    }

    static List<Row> rowsFromView(Map<?, ?> doc) {
        List<Row> out = new ArrayList<>();
        if (doc == null || doc.isEmpty()) {
            return out;
        }
        String customer = numberOf(doc.get("F_ora_Base"));
        Map<?, ?> org = map(doc.get("PrdOrgId"));
        String billNo = text(doc.get("BillNo"));
        String docStatus = text(doc.get("DocumentStatus"));
        String pi = text(doc.get("F_HX_PI"));
        String po = text(doc.get("F_HX_PO"));
        String orgCode = text(org.get("Number"));
        String orgName = localeName(org.get("Name"));
        LocalDateTime modify = modifyTime(doc.get("ModifyDate"));
        if (doc.get("TreeEntity") instanceof List<?> lines) {
            for (Object x : lines) {
                if (!(x instanceof Map<?, ?> lm)) {
                    continue;
                }
                Map<?, ?> material = map(lm.get("MaterialId"));
                Map<?, ?> shop = map(lm.get("WorkShopID"));
                out.add(
                        new Row(
                                billNo,
                                docStatus,
                                customer,
                                pi,
                                po,
                                orgCode,
                                orgName,
                                seqOf(lm.get("Seq")),
                                text(lm.get("FTERM")),
                                text(material.get("Number")),
                                localeName(material.get("Name")),
                                numberText(lm.get("Flength")),
                                qty(lm.get("Qty")),
                                modify,
                                text(lm.get("FProductUnit")),
                                text(lm.get("FPN")),
                                text(lm.get("FCD")),
                                text(lm.get("FAdtek")),
                                localeName(material.get("Specification")),
                                text(shop.get("Number")),
                                localeName(shop.get("Name"))));
            }
        }
        return out;
    }

    static String errorOf(Object result) {
        if (result instanceof List<?> l && !l.isEmpty()) {
            return errorOf(l.getFirst());
        }
        if (!(result instanceof Map<?, ?> m)) {
            return null;
        }
        Object r = m.get("Result");
        Object st = r instanceof Map<?, ?> rm ? rm.get("ResponseStatus") : null;
        if (st instanceof Map<?, ?> sm && Boolean.FALSE.equals(sm.get("IsSuccess"))) {
            List<String> msgs = new ArrayList<>();
            if (sm.get("Errors") instanceof List<?> errs) {
                for (Object e : errs) {
                    if (e instanceof Map<?, ?> em && em.get("Message") != null) {
                        msgs.add(text(em.get("Message")));
                    }
                }
            }
            return msgs.isEmpty() ? "查询失败" : String.join("; ", msgs);
        }
        return null;
    }

    // ------------------------------------------------------------------ HTTP

    /** 一次调用的会话（登录 Cookie）。 */
    private static final class Session {
        final Connection c;
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient http;

        Session(Connection c) {
            this.c = c;
            this.http =
                    HttpClient.newBuilder()
                            .proxy(HttpClient.Builder.NO_PROXY)
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .cookieHandler(cookies)
                            .connectTimeout(Duration.ofSeconds(30))
                            .build();
        }

        String url(String svc) {
            return c.serverUrl().replaceAll("/+$", "") + "/" + svc;
        }
    }

    private static Map<String, Object> invoke(Object... params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", 1);
        m.put("useragent", "ApiClient");
        m.put("rid", UUID.randomUUID().toString());
        m.put("parameters", List.of(params));
        m.put("timestamp", INVOKE_TS.format(LocalDateTime.now()));
        m.put("v", "1.0");
        return m;
    }

    private static Object post(
            Session s, String url, Object payload, int timeout, K3Exception.Kind kind) {
        try {
            HttpRequest req =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(Math.max(1, timeout)))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            JSON.writeValueAsString(payload),
                                            StandardCharsets.UTF_8))
                            .build();
            HttpResponse<String> resp =
                    s.http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) {
                throw new K3Exception(kind, "HTTP " + resp.statusCode());
            }
            Object body = JSON.readValue(resp.body(), Object.class);
            // 部分环境把 JSON 再包一层字符串
            if (body instanceof String str && (str.startsWith("[") || str.startsWith("{"))) {
                body = JSON.readValue(str, Object.class);
            }
            return body;
        } catch (K3Exception e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new K3Exception(kind, "interrupted");
        } catch (IOException | RuntimeException e) {
            String msg = e.getMessage();
            throw new K3Exception(
                    kind, msg == null || msg.isEmpty() ? e.getClass().getSimpleName() : msg);
        }
    }

    private static Session login(Connection c) {
        Session s = new Session(c);
        Object result =
                post(
                        s,
                        s.url(LOGIN_SVC),
                        invoke(c.acctId(), c.username(), c.appId(), c.appSecret(), c.lcid()),
                        c.loginTimeout(),
                        K3Exception.Kind.LOGIN);
        Map<?, ?> m = map(result);
        Object lt = m.get("LoginResultType");
        if (!(lt instanceof Number n) || lt instanceof Boolean || n.intValue() != 1) {
            String msg = text(m.get("Message"));
            throw new K3Exception(
                    K3Exception.Kind.LOGIN, msg.isEmpty() ? "LoginResultType != 1" : msg);
        }
        Object sid = m.get("KDSVCSessionId");
        if (sid != null && !text(sid).isEmpty()) {
            URI base = URI.create(s.url(""));
            HttpCookie cookie = new HttpCookie("kdservice-sessionid", text(sid));
            cookie.setDomain(base.getHost());
            cookie.setPath("/");
            cookie.setVersion(0);
            s.cookies.getCookieStore().add(base, cookie);
        }
        return s;
    }

    private static List<?> query(Session s, Map<String, Object> data) {
        Object page =
                post(
                        s,
                        s.url(QUERY_SVC),
                        invoke(JSON.writeValueAsString(data)),
                        s.c.queryTimeout(),
                        K3Exception.Kind.QUERY);
        String err = errorOf(page);
        if (err != null) {
            throw new K3Exception(K3Exception.Kind.QUERY, err);
        }
        if (!(page instanceof List<?> list)) {
            throw new K3Exception(K3Exception.Kind.QUERY, "unexpected response");
        }
        return list;
    }

    private static List<?> queryRetry(Session s, Map<String, Object> data) {
        int attempt = 0;
        while (true) {
            try {
                return query(s, data);
            } catch (K3Exception e) {
                if (attempt >= s.c.pageRetries()) {
                    throw e;
                }
                attempt++;
                try {
                    Thread.sleep((long) (s.c.pageRetrySeconds() * 1000));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static List<Object> paged(
            Session s,
            String fieldKeys,
            String filter,
            String order,
            boolean retry,
            IntConsumer onRows) {
        List<Object> merged = new ArrayList<>();
        int start = 0;
        while (true) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("FormId", FORM_PRD_MO);
            data.put("FieldKeys", fieldKeys);
            data.put("FilterString", filter);
            data.put("OrderString", order);
            data.put("TopRowCount", 0);
            data.put("StartRow", start);
            data.put("Limit", PAGE_SIZE);
            List<?> page = retry ? queryRetry(s, data) : query(s, data);
            merged.addAll(page);
            if (onRows != null) {
                onRows.accept(merged.size());
            }
            if (page.size() < PAGE_SIZE) {
                return merged;
            }
            start += PAGE_SIZE;
        }
    }

    private List<Object> detailPaged(Session s, String filter, IntConsumer onRows) {
        String base = String.join(",", DETAIL_KEYS);
        long failed = displayFailedAt;
        if (failed == 0 || System.currentTimeMillis() - failed >= DISPLAY_RETRY_SECONDS * 1000) {
            List<String> all = new ArrayList<>(DETAIL_KEYS);
            all.addAll(DISPLAY_KEYS);
            try {
                List<Object> raw = paged(s, String.join(",", all), filter, ORDER_BY, false, onRows);
                displayFailedAt = 0;
                return raw;
            } catch (K3Exception e) {
                List<Object> raw = paged(s, base, filter, ORDER_BY, true, onRows);
                displayFailedAt = System.currentTimeMillis();
                log.warn("k3_display_fields_skipped reason={}", e.getMessage());
                return raw;
            }
        }
        return paged(s, base, filter, ORDER_BY, true, onRows);
    }

    @Override
    public List<Head> fetchApprovedHeads(Connection c) {
        Session s = login(c);
        List<Object> raw =
                paged(s, "FBillNo,FModifyDate", APPROVED_FILTER, "FBillNo ASC", true, null);
        Map<String, Head> out = new LinkedHashMap<>();
        for (Object o : raw) {
            if (o instanceof List<?> l && !l.isEmpty()) {
                String no = text(l.getFirst());
                if (!no.isEmpty() && !out.containsKey(no)) {
                    out.put(no, new Head(no, modifyTime(l.size() > 1 ? l.get(1) : null)));
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    @Override
    public List<Row> fetchBills(Connection c, List<String> billNos, IntConsumer onBills) {
        if (billNos.isEmpty()) {
            return List.of();
        }
        Session s = login(c);
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < billNos.size(); i += BILLS_PER_QUERY) {
            List<String> part = billNos.subList(i, Math.min(billNos.size(), i + BILLS_PER_QUERY));
            String flt =
                    APPROVED_FILTER
                            + " AND FBillNo IN ("
                            + String.join(
                                    ",", part.stream().map(K3GatewayServiceImpl::quote).toList())
                            + ")";
            for (Object o : detailPaged(s, flt, null)) {
                Row r = o instanceof List<?> l ? rowOfQuery(l) : null;
                if (r != null) {
                    rows.add(r);
                }
            }
            if (onBills != null) {
                onBills.accept(Math.min(i + BILLS_PER_QUERY, billNos.size()));
            }
        }
        return rows;
    }

    @Override
    public List<Row> fetchAll(Connection c, IntConsumer onRows) {
        Session s = login(c);
        List<Row> out = new ArrayList<>();
        for (Object o : detailPaged(s, APPROVED_FILTER, onRows)) {
            Row r = o instanceof List<?> l ? rowOfQuery(l) : null;
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public BillView viewBill(Connection c, String billNo) {
        Session s = login(c);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("CreateOrgId", 0);
        data.put("Number", billNo);
        data.put("Id", "");
        data.put("IsSortBySeq", true);
        Object body =
                post(
                        s,
                        s.url(VIEW_SVC),
                        invoke(FORM_PRD_MO, JSON.writeValueAsString(data)),
                        c.viewTimeout(),
                        K3Exception.Kind.QUERY);
        Map<?, ?> result = map(map(body).get("Result"));
        Map<?, ?> st = map(result.get("ResponseStatus"));
        if (!Boolean.TRUE.equals(st.get("IsSuccess"))) {
            // MsgCode 10 = 未找到对应数据：单据已删除
            if (st.get("MsgCode") instanceof Number code
                    && !(st.get("MsgCode") instanceof Boolean)
                    && code.intValue() == 10) {
                return new BillView(false, "", List.of());
            }
            String err = errorOf(body);
            throw new K3Exception(K3Exception.Kind.QUERY, err == null ? "View failed" : err);
        }
        Map<?, ?> doc = map(result.get("Result"));
        List<Row> rows = rowsFromView(doc).stream().filter(r -> r.billNo().equals(billNo)).toList();
        return new BillView(true, text(doc.get("DocumentStatus")), rows);
    }

    @Override
    public List<Org> fetchOrganizations(Connection c) {
        Session s = login(c);
        List<Object> raw = new ArrayList<>();
        int start = 0;
        while (true) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("FormId", FORM_ORG);
            data.put("FieldKeys", ORG_FIELDS);
            data.put("FilterString", "");
            data.put("OrderString", "FNumber");
            data.put("TopRowCount", 0);
            data.put("StartRow", start);
            data.put("Limit", PAGE_SIZE);
            List<?> page = query(s, data);
            raw.addAll(page);
            if (page.size() < PAGE_SIZE) {
                break;
            }
            start += PAGE_SIZE;
        }
        List<Org> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object o : raw) {
            if (o instanceof List<?> l && l.size() >= 2) {
                String number = text(l.get(0));
                String name = localeName(l.get(1));
                String forbid = l.size() > 2 ? text(l.get(2)) : "A";
                if (!number.isEmpty() && seen.add(number)) {
                    out.add(
                            new Org(
                                    number,
                                    name.isEmpty() ? number : name,
                                    forbid.equalsIgnoreCase("A")));
                }
            }
        }
        return out;
    }
}
