package com.adtek.framework.security;

import java.util.List;

/** 业务模块按稳定角色代码管理人员账号；身份模块保留部门、当前授权、并发版本和审计职责。 */
public interface AccountAdministrationPort {
    Profile read(String id);

    String create(Create command);

    void update(String id, Update command);

    /** 保留凭据版本的业务启停；停用期间所有请求仍拒绝。 */
    String status(String id, boolean enabled, String version);

    /** 业务初始密码重置可用于本人及原密码；递增凭据版本并强制改密。 */
    String resetPassword(String id, String password, String version);

    record Profile(
            String id,
            String username,
            String displayName,
            List<String> roleCodes,
            List<String> factoryIds,
            String version) {}

    record Create(
            String username,
            String displayName,
            List<String> roleCodes,
            List<String> factoryIds,
            String password) {
        @Override
        public String toString() {
            return "AccountCreate[redacted]";
        }
    }

    record Update(
            String displayName, List<String> roleCodes, List<String> factoryIds, String version) {}

    enum Reason {
        DUPLICATE_USERNAME,
        PASSWORD_WEAK,
        SELF_DISABLE,
        LAST_ADMIN
    }

    /** 只携带公开拒绝类型，不把凭据或底层异常带入业务错误。 */
    final class Rejected extends RuntimeException {
        private final Reason reason;

        public Rejected(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
