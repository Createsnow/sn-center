package com.adtek.framework.security;

import com.adtek.common.core.Actor;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** 仅认证转换器在签名、会话和当前权限校验后创建；不持有原始 bearer token。 */
public final class ActorAuthenticationToken extends AbstractAuthenticationToken {
    private final Actor actor;
    private final String sessionId;

    public ActorAuthenticationToken(Actor actor, String sessionId) {
        super(actor.permissions().stream().map(SimpleGrantedAuthority::new).toList());
        this.actor = actor;
        this.sessionId = sessionId;
        super.setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Actor getPrincipal() {
        return actor;
    }

    @Override
    public String getName() {
        return Long.toString(actor.userId());
    }

    public String sessionId() {
        return sessionId;
    }
}
