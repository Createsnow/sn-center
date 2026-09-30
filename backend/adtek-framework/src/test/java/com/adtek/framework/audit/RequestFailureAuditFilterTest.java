package com.adtek.framework.audit;

import static org.assertj.core.api.Assertions.*;

import com.adtek.common.core.Actor;
import com.adtek.framework.security.ActorAuthenticationToken;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.*;

import java.io.IOException;
import java.util.*;

class RequestFailureAuditFilterTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/unsafe/value");
        request.setServletPath("/api/unsafe/value");
        request.setAttribute("traceId", "test-trace");
        return request;
    }

    @Test
    void anonymousRejectionDoesNotTrustHeadersOrReadPayload() throws Exception {
        var events = new ArrayList<RequestFailureAuditPort.Event>();
        var filter = new RequestFailureAuditFilter(events::add, (method, path) -> "/api/**");
        var request = request();
        request.addHeader("Authorization", "Bearer secret");
        request.addHeader("X-Actor-Id", "123");
        request.setQueryString("password=secret");
        request.setContent("password=secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> {
                    RequestAuditState.error(request, 1003000004);
                    ((jakarta.servlet.http.HttpServletResponse) res).setStatus(403);
                });
        assertThat(events)
                .containsExactly(
                        new RequestFailureAuditPort.Event(
                                null, "POST", "/api/**", 403, 1003000004, "test-trace"));
    }

    @Test
    void verifiedActorIsCapturedAndCommittedBusinessFailureIsNotDuplicated() throws Exception {
        var events = new ArrayList<RequestFailureAuditPort.Event>();
        var filter = new RequestFailureAuditFilter(events::add, (method, path) -> "/api/**");
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new ActorAuthenticationToken(
                                new Actor(9007199254741001L, 1, false, Map.of()), "session"));
        var first = request();
        filter.doFilter(
                first,
                new MockHttpServletResponse(),
                (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(403));
        assertThat(events.getFirst().actorId()).isEqualTo(9007199254741001L);
        var second = request();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(second));
        filter.doFilter(
                second,
                new MockHttpServletResponse(),
                (req, res) -> {
                    RequestAuditState.failureCommitted();
                    ((jakarta.servlet.http.HttpServletResponse) res).setStatus(409);
                });
        assertThat(events).hasSize(1);
    }

    @Test
    void persistenceFailureDoesNotChangeRejectionOrHideOriginalException() throws Exception {
        var filter =
                new RequestFailureAuditFilter(
                        event -> {
                            throw new IllegalStateException("not logged");
                        },
                        (method, path) -> "/api/**");
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request(),
                response,
                (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(401));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        request(),
                                        new MockHttpServletResponse(),
                                        (req, res) -> {
                                            throw new ServletException("original");
                                        }))
                .isInstanceOf(ServletException.class)
                .hasMessage("original");
    }

    @Test
    void successAndNonApiTrafficDoNotCreateFailureEvents() throws IOException, ServletException {
        var events = new ArrayList<RequestFailureAuditPort.Event>();
        var filter = new RequestFailureAuditFilter(events::add, (method, path) -> "/api/**");
        filter.doFilter(request(), new MockHttpServletResponse(), (req, res) -> {});
        var health = request();
        health.setServletPath("/actuator/health/readiness");
        filter.doFilter(
                health,
                new MockHttpServletResponse(),
                (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(503));
        assertThat(events).isEmpty();
    }
}
