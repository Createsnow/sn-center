package com.adtek.framework.security;

import java.util.List;

/** 业务模块按稳定角色代码管理人员账号；身份模块保留部门、当前授权、并发版本和审计职责。 */
public interface AccountAdministrationPort {
    Profile read(String id);

    String create(Create command);

    void update(String id, Update command);

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
        PASSWORD_WEAK
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
