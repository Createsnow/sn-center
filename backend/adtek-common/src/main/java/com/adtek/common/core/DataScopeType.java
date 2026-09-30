package com.adtek.common.core;

/** v1.1 固定的角色部门范围编码；未知值拒绝，不能回退为全部。 */
public enum DataScopeType {
    ALL(1), CUSTOM_DEPARTMENTS(2), DEPARTMENT(3), DEPARTMENT_AND_DESCENDANTS(4), SELF(5);
    private final int code;
    DataScopeType(int code) { this.code = code; }
    public int code() { return code; }
    public static DataScopeType fromCode(int code) {
        for (var value : values()) { if (value.code == code) { return value; } }
        throw new IllegalStateException("Unknown data scope in authoritative role");
    }
}
