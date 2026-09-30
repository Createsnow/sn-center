package com.adtek.common.core;

import java.util.Set;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

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
    void departmentAndFactoryScopesRemainIndependent() {
        var scope = new AuthorizationScope(new DataScope(true, Set.of(), false), false, Set.of(20L));
        assertThat(scope.permitsFactory(20)).isTrue();
        assertThat(scope.permitsFactory(21)).isFalse();
    }

    @Test
    void crossFactoryReadGrantDoesNotBroadenOwnFactoryWriteGrant() {
        var departments = new DataScope(true, Set.of(), false);
        var actor = new Actor(1, 1, false, Map.of(
                "sn:query:list", new AuthorizationScope(departments, true, Set.of()),
                "sn:acquire:execute", new AuthorizationScope(departments, false, Set.of(20L))));
        assertThat(actor.grants().get("sn:query:list").permitsFactory(21)).isTrue();
        assertThat(actor.grants().get("sn:acquire:execute").permitsFactory(20)).isTrue();
        assertThat(actor.grants().get("sn:acquire:execute").permitsFactory(21)).isFalse();
    }
}
