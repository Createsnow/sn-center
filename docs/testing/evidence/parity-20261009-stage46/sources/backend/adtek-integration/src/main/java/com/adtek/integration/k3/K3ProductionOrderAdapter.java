package com.adtek.integration.k3;

import static com.adtek.integration.constant.ErrorCodeConstants.*;

import com.adtek.common.core.BusinessException;
import com.adtek.sn.service.port.ErpProductionOrderPort;

import org.springframework.stereotype.Component;

import tools.jackson.databind.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/**
 * K3旧协议的严格只读适配：LoginByAppSecret/ExecuteBillQuery/View。
 * 字段映射增加FID、FTreeEntity_FEntryId、FPrdOrgId/FNumber；组织内码直接取FPrdOrgId，已通过目标账套只读核验。
 * 没有稳定字段即拒绝，不以显示行号推测；计划小数完整保留。仅返回经过长度与秘密检查的业务错误摘要，不记录原报文。
 */
@Component
public class K3ProductionOrderAdapter implements ErpProductionOrderPort {
    private static final String LOGIN =
            "Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret.common.kdsvc";
    private static final String QUERY =
            "Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc";
    private static final String VIEW =
            "Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.View.common.kdsvc";
    private static final String FIELDS =
            "F_ora_kh.FNumber,FBillNo,F_HX_PO,F_HX_PI,FMaterialId.FNumber,FMaterialId.FName,FQty,FStatus,FSaleOrderNo,FPrdOrgId.FName,FID,FTreeEntity_FEntryId,FPrdOrgId,FPrdOrgId.FNumber";
    private static final DateTimeFormatter K3_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
    private final K3Settings config;
    private final ObjectMapper json;

    public K3ProductionOrderAdapter(K3Settings config, ObjectMapper json) {
        this.config = config;
        this.json = json;
    }

