package com.adtek.sn.constant;

import com.adtek.common.core.ErrorCode;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SN 模块错误码（号段 1002）：HTTP 状态 + 中文模板（{name} 为参数占位，日志、审计与 MES 应答用）；前端按 key 本地化，参数名与模板占位一致。
 *
 * <p>与原 Python 版错误码一一对应（名称相同）；会话认证类错误见 framework 的 AuthErrorCode。
 */
public enum SnErrorCode implements ErrorCode {
    LOGIN_FAILED(1_002_000001, "error.sn.loginFailed", 401, "工号或密码错误"),
    MES_NO_PAGE_LOGIN(1_002_000002, "error.sn.mesNoPageLogin", 403, "MES 接口账户不能登录页面"),
    FORBIDDEN(1_002_000003, "error.sn.forbidden", 403, "当前角色无权执行该操作"),
    FACTORY_FORBIDDEN(1_002_000004, "error.sn.factoryForbidden", 403, "只能操作授权生产组织的数据：{factory}"),
    VALIDATION(1_002_000005, "error.sn.validation", 422, "参数不合法：{detail}"),
    REQUEST_TOO_LARGE(1_002_000006, "error.sn.requestTooLarge", 413, "请求体超过 {max} MB"),
    CONFLICT_RETRY(1_002_000007, "error.sn.conflictRetry", 409, "数据正被其他操作占用，请稍后重试"),
    PI_BUSY(1_002_000008, "error.sn.piBusy", 409, "PI {pi} 正在进行其他操作（生成、删号、分配或转移），请稍后再试"),
    REASON_REQUIRED(1_002_000009, "error.sn.reasonRequired", 400, "原因必填（不超过 500 字）"),
    USER_NOT_FOUND(1_002_000010, "error.sn.userNotFound", 404, "账户不存在"),
    USER_EMP_NO_INVALID(1_002_000011, "error.sn.userEmpNoInvalid", 400, "工号须为 3–32 位字母、数字和 -"),
    USER_EMP_NO_EXISTS(1_002_000012, "error.sn.userEmpNoExists", 409, "工号已存在：{emp_no}"),
    USER_NAME_REQUIRED(1_002_000013, "error.sn.userNameRequired", 400, "姓名必填，不超过 64 字"),
    USER_ROLE_INVALID(1_002_000014, "error.sn.userRoleInvalid", 400, "角色无效"),
    USER_ROLE_FIXED(1_002_000015, "error.sn.userRoleFixed", 400, "MES 接口账户与页面账户不能互相改角色"),
    USER_FACTORY_REQUIRED(
            1_002_000016, "error.sn.userFactoryRequired", 400, "普通操作员、审计员必须至少授权一个生产组织"),
    USER_MES_FACTORY_REQUIRED(
            1_002_000017, "error.sn.userMesFactoryRequired", 400, "MES 接口账户必须绑定一个生产组织"),
    USER_ADMIN_NO_FACTORY(1_002_000018, "error.sn.userAdminNoFactory", 400, "系统管理员不绑定生产组织"),
    USER_ADMIN_UNIQUE(
            1_002_000019, "error.sn.userAdminUnique", 400, "系统管理员只有一个，不能新建系统管理员或把账户改为系统管理员"),
    USER_PASSWORD_WEAK(
            1_002_000020,
            "error.sn.userPasswordWeak",
            400,
            "密码须为 4–64 位，同时含字母和数字，只能用字母、数字和 ! @ # $ & * -"),
    USER_PASSWORD_WRONG(1_002_000021, "error.sn.userPasswordWrong", 400, "原密码不正确"),
    USER_PASSWORD_SAME(1_002_000022, "error.sn.userPasswordSame", 400, "新密码不能与原密码相同"),
    USER_SELF_DISABLE(1_002_000023, "error.sn.userSelfDisable", 400, "不能停用自己的账户"),
    USER_LAST_ADMIN(1_002_000024, "error.sn.userLastAdmin", 400, "系统须保留至少一名启用中的管理员"),
    USER_MANAGE_FORBIDDEN(1_002_000025, "error.sn.userManageForbidden", 403, "只有系统管理员可以管理账户"),
    USER_SUPER_ADMIN_FIXED(
            1_002_000026, "error.sn.userSuperAdminFixed", 400, "系统管理员必须保持启用，不能改角色或停用"),
    USER_NOT_MES(1_002_000027, "error.sn.userNotMes", 400, "该账户不是 MES 接口账户"),
    USER_SECRET_UNAVAILABLE(
            1_002_000028,
            "error.sn.userSecretUnavailable",
            400,
            "该账户的密钥无法查看（更换过 SN_SECRET 或早于密钥可查看功能创建），请重置密钥"),
    USER_IP_LIST_INVALID(1_002_000029, "error.sn.userIpListInvalid", 400, "来源地址白名单格式不正确：{value}"),
    FACTORY_NOT_FOUND(1_002_000030, "error.sn.factoryNotFound", 400, "生产组织不存在：{factory}"),
    FACTORY_DISABLED(1_002_000031, "error.sn.factoryDisabled", 400, "生产组织 {factory} 在金蝶已禁用"),
    MES_CONFIG_INVALID(1_002_000032, "error.sn.mesConfigInvalid", 400, "MES 对接配置不合法：{detail}"),
    MES_TEST_REQUIRED(
            1_002_000033,
            "error.sn.mesTestRequired",
            400,
            "接口地址、凭证（无认证时不需要）已填且连接测试通过后才能开启「对接 MES」"),
    MES_TEST_FAILED(1_002_000034, "error.sn.mesTestFailed", 502, "连接测试未通过：{message}"),
    MES_DISABLE_BLOCKED(
            1_002_000035,
            "error.sn.mesDisableBlocked",
            409,
            "生产组织 {factory} 还有未完成的推送：{detail}；请先续推、等待 MES 回执，或由总部人工确认 / 作废"),
    MES_NOT_ENABLED(1_002_000036, "error.sn.mesNotEnabled", 400, "生产组织 {factory} 未开启「对接 MES」"),
    K3_CONFIG_MISSING(1_002_000037, "error.sn.k3ConfigMissing", 503, "金蝶配置缺失：{missing}"),
    K3_LOGIN_FAILED(1_002_000038, "error.sn.k3LoginFailed", 502, "金蝶登录失败：{message}"),
    K3_QUERY_FAILED(1_002_000039, "error.sn.k3QueryFailed", 502, "金蝶查询失败：{message}"),
    K3_SECRET_UNREADABLE(
            1_002_000040,
            "error.sn.k3SecretUnreadable",
            503,
            "金蝶应用密钥无法解密（SN_SECRET 已更换）：请在生产订单页「金蝶连接」重新填写应用密钥"),
    K3_CONFIG_INVALID(1_002_000041, "error.sn.k3ConfigInvalid", 400, "金蝶连接参数不合法：{detail}"),
    K3_TEST_FAILED(1_002_000042, "error.sn.k3TestFailed", 502, "金蝶连接测试未通过，未保存：{message}"),
    K3_RESET_FAILED(
            1_002_000043, "error.sn.k3ResetFailed", 502, "用 .env 的参数连接测试未通过，未恢复默认：{message}"),
    ORDER_BILL_REQUIRED(1_002_000044, "error.sn.orderBillRequired", 400, "请输入单据编号"),
    ORDER_NOT_FOUND(
            1_002_000045, "error.sn.orderNotFound", 404, "生产订单 {bill_no} 不在快照中（不是已审核，或尚未同步）"),
    ORDER_LINE_NOT_FOUND(
            1_002_000046, "error.sn.orderLineNotFound", 404, "物料行 {bill_no} / {seq} 不在快照中"),
    ORDER_SYNC_BUSY(1_002_000047, "error.sn.orderSyncBusy", 409, "订单同步正在进行，请稍后再试"),
    ATTACH_INVALID(1_002_000048, "error.sn.attachInvalid", 400, "附件项次不能为空，不超过 64 字"),
    ATTACH_FILL_K3(1_002_000049, "error.sn.attachFillK3", 409, "金蝶已有附件项次，不能在页面修改"),
    LENGTH_FILL_INVALID(1_002_000050, "error.sn.lengthFillInvalid", 400, "米数不超过 64 字；统一设置时不能为空"),
    LENGTH_FILL_CHINESE(
            1_002_000051, "error.sn.lengthFillChinese", 400, "米数 {value} 含中文，请改成不含中文的米数"),
    UNIT_FILL_INVALID(1_002_000052, "error.sn.unitFillInvalid", 400, "产品单位不超过 64 字"),
    UNIT_FILL_CHINESE(
            1_002_000053, "error.sn.unitFillChinese", 400, "产品单位 {value} 含中文，请填写不含中文的单位（如 M、m、L）"),
    LINE_EDIT_GENERATED(
            1_002_000054,
            "error.sn.lineEditGenerated",
            409,
            "物料行 {bill_no} / {seq} 有号，附件项次、米数和产品单位不能修改；先删除该行的号再修改"),
    LINE_EDIT_ALLOCATED(
            1_002_000055,
            "error.sn.lineEditAllocated",
            409,
            "物料行 {bill_no} / {seq} 有已分配的号，附件项次、米数和产品单位不能修改；先删除该行的号再修改"),
    ALERT_NOT_FOUND(1_002_000056, "error.sn.alertNotFound", 404, "订单变更提醒不存在或已处理"),
    ALERT_ACTION_INVALID(1_002_000057, "error.sn.alertActionInvalid", 400, "该提醒不能这样处理"),
    ALERT_MAP_TARGET_INVALID(
            1_002_000058, "error.sn.alertMapTargetInvalid", 400, "确认对应的目标物料行须是该单据快照中的另一行：{seq}"),
    ALERT_MAP_FROZEN(
            1_002_000059,
            "error.sn.alertMapFrozen",
            409,
            "失去对应的物料行有 {count} 枚号在未确认或失败的批次中，须等批次确认后再确认对应"),
    RULE_NOT_FOUND(1_002_000060, "error.sn.ruleNotFound", 404, "规则不存在"),
    RULE_VERSION_NOT_FOUND(1_002_000061, "error.sn.ruleVersionNotFound", 404, "规则版本不存在"),
    RULE_CODE_INVALID(1_002_000062, "error.sn.ruleCodeInvalid", 400, "规则编码须为 1–64 位字母、数字、下划线或横线"),
    RULE_CODE_EXISTS(1_002_000063, "error.sn.ruleCodeExists", 409, "规则编码已存在：{rule_code}"),
    RULE_NAME_REQUIRED(1_002_000064, "error.sn.ruleNameRequired", 400, "规则名称必填，不超过 128 字"),
    RULE_CATEGORY_INVALID(1_002_000065, "error.sn.ruleCategoryInvalid", 400, "规则只能绑定 PI"),
    RULE_CATEGORY_REQUIRED(
            1_002_000066, "error.sn.ruleCategoryRequired", 400, "须填写规则绑定的 PI（不超过 64 位）"),
    RULE_IN_USE(1_002_000067, "error.sn.ruleInUse", 409, "规则 {rule_code} 已生成过号，不能删除"),
    RULE_ASSIGNED(1_002_000068, "error.sn.ruleAssigned", 409, "规则 {rule_code} 已指定给 PI {pi}，不能删除"),
    RULE_PREFIX_REQUIRED(1_002_000069, "error.sn.rulePrefixRequired", 400, "前缀必填"),
    RULE_AFFIX_INVALID(1_002_000070, "error.sn.ruleAffixInvalid", 400, "米数前、后分隔符各不超过 16 个字符"),
    RULE_LENGTH_PAD_INVALID(
            1_002_000071, "error.sn.ruleLengthPadInvalid", 400, "米数补 0 位数须为 0–32（0 = 不补）"),
    RULE_PREFIX_CONFLICT(1_002_000072, "error.sn.rulePrefixConflict", 409, "前缀{prefix}已被使用，请更换其他。"),
    RULE_BASE_INVALID(1_002_000073, "error.sn.ruleBaseInvalid", 400, "进制仅支持 10 / 16 / 32 / 36"),
    RULE_SEQ_LEN_INVALID(1_002_000074, "error.sn.ruleSeqLenInvalid", 400, "流水位数须为 1–12"),
    RULE_CHARSET_LENGTH(1_002_000075, "error.sn.ruleCharsetLength", 400, "字符表的字符数必须等于进制"),
    RULE_CHARSET_INVALID(1_002_000076, "error.sn.ruleCharsetInvalid", 400, "字符表不能含空格"),
    RULE_CHARSET_DUPLICATE(1_002_000077, "error.sn.ruleCharsetDuplicate", 400, "字符表不能有重复字符"),
    RULE_SN_TOO_LONG(
            1_002_000078, "error.sn.ruleSnTooLong", 400, "前缀 + 流水 + 分隔符 + 米数 + 单位 + 后缀总长不能超过 64"),
    RULE_PREFIX_FORMAT_LOCKED(
            1_002_000079,
            "error.sn.rulePrefixFormatLocked",
            409,
            "前缀 {prefix} 已按 {base} 进制、{seq_len} 位流水使用；同一前缀下进制、流水位数、字符表不能改，须换新前缀"),
    RULE_PREFIX_OVERLAP(
            1_002_000080,
            "error.sn.rulePrefixOverlap",
            409,
            "前缀 {prefix} 与 PI {pi} 的前缀 {other} 一个是另一个的开头，可能编出相同的号，请更换"),
    RULE_PREFIX_CHANGE_REQUIRED(
            1_002_000081,
            "error.sn.rulePrefixChangeRequired",
            409,
            "规则 {rule_code} 版本 {version} 已用于 PI {other_pi}，须为本 PI 填新前缀"),
    RULE_LINE_REQUIRED(1_002_000082, "error.sn.ruleLineRequired", 400, "请选择物料行"),
    RULE_GLOBAL_READONLY(
            1_002_000083, "error.sn.ruleGlobalReadonly", 403, "全局规则只有系统管理员能修改、删除：{rule_code}"),
    RULE_FACTORY_REQUIRED(1_002_000084, "error.sn.ruleFactoryRequired", 400, "请选择规则所属的生产组织"),
    RULE_SEQ_START_INVALID(
            1_002_000085, "error.sn.ruleSeqStartInvalid", 400, "起始号须为正整数，且不超过规则的最大流水 {max}"),
    GEN_SCOPE_INVALID(1_002_000086, "error.sn.genScopeInvalid", 400, "生成范围须为同一张 PI 下的单据或物料行"),
    GEN_NOT_GENERATABLE(1_002_000087, "error.sn.genNotGeneratable", 400, "所选范围不能生成：{detail}"),
    GEN_PI_QUOTA_EMPTY(1_002_000088, "error.sn.genPiQuotaEmpty", 400, "所选范围没有可生成额度"),
    GEN_RULE_EXHAUSTED(
            1_002_000089, "error.sn.genRuleExhausted", 400, "规则位数已用尽：最大流水 {max}，本次需要排到 {end}"),
    GEN_PREVIEW_REQUIRED(1_002_000090, "error.sn.genPreviewRequired", 400, "没有完成本次预演，不能生成"),
    GEN_PREVIEW_EXPIRED(1_002_000091, "error.sn.genPreviewExpired", 400, "预演已过期，请重新预演"),
    GEN_PREVIEW_CHANGED(1_002_000092, "error.sn.genPreviewChanged", 409, "预演条件已改变，请重新预演"),
    GEN_PREVIEW_STALE(
            1_002_000093,
            "error.sn.genPreviewStale",
            409,
            "PI {pi} 的最大号已从 {expected} 变为 {actual}，预演失效，请重新预演"),
    GEN_PREVIEW_USED(1_002_000094, "error.sn.genPreviewUsed", 409, "该预演已用于一次生成，请重新预演"),
    GEN_BUSY(1_002_000095, "error.sn.genBusy", 409, "PI {pi} 正在生成，请稍后再试"),
    GEN_DUPLICATE_SN(
            1_002_000096,
            "error.sn.genDuplicateSn",
            409,
            "完整 SN {sn} 与 PI {pi} 已有的号重复，整次生成已回滚（不自动跳号）"),
    GEN_JOB_NOT_FOUND(1_002_000097, "error.sn.genJobNotFound", 404, "生成任务不存在"),
    GEN_SEQ_RANGE_INVALID(1_002_000098, "error.sn.genSeqRangeInvalid", 400, "起止流水号须为正整数，止号不能小于起号"),
    GEN_IMPORTED_SN(
            1_002_000099,
            "error.sn.genImportedSn",
            409,
            "完整 SN {sn} 是 SN 导入的号（PI {pi}），不能再生成，整次生成已回滚（不自动跳号）"),
    DELETE_TARGET_INVALID(
            1_002_000100, "error.sn.deleteTargetInvalid", 400, "请选择要删除的单据、物料行，或填写 SN（起止号）"),
    DELETE_SN_NOT_FOUND(1_002_000101, "error.sn.deleteSnNotFound", 404, "PI {pi} 下没有 SN {sn}"),
    DELETE_RANGE_INVALID(
            1_002_000102,
            "error.sn.deleteRangeInvalid",
            400,
            "起止 SN 须属于同一套流水（流水归属 PI 相同），止号不能在起号之前"),
    DELETE_NOTHING(1_002_000103, "error.sn.deleteNothing", 400, "所选范围没有可删除的号"),
    GEN_QTY_OVER_MAX(
            1_002_000104,
            "error.sn.genQtyOverMax",
            400,
            "单次最多生成 {max} 枚，所选物料行共 {qty} 枚；请少选几行分次生成，或联系管理员"),
    GEN_CANCELED(1_002_000105, "error.sn.genCanceled", 409, "生成已取消，已写入的号全部删除"),
    GEN_CANCEL_NOT_RUNNING(1_002_000106, "error.sn.genCancelNotRunning", 409, "这次生成已经结束，不能取消"),
    ALLOC_NOTHING(1_002_000107, "error.sn.allocNothing", 400, "所选范围没有可分配的号"),
    PI_REQUIRED(1_002_000108, "error.sn.piRequired", 400, "PI 必填"),
    PUSH_NOTHING(1_002_000109, "error.sn.pushNothing", 400, "生产组织 {factory} 的 PI {pi} 没有需要推送的号或批次"),
    BATCH_NOT_FOUND(1_002_000110, "error.sn.batchNotFound", 404, "批次 {batch_no} 不存在"),
    BATCH_NOT_PENDING(
            1_002_000111, "error.sn.batchNotPending", 409, "批次 {batch_no} 已是 {result}，不能再处理"),
    BATCH_NOT_VOIDABLE(
            1_002_000112,
            "error.sn.batchNotVoidable",
            409,
            "批次 {batch_no} MES 可能已处理（应答无效或不符、回执或内容不符），不能作废，只能由 MES 补交回执或人工确认"),
    BATCH_BASIS_REQUIRED(
            1_002_000113, "error.sn.batchBasisRequired", 400, "须填写依据（如 MES 方书面确认，不超过 500 字）"),
    DALERT_NOT_FOUND(1_002_000114, "error.sn.dalertNotFound", 404, "交付告警不存在或已处理"),
    EXPORT_NOTHING(1_002_000115, "error.sn.exportNothing", 404, "PI {pi} 的单据 {bill_no} 没有已分配的号"),
    IMPORT_FILE_INVALID(1_002_000116, "error.sn.importFileInvalid", 400, "导入文件须为 xlsx 格式"),
    IMPORT_FILE_TOO_LARGE(1_002_000117, "error.sn.importFileTooLarge", 400, "导入文件不能超过 {max} MB"),
    IMPORT_HEADER_INVALID(
            1_002_000118,
            "error.sn.importHeaderInvalid",
            400,
            "导入文件表头缺少：{missing}（须有 {required} 列，{optional} 列选填；按列名认列，顺序不限，其他列不读）"),
    IMPORT_HEADER_DUPLICATE(
            1_002_000119,
            "error.sn.importHeaderDuplicate",
            400,
            "导入文件表头「{column}」出现了不止一次（含别名），不知道读哪一列"),
    IMPORT_EMPTY(1_002_000120, "error.sn.importEmpty", 400, "导入文件没有数据行"),
    IMPORT_TOO_MANY(1_002_000121, "error.sn.importTooMany", 400, "单次最多导入 {max} 行，文件有 {rows} 行"),
    IMPORT_HAS_ISSUES(
            1_002_000122, "error.sn.importHasIssues", 400, "有 {count} 行不能导入，整个文件都没有导入；请改好后重新导入"),
    IMPORT_NOT_FOUND(1_002_000123, "error.sn.importNotFound", 404, "导入单 {import_no} 不存在或已删除"),
    IMPORT_DELETE_ALLOCATED(
            1_002_000124,
            "error.sn.importDeleteAllocated",
            409,
            "导入单 {import_no} 有 {count} 枚号已分配，不能整单删除（全部待分配时才能删除）"),
    IMPORT_CONFLICT(
            1_002_000125, "error.sn.importConflict", 409, "SN {sn} 刚被生成或导入，整个文件都没有导入；请重新检查后导入"),
    TR_SCOPE_INVALID(
            1_002_000126, "error.sn.trScopeInvalid", 400, "转移范围只能是整单转移或按物料编码转移（单个或多个物料编码）"),
    TR_MATERIAL_REQUIRED(1_002_000127, "error.sn.trMaterialRequired", 400, "按物料编码转移时须至少选择一个物料编码"),
    TR_LINE_MODE_INVALID(
            1_002_000128, "error.sn.trLineModeInvalid", 400, "按物料编码转移的方式只能是全部、号段或指定数量"),
    TR_LINE_DUPLICATE(
            1_002_000129, "error.sn.trLineDuplicate", 400, "物料编码 {material}（附件项次 {attach_no}）重复选择"),
    TR_LINE_EMPTY(
            1_002_000130,
            "error.sn.trLineEmpty",
            400,
            "来源单据中没有物料编码 {material}（附件项次 {attach_no}）的号"),
    TR_SN_REQUIRED(1_002_000131, "error.sn.trSnRequired", 400, "按号段转移时起始 SN 必填（只填起始 = 单枚）"),
    TR_QTY_INVALID(1_002_000132, "error.sn.trQtyInvalid", 400, "指定数量须为正整数"),
    TR_QTY_EXCEEDS(
            1_002_000133,
            "error.sn.trQtyExceeds",
            400,
            "物料编码 {material}（附件项次 {attach_no}）可转 {available} 枚，不足指定数量 {qty} 枚"),
    TR_TO_PI_REQUIRED(1_002_000134, "error.sn.trToPiRequired", 400, "目标 PI 必填"),
    TR_TO_BILL_REQUIRED(1_002_000135, "error.sn.trToBillRequired", 400, "目标单据必填"),
    TR_TO_FACTORY_REQUIRED(1_002_000136, "error.sn.trToFactoryRequired", 400, "目标生产组织必填"),
    TR_TARGET_PI_MISMATCH(
            1_002_000137,
            "error.sn.trTargetPiMismatch",
            400,
            "目标单据 {bill_no} 的 PI 是 {pi}，与所填目标 PI 不同"),
    TR_TARGET_ORG_MISMATCH(
            1_002_000138,
            "error.sn.trTargetOrgMismatch",
            400,
            "转单的目标生产组织须为目标单据 {bill_no} 的生产组织 {factory}"),
    TR_TARGET_LINE_NOT_MATCH(
            1_002_000139,
            "error.sn.trTargetLineNotMatch",
            400,
            "目标单据 {bill_no} 中没有物料编码 {material}、附件项次 {attach_no} 都相同的物料行：只有同物料编码、同附件项次才能转移"),
    TR_NOTHING(1_002_000140, "error.sn.trNothing", 400, "所选范围内没有可转移的号"),
    TR_SN_NOT_TRANSFERABLE(
            1_002_000141, "error.sn.trSnNotTransferable", 400, "SN {sn} 当前不能转移：{reason}"),
    TR_SAME_TARGET(1_002_000142, "error.sn.trSameTarget", 400, "转入目标与来源完全相同"),
    TR_NOT_FOUND(1_002_000143, "error.sn.trNotFound", 404, "转移单不存在"),
    TR_LENGTH_MISMATCH(
            1_002_000144,
            "error.sn.trLengthMismatch",
            400,
            "目标物料米数 {target} 与号上的米数 {current} 不同，不能转移"),
    TR_TARGET_BILL_INVALID(
            1_002_000145,
            "error.sn.trTargetBillInvalid",
            400,
            "目标单据 {bill_no} 须在快照中且有 PI；转单时不能是来源单据"),
    TR_TARGET_LINE_REQUIRED(
            1_002_000146,
            "error.sn.trTargetLineRequired",
            400,
            "目标单据 {bill_no} 中物料编码 {material}、附件项次 {attach_no} 有多行，须选定目标ERP行号"),
    TR_TARGET_LINE_INVALID(
            1_002_000147, "error.sn.trTargetLineInvalid", 400, "目标ERP行号 {seq} 不在目标单据中"),
    TR_RANGE_INVALID(
            1_002_000148,
            "error.sn.trRangeInvalid",
            400,
            "起止号 {start_sn} ~ {end_sn} 不在所选单据、物料编码和附件项次下，或不在同一流水归属 PI 内"),
    TR_FROZEN(1_002_000149, "error.sn.trFrozen", 409, "有 {count} 枚号不能转移：{detail}"),
    TR_LOCATE_NOT_FOUND(
            1_002_000150,
            "error.sn.trLocateNotFound",
            404,
            "没有找到 {q}：请输入完整 SN、单据编号或 PI（只查授权组织内分配成功的号）"),
    QUERY_PI_REQUIRED(1_002_000151, "error.sn.queryPiRequired", 400, "查看同一 PI 各生产组织的号时必须指定 PI"),
    QUERY_PI_NOT_OWNED(
            1_002_000152, "error.sn.queryPiNotOwned", 403, "本生产组织没有 PI {pi} 的号，不能查看其他生产组织"),
    QUERY_RANGE_PI_REQUIRED(1_002_000153, "error.sn.queryRangePiRequired", 400, "按流水范围查询时必须指定 PI"),
    QUERY_RANGE_INVALID(
            1_002_000154, "error.sn.queryRangeInvalid", 400, "{value} 不是 PI {pi} 的流水号或完整 SN"),
    QUERY_RANGE_REVERSED(
            1_002_000155, "error.sn.queryRangeReversed", 400, "起始流水 {start} 大于截止流水 {end}"),
    EXPORT_TOO_LARGE(1_002_000156, "error.sn.exportTooLarge", 400, "单次导出最多 {max} 条，请缩小筛选范围");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private final int code;
    private final String key;
    private final int httpStatus;
    private final String zh;

    SnErrorCode(int code, String key, int httpStatus, String zh) {
        this.code = code;
        this.key = key;
        this.httpStatus = httpStatus;
        this.zh = zh;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    /** 中文模板。 */
    public String zh() {
        return zh;
    }

    /** 按参数渲染中文说明（缺少的参数为空串）。 */
    public String render(Map<String, String> args) {
        Matcher m = PLACEHOLDER.matcher(zh);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = args.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
