package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import com.adtek.common.core.BusinessException;
import com.adtek.integration.k3.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/** 独立回环HTTP协议验证错误归属；不连接真实ERP，不把外部原报文传给页面。 */
class K3ErrorsTest {
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
                            assertThat(error.getParams()).isEmpty();
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
    void transportStatusAndTimeoutHaveUnavailableCode() {
        try (var f = new K3ProtocolFixture()) {
            f.failure = "http-down";
            rejected(adapter(f, true), 1005000003, 503);
            f.failure = "timeout";
            rejected(adapter(f, true), 1005000003, 503);
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
                                    assertThat(error.getParams()).isEmpty();
                                    assertThat(error.getMessage()).doesNotContain("SYNTHETIC");
                                });
            }
        }
    }
}
