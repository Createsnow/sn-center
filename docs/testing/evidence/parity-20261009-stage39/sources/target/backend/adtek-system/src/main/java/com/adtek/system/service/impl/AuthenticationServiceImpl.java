package com.adtek.system.service.impl;

import com.adtek.common.annotation.RateLimiter;
import com.adtek.common.core.Actor;
import com.adtek.common.core.BusinessException;
import com.adtek.framework.audit.AuthenticationEventObserver.Outcome;
import com.adtek.framework.security.AuthorizationPort;
import com.adtek.framework.security.CommandValidator;
import com.adtek.framework.security.CurrentActor;
import com.adtek.framework.security.JwtTokenCodec;
import com.adtek.framework.security.SecuritySettings;
import com.adtek.framework.security.SessionStore;
import com.adtek.system.constant.ErrorCodeConstants;
import com.adtek.system.convert.UserConvert;
import com.adtek.system.domain.SysUserEntity;
import com.adtek.system.dto.LanguageChangeDTO;
import com.adtek.system.dto.LoginCommandDTO;
import com.adtek.system.dto.PasswordChangeDTO;
import com.adtek.system.dto.RefreshCommandDTO;
import com.adtek.system.mapper.IdentityMapper;
import com.adtek.system.service.AuthenticationAuditService;
import com.adtek.system.service.AuthenticationService;
import com.adtek.system.service.support.LoginFailureGuard;
import com.adtek.system.service.support.PasswordPolicy;
import com.adtek.system.vo.AccessTokenVO;
import com.adtek.system.vo.UserProfileVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/** 人员认证用例。DB 当前版本是权限变更的权威，Redis 不缓存可长期复用的权限快照。 */
@Service
public class AuthenticationServiceImpl implements AuthenticationService {
    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationServiceImpl.class);
    private final IdentityMapper identities;
    private final AuthorizationPort authorizations;
    private final CurrentActor current;
    private final SessionStore sessions;
    private final JwtTokenCodec tokens;
    private final PasswordEncoder passwords;
    private final SecuritySettings settings;
    private final CommandValidator validator;
    private final UserConvert convert;
    private final AuthenticationAuditService audit;
    private final LoginFailureGuard loginGuard;
    private final String dummyHash;

    public AuthenticationServiceImpl(
            IdentityMapper identities,
            AuthorizationPort authorizations,
            CurrentActor current,
            SessionStore sessions,
            JwtTokenCodec tokens,
            PasswordEncoder passwords,
            SecuritySettings settings,
            CommandValidator validator,
            UserConvert convert,
            AuthenticationAuditService audit,
            LoginFailureGuard loginGuard) {
        this.identities = identities;
        this.authorizations = authorizations;
        this.current = current;
        this.sessions = sessions;
        this.tokens = tokens;
        this.passwords = passwords;
        this.settings = settings;
        this.validator = validator;
        this.convert = convert;
        this.audit = audit;
        this.loginGuard = loginGuard;
        // 不存在的账号也走相同哈希成本；该值不是可登录的账号或默认密码。
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Override
    @RateLimiter(policy = "auth-login")
    @Transactional
    public AuthenticationResult login(LoginCommandDTO command) {
        validator.requireValid(command);
        try {
            return authenticate(command);
        } catch (BusinessException error) {
            if (error.getError().equals(ErrorCodeConstants.LOGIN_LOCKED))
                rejected(
                        command.username(),
                        null,
                        Outcome.LOCKED,
                        false,
                        Integer.valueOf(error.getParams().get("minutes")));
            throw error;
        }
    }

    private AuthenticationResult authenticate(LoginCommandDTO command) {
        loginGuard.check(command.username());
        var user = identities.findByUsername(command.username());
        boolean matched =
                matches(command.password(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !"USER".equals(user.getPrincipalType()) || !matched) {
            rejected(
                    command.username(),
                    user != null && "USER".equals(user.getPrincipalType()) ? user.getId() : null,
                    Outcome.CREDENTIALS_REJECTED,
                    loginGuard.failed(command.username()),
                    null);
            throw new BusinessException(ErrorCodeConstants.LOGIN_FAILED);
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            rejected(command.username(), user.getId(), Outcome.DISABLED, false, null);
            throw new BusinessException(ErrorCodeConstants.ACCOUNT_DISABLED);
        }
        if (passwords.upgradeEncoding(user.getPasswordHash())
                && identities.upgradePasswordHash(
                                user.getId(),
                                user.getSecurityVersion(),
                                user.getPasswordHash(),
                                passwords.encode(command.password()))
                        != 1) {
            throw new BusinessException(ErrorCodeConstants.LOGIN_FAILED);
        }
        Actor actor =
                authorizations
                        .loadCurrent(user.getId())
                        .orElseThrow(() -> new BusinessException(ErrorCodeConstants.LOGIN_FAILED));
        loginGuard.succeeded(command.username());
        if (actor.securityVersion() != user.getSecurityVersion()
                || identities.recordLogin(actor.userId(), user.getSecurityVersion()) != 1) {
            throw new BusinessException(ErrorCodeConstants.LOGIN_FAILED);
        }
        var session = sessions.create(actor.userId(), actor.securityVersion(), refreshLifetime());
        audit.recordSuccess(actor.userId(), AuthenticationAuditService.Action.LOGIN);
        return issue(actor, user, session.sessionId(), session.credential());
    }

    private void rejected(
            String username, Long userId, Outcome outcome, boolean lockStarted, Integer minutes) {
        try {
            audit.recordLoginFailure(username, userId, outcome, lockStarted, minutes);
            com.adtek.framework.audit.RequestAuditState.failureCommitted();
        } catch (RuntimeException error) {
            LOG.error(
                    "Rejected-login audit unavailable traceId={} exceptionType={}",
                    org.slf4j.MDC.get("traceId"),
                    error.getClass().getSimpleName());
        }
    }

    @Override
    @RateLimiter(policy = "auth-refresh")
    @Transactional
    public AuthenticationResult refresh(RefreshCommandDTO command) {
        if (command == null) {
            throw new BusinessException(ErrorCodeConstants.ACCOUNT_CHANGED);
        }
        var rotated = sessions.rotate(command.credential(), refreshLifetime());
        var actor = authorizations.loadCurrent(rotated.userId());
        if (actor.isEmpty()
                || actor.get().securityVersion() != rotated.securityVersion()
                || !"USER".equals(actor.get().principalType())) {
            sessions.revoke(rotated.sessionId());
            throw new BusinessException(ErrorCodeConstants.ACCOUNT_CHANGED);
        }
        var user = identities.findById(rotated.userId());
        audit.recordSuccess(rotated.userId(), AuthenticationAuditService.Action.REFRESH);
        return issue(actor.get(), user, rotated.sessionId(), rotated.credential());
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileVO me() {
        Actor actor = current.requireAuthenticated();
        requirePerson(actor);
        var fresh =
                authorizations
                        .loadCurrent(actor.userId())
                        .orElseThrow(
                                () -> new BusinessException(ErrorCodeConstants.ACCOUNT_CHANGED));
        if (fresh.securityVersion() != actor.securityVersion()) {
            throw new BusinessException(ErrorCodeConstants.ACCOUNT_CHANGED);
        }
        return convert.toProfile(identities.findById(actor.userId()), fresh.permissions());
    }

    @Override
    @Transactional
    public void logout() {
        var authentication = current.token();
        requirePerson(authentication.getPrincipal());
        sessions.revoke(authentication.sessionId());
        audit.recordSuccess(
                authentication.getPrincipal().userId(), AuthenticationAuditService.Action.LOGOUT);
    }

    @Override
    @Transactional
    public AuthenticationResult changePassword(PasswordChangeDTO command) {
        validator.requireValid(command);
        Actor actor = current.requireAuthenticated();
        try {
            return changePassword(actor, command);
        } catch (BusinessException error) {
            Outcome outcome =
                    error.getError().equals(ErrorCodeConstants.PASSWORD_WRONG)
                            ? Outcome.PASSWORD_WRONG
                            : error.getError().equals(ErrorCodeConstants.PASSWORD_SAME)
                                    ? Outcome.PASSWORD_SAME
                                    : error.getError().equals(ErrorCodeConstants.PASSWORD_WEAK)
                                            ? Outcome.PASSWORD_WEAK
                                            : null;
            if (outcome != null) {
                try {
                    audit.recordPasswordFailure(actor.userId(), outcome);
                    com.adtek.framework.audit.RequestAuditState.failureCommitted();
                } catch (RuntimeException unavailable) {
                    LOG.error(
                            "Password rejection audit unavailable traceId={} exceptionType={}",
                            org.slf4j.MDC.get("traceId"),
                            unavailable.getClass().getSimpleName());
                }
            }
            throw error;
        }
    }

    private AuthenticationResult changePassword(Actor actor, PasswordChangeDTO command) {
        var user = lockCurrent(actor);
        if (!matches(command.oldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCodeConstants.PASSWORD_WRONG);
        }
        PasswordPolicy.requireStrong(command.newPassword());
        if (matches(command.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCodeConstants.PASSWORD_SAME);
        }
        if (identities.changePassword(
                        actor.userId(),
                        actor.securityVersion(),
                        passwords.encode(command.newPassword()))
                != 1) {
            throw new BusinessException(ErrorCodeConstants.ACCOUNT_CHANGED);
        }
        // 所有请求/刷新都重新比较 MySQL 版本，提交后不依赖 Redis 删除成功才失效。
        audit.recordSuccess(actor.userId(), AuthenticationAuditService.Action.PASSWORD_CHANGE);
        var fresh =
                authorizations
                        .loadCurrent(actor.userId())
                        .orElseThrow(
                                () -> new BusinessException(ErrorCodeConstants.ACCOUNT_CHANGED));
        var session = sessions.create(fresh.userId(), fresh.securityVersion(), refreshLifetime());
        return issue(
                fresh,
                identities.findById(fresh.userId()),
                session.sessionId(),
                session.credential());
    }

    @Override
    @Transactional
    public void changeLanguage(LanguageChangeDTO command) {
        validator.requireValid(command);
        Actor actor = current.requireAuthenticated();
        var user = lockCurrent(actor);
        if (!user.getLanguage().equals(command.language())) {
            identities.updateLanguage(actor.userId(), command.language());
            audit.recordLanguageChange(actor.userId(), user.getLanguage(), command.language());
        }
    }

    private SysUserEntity lockCurrent(Actor actor) {
        requirePerson(actor);
        var user = identities.lockById(actor.userId());
        if (user == null || user.getSecurityVersion() != actor.securityVersion()) {
            throw new BusinessException(ErrorCodeConstants.ACCOUNT_CHANGED);
        }
        return user;
    }

    private AuthenticationResult issue(
            Actor actor, SysUserEntity user, String sid, String credential) {
        var token = tokens.issue(actor, sid);
        return new AuthenticationResult(
                new AccessTokenVO(
                        token.accessToken(),
                        "Bearer",
                        token.expiresAt(),
                        convert.toProfile(user, actor.permissions())),
                credential);
    }

    private Duration refreshLifetime() {
        return Duration.ofHours(settings.refreshHours());
    }

    private static void requirePerson(Actor actor) {
        if (!"USER".equals(actor.principalType())) {
            throw new BusinessException(com.adtek.framework.constant.ErrorCodeConstants.FORBIDDEN);
        }
    }

    private boolean matches(String candidate, String encoded) {
        try {
            return passwords.matches(candidate, encoded);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
