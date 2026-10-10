package com.adtek.system.service.impl;

import static com.adtek.common.annotation.OperLog.BusinessType.*;

import com.adtek.common.annotation.OperLog;
import com.adtek.common.annotation.RateLimiter;
import com.adtek.common.core.*;
import com.adtek.framework.audit.AuditContext;
import com.adtek.framework.audit.ManagementAuditPort;
import com.adtek.framework.security.*;
import com.adtek.system.convert.UserConvert;
import com.adtek.system.dto.*;
import com.adtek.system.mapper.*;
import com.adtek.system.service.ServiceClientService;
import com.adtek.system.vo.*;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

/** 人员管理与服务凭据分开；共享稳定身份主键仅用于审计、版本撤销和任务所有权。 */
@Service
public class ServiceClientServiceImpl implements ServiceClientService {
    private static final String MANAGE = "system:service-client:manage";
    private static final Set<String> ALLOWED =
            Set.of("sn:acquire:view", "sn:acquire:take", "sn:acquire:callback", "sn:items:read");
    private final ServiceClientMapper clients;
    private final IdentityMapper users;
    private final AuthorizationMutationPort mutations;
    private final AuthorizationPort authorization;
    private final CurrentActor current;
    private final FactoryMembershipPort factories;
    private final CommandValidator validator;
    private final PasswordEncoder encoder;
    private final SessionStore sessions;
    private final JwtTokenCodec tokens;
    private final ManagementAuditPort audit;
    private final UserConvert convert;
    private final String dummy;

    public ServiceClientServiceImpl(
            ServiceClientMapper clients,
            IdentityMapper users,
            AuthorizationMutationPort mutations,
            AuthorizationPort authorization,
            CurrentActor current,
            FactoryMembershipPort factories,
            CommandValidator validator,
            PasswordEncoder encoder,
            SessionStore sessions,
            JwtTokenCodec tokens,
            ManagementAuditPort audit,
            UserConvert convert) {
        this.clients = clients;
        this.users = users;
        this.mutations = mutations;
        this.authorization = authorization;
        this.current = current;
        this.factories = factories;
        this.validator = validator;
        this.encoder = encoder;
        this.sessions = sessions;
        this.tokens = tokens;
        this.audit = audit;
        this.convert = convert;
        dummy = encoder.encode(secret());
    }

    private Actor administrator(boolean lock) {
        var actor = current.requirePermission(MANAGE);
        if (lock) actor = mutations.lockCurrent(actor);
        if (!"USER".equals(actor.principalType())
                || !actor.grants().get(MANAGE).departments().all()
                || !actor.grants().containsKey("system:security:administer")
                || !actor.grants().get("system:security:administer").departments().all())
            throw forbidden();
        return actor;
    }

