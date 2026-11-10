package com.adtek.sn.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.adtek.framework.security.PrincipalPort;
import com.adtek.sn.domain.UserEntity;
import com.adtek.sn.support.Passwords;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** PrincipalPort 契约：按主键读当前状态与授权范围；不存在为空；停用、MES 接口账户、缺少授权范围如实反映。 */
class PrincipalServiceImplTest {
    private final UserServiceImpl users = mock(UserServiceImpl.class);
    private final PrincipalServiceImpl port = new PrincipalServiceImpl(users);

    private static UserEntity user(long id, String role, String status) {
        UserEntity u = new UserEntity();
        u.id = id;
        u.empNo = "E" + id;
        u.name = "N" + id;
        u.role = role;
        u.factoryCode = "110";
        u.status = status;
        u.pwdChangedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        return u;
    }

    @Test
    void missingAccountIsEmpty() {
        when(users.byId(9L)).thenReturn(null);
        assertThat(port.load(9L)).isEmpty();
    }

    @Test
    void operatorWithScope() {
        UserEntity u = user(1, "operator", UserServiceImpl.ACTIVE);
        when(users.byId(1L)).thenReturn(u);
        when(users.readScope(1L, "operator", "110")).thenReturn(List.of("110", "120"));
        PrincipalPort.Snapshot s = port.load(1L).orElseThrow();
        assertThat(s.factories()).containsExactly("110", "120");
        assertThat(s.active()).isTrue();
        assertThat(s.interfaceOnly()).isFalse();
        assertThat(s.scopeMissing()).isFalse();
        assertThat(s.passwordVersion()).isEqualTo(Passwords.version(u.pwdChangedAt));
    }

    @Test
    void disabledMesAndScopeless() {
        when(users.byId(2L)).thenReturn(user(2, "mes", "DISABLED"));
        when(users.readScope(2L, "mes", "110")).thenReturn(List.of("110"));
        Optional<PrincipalPort.Snapshot> mes = port.load(2L);
        assertThat(mes.orElseThrow().active()).isFalse();
        assertThat(mes.orElseThrow().interfaceOnly()).isTrue();
        when(users.byId(3L)).thenReturn(user(3, "auditor", UserServiceImpl.ACTIVE));
        when(users.readScope(3L, "auditor", "110")).thenReturn(List.of());
        assertThat(port.load(3L).orElseThrow().scopeMissing()).isTrue();
    }
}
