package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import com.adtek.system.service.impl.SystemRequestAuditRoutes;
import com.adtek.system.service.support.AuditChanges;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class AuditProjectionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void historyIsProjectedWithWhitelistAndPasswordMaterialCannotHideInBooleanFlag() {
        var projected =
                AuditChanges.project(
                        json,
                        """
                        {"displayName":{"before":"旧名","after":"新名"},"password":{"before":"secret","after":"secret"},
                         "mustChangePassword":{"before":"secret","after":true},"token":"secret","reason":"credentialsRejected"}
                        """);
        assertThat(projected.redacted()).isTrue();
        assertThat(projected.changes()).hasSize(1);
        assertThat(projected.changes().getFirst().field()).isEqualTo("displayName");
        assertThat(projected.reason()).isEqualTo("credentialsRejected");
    }

    @Test
    void preciseRelationshipIdsSurviveWhileMalformedOrOversizedJsonIsMarked() {
        var projected =
                AuditChanges.project(
                        json,
                        """
                        {"roleIds":{"before":[],"after":["9007199254741001"]},"errorCode":"1002000008"}
                        """);
        assertThat(projected.changes().getFirst().after()).isEqualTo("9007199254741001");
        assertThat(projected.redacted()).isFalse();
        assertThat(projected.failureCode()).isEqualTo("1002000008");
        assertThat(AuditChanges.project(json, "[").redacted()).isTrue();
        assertThat(AuditChanges.project(json, " ".repeat(65537)).redacted()).isTrue();
    }

    @Test
    void routeRegistryNeverCopiesDynamicPathOrUnrecognizedUrls() {
        var routes = new SystemRequestAuditRoutes();
        assertThat(routes.template("POST", "/api/v1/system/users/secret-or-invalid-id/password"))
                .isEqualTo("/api/v1/system/users/{id}/password");
        assertThat(routes.template("GET", "/api/v1/system/audits/options"))
                .isEqualTo("/api/v1/system/audits/options");
        assertThat(routes.template("GET", "/api/secret-token")).isEqualTo("/api/**");
    }
}
