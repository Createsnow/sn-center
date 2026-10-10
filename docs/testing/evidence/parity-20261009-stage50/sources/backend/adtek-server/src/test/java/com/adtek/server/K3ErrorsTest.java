package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import com.adtek.common.core.BusinessException;
import com.adtek.integration.k3.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/** 独立回环HTTP协议验证错误归属；不连接真实ERP，不把外部原报文传给页面。 */
class K3ErrorsTest {
    private void message(Runnable action, int code, String expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> {
                            assertThat(error.getError().code()).isEqualTo(code);
                            assertThat(error.getParams().get("message")).isEqualTo(expected);
                        });
    }

    private K3ProductionOrderAdapter adapter(K3ProtocolFixture fixture, boolean enabled) {
        return new K3ProductionOrderAdapter(
                new K3Settings(
                        enabled,
                        fixture.url(),
                        "fixture-account",
                        "fixture-app",
                        "fixture-secret",
                        "fixture-user",
                        2052,
                        1,
                        1,
                        1,
                        3,
                        2,
                        2,
                        4,
                        1024),
                new ObjectMapper());
    }

    private void rejected(K3ProductionOrderAdapter adapter, int code, int status) {
        assertThatThrownBy(() -> adapter.orders(null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> {
                            assertThat(error.getError().code()).isEqualTo(code);
                            assertThat(error.getError().httpStatus()).isEqualTo(status);
                            assertThat(error.getParams().toString())
                                    .doesNotContain(
                                            "SYNTHETIC", "fixture-secret", "fixture-account");
                            assertThat(error.getMessage())
                                    .doesNotContain(
                                            "SYNTHETIC", "fixture-secret", "fixture-account");
                        });
    }

    @Test
    void disabledConfigurationHasDedicatedCode() {
        try (var f = new K3ProtocolFixture()) {
            rejected(adapter(f, false), 1005000001, 503);
        }
    }

    @Test
    void loginRejectionAndHttpAuthenticationRejectionAgree() {
        try (var f = new K3ProtocolFixture()) {
            f.failure = "login";
            rejected(adapter(f, true), 1005000002, 502);
            f.failure = "http-login";
            rejected(adapter(f, true), 1005000002, 502);
        }
    }

    @Test
    void httpFailureRetainsOperationWhileTimeoutHasUnavailableCode() {
        try (var f = new K3ProtocolFixture()) {
            f.failure = "http-down";
            rejected(adapter(f, true), 1005000002, 502);
            f.failure = "timeout";
            rejected(adapter(f, true), 1005000003, 503);
        }
    }

    @Test
    void businessMessageIsBoundedAndSecretsNeverEnterPublicParameters() {
        try (var f = new K3ProtocolFixture()) {
            f.failure = "login";
            for (String message :
                    java.util.List.of(
                            "连接用户权限不足",
                            "x".repeat(700),
                            "fixture-account",
                            "password=example",
                            "fixture-secret")) {
                f.errorMessage = message;
                assertThatThrownBy(() -> adapter(f, true).orders(null))
                        .isInstanceOfSatisfying(
                                BusinessException.class,
                                error -> {
                                    String publicMessage = error.getParams().get("message");
                                    assertThat(publicMessage).hasSizeLessThanOrEqualTo(500);
                                    if (message.equals("连接用户权限不足"))
                                        assertThat(publicMessage).isEqualTo(message);
                                    if (message.startsWith("x"))
                                        assertThat(publicMessage).hasSize(500);
                                    assertThat(publicMessage)
                                            .doesNotContain("fixture-", "password", "example");
                                });
            }
        }
    }

    @Test
    void invalidJsonIsProtocolFailureAndOversizeIsBoundedResourceFailure() {
        try (var f = new K3ProtocolFixture()) {
            f.failure = "malformed";
            rejected(adapter(f, true), 1005000004, 502);
            f.failure = "oversize";
            rejected(adapter(f, true), 1005000005, 409);
        }
    }

    @Test
    void laterQueryRejectionIsDistinctAndDoesNotReturnEarlierRows() {
        try (var f = new K3ProtocolFixture()) {
            f.orders =
                    java.util.List.of(
                            K3ProtocolFixture.row("1", "1", "B", "C", "P", "1", "F", "厂", "M", "1"),
                            K3ProtocolFixture.row(
                                    "1", "2", "B", "C", "P", "1", "F", "厂", "M", "1"));
            f.failure = "page";
            rejected(adapter(f, true), 1005000006, 502);
        }
    }

    @Test
    void arrayWrappedLaterPageAndOrganizationErrorsAreQueryRejections() {
        for (String wrapper : java.util.List.of("array", "nested")) {
            try (var f = new K3ProtocolFixture()) {
                f.orders =
                        java.util.List.of(
                                K3ProtocolFixture.row(
                                        "1", "1", "B", "C", "P", "1", "F", "厂", "M", "1"),
                                K3ProtocolFixture.row(
                                        "1", "2", "B", "C", "P", "1", "F", "厂", "M", "1"));
                f.failure = wrapper + "-page";
                rejected(adapter(f, true), 1005000006, 502);
                assertThat(f.orderOffsets).containsExactly(0, 2);
                f.failure = wrapper + "-org";
                assertThatThrownBy(() -> adapter(f, true).organizations())
                        .isInstanceOfSatisfying(
                                BusinessException.class,
                                error -> {
                                    assertThat(error.getError().code()).isEqualTo(1005000006);
                                    assertThat(error.getParams().toString())
                                            .doesNotContain(
                                                    "SYNTHETIC",
                                                    "fixture-secret",
                                                    "fixture-account");
                                    assertThat(error.getMessage()).doesNotContain("SYNTHETIC");
                                });
            }
        }
    }

    @Test
    void missingBooleanOrScalarLoginResultsKeepTheLoginFailureReason() {
        try (var f = new K3ProtocolFixture()) {
            for (String response :
                    java.util.List.of(
                            "{}",
                            "null",
                            "true",
                            "\"OK\"",
                            "{\"LoginResultType\":true}",
                            "{\"LoginResultType\":\"1\"}")) {
                f.responses.put("login", response);
                message(() -> adapter(f, true).orders(null), 1005000002, "LoginResultType != 1");
            }
            f.responses.put("login", "{\"LoginResultType\":0,\"Message\":123}");
            message(() -> adapter(f, true).orders(null), 1005000002, "123");
        }
    }

    @Test
    void numericLoginResultsAndEmptySessionUseThePrototypeSemantics() {
        try (var f = new K3ProtocolFixture()) {
            // 此样例验证无会话的成功登录；查询端允许匿名空结果，正常协议测试仍严格校验Cookie。
            f.responses.put("query", "[]");
            for (String response :
                    java.util.List.of(
                            "{\"LoginResultType\":1.0}",
                            "{\"LoginResultType\":1.25}",
                            "{\"LoginResultType\":1,\"KDSVCSessionId\":\"   \"}")) {
                f.responses.put("login", response);
                assertThat(adapter(f, true).orders(null)).isEmpty();
            }
            f.responses.put("login", "{\"LoginResultType\":1e100000}");
            message(() -> adapter(f, true).orders(null), 1005000002, "LoginResultType != 1");
        }
    }

    @Test
    void validJsonScalarsAndObjectsAreQueryFailuresInsteadOfDecodeFailures() {
        try (var f = new K3ProtocolFixture()) {
            for (String response : java.util.List.of("{}", "null", "true", "123", "\"OK\"")) {
                f.responses.put("query", response);
                message(() -> adapter(f, true).orders(null), 1005000006, "unexpected response");
                message(() -> adapter(f, true).organizations(), 1005000006, "unexpected response");
            }
        }
    }

    @Test
    void queryMessageValuesAreTrimmedBeforeJoiningAndEmptyIsDistinctFromMissing() {
        try (var f = new K3ProtocolFixture()) {
            f.responses.put(
                    "query",
                    "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false,\"Errors\":["
                            + "{\"Message\":\" \\u0085组织权限不足\\u0085 \"},{\"Message\":123},"
                            + "{\"Message\":false},{\"Message\":null}]}}}");
            message(() -> adapter(f, true).orders(null), 1005000006, "组织权限不足; 123; false");
            f.responses.put(
                    "query",
                    "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false,\"Errors\":[{\"Message\":\"   \"}]}}}");
            message(() -> adapter(f, true).orders(null), 1005000006, "");
            f.responses.put("query", "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false}}}");
            message(() -> adapter(f, true).orders(null), 1005000006, "查询失败");
        }
    }

    @Test
    void missingViewStatusFailsWhileEmptySuccessfulDocumentsMeanNoPlannedLines() {
        try (var f = new K3ProtocolFixture()) {
            for (String response : java.util.List.of("{}", "null", "\"OK\"")) {
                f.responses.put("view", response);
                message(() -> adapter(f, true).orders("B"), 1005000006, "View failed");
            }
            for (String response :
                    java.util.List.of(
                            "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false,\"MsgCode\":10.0}}}",
                            "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false,\"MsgCode\":10.25}}}",
                            "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":true},\"Result\":{}}}",
                            "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":true},\"Result\":{\"TreeEntity\":[]}}}")) {
                f.responses.put("view", response);
                assertThat(adapter(f, true).orders("B")).isEmpty();
            }
            f.responses.put("view", "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false}}}");
            message(() -> adapter(f, true).orders("B"), 1005000006, "查询失败");
        }
    }
}
