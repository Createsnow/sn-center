package com.adtek.sn.constant;

/** 操作痕迹动作（与原系统 app/services/audit.py 一致，前端按动作名本地化）。 */
public final class AuditAction {
    private AuditAction() {}

    public static final String LOGIN = "LOGIN";
    public static final String PASSWORD_CHANGE = "PASSWORD_CHANGE";
    public static final String MES_AUTH = "MES_AUTH";
    public static final String MES_AUTH_LOCK = "MES_AUTH_LOCK";
    public static final String MES_AUTH_UNLOCK = "MES_AUTH_UNLOCK";
    public static final String USER_CREATE = "USER_CREATE";
    public static final String USER_UPDATE = "USER_UPDATE";
    public static final String USER_DISABLE = "USER_DISABLE";
    public static final String USER_ENABLE = "USER_ENABLE";
    public static final String USER_RESET_PWD = "USER_RESET_PWD";
    public static final String USER_RESET_SECRET = "USER_RESET_SECRET";
    public static final String USER_VIEW_SECRET = "USER_VIEW_SECRET";
    public static final String FACTORY_SYNC = "FACTORY_SYNC";
    public static final String FACTORY_MES_CONFIG = "FACTORY_MES_CONFIG";
    public static final String FACTORY_MES_TOGGLE = "FACTORY_MES_TOGGLE";
    public static final String FACTORY_MES_TEST = "FACTORY_MES_TEST";
    public static final String K3_CONFIG = "K3_CONFIG";
    public static final String K3_CONFIG_TEST = "K3_CONFIG_TEST";
    public static final String ORDER_SYNC = "ORDER_SYNC";
    public static final String ORDER_ATTACH_FILL = "ORDER_ATTACH_FILL";
    public static final String ORDER_LENGTH_FILL = "ORDER_LENGTH_FILL";
    public static final String ORDER_UNIT_FILL = "ORDER_UNIT_FILL";
    public static final String ORDER_ALERT = "ORDER_ALERT";
    public static final String ORDER_ALERT_HANDLE = "ORDER_ALERT_HANDLE";
    public static final String ORDER_SUSPEND = "ORDER_SUSPEND";
    public static final String ORDER_RESUME = "ORDER_RESUME";
    public static final String RULE_CREATE = "RULE_CREATE";
    public static final String RULE_UPDATE = "RULE_UPDATE";
    public static final String RULE_VERSION = "RULE_VERSION";
    public static final String RULE_RENAME = "RULE_RENAME";
    public static final String RULE_CATEGORY = "RULE_CATEGORY";
    public static final String RULE_PI_BIND = "RULE_PI_BIND";
    public static final String RULE_PI_UNBIND = "RULE_PI_UNBIND";
    public static final String RULE_LINE_SELECT = "RULE_LINE_SELECT";
    public static final String LINE_CONFIG_REVOKE = "LINE_CONFIG_REVOKE";
    public static final String RULE_PREFIX_VERSION = "RULE_PREFIX_VERSION";
    public static final String RULE_DELETE = "RULE_DELETE";
    public static final String SN_GENERATE = "SN_GENERATE";
    public static final String SN_GEN_CANCEL = "SN_GEN_CANCEL";
    public static final String SN_REVOKE = "SN_REVOKE";
    public static final String SN_DELETE = "SN_DELETE";
    public static final String SN_IMPORT = "SN_IMPORT";
    public static final String SN_IMPORT_DELETE = "SN_IMPORT_DELETE";
    public static final String SN_ALLOCATE = "SN_ALLOCATE";
    public static final String SN_BATCH_CREATE = "SN_BATCH_CREATE";
    public static final String SN_PUSH = "SN_PUSH";
    public static final String SN_PUSH_RESUME = "SN_PUSH_RESUME";
    public static final String SN_PULL = "SN_PULL";
    public static final String SN_RECEIPT = "SN_RECEIPT";
    public static final String SN_BATCH_MANUAL = "SN_BATCH_MANUAL";
    public static final String SN_BATCH_VOID = "SN_BATCH_VOID";
    public static final String SN_ALERT_HANDLE = "SN_ALERT_HANDLE";
    public static final String SN_READ = "SN_READ";
    public static final String TRANSFER_BILL = "TRANSFER_BILL";
    public static final String TRANSFER_DIRECT = "TRANSFER_DIRECT";
    public static final String AUDIT_ARCHIVE = "AUDIT_ARCHIVE";
    public static final String AUDIT_ARCHIVE_PURGE = "AUDIT_ARCHIVE_PURGE";
    public static final String SELF_CHECK = "SELF_CHECK";
    public static final String OTHER = "OTHER";
}
