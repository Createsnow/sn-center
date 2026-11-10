package com.adtek.sn.service.impl;

import com.adtek.framework.integration.K3Exception;
import com.adtek.framework.integration.K3GatewayPort;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.domain.K3ConfigEntity;
import com.adtek.sn.mapper.K3ConfigMapper;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.K3Params;
import com.adtek.sn.support.SecretBox;
import com.adtek.sn.support.SnSettings;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * 金蝶 K3 Cloud（只读）：连接参数取「金蝶连接」页面保存的（sn_k3_config，应用密钥加密保存）优先，没有时读环境变量 K3_*；每次调用即读即用， 保存后下一次同步即生效。调用经
 * {@link K3GatewayPort}（adtek-integration-k3）。
 */
@Service
public class K3ServiceImpl {
    public static final String SOURCE_PAGE = "PAGE";
    public static final String SOURCE_ENV = "ENV";

    /** 文本参数（页面字段 → 环境变量名）。 */
    public static final List<String[]> TEXT_PARAMS =
            List.of(
                    new String[] {"server_url", "K3_SERVER_URL"},
                    new String[] {"acct_id", "K3_ACCT_ID"},
                    new String[] {"app_id", "K3_APP_ID"},
                    new String[] {"app_secret", "K3_APP_SECRET"},
                    new String[] {"username", "K3_USERNAME"});

    private final K3ConfigMapper mapper;
    private final K3GatewayPort gateway;
    private final SecretBox secretBox;
    private final SnSettings settings;

    public K3ServiceImpl(
            K3ConfigMapper mapper,
            K3GatewayPort gateway,
            SecretBox secretBox,
            SnSettings settings) {
        this.mapper = mapper;
        this.gateway = gateway;
        this.secretBox = secretBox;
        this.settings = settings;
    }

    /** 环境变量里的 K3_*。 */
    public K3Params envValues() {
        return new K3Params(
                settings.get("K3_SERVER_URL", ""),
                settings.get("K3_ACCT_ID", ""),
                settings.get("K3_APP_ID", ""),
                settings.get("K3_APP_SECRET", ""),
                settings.get("K3_USERNAME", ""),
                settings.getInt("K3_LCID", 2052),
                settings.getInt("K3_LOGIN_TIMEOUT", 30),
                settings.getInt("K3_QUERY_TIMEOUT", 120),
                settings.getInt("K3_VIEW_TIMEOUT", 60),
                settings.getInt("K3_PAGE_RETRIES", 3),
                settings.getInt("K3_PAGE_RETRY_SECONDS", 10));
    }

    public K3ConfigEntity storedRow() {
        return mapper.selectStored();
    }

    public static K3Params paramsOf(K3ConfigEntity row, String secret) {
        return new K3Params(
                row.serverUrl,
                row.acctId,
                row.appId,
                secret,
                row.username,
                row.lcid,
                row.loginTimeout,
                row.queryTimeout,
                row.viewTimeout,
                row.pageRetries,
                row.pageRetrySeconds);
    }

    /** 当前生效的连接参数（含应用密钥明文）与来源；已存的密钥解不开抛 K3_SECRET_UNREADABLE。 */
    public Current currentValues() {
        K3ConfigEntity row = mapper.selectStored();
        if (row == null) {
            return new Current(SOURCE_ENV, envValues());
        }
        String secret = secretBox.open(row.appSecret);
        if (secret == null) {
            throw Biz.err(SnErrorCode.K3_SECRET_UNREADABLE);
        }
        return new Current(SOURCE_PAGE, paramsOf(row, secret));
    }

    /** 当前参数与来源。 */
    public record Current(String source, K3Params values) {}

    /** 必填参数检查后的连接。 */
    public K3GatewayPort.Connection connectionOf(K3Params v) {
        List<String> missing = new ArrayList<>();
        if (v.serverUrl() == null || v.serverUrl().isEmpty()) {
            missing.add("K3_SERVER_URL");
        }
        if (v.acctId() == null || v.acctId().isEmpty()) {
            missing.add("K3_ACCT_ID");
        }
        if (v.appId() == null || v.appId().isEmpty()) {
            missing.add("K3_APP_ID");
        }
        if (v.appSecret() == null || v.appSecret().isEmpty()) {
            missing.add("K3_APP_SECRET");
        }
        if (v.username() == null || v.username().isEmpty()) {
            missing.add("K3_USERNAME");
        }
        if (!missing.isEmpty()) {
            throw Biz.err(SnErrorCode.K3_CONFIG_MISSING, "missing", String.join(", ", missing));
        }
        return v.connection();
    }

    private K3GatewayPort.Connection current() {
        return connectionOf(currentValues().values());
    }

    private static <T> T call(Supplier<T> body) {
        try {
            return body.get();
        } catch (K3Exception e) {
            throw Biz.err(
                    e.kind() == K3Exception.Kind.LOGIN
                            ? SnErrorCode.K3_LOGIN_FAILED
                            : SnErrorCode.K3_QUERY_FAILED,
                    "message",
                    e.getMessage());
        }
    }

    public List<K3GatewayPort.Head> fetchApprovedHeads() {
        K3GatewayPort.Connection c = current();
        return call(() -> gateway.fetchApprovedHeads(c));
    }

    public List<K3GatewayPort.Row> fetchBills(List<String> billNos, IntConsumer onBills) {
        if (billNos.isEmpty()) {
            return List.of();
        }
        K3GatewayPort.Connection c = current();
        return call(() -> gateway.fetchBills(c, billNos, onBills));
    }

    public List<K3GatewayPort.Row> fetchAll(IntConsumer onRows) {
        K3GatewayPort.Connection c = current();
        return call(() -> gateway.fetchAll(c, onRows));
    }

    public K3GatewayPort.BillView viewBill(String billNo) {
        K3GatewayPort.Connection c = current();
        return call(() -> gateway.viewBill(c, billNo));
    }

    public List<K3GatewayPort.Org> fetchOrganizations() {
        K3GatewayPort.Connection c = current();
        return call(() -> gateway.fetchOrganizations(c));
    }

    /** 用给定参数读组织机构（连接测试）。 */
    public List<K3GatewayPort.Org> fetchOrganizations(K3Params params) {
        K3GatewayPort.Connection c = connectionOf(params);
        return call(() -> gateway.fetchOrganizations(c));
    }
}
