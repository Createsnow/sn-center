package com.adtek.framework.integration;

/** 金蝶调用失败：登录失败或查询失败，message 为可展示给管理员的原因（不含凭证）。 */
public class K3Exception extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** 失败种类。 */
    public enum Kind {
        LOGIN,
        QUERY
    }

    private final Kind kind;

    public K3Exception(Kind kind, String message) {
        super(message, null, false, false);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
