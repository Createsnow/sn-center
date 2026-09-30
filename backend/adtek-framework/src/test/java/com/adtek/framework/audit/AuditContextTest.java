package com.adtek.framework.audit;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;

import java.util.*;

class AuditContextTest {
    @AfterEach
    void clear() {
        AuditContext.clear();
    }

    @Test
    void diffIncludesOnlyActualChangesAndRepresentsMissingValuesAsNull() {
        AuditContext.begin(Set.of("displayName", "deptId"));
        AuditContext.record(
                "9007199254741001",
                Map.of("displayName", "before"),
                Map.of("displayName", "after", "deptId", "9007199254741002"));
        assertThat(AuditContext.get().diff()).containsOnlyKeys("displayName", "deptId");
        var department = (Map<?, ?>) AuditContext.get().diff().get("deptId");
        assertThat(department.get("before")).isNull();
        assertThat(department.get("after")).isEqualTo("9007199254741002");
        AuditContext.record("1", Map.of("displayName", "same"), Map.of("displayName", "same"));
        assertThat(AuditContext.get().diff()).isEmpty();
    }

    @Test
    void arbitraryFieldsSecretDeclarationsAndPasswordStringsAreRejected() {
        assertThatThrownBy(() -> AuditContext.begin(Set.of("accessToken")))
                .isInstanceOf(IllegalArgumentException.class);
        AuditContext.begin(Set.of("mustChangePassword"));
        assertThatThrownBy(
                        () -> AuditContext.record("1", Map.of(), Map.of("passwordHash", "secret")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                AuditContext.record(
                                        "1", Map.of(), Map.of("mustChangePassword", "secret")))
                .isInstanceOf(IllegalArgumentException.class);
        AuditContext.record(
                "1", Map.of("mustChangePassword", false), Map.of("mustChangePassword", true));
        assertThat(AuditContext.get().diff()).containsOnlyKeys("mustChangePassword");
    }

    @Test
    void recordingWithoutAnOperationBoundaryIsRejected() {
        assertThatThrownBy(() -> AuditContext.record("1", Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
