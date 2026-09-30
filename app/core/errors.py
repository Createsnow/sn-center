"""业务错误码：默认 HTTP 状态 + 中文模板（{name} 为参数占位）。

与 Java 后端 ErrorCode 一一对应（tests/test_parity.py 校验）；前端 locales/errors.*.json
必须有同名词条且占位一致（tests/test_i18n_contract.py 校验）。
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
    LOGIN_LOCKED = (429, "登录失败次数过多，请 {minutes} 分钟后再试")
    PASSWORD_CHANGE_REQUIRED = (403, "首次登录或密码已重置，请先修改密码")
    FORBIDDEN = (403, "当前角色无权执行该操作")
    FACTORY_FORBIDDEN = (403, "只能操作本厂的号：{factory}")
    VALIDATION = (422, "参数不合法：{detail}")
    CONFLICT_RETRY = (409, "数据正被其他操作占用，请稍后重试")
    # ---------- 账户 ----------
    USER_NOT_FOUND = (404, "账户不存在")
    USER_EMP_NO_INVALID = (400, "工号须为 2–32 位字母、数字、下划线或横线")
    USER_EMP_NO_EXISTS = (409, "工号已存在：{emp_no}")
    USER_NAME_REQUIRED = (400, "姓名必填")
    USER_ROLE_INVALID = (400, "角色无效")
    USER_FACTORY_REQUIRED = (400, "厂区操作员必须绑定工厂")
    USER_ADMIN_NO_FACTORY = (400, "系统管理员不绑定工厂")
    USER_PASSWORD_WEAK = (400, "密码须为 8–64 位，且同时包含字母和数字")
    USER_PASSWORD_WRONG = (400, "原密码不正确")
    USER_PASSWORD_SAME = (400, "新密码不能与原密码相同")
    USER_SELF_DISABLE = (400, "不能停用自己的账户")
    USER_LAST_ADMIN = (400, "系统须保留至少一名启用中的管理员")
    # ---------- 工厂 / 金蝶 ----------
    FACTORY_NOT_FOUND = (400, "工厂不存在：{factory}")
    FACTORY_EXISTS = (409, "工厂编码或名称已存在：{factory}")
    FACTORY_CODE_INVALID = (400, "工厂编码与名称必填，编码不超过 64 位")
    FACTORY_UNMAPPED = (400, "生产组织「{org}」尚未建档为工厂，请先同步工厂")
    K3_CONFIG_MISSING = (503, "金蝶配置缺失：{missing}")
    K3_LOGIN_FAILED = (502, "金蝶登录失败：{message}")
    K3_QUERY_FAILED = (502, "金蝶查询失败：{message}")
    ORDER_BILL_REQUIRED = (400, "请输入单据编号")
    ORDER_NOT_FOUND = (404, "生产订单 {bill_no} 不在快照中（不是计划 / 计划确认，或尚未同步）")
    ORDER_NO_CUSTOMER = (400, "生产订单 {bill_no} 没有客户编码，不能生成")
    ORDER_NO_PI = (400, "生产订单 {bill_no} 没有 PI，不能生成")
    ORDER_SYNC_INTERVAL_INVALID = (400, "定时同步间隔须为 {min}–{max} 分钟")
    # ---------- 规则 ----------
    RULE_NOT_FOUND = (404, "规则不存在")
    RULE_CODE_INVALID = (400, "规则编码须为 1–64 位字母、数字、下划线或横线")
    RULE_CODE_EXISTS = (409, "规则编码已存在：{rule_code}")
    RULE_NAME_REQUIRED = (400, "规则名称必填")
    RULE_SCOPE_INVALID = (400, "绑定方式只能是通用、按客户或按 PI")
    RULE_BIND_REQUIRED = (400, "按客户或按 PI 绑定时，绑定对象必填")
    RULE_BIND_EXISTS = (409, "该绑定对象已有规则：{rule_code}")
    RULE_BASE_INVALID = (400, "进制仅支持 10 / 16 / 32 / 36")
    RULE_SEQ_LEN_INVALID = (400, "流水位数须为 1–12")
    RULE_CHARSET_LENGTH = (400, "字符表长度必须等于进制")
    RULE_CHARSET_DUPLICATE = (400, "字符表不能有重复字符")
    RULE_SN_TOO_LONG = (400, "前缀 + 流水 + 后缀总长不能超过 64")
    RULE_MISSING = (400, "PI {pi} 没有可用规则：请先在「规则模板」页新建规则（可按 PI、按客户（{customer}）绑定）")
    RULE_CHOICE_REQUIRED = (400, "PI {pi} 没有绑定规则，请先选择一条规则模板")
    # ---------- 生成 / 分配 ----------
    GEN_QTY_INVALID = (400, "数量须为正整数")
    GEN_QUOTA_EMPTY = (400, "生产订单 {bill_no} 的可生成额度为 0")
    GEN_QUOTA_EXCEEDED = (400, "生成数量 {qty} 超出可生成额度 {quota}")
    GEN_START_NOT_ALLOWED = (400, "PI {pi} 已指定过起始号或已生成过，不能再指定起始号")
    GEN_START_INVALID = (400, "起始号须在 1 与 {max} 之间")
    GEN_START_TAKEN = (400, "起始号须大于 PI {pi} 已转入号 {sn} 的流水 {seq}")
    GEN_RULE_EXHAUSTED = (400, "规则位数已用尽：最大流水 {max}，本次需要排到 {end}")
    GEN_PREVIEW_REQUIRED = (400, "没有完成本次预演，不能生成")
    GEN_PREVIEW_EXPIRED = (400, "预演已过期，请重新预演")
    GEN_PREVIEW_CHANGED = (409, "预演条件已改变，请重新预演")
    GEN_PREVIEW_STALE = (409, "PI {pi} 的最大号已从 {expected} 变为 {actual}，预演失效，请重新预演")
    GEN_PREVIEW_USED = (409, "该预演已用于一次生成，请重新预演")
    GEN_BUSY = (409, "PI {pi} 正在生成，请稍后再试")
    GEN_DUPLICATE_SN = (409, "完整 SN {sn} 与 PI {pi} 已有的号重复，已停止生成（不自动跳号）")
    GEN_JOB_NOT_FOUND = (404, "生成任务不存在")
    ALLOC_NOTHING = (400, "生产订单 {bill_no} 没有待分配的号")
    ALLOC_QTY_EXCEEDED = (400, "分配数量 {qty} 超出待分配数量 {pending}")
    ALLOC_FACTORY_MISMATCH = (400, "只能分到该订单的生产组织 {factory}")
    # ---------- 起始号 / 历史导入 ----------
    PI_REQUIRED = (400, "PI 必填")
    PI_INIT_LOCKED = (400, "PI {pi} 已指定起始号或已生成过，不能再初始化")
    PI_INIT_EMPTY = (400, "请填写起始号，或导入历史 SN 清单")
    PI_IMPORT_SN_INVALID = (400, "第 {line} 行 SN 为空或超过 64 位")
    PI_IMPORT_DUP_IN_LIST = (400, "导入清单内有重复 SN：{sn}")
    PI_IMPORT_DUP_EXISTING = (409, "SN {sn} 已存在于 PI {pi}")
    PI_IMPORT_SEQ_DUP = (409, "导入清单中有流水 {seq} 已被 PI {pi} 占用")
    PI_IMPORT_TOO_LARGE = (400, "单次导入最多 {max} 条")
    # ---------- 领取 / 打印 / 回调 ----------
    ACQ_REQUEST_NO_REQUIRED = (400, "请求号必填（1–64 位）")
    ACQ_NOTHING = (400, "工厂 {factory} 的 PI {pi} 没有待领取的号")
    ACQ_PI_NOT_IN_ERP = (400, "PI {pi} 不在生产订单快照（计划 / 计划确认）中，不能领取")
    ACQ_REQUEST_CONFLICT = (409, "请求号 {request_no} 已用于工厂 {factory} 的 PI {pi}")
    ACQ_BATCH_NOT_FOUND = (404, "领取批次 {batch_no} 不存在")
    PRINT_NOTHING = (400, "工厂 {factory} 的 PI {pi} 没有待打印的号")
    PRINT_NOT_FOUND = (404, "打印单 {print_no} 不存在")
    CALLBACK_EMPTY = (400, "请传入 SN 清单或起止号")
    CALLBACK_STATUS_INVALID = (400, "目标状态只能是 PRINTED")
    CALLBACK_RANGE_INVALID = (400, "起止号 {start_sn} ~ {end_sn} 不属于批次 {batch_no}")
    CALLBACK_TOO_MANY = (400, "单次回调最多 {max} 枚")
    # ---------- 转厂 ----------
    TR_SCOPE_INVALID = (400, "转移范围只能是整张 PI、PI + 物料、单枚 SN")
    TR_MATERIAL_REQUIRED = (400, "按物料转移时物料编码必填")
    TR_SN_REQUIRED = (400, "单枚转移时 SN 必填")
    TR_REASON_REQUIRED = (400, "转厂原因必填")
    TR_TO_PI_REQUIRED = (400, "转入 PI 必填")
    TR_NOTHING = (400, "所选范围内没有可转移的号（待分配、申请中的号不能转移）")
    TR_SN_NOT_TRANSFERABLE = (400, "SN {sn} 当前状态不能转移")
    TR_SAME_TARGET = (400, "转入目标与来源完全相同")
    TR_DUPLICATE = (409, "转入 PI {pi} 下已存在 {count} 个相同完整 SN（如 {sn}），拒绝整次转移")
    TR_NOT_FOUND = (404, "转厂记录不存在")
    TR_NOT_PENDING = (409, "转厂申请 {transfer_no} 已处理，不能重复操作")
    TR_WITHDRAW_FORBIDDEN = (403, "只能撤回本厂提交的申请")
    TR_CONCURRENT = (409, "部分号的状态刚刚变化，请刷新后重试")
    # ---------- 查询 / 审计 ----------
    QUERY_PI_REQUIRED = (400, "查看同一 PI 各厂的号时必须指定 PI")
    QUERY_PI_NOT_OWNED = (403, "本厂没有 PI {pi} 的号，不能查看其他工厂")
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
