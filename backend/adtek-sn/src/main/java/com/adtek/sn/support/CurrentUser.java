package com.adtek.sn.support;

import com.adtek.common.constant.CommonErrorCode;
import com.adtek.common.core.BusinessException;
import com.adtek.framework.security.AuthPrincipal;
import com.adtek.sn.constant.SnErrorCode;

import java.util.List;

/**
 * 当前操作人（原系统 CurrentUser）：只由服务端会话解析的 {@link AuthPrincipal} 或系统任务构造，不接受客户端提交。
 *
 * @param factories 授权的生产组织：普通操作员、审计员 = 绑定的一个或多个；MES 接口账户 = 本组织；系统管理员为空 = 全部
 * @param superAdmin 是否为唯一的系统管理员账户（工号 = SN_INIT_ADMIN_EMP_NO）
 */
public record CurrentUser(
        Long id,
        String empNo,
        String name,
        String role,
        String factoryCode,
        String source,
        String traceId,
        List<String> factories,
        boolean superAdmin) {
    public static final String PAGE = AuthPrincipal.PAGE;
    public static final String API = AuthPrincipal.API;
    public static final String SYSTEM = AuthPrincipal.SYSTEM;

    public CurrentUser {
        factories = factories == null ? List.of() : List.copyOf(factories);
    }

    public static CurrentUser system() {
        return new CurrentUser(
                null, "system", "系统", Util.ADMIN, null, SYSTEM, Trace.id(), List.of(), false);
    }

    /** 当前请求的操作人；未登录抛 401。 */
    public static CurrentUser current(String adminEmpNo) {
        AuthPrincipal p =
                AuthPrincipal.current()
                        .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHENTICATED));
        return new CurrentUser(
                p.userId(),
                p.empNo(),
                p.name(),
                p.role(),
                p.factoryCode(),
                p.source(),
                Trace.id(),
                p.factories(),
                Util.isSuperAdmin(p.empNo(), p.role(), adminEmpNo));
    }

    public boolean isAdmin() {
        return Util.ADMIN.equals(role);
    }

    public boolean isOperator() {
        return Util.OPERATOR.equals(role);
    }

    public boolean isAuditor() {
        return Util.AUDITOR.equals(role);
    }

    public boolean isMes() {
        return Util.MES.equals(role);
    }

    /** 数据范围受限（系统管理员以外的账户）。 */
    public boolean bound() {
        return !isAdmin();
    }

    public String scopeText() {
        return String.join("、", factories);
    }

    public boolean canRead(String factory) {
        return isAdmin() || (factory != null && factories.contains(factory));
    }

    /** 接口允许的角色（原系统路由依赖 AdminDep / OperatorDep / AuditDep）。 */
    public CurrentUser requireRole(String... roles) {
        for (String r : roles) {
            if (r.equals(role)) {
                return this;
            }
        }
        throw Biz.err(SnErrorCode.FORBIDDEN);
    }

    /** 系统管理员 + 普通操作员。 */
    public CurrentUser requireOperator() {
        return requireRole(Util.ADMIN, Util.OPERATOR);
    }

    /** 只有系统管理员角色。 */
    public CurrentUser requireAdmin() {
        return requireRole(Util.ADMIN);
    }

    /** 系统管理员 + 审计员。 */
    public CurrentUser requireAudit() {
        return requireRole(Util.ADMIN, Util.AUDITOR);
    }

    /** 账户管理：只有系统管理员账户。 */
    public CurrentUser requireSuperAdmin() {
        if (!superAdmin || SYSTEM.equals(source)) {
            throw Biz.err(SnErrorCode.USER_MANAGE_FORBIDDEN);
        }
        return this;
    }

    /** 记录涉及的生产组织里至少有一个在授权范围内，否则拒绝。 */
    public void checkRead(String... fs) {
        for (String f : fs) {
            if (canRead(f)) {
                return;
            }
        }
        throw Biz.err(SnErrorCode.FACTORY_FORBIDDEN, "factory", scopeText());
    }

    /** 写操作：系统管理员不限；普通操作员涉及的生产组织须全部在授权范围内（空值不计）；其他角色不能写。 */
    public void checkWrite(String... fs) {
        if (isAdmin()) {
            return;
        }
        if (!isOperator()) {
            throw Biz.err(SnErrorCode.FORBIDDEN);
        }
        for (String f : fs) {
            if (f != null && !f.isEmpty() && !factories.contains(f)) {
                throw Biz.err(SnErrorCode.FACTORY_FORBIDDEN, "factory", scopeText());
            }
        }
    }

    /** 写操作（转组织）的来源生产组织：系统管理员必须指定；普通操作员只能是授权组织，只授权一个时可不填。 */
    public String writeFactory(String requested) {
        String req = Util.trim(requested);
        if (isAdmin()) {
            if (req.isEmpty()) {
                throw Biz.err(SnErrorCode.FACTORY_NOT_FOUND, "factory", "");
            }
            return req;
        }
        if (isOperator()) {
            if (!req.isEmpty()) {
                checkWrite(req);
                return req;
            }
            if (factories.size() == 1) {
                return factories.getFirst();
            }
            throw Biz.err(SnErrorCode.FACTORY_NOT_FOUND, "factory", "");
        }
        throw Biz.err(SnErrorCode.FORBIDDEN);
    }

    /** 读范围的生产组织：null = 全部（只有系统管理员）；指定了组织须在授权范围内，没指定 = 全部授权的组织。 */
    public List<String> readFactories(String requested) {
        String req = Util.trim(requested);
        if (!req.isEmpty()) {
            checkRead(req);
            return List.of(req);
        }
        return isAdmin() ? null : factories;
    }
}
