package com.adtek.sn.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.adtek.common.constant.CommonErrorCode;
import com.adtek.framework.security.AuthPrincipal;
import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.SnSettings;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** FailureAuditPort 契约：被拒绝的写请求在独立事务里记一条 FAIL 痕迹，动作按路径、要素取自请求体，路径记模板。 */
class FailureAuditServiceImplTest {
    private final AuditServiceImpl audit = mock(AuditServiceImpl.class);
    private final SnSettings settings = mock(SnSettings.class);
    private final FailureAuditServiceImpl port = new FailureAuditServiceImpl(audit, settings);

    @Test
    void recordsFailureWithFieldsFromTheBody() {
        when(settings.initAdminEmpNo()).thenReturn("admin");
        AuthPrincipal p =
                new AuthPrincipal(
                        5L,
                        "E5",
                        "张三",
                        "operator",
                        "110",
                        List.of("110"),
                        AuthPrincipal.PAGE,
                        "s1");
        byte[] body =
                "{\"factoryCode\":\"120\",\"pi\":\"PI-1\",\"reason\":\"r\",\"qty\":3}"
                        .getBytes(StandardCharsets.UTF_8);
        port.recordFailure(
                p,
                "POST",
                "/api/v1/sn/delivery/batches/PS-1/void",
                "/api/v1/sn/delivery/batches/{batchNo}/void",
                body,
                SnErrorCode.BATCH_NOT_FOUND,
                Map.of("batch_no", "PS-1"));
        ArgumentCaptor<AuditEntryDTO> e = ArgumentCaptor.forClass(AuditEntryDTO.class);
        ArgumentCaptor<CurrentUser> u = ArgumentCaptor.forClass(CurrentUser.class);
        verify(audit).recordIsolated(u.capture(), e.capture());
        assertThat(u.getValue().empNo()).isEqualTo("E5");
        AuditEntryDTO x = e.getValue();
        assertThat(x.action).isEqualTo(AuditAction.SN_BATCH_VOID);
        assertThat(x.result).isEqualTo("FAIL");
        assertThat(x.errorMsg).isEqualTo("批次 PS-1 不存在");
        assertThat(x.factoryCode).isEqualTo("120");
        assertThat(x.piNo).isEqualTo("PI-1");
        assertThat(x.qty).isEqualTo(3L);
        assertThat(x.detail).isEqualTo("POST /api/v1/sn/delivery/batches/{batchNo}/void");
    }

    @Test
    void nonJsonBodyRecordsOnlyActionAndReason() {
        when(settings.initAdminEmpNo()).thenReturn("admin");
        AuthPrincipal p =
                new AuthPrincipal(
                        1L, "admin", "管理员", "admin", null, List.of(), AuthPrincipal.PAGE, "s");
        port.recordFailure(
                p,
                "POST",
                "/api/v1/sn/users",
                "/api/v1/sn/users",
                "xx".getBytes(StandardCharsets.UTF_8),
                CommonErrorCode.FORBIDDEN,
                Map.of());
        ArgumentCaptor<AuditEntryDTO> e = ArgumentCaptor.forClass(AuditEntryDTO.class);
        verify(audit).recordIsolated(any(), e.capture());
        assertThat(e.getValue().action).isEqualTo(AuditAction.USER_CREATE);
        assertThat(e.getValue().factoryCode).isNull();
    }
}
