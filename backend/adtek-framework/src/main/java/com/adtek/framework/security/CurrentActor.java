package com.adtek.framework.security;

import com.adtek.common.core.Actor;
import com.adtek.common.core.BusinessException;
import com.adtek.framework.constant.ErrorCodeConstants;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 用例入口的可信主体与权限核验；按钮或 Controller 的检查不能替代本边界。 */
@Component
public class CurrentActor {
    public Actor requireAuthenticated() {
        return token().getPrincipal();
    }

    public Actor requirePermission(String permission) {
        Actor actor = requireAuthenticated();
        if (actor.mustChangePassword()) {
            throw new BusinessException(ErrorCodeConstants.PASSWORD_CHANGE_REQUIRED);
        }
        if (!actor.permissions().contains(permission)) {
            throw new BusinessException(ErrorCodeConstants.FORBIDDEN);
        }
        return actor;
    }

    public ActorAuthenticationToken token() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof ActorAuthenticationToken token) || !token.isAuthenticated()) {
            throw new BusinessException(ErrorCodeConstants.SESSION_INVALID);
        }
        return token;
    }
}
