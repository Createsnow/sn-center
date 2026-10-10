package com.adtek.system.service.impl;

import com.adtek.framework.audit.AuditEventObserver;
import com.adtek.framework.audit.AuthenticationEventObserver;
import com.adtek.system.mapper.OperLogMapper;
import com.adtek.system.service.AuthenticationAuditService;
import com.adtek.system.service.support.LoginGuardSettings;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Service
public class AuthenticationAuditServiceImpl implements AuthenticationAuditService {
    private final OperLogMapper logs;
    private final ObjectMapper json;
    private final java.util.List<AuthenticationEventObserver> observers;
    private final LoginGuardSettings guard;

    public AuthenticationAuditServiceImpl(
            OperLogMapper logs,
            ObjectMapper json,
            java.util.List<AuthenticationEventObserver> observers,
            LoginGuardSettings guard) {
        this.logs = logs;
        this.json = json;
        this.observers = java.util.List.copyOf(observers);
        this.guard = guard;
    }

    @Override
    @Transactional
    public void recordSuccess(long userId, Action action) {
        var actor = logs.actorSnapshot(userId);
        logs.insert(
                userId,
                "auth:" + action.name(),
                Long.toString(userId),
                true,
                "{}",
                MDC.get("traceId"),
                actor == null ? null : actor.departmentId(),
                actor == null ? null : actor.username());
        if (action == Action.LOGIN || action == Action.PASSWORD_CHANGE)
            publish(
                    userId,
                    "",
                    AuthenticationEventObserver.Action.valueOf(action.name()),
                    AuthenticationEventObserver.Outcome.SUCCESS,
                    false,
                    null);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginFailure(
            String username,
            Long userId,
            AuthenticationEventObserver.Outcome outcome,
            boolean lockStarted,
            Integer remainingMinutes) {
        if (!username.matches("[A-Za-z0-9_-]{2,32}"))
            throw new IllegalArgumentException("Invalid audit username");
        boolean locked = outcome == AuthenticationEventObserver.Outcome.LOCKED;
        logs.insert(
                null,
                "auth:LOGIN",
                null,
                false,
                json.writeValueAsString(
                        Map.of(
                                "reason",
                                locked ? "credentialsLocked" : "credentialsRejected",
                                "lockStarted",
                                lockStarted)),
                MDC.get("traceId"),
                null,
                null);
        publish(
                userId,
                username,
                AuthenticationEventObserver.Action.LOGIN,
                outcome,
                lockStarted,
                lockStarted ? Integer.valueOf((guard.lockSeconds() + 59) / 60) : remainingMinutes);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPasswordFailure(long userId, AuthenticationEventObserver.Outcome outcome) {
        if (!java.util.Set.of(
                        AuthenticationEventObserver.Outcome.PASSWORD_WRONG,
                        AuthenticationEventObserver.Outcome.PASSWORD_SAME,
                        AuthenticationEventObserver.Outcome.PASSWORD_WEAK)
                .contains(outcome))
            throw new IllegalArgumentException("Invalid password audit outcome");
        var actor = logs.actorSnapshot(userId);
        logs.insert(
                userId,
                "auth:PASSWORD_CHANGE",
                Long.toString(userId),
                false,
                "{}",
                MDC.get("traceId"),
                actor == null ? null : actor.departmentId(),
                actor == null ? null : actor.username());
        publish(
                userId,
                "",
                AuthenticationEventObserver.Action.PASSWORD_CHANGE,
                outcome,
                false,
                null);
    }

    private void publish(
            Long userId,
            String attemptedUsername,
            AuthenticationEventObserver.Action action,
            AuthenticationEventObserver.Outcome outcome,
            boolean lockStarted,
            Integer minutes) {
        var actor = userId == null ? null : logs.actorSnapshot(userId);
        var snapshot =
                new AuditEventObserver.ActorSnapshot(
                        actor == null ? attemptedUsername : actor.username(),
                        actor == null ? "" : actor.displayName(),
                        actor == null ? "[]" : actor.roles(),
                        "USER");
        String client = "-";
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof
                org.springframework.web.context.request.ServletRequestAttributes request) {
            // 不信任任意转发头；反向代理的可信地址由服务器连接层配置。
            client = java.util.Objects.toString(request.getRequest().getRemoteAddr(), "-");
        }
        var event =
                new AuthenticationEventObserver.Event(
                        userId,
                        snapshot,
                        action,
                        outcome,
                        client,
                        minutes,
                        lockStarted,
                        MDC.get("traceId"));
        for (var observer : observers) observer.recorded(event);
    }

    @Override
    @Transactional
    public void recordLanguageChange(long userId, String previous, String current) {
        String changes =
                json.writeValueAsString(
                        Map.of("language", Map.of("before", previous, "after", current)));
        var actor = logs.actorSnapshot(userId);
        logs.insert(
                userId,
                "auth:LANGUAGE_CHANGE",
                Long.toString(userId),
                true,
                changes,
                MDC.get("traceId"),
                actor == null ? null : actor.departmentId(),
                actor == null ? null : actor.username());
    }
}
