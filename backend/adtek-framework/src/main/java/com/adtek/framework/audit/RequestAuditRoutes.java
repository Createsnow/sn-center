package com.adtek.framework.audit;

/** 装配模块提供代码白名单，将URL归一为路由模板；未知路径返回固定占位，不保存原始输入。 */
public interface RequestAuditRoutes {
    String template(String method, String servletPath);
}
