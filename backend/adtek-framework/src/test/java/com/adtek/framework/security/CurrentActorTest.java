package com.adtek.framework.security;

import static org.assertj.core.api.Assertions.*;

import com.adtek.common.core.Actor;
import com.adtek.common.core.AuthorizationScope;
import com.adtek.common.core.BusinessException;
import com.adtek.common.core.DataScope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.Set;

class CurrentActorTest {
    private final CurrentActor current = new CurrentActor();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsUntypedPrincipalAndAnonymousCallers() {
        assertThatThrownBy(current::requireAuthenticated).isInstanceOf(BusinessException.class);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new TestingAuthenticationToken("admin", null, "system:user:add"));
        assertThatThrownBy(() -> current.requirePermission("system:user:add"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void passwordChangeRestrictionStillAppliesToExplicitPermissions() {
        Actor actor =
                new Actor(
                        1,
                        1,
                        true,
                        Map.of(
                                "system:user:add",
                                new AuthorizationScope(
                                        new DataScope(true, Set.of(), false), true, Set.of())));
        SecurityContextHolder.getContext()
                .setAuthentication(new ActorAuthenticationToken(actor, "session"));
        assertThat(current.requireAuthenticated()).isEqualTo(actor);
        assertThatThrownBy(() -> current.requirePermission("system:user:add"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getError().code()).isEqualTo(1_003_000_005));
    }

    @Test
    void grantsOnlyCurrentExplicitPermission() {
        Actor actor =
                new Actor(
                        1,
                        1,
                        false,
                        Map.of(
                                "system:user:list",
                                new AuthorizationScope(
                                        new DataScope(true, Set.of(), false), true, Set.of())));
        SecurityContextHolder.getContext()
                .setAuthentication(new ActorAuthenticationToken(actor, "session"));
        assertThat(current.requirePermission("system:user:list")).isEqualTo(actor);
        assertThatThrownBy(() -> current.requirePermission("system:user:add"))
                .isInstanceOf(BusinessException.class);
    }
}