    @Override
    public Source source() {
        if (!config.enabled()
                || blank(config.url())
                || blank(config.account())
                || blank(config.appId())
                || blank(config.appSecret())
                || blank(config.username())) throw new BusinessException(K3_CONFIG_MISSING);
        if (config.account().length() > 64) throw invalid();
        URI uri;
        try {
            uri = URI.create(config.url());
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (!Set.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) throw invalid();
        return new Source("K3", config.account());
    }

    @Override
    public List<Row> orders(String billNo) {
        source();
        try (var call = new Call()) {
            call.login();
            List<Row> rows = new ArrayList<>();
            if (billNo != null) {
                view(call, billNo, rows);
            } else {
                int ordinal = 0;
                for (var raw :
                        pages(
                                call,
                                "PRD_MO",
                                FIELDS,
                                "(FStatus='1' OR FStatus='2')",
                                "FID ASC, FTreeEntity_FEntryId ASC")) {
                    if (!raw.isArray() || raw.size() != 14) throw invalid();
                    String status = status(raw.get(7));
                    if (!Set.of("1", "2").contains(status)) continue;
                    rows.add(
                            new Row(
                                    identity(raw.get(10)),
                                    identity(raw.get(11)),
                                    text(raw.get(1), 64, true),
                                    text(raw.get(0), 64, false),
                                    text(raw.get(2), 128, false),
                                    text(raw.get(3), 128, false),
                                    identity(raw.get(12)),
                                    text(raw.get(13), 64, true),
                                    name(raw.get(9), 128, true),
                                    ++ordinal,
                                    text(raw.get(4), 128, false),
                                    name(raw.get(5), 255, false),
                                    quantity(raw.get(6)),
                                    status,
                                    text(raw.get(8), 128, false)));
                }
            }
            return List.copyOf(rows);
        }
    }

    @Override
    public List<Organization> organizations() {
        source();
        try (var call = new Call()) {
            call.login();
            List<Organization> out = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (var row :
                    pages(
                            call,
                            "ORG_Organizations",
                            "FOrgID,FNumber,FName,FForbidStatus",
                            "FForbidStatus='A'",
                            "FNumber")) {
                if (!row.isArray() || row.size() < 3) continue;
                String status = row.size() > 3 ? directoryText(row.get(3)) : "A";
                String code = directoryText(row.get(1));
                String label = directoryText(row.get(2));
                if (code.isEmpty()
                        || label.isEmpty()
                        || !status.equalsIgnoreCase("A")
                        || !names.add(label)) continue;
                // 原型目录不要求内码；缺失或非法内码不能用于显式绑定，仍可按编码维护工厂。
                String id = directoryText(row.get(0));
                if (!id.matches("[1-9][0-9]{0,63}")) id = null;
                var org = new Organization(id, code, label);
                out.add(org);
            }
            return List.copyOf(out);
        }
    }

    private static String directoryText(JsonNode node) {
        if (node == null || node.isNull()) return "";
        return com.adtek.sn.service.support.FactoryText.trim(
                (node.isObject() || node.isArray()) ? name(node, 128, false) : node.asString());
    }

    private List<JsonNode> pages(
            Call call, String form, String fields, String filter, String order) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 0; page < config.maxPages(); page++) {
            var data =
                    Map.of(
                            "FormId",
                            form,
                            "FieldKeys",
                            fields,
                            "FilterString",
                            filter,
                            "OrderString",
                            order,
                            "TopRowCount",
                            0,
                            "StartRow",
                            page * config.pageSize(),
                            "Limit",
                            config.pageSize());
            var body =
                    call.post(QUERY, invoke(json.writeValueAsString(data)), config.querySeconds());
            rejectQueryFailure(body);
            if (!body.isArray() || body.size() > config.pageSize()) throw invalid();
            for (var row : body) {
                rejectQueryFailure(row);
                if (!row.isArray()) throw invalid();
                out.add(row);
            }
            if (out.size() > config.maxRows()) throw new BusinessException(K3_RESOURCE_LIMIT);
            if (body.size() < config.pageSize()) return out;
        }
        throw new BusinessException(K3_RESOURCE_LIMIT);
    }

    /** 旧K3可直接返回错误对象，也可用数组包装；任何失败均中止整次读取。 */
    private void rejectQueryFailure(JsonNode body) {
        while (body.isArray() && !body.isEmpty()) body = body.get(0);
        var response = body.path("Result").path("ResponseStatus").path("IsSuccess");
        if (response.isBoolean() && !response.asBoolean()) throw queryFailure(body);
    }

    private BusinessException queryFailure(JsonNode body) {
        List<String> messages = new ArrayList<>();
        var errors = body.path("Result").path("ResponseStatus").path("Errors");
        if (errors.isArray())
            for (var error : errors)
                if (error.path("Message").isString())
                    messages.add(error.path("Message").asString());
        return new BusinessException(
                K3_QUERY_REJECTED,
                Map.of("message", safeMessage(String.join("; ", messages), "查询失败")));
    }

    /** 业务拒绝允许展示摘要；包含凭据字段或当前连接秘密的整段内容回退，不截出部分秘密。 */
    private String safeMessage(String raw, String fallback) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()
                || java.util.regex.Pattern.compile(
                                "password|passwd|secret|token|credential|authorization|密钥|密码|凭据|令牌",
                                java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(value)
                        .find()) return fallback;
        for (String secret :
                List.of(config.appSecret(), config.appId(), config.account(), config.username()))
            if (!blank(secret) && value.contains(secret)) return fallback;
        value = value.replaceAll("[\\p{Cntrl}]", " ");
        return value.codePointCount(0, value.length()) <= 500
                ? value
                : value.substring(0, value.offsetByCodePoints(0, 500));
    }

    private void view(Call call, String billNo, List<Row> rows) {
        var data = Map.of("CreateOrgId", 0, "Number", billNo, "Id", "", "IsSortBySeq", false);
        var body =
                call.post(
                        VIEW,
                        invoke("PRD_MO", json.writeValueAsString(data)),
                        config.querySeconds());
        var result = body.path("Result");
        var response = result.path("ResponseStatus");
        if (!response.path("IsSuccess").isBoolean()) throw invalid();
        if (!response.path("IsSuccess").asBoolean()) {
            if (response.path("MsgCode").isIntegralNumber()
                    && response.path("MsgCode").asInt() == 10) return;
            throw queryFailure(body);
        }
        var doc = result.path("Result");
        if (!doc.isObject() || !doc.path("TreeEntity").isArray()) throw invalid();
        String returned = text(doc.get("BillNo"), 64, true);
        if (!billNo.equals(returned)) throw invalid();
        String bill = identity(doc.get("Id"));
        var org = doc.path("PrdOrgId");
        String orgId = identity(org.get("Id")),
                orgCode = text(org.get("Number"), 64, true),
                orgName = name(org.get("Name"), 128, true);
        if (doc.path("TreeEntity").size() > config.maxRows())
            throw new BusinessException(K3_RESOURCE_LIMIT);
        int ordinal = 0;
        for (var line : doc.path("TreeEntity")) {
            if (!line.isObject()) throw invalid();
            String status = status(line.get("Status"));
            if (!Set.of("1", "2").contains(status)) continue;
            var material = line.path("MaterialId");
            rows.add(
                    new Row(
                            bill,
                            identity(line.get("Id")),
                            returned,
                            text(doc.path("F_ora_Base").get("Number"), 64, false),
                            text(doc.get("F_HX_PO"), 128, false),
                            text(doc.get("F_HX_PI"), 128, false),
                            orgId,
                            orgCode,
                            orgName,
                            ++ordinal,
                            text(material.get("Number"), 128, false),
                            name(material.get("Name"), 255, false),
                            quantity(line.get("Qty")),
                            status,
                            text(line.get("SaleOrderNo"), 128, false)));
        }
    }

    // 沿用旧部署TZ=Asia/Shanghai与Python的秒级空格格式，不能发送ISO的T/纳秒或容器默认UTC。
    private Map<String, Object> invoke(Object... params) {
        return Map.of(
                "format",
                1,
                "useragent",
                "ApiClient",
                "rid",
                UUID.randomUUID().toString(),
                "parameters",
                List.of(params),
                "timestamp",
                K3_TIMESTAMP.format(Instant.now()),
                "v",
                "1.0");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static BusinessException invalid() {
        return new BusinessException(K3_RESPONSE_INVALID);
    }

    private static String text(JsonNode node, int max, boolean required) {
        if (node == null || node.isNull()) {
            if (required) throw invalid();
            return "";
        }
        if (!node.isString() && !node.isIntegralNumber()) throw invalid();
        String value = node.asString().strip();
        if ((required && value.isEmpty())
                || value.codePointCount(0, value.length()) > max
                || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }

    private static String identity(JsonNode node) {
        String value = text(node, 64, true);
        if (!value.matches("[1-9][0-9]{0,63}")) throw invalid();
        return value;
    }

    private static BigDecimal quantity(JsonNode node) {
        if (node == null
                || node.isNull()
                || node.isBoolean()
                || (!node.isNumber() && !node.isString())) throw invalid();
        try {
            var value = new BigDecimal(node.asString());
            if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0)
                throw invalid();
            return value.setScale(4, java.math.RoundingMode.UNNECESSARY).stripTrailingZeros();
        } catch (NumberFormatException | ArithmeticException e) {
            throw invalid();
        }
    }

    private static String status(JsonNode node) {
        if (node == null || (!node.isNumber() && !node.isString())) throw invalid();
        try {
            return new BigDecimal(node.asString()).toBigIntegerExact().toString();
        } catch (NumberFormatException | ArithmeticException e) {
            throw invalid();
        }
    }

    private static String name(JsonNode node, int max, boolean required) {
        if (node != null && node.isObject())
            return node.has("Name")
                    ? name(node.get("Name"), max, required)
                    : text(node.get("Value"), max, required);
        if (node != null && node.isArray()) {
            for (var item : node) {
                if (item.path("Key").isIntegralNumber() && item.path("Key").asInt() == 2052)
                    return text(item.get("Value"), max, required);
            }
            throw invalid();
        }
        return text(node, max, required);
    }

    /** 每次读取独立会话，禁止重定向和系统代理转发凭据；总预算覆盖每页完整响应，无自动重试。 */
    private final class Call implements AutoCloseable {
        final long deadline =
                System.nanoTime() + Duration.ofSeconds(config.budgetSeconds()).toNanos();
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(config.connectSeconds()))
                        .cookieHandler(cookies)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .proxy(
                                new ProxySelector() {
                                    public List<Proxy> select(URI uri) {
                                        return List.of(Proxy.NO_PROXY);
                                    }

                                    public void connectFailed(
                                            URI uri, SocketAddress address, IOException error) {}
                                })
                        .build();

        void login() {
            var body =
                    post(
                            LOGIN,
                            invoke(
                                    config.account(),
                                    config.username(),
                                    config.appId(),
                                    config.appSecret(),
                                    config.lcid()),
                            config.loginSeconds());
            if (!body.path("LoginResultType").isIntegralNumber()) throw invalid();
            if (body.path("LoginResultType").asInt() != 1)
                throw new BusinessException(
                        K3_LOGIN_REJECTED,
                        Map.of(
                                "message",
                                safeMessage(
                                        body.path("Message").asString(""),
                                        "LoginResultType != 1")));
            // 与旧httpx相同的Version=0 Cookie；Java默认Version=1会添加引号，目标K3无法识别其会话。
            var sid = body.get("KDSVCSessionId");
            if (sid != null && !sid.isNull()) {
                String value = text(sid, 4096, true);
                var cookie = new HttpCookie("kdservice-sessionid", value);
                cookie.setVersion(0);
                cookie.setPath("/");
                cookies.getCookieStore().add(URI.create(config.url()), cookie);
            }
        }

        JsonNode post(String service, Map<String, Object> data, int seconds) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new BusinessException(K3_UNAVAILABLE);
            long timeout = Math.min(remaining, Duration.ofSeconds(seconds).toNanos());
            String base = config.url().replaceAll("/+$", "");
            var request =
                    HttpRequest.newBuilder(URI.create(base + "/" + service))
                            .timeout(Duration.ofNanos(timeout))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(data), StandardCharsets.UTF_8))
                            .build();
            var future = client.sendAsync(request, info -> new LimitedBody(config.responseBytes()));
            try {
                var response = future.get(timeout, TimeUnit.NANOSECONDS);
                if (response.statusCode() != 200)
                    throw new BusinessException(
                            service.equals(LOGIN) ? K3_LOGIN_REJECTED : K3_QUERY_REJECTED,
                            Map.of("message", "HTTP " + response.statusCode()));
                try {
                    var reader =
                            json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
                    JsonNode body = reader.readTree(response.body());
                    if (body == null) throw invalid();
                    if (body.isString()) body = reader.readTree(body.asString());
                    if (body == null) throw invalid();
                    return body;
                } catch (BusinessException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw invalid();
                }
            } catch (BusinessException e) {
                throw e;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                while (cause != null) {
                    if (cause instanceof ResponseLimit)
                        throw new BusinessException(K3_RESOURCE_LIMIT);
                    cause = cause.getCause();
                }
                throw new BusinessException(K3_UNAVAILABLE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(K3_UNAVAILABLE);
            } catch (Exception e) {
                throw new BusinessException(K3_UNAVAILABLE);
            } finally {
                if (!future.isDone()) future.cancel(true);
            }
        }

        @Override
        public void close() {
            client.shutdownNow();
        }
    }

    /** 在收包时限制大小，不先无界读入内存；取消订阅后以异常完成，不把截断报文当成功。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final int limit;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final CompletableFuture<byte[]> done = new CompletableFuture<>();
        Flow.Subscription subscription;

        LimitedBody(int limit) {
            this.limit = limit;
        }

        public CompletionStage<byte[]> getBody() {
            return done;
        }

        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if ((long) out.size() + buffer.remaining() > limit) {
                    subscription.cancel();
                    done.completeExceptionally(new ResponseLimit());
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                out.writeBytes(bytes);
            }
            subscription.request(1);
        }

        public void onError(Throwable error) {
            done.completeExceptionally(error);
        }

        public void onComplete() {
            done.complete(out.toByteArray());
        }
    }

    private static final class ResponseLimit extends IOException {}
}
