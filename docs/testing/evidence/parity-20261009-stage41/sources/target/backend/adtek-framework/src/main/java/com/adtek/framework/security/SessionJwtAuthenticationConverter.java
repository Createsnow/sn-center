package com.adtek.framework.security;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.constant.ErrorCodeConstants;

import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** 每次验签后核对 MySQL 凭据版本，并装配当前权限、工厂范围及授权并发版本。 */
@Component
public class SessionJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {
    private final SessionStore sessions;
    private final AuthorizationPort authorizations;

    public SessionJwtAuthenticationConverter(
            SessionStore sessions, AuthorizationPort authorizations) {
        this.sessions = sessions;
        this.authorizations = authorizations;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            long userId = Long.parseLong(jwt.getSubject());
            long version = Long.parseLong(jwt.getClaimAsString("sv"));
            String sid = jwt.getClaimAsString("sid");
            sessions.requireActive(sid, userId, version);
            var actor =
                    authorizations
                            .loadCurrent(userId)
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCodeConstants.SESSION_INVALID));
            if (actor.credentialVersion() != version
                    || !actor.principalType()
                            .equals(
                                    jwt.getClaimAsString("pt") == null
                                            ? "USER"
                                            : jwt.getClaimAsString("pt"))) {
                throw new BusinessException(ErrorCodeConstants.SESSION_INVALID);
            }
            return new ActorAuthenticationToken(actor, sid);
        } catch (BusinessException error) {
            throw new ActorAuthenticationException(error.getError(), error);
        } catch (DataAccessException error) {
            throw new ActorAuthenticationException(
                    ErrorCodeConstants.DEPENDENCY_UNAVAILABLE, error);
        } catch (NumberFormatException error) {
            throw new ActorAuthenticationException(ErrorCodeConstants.SESSION_INVALID, error);
        }
    }
}
