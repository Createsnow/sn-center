package com.adtek.common.core;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

class DataScopeTest {
    @Test
    void emptyScopeDoesNotBecomeAllAndSelfDoesNotGrantOtherDepartments() {
        var empty = new DataScope(false, Set.of(), false);
        assertThat(empty.permits(1, 1, null)).isFalse();
        var self = new DataScope(false, Set.of(), true);
        assertThat(self.permits(1, 1, null)).isTrue();
        assertThat(self.permits(1, 2, 10L)).isFalse();
        var departments = new DataScope(false, Set.of(10L), false);
        assertThat(departments.permits(1, 2, 10L)).isTrue();
        assertThat(departments.permits(1, 2, 11L)).isFalse();
        assertThat(departments.permits(1, 2, null)).isFalse();
    }

    @Test
    void departmentAndResourceScopesRemainIndependent() {
        var scope =
                new AuthorizationScope(new DataScope(true, Set.of(), false), false, Set.of(20L));
        assertThat(scope.permitsResource(20)).isTrue();
        assertThat(scope.permitsResource(21)).isFalse();
    }

    @Test
    void crossResourceReadGrantDoesNotBroadenOwnResourceWriteGrant() {
        var departments = new DataScope(true, Set.of(), false);
        var actor =
                new Actor(
                        1,
                        1,
                        false,
                        Map.of(
                                "sample:resource:list",
                                        new AuthorizationScope(departments, true, Set.of()),
                                "sample:resource:update",
                                        new AuthorizationScope(departments, false, Set.of(20L))));
        assertThat(actor.grants().get("sample:resource:list").permitsResource(21)).isTrue();
        assertThat(actor.grants().get("sample:resource:update").permitsResource(20)).isTrue();
        assertThat(actor.grants().get("sample:resource:update").permitsResource(21)).isFalse();
    }
}