    private long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw invalid();
        }
    }

    private void grant(Actor actor, long user, ServiceClientCreateDTO command) {
        validator.requireValid(command);
        if (!ALLOWED.containsAll(command.permissions())) throw invalid();
        var ids = new HashSet<Long>();
        command.factoryIds().forEach(value -> ids.add(id(value)));
        factories.replace(actor, user, ids);
        clients.clear(user);
        command.permissions().stream()
                .sorted()
                .forEach(permission -> clients.grant(user, permission));
    }

    private void visible(Actor actor, long user) {
        var scope = actor.grants().get("sn:factory:assign");
        if (scope == null
                || (!scope.allFactories()
                        && !scope.factoryIds().containsAll(factories.factoryIds(user))))
            throw forbidden();
    }

    private com.adtek.system.domain.SysUserEntity target(Actor actor, long id, String version) {
        var user = users.lockById(id);
        if (user == null || !"SERVICE".equals(user.getPrincipalType())) throw missing();
        visible(actor, id);
        if (user.getSecurityVersion() != id(version))
            throw new BusinessException(com.adtek.system.constant.ErrorCodeConstants.USER_CONFLICT);
        return user;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    @OperLog(
            title = "MES服务主体",
            action = "system:service-client:create",
            businessType = INSERT,
            fields = {
                "displayName",
                "enabled",
                "factoryIds",
                "permissions",
                "authorizationVersion"
            })
    public ServiceCredentialVO create(ServiceClientCreateDTO command) {
        validator.requireValid(command);
        var actor = administrator(true);
        if (!ALLOWED.containsAll(command.permissions())) throw invalid();
        String client = "mes_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                secret = secret();
        var user = convert.fromServiceClient(command);
        user.setUsername(client);
        user.setPasswordHash(encoder.encode(secret()));
        user.setPrincipalType("SERVICE");
        user.setEnabled(true);
        user.setMustChangePassword(false);
        user.setSecurityVersion(1L);
        user.setLanguage("zh-CN");
        user.setCreateBy(actor.userId());
        user.setUpdateBy(actor.userId());
        clients.insert(user);
        clients.create(user.getId(), client, encoder.encode(secret));
        grant(actor, user.getId(), command);
        var value = view(user.getId());
        AuditContext.record(value.id(), Map.of(), snapshot(value));
        return new ServiceCredentialVO(value, secret);
    }

    @Override
    @Transactional(readOnly = true)
    public ServiceClientVO get(String value) {
        var actor = administrator(false);
        long id = id(value);
        visible(actor, id);
        return view(id);
    }

    private ServiceClientVO view(long id) {
        var client = clients.byUser(id);
        var user = users.findById(id);
        if (client == null || user == null) throw missing();
        return convert.serviceClient(
                user,
                client.clientId(),
                factories.factoryIds(id).stream()
                        .map(Object::toString)
                        .collect(java.util.stream.Collectors.toSet()),
                Set.copyOf(clients.permissions(id)));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    @OperLog(
            title = "MES服务主体",
            action = "system:service-client:update",
            businessType = UPDATE,
            fields = {
                "displayName",
                "enabled",
                "factoryIds",
                "permissions",
                "authorizationVersion"
            })
    public ServiceClientVO update(String value, ServiceClientUpdateDTO command) {
        validator.requireValid(command);
        var actor = administrator(true);
        long id = id(value);
        target(actor, id, command.version());
        var before = snapshot(view(id));
        grant(actor, id, command.grant());
        clients.updateClient(id, command.enabled(), command.grant().displayName(), actor.userId());
        var after = view(id);
        AuditContext.record(value, before, snapshot(after));
        return after;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    @OperLog(
            title = "MES服务主体",
            action = "system:service-client:rotate",
            businessType = UPDATE,
            fields = {"authorizationVersion"})
    public ServiceCredentialVO rotate(String value, String version) {
        var actor = administrator(true);
        long id = id(value);
        var user = target(actor, id, version);
        String secret = secret();
        clients.rotate(id, encoder.encode(secret));
        clients.updateClient(id, user.getEnabled(), user.getDisplayName(), actor.userId());
        var after = view(id);
        AuditContext.record(
                value,
                Map.of("authorizationVersion", version),
                Map.of("authorizationVersion", after.version()));
        return new ServiceCredentialVO(after, secret);
    }

    @Override
    @RateLimiter(policy = "mes-token")
    @Transactional
    public ServiceTokenVO token(ServiceTokenDTO command) {
        validator.requireValid(command);
        var client = clients.client(command.clientId());
        boolean match =
                encoder.matches(
                        command.clientSecret(), client == null ? dummy : client.secretHash());
        var actor =
                client == null
                        ? Optional.<Actor>empty()
                        : authorization.loadCurrent(client.userId());
        if (!match || actor.isEmpty() || !"SERVICE".equals(actor.get().principalType()))
            throw new BusinessException(com.adtek.system.constant.ErrorCodeConstants.LOGIN_FAILED);
        // 只给调用方短时access token；Redis会话同样短寿命，不返回可用于人员refresh的凭据。
        var session =
                sessions.create(
                        actor.get().userId(),
                        actor.get().credentialVersion(),
                        Duration.ofMinutes(30));
        var token = tokens.issue(actor.get(), session.sessionId());
        fact(actor.get(), "token", actor.get().userId());
        return new ServiceTokenVO(token.accessToken(), "Bearer", token.expiresAt());
    }

    private void fact(Actor actor, String action, long id) {
        audit.success(
                new ManagementAuditPort.Event(
                        actor.userId(),
                        "MES服务主体",
                        "system:service-client:" + action,
                        "OTHER",
                        Long.toString(id),
                        Map.of("principalType", "SERVICE"),
                        org.slf4j.MDC.get("traceId")));
    }

    // 仅管理白名单字段进入diff；秘密、散列、令牌以及整个请求对象永不作为快照。
    private Map<String, Object> snapshot(ServiceClientVO client) {
        return Map.of(
                "displayName",
                client.displayName(),
                "enabled",
                client.enabled(),
                "factoryIds",
                client.factoryIds().stream().sorted().toList(),
                "permissions",
                String.join(", ", client.permissions().stream().sorted().toList()),
                "authorizationVersion",
                client.version());
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static BusinessException forbidden() {
        return new BusinessException(com.adtek.framework.constant.ErrorCodeConstants.FORBIDDEN);
    }

    private static BusinessException invalid() {
        return new BusinessException(com.adtek.common.constant.ErrorCodeConstants.INVALID_ARGUMENT);
    }

    private static BusinessException missing() {
        return new BusinessException(com.adtek.common.constant.ErrorCodeConstants.NOT_FOUND);
    }
}
