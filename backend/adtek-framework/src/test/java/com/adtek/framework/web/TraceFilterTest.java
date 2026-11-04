package com.adtek.framework.web;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceFilterTest {
    @Test
    void generatesTrustedTraceAndCleansThreadContextOnFailure() {
        var request = new MockHttpServletRequest("GET", "/api/test");
        request.addHeader("X-Trace-Id", "external-untrusted");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(
                        () ->
                                new TraceFilter()
                                        .doFilter(
                                                request,
                                                response,
                                                (req, res) -> {
                                                    assertThat(MDC.get("traceId"))
                                                            .isEqualTo(
                                                                    response.getHeader(
                                                                            "X-Trace-Id"));
                                                    assertThat(TraceFilter.current(request))
                                                            .matches("[0-9a-f-]{36}");
                                                    assertThat(TraceFilter.current(request))
                                                            .isNotEqualTo("external-untrusted");
                                                    throw new IllegalStateException(
                                                            "simulated downstream failure");
                                                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("traceId")).isNull();
    }
}
