"""业务错误码：默认 HTTP 状态 + 中文模板（{name} 为参数占位）。

前端 locales/errors.*.json 必须有同名词条且占位一致（tests/test_unit.py::test_every_error_code_translated 校验）；
新增错误码时在 frontend/scripts/error_messages.py 补英 / 越译文后运行 gen_locales.py。
"""

from __future__ import annotations

import re
from enum import Enum
from typing import Any

from app.core.exceptions import BizError

_PLACEHOLDER = re.compile(r"\{(\w+)}")


class ErrorCode(Enum):
    # ---------- 通用 / 认证 ----------
    UNAUTHENTICATED = (401, "请先登录")
    TOKEN_INVALID = (401, "登录已失效，请重新登录")
    LOGIN_FAILED = (401, "工号或密码错误")
    ACCOUNT_DISABLED = (401, "账户已停用")
    SESSION_REPLACED = (401, "该账户已在其他页面登录，本页已退出")
    MES_NO_PAGE_LOGIN = (403, "MES 接口账户不能登录页面")
    PASSWORD_CHANGE_REQUIRED = (403, "密码已被管理员重置，请先修改密码")
    FORBIDDEN = (403, "当前角色无权执行该操作")
    FACTORY_FORBIDDEN = (403, "只能操作本生产组织的号：{factory}")
    VALIDATION = (422, "参数不合法：{detail}")
    CONFLICT_RETRY = (409, "数据正被其他操作占用，请稍后重试")
    PI_BUSY = (409, "PI {pi} 正在进行其他操作（生成、撤销、分配或转移），请稍后再试")
    REASON_REQUIRED = (400, "原因必填（不超过 500 字）")
    # ---------- 账户 ----------
    USER_NOT_FOUND = (404, "账户不存在")
    USER_EMP_NO_INVALID = (400, "工号须为 4–32 位字母和数字")
    USER_EMP_NO_EXISTS = (409, "工号已存在：{emp_no}")
    USER_NAME_REQUIRED = (400, "姓名必填，不超过 64 字")
    USER_ROLE_INVALID = (400, "角色无效")
    USER_ROLE_FIXED = (400, "MES 接口账户与页面账户不能互相改角色")
    USER_FACTORY_REQUIRED = (400, "生产组织操作员必须绑定一个生产组织")
    USER_MES_FACTORY_REQUIRED = (400, "MES 接口账户必须绑定一个「对接 MES = 是」的生产组织")
    USER_ADMIN_NO_FACTORY = (400, "系统管理员不绑定生产组织")
    USER_PASSWORD_WEAK = (400, "密码须为 8–64 位，同时含字母和数字，只能用字母、数字和 ! @ # $ & *")
    USER_PASSWORD_WRONG = (400, "原密码不正确")
    USER_PASSWORD_SAME = (400, "新密码不能与原密码相同")
    USER_SELF_DISABLE = (400, "不能停用自己的账户")
    USER_LAST_ADMIN = (400, "系统须保留至少一名启用中的管理员")
    USER_MANAGE_FORBIDDEN = (403, "只有内置管理员可以管理账户")
    USER_SUPER_ADMIN_FIXED = (400, "内置管理员必须是启用中的系统管理员，不能改角色或停用")
    USER_NOT_MES = (400, "该账户不是 MES 接口账户")
    USER_IP_LIST_INVALID = (400, "来源地址白名单格式不正确：{value}")
    # ---------- 生产组织 / MES 配置 / 金蝶 ----------
    FACTORY_NOT_FOUND = (400, "生产组织不存在：{factory}")
    FACTORY_DISABLED = (400, "生产组织 {factory} 在金蝶已禁用")
    MES_CONFIG_INVALID = (400, "MES 对接配置不合法：{detail}")
    MES_TEST_REQUIRED = (400, "接口地址、凭证已填且连接测试通过后才能开启「对接 MES」")
    MES_TEST_FAILED = (502, "连接测试未通过：{message}")
    MES_DISABLE_BLOCKED = (
        409,
        "生产组织 {factory} 还有未完成的推送：{detail}；请先续推、等待 MES 回执，或由总部人工确认 / 作废",
    )
    MES_NOT_ENABLED = (400, "生产组织 {factory} 未开启「对接 MES」")
    K3_CONFIG_MISSING = (503, "金蝶配置缺失：{missing}")
    K3_LOGIN_FAILED = (502, "金蝶登录失败：{message}")
    K3_QUERY_FAILED = (502, "金蝶查询失败：{message}")
    ORDER_BILL_REQUIRED = (400, "请输入单据编号")
    ORDER_NOT_FOUND = (404, "生产订单 {bill_no} 不在快照中（不是已审核，或尚未同步）")
    ORDER_LINE_NOT_FOUND = (404, "物料行 {bill_no} / {seq} 不在快照中")
    ORDER_SYNC_BUSY = (409, "订单同步正在进行，请稍后再试")
    ATTACH_INVALID = (400, "附件项次不能为空，不超过 64 字")
    ATTACH_FILL_K3 = (409, "金蝶已有附件项次，不能在页面修改")
    LENGTH_FILL_INVALID = (400, "长度不超过 64 字；统一设置时不能为空")
    LENGTH_FILL_K3 = (409, "金蝶已有长度，不能在页面修改")
    LINE_EDIT_GENERATED = (
        409,
        "物料行 {bill_no} / {seq} 已生成过号，附件项次和长度不能修改；号还未分配时可先撤销生成再修改",
    )
    LINE_EDIT_ALLOCATED = (409, "物料行 {bill_no} / {seq} 的号已分配，附件项次和长度不能再修改")
    ALERT_NOT_FOUND = (404, "订单变更提醒不存在或已处理")
    ALERT_ACTION_INVALID = (400, "该提醒不能这样处理")
    ALERT_MAP_TARGET_INVALID = (400, "确认对应的目标物料行须是该单据快照中的另一行：{seq}")
    ALERT_MAP_FROZEN = (409, "失去对应的物料行有 {count} 枚号在未确认或失败的批次中，须等批次确认后再确认对应")
    # ---------- 规则 ----------
    RULE_NOT_FOUND = (404, "规则不存在")
    RULE_VERSION_NOT_FOUND = (404, "规则版本不存在")
    RULE_CODE_INVALID = (400, "规则编码须为 1–64 位字母、数字、下划线或横线")
    RULE_CODE_EXISTS = (409, "规则编码已存在：{rule_code}")
    RULE_NAME_REQUIRED = (400, "规则名称必填，不超过 128 字")
    RULE_CATEGORY_INVALID = (400, "分类只能是未分类、客户或 PI")
    RULE_CATEGORY_REQUIRED = (400, "按客户或 PI 分类时，须填写客户或 PI（不超过 64 位）")
    RULE_IN_USE = (409, "规则 {rule_code} 已生成过号，不能删除")
    RULE_ASSIGNED = (409, "规则 {rule_code} 已指定给 PI {pi}，不能删除")
    RULE_PREFIX_REQUIRED = (400, "前缀必填")
    RULE_AFFIX_INVALID = (400, "前缀只能用字母或数字，后缀只能用字母、数字和 _")
    RULE_PREFIX_CONFLICT = (
        409,
        "前缀 {prefix} 已被规则 {rule_name} 版本 {version} 使用，会撞号",
    )
    RULE_BASE_INVALID = (400, "进制仅支持 10 / 16 / 32 / 36")
    RULE_SEQ_LEN_INVALID = (400, "流水位数须为 1–12")
    RULE_CHARSET_LENGTH = (400, "字符表的字符数必须等于进制")
    RULE_CHARSET_INVALID = (400, "字符表只能用字母和数字")
    RULE_CHARSET_DUPLICATE = (400, "字符表不能有重复字符")
    RULE_SN_TOO_LONG = (400, "前缀 + 长度 + 流水 + 后缀总长不能超过 64")
    RULE_USED_FORMAT = (409, "版本 {version} 已生成过号，改格式须另出一版并换新前缀")
    RULE_PREFIX_CHANGE_REQUIRED = (409, "规则 {rule_code} 版本 {version} 已用于 PI {other_pi}，须为本 PI 填新前缀")
    RULE_CUSTOMER_BOUND = (409, "客户 {customer} 已绑定规则 {rule_code}")
    RULE_LINE_REQUIRED = (400, "请选择物料行")
    # ---------- 生成 ----------
    GEN_SCOPE_INVALID = (400, "生成范围须为同一张 PI 下的单据或物料行")
    GEN_NOT_GENERATABLE = (400, "所选范围不能生成：{detail}")
    GEN_PI_QUOTA_EMPTY = (400, "所选范围没有可生成额度")
    GEN_RULE_EXHAUSTED = (400, "规则位数已用尽：最大流水 {max}，本次需要排到 {end}")
    GEN_PREVIEW_REQUIRED = (400, "没有完成本次预演，不能生成")
    GEN_PREVIEW_EXPIRED = (400, "预演已过期，请重新预演")
    GEN_PREVIEW_CHANGED = (409, "预演条件已改变，请重新预演")
    GEN_PREVIEW_STALE = (409, "PI {pi} 的最大号已从 {expected} 变为 {actual}，预演失效，请重新预演")
    GEN_PREVIEW_USED = (409, "该预演已用于一次生成，请重新预演")
    GEN_BUSY = (409, "PI {pi} 正在生成，请稍后再试")
    GEN_DUPLICATE_SN = (409, "完整 SN {sn} 与 PI {pi} 已有的号重复，整次生成已回滚（不自动跳号）")
    GEN_JOB_NOT_FOUND = (404, "生成任务不存在")
    GEN_REVOKE_NOT_SUCCESS = (409, "只能撤销已成功的生成")
    GEN_REVOKE_NOT_LAST = (409, "只能撤销 PI {pi} 流水排在最后的物料行，请从后往前逐行撤销")
    GEN_REVOKE_LINE_INVALID = (409, "这次生成中没有该物料行，或已撤销")
    GEN_REVOKE_ALLOCATED = (409, "要撤销的号已有 {count} 枚分配或转过单，不能撤销")
    GEN_QTY_OVER_MAX = (400, "单次最多生成 {max} 枚，所选物料行共 {qty} 枚；请少选几行分次生成，或联系管理员")
    GEN_CANCELED = (409, "生成已取消，已写入的号全部删除")
    GEN_CANCEL_NOT_RUNNING = (409, "这次生成已经结束，不能取消")
    # ---------- 分配 / 推送 / 批次 ----------
    ALLOC_NOTHING = (400, "所选范围没有可分配的号")
    PI_REQUIRED = (400, "PI 必填")
    PUSH_NOTHING = (400, "生产组织 {factory} 的 PI {pi} 没有需要推送的号或批次")
    BATCH_NOT_FOUND = (404, "批次 {batch_no} 不存在")
    BATCH_NOT_PENDING = (409, "批次 {batch_no} 已是 {result}，不能再处理")
    BATCH_NOT_VOIDABLE = (
        409,
        "批次 {batch_no} MES 已处理过（回执不符或内容不符），不能作废，只能由 MES 修正回执或人工确认",
    )
    BATCH_BASIS_REQUIRED = (400, "须填写依据（如 MES 方书面确认，不超过 500 字）")
    DALERT_NOT_FOUND = (404, "交付告警不存在或已处理")
    DALERT_RESEND_BLOCKED = (409, "SN {sn} 不能以本系统为准重推：{detail}")
    # ---------- 领取 ----------
    ACQ_REQUEST_NO_REQUIRED = (400, "请求号必填（1–64 位）")
    ACQ_NOTHING = (400, "生产组织 {factory} 的 PI {pi} 没有可领取的号")
    ACQ_REQUEST_CONFLICT = (409, "请求号 {request_no} 已用于生产组织 {factory} 的 PI {pi}")
    ACQ_BATCH_NOT_FOUND = (404, "领取批次 {batch_no} 不存在")
    ACQ_BATCH_INCOMPLETE = (409, "领取批次 {batch_no} 未完成，请点「重试」接着领取后再下载")
    ACQ_RETRY_FORBIDDEN = (403, "只有原操作人或总部可以重试领取批次")
    # ---------- 转单 / 转组织 ----------
    TR_SCOPE_INVALID = (400, "转移范围只能是整张 PI、PI + 物料、单枚 SN")
    TR_MATERIAL_REQUIRED = (400, "按物料转移时物料编码必填")
    TR_SN_REQUIRED = (400, "单枚转移时 SN 必填")
    TR_TO_PI_REQUIRED = (400, "转入 PI 必填")
    TR_NOTHING = (400, "所选范围内没有可转移的号")
    TR_SN_NOT_TRANSFERABLE = (400, "SN {sn} 当前不能转移：{reason}")
    TR_SAME_TARGET = (400, "转入目标与来源完全相同")
    TR_NOT_FOUND = (404, "转移单不存在")
    TR_NOT_PENDING = (409, "转组织申请 {transfer_no} 已处理，不能重复操作")
    TR_TARGET_DISABLED = (409, "转入生产组织 {factory} 在金蝶已禁用，不能确认")
    TR_WITHDRAW_FORBIDDEN = (403, "只能撤回本组织提交的申请")
    TR_CONCURRENT = (409, "部分号的状态刚刚变化，请刷新后重试")
    TR_LENGTH_MISMATCH = (400, "目标物料长度 {target} 与号上的长度 {current} 不同，不能转移")
    TR_TARGET_BILL_INVALID = (400, "目标单据 {bill_no} 须在快照中、有客户编码和 PI，且不能是来源单据")
    TR_TARGET_LINE_REQUIRED = (
        400,
        "来源物料行 {seq}（物料 {material}）在目标单据中没有或有多行对应，须手工选目标物料行",
    )
    TR_TARGET_LINE_INVALID = (400, "目标物料行 {seq} 不在目标单据中")
    TR_RANGE_INVALID = (400, "起止号 {start_sn} ~ {end_sn} 不在所选物料行中")
    TR_FROZEN = (409, "有 {count} 枚号不能转移：{detail}")
    # ---------- 查询 / 审计 ----------
    QUERY_PI_REQUIRED = (400, "查看同一 PI 各生产组织的号时必须指定 PI")
    QUERY_PI_NOT_OWNED = (403, "本生产组织没有 PI {pi} 的号，不能查看其他生产组织")
    QUERY_RANGE_PI_REQUIRED = (400, "按流水范围查询时必须指定 PI")
    QUERY_RANGE_INVALID = (400, "{value} 不是 PI {pi} 的流水号或完整 SN")
    QUERY_RANGE_REVERSED = (400, "起始流水 {start} 大于截止流水 {end}")
    EXPORT_TOO_LARGE = (400, "单次导出最多 {max} 条，请缩小筛选范围")

    @property
    def status(self) -> int:
        return self.value[0]

    @property
    def zh(self) -> str:
        return self.value[1]


def java_str(v: Any) -> str:
    """模板渲染与 Java String.valueOf 一致：None → 空串，布尔 → true / false。"""
    if v is None:
        return ""
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v)


def render(code: ErrorCode, params: dict[str, Any]) -> str:
    return _PLACEHOLDER.sub(lambda m: java_str(params.get(m.group(1))), code.zh)


def biz(code: ErrorCode, **params: Any) -> BizError:
    """按错误码生成业务错误：message 为中文模板渲染结果，code + params 随响应返回供前端本地化。"""
    return BizError(render(code, params), code.status, code.name, params)
