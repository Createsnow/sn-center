/** 后端业务错误体：{code, message, params}。前端按 errors.<code> 渲染当前语言。 */
export interface ApiErrorBody {
  code?: string;
  message?: string;
  params?: Record<string, unknown>;
}

export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  page_size: number;
  total_capped?: boolean;
}

export interface UserView {
  id: number;
  emp_no: string;
  name: string;
  /** admin 系统管理员 / operator 普通操作员 / auditor 审计员 / mes */
  role: string;
  factory_code: string | null;
  factory_name: string | null;
  /** 授权的生产组织：普通操作员、审计员 = 授权的组织，MES 接口账户 = 本组织；系统管理员为空 = 全部 */
  factory_codes: string[];
  factory_names: string[];
  status: string;
  /** 系统管理员账户（唯一能管理账户） */
  super_admin: boolean;
  /** MES 接口账户的来源地址白名单 */
  ip_whitelist: string | null;
  locked_until: string | null;
  /** 显示时区（分钟，东八区 = 480） */
  tz_offset_min: number;
  lang: string | null;
  last_login_at: string | null;
  created_at: string;
  created_by: string | null;
  disabled_at: string | null;
  disabled_by: string | null;
  /** 新建 / 重置密钥时返回一次 */
  secret?: string;
}

/** 生产组织；MES 配置字段只有总部能看到。 */
export interface Factory {
  factory_code: string;
  factory_name: string;
  enabled: boolean;
  mes_enabled: boolean;
  tz_offset_min: number;
  mes_url?: string;
  /** NONE = 无认证（推送不带认证头、不需凭证） */
  mes_auth_type?: "TOKEN" | "HMAC" | "NONE";
  /** 推送请求体 gzip 压缩（默认关，发原始 JSON） */
  mes_gzip?: boolean;
  batch_size?: number;
  push_timeout_sec?: number;
  push_concurrency?: number;
  /** UNCONFIGURED / OK / FAILED / CHANGED */
  cfg_status?: string;
  cfg_message?: string;
  cfg_checked_at?: string | null;
  credential_set?: boolean;
  tested?: boolean;
  bound_users?: number;
  updated_at?: string;
}

/** 物料行实际使用的规则（含来源与本 PI 的使用方式）。 */
export interface LineRule {
  /** LINE 物料行选定 / PI 绑定 / CUSTOMER 客户默认 */
  source: "LINE" | "PI" | "CUSTOMER";
  mode: "LIBRARY" | "CUSTOM";
  /** USE 已归属本 PI / CLAIM 首次归属 / NEW 新建版本 / PREFIX 须换前缀 */
  plan: "USE" | "CLAIM" | "NEW" | "PREFIX";
  rule_id: number | null;
  rule_code: string | null;
  rule_name: string | null;
  version_id: number | null;
  version: number;
  other_pi: string | null;
  custom: CustomRule | null;
  format: string;
  first_sn: string | null;
  max_seq: number;
  prefix: string;
  base: number;
  seq_len: number;
  with_length: boolean;
  length_pad?: number;
  sep_before?: string;
  sep_after?: string;
  with_unit?: boolean;
  suffix: string;
}

/** 自定义规则：前缀 + 流水 + 米数前分隔符 + 米数（补 0）+ 米数后分隔符 + 单位 + 后缀 */
export interface CustomRule {
  prefix: string;
  base: number;
  seq_len: number;
  with_length: boolean;
  length_pad: number;
  sep_before: string;
  sep_after: string;
  with_unit: boolean;
  suffix: string;
}

export interface BillState {
  suspend: "REVERSAL" | "CHANGE" | null;
  leave_state: string | null;
  pi_frozen: boolean;
  open_alerts: number;
  issued: boolean;
}

export interface OrderLine {
  bill_no: string;
  seq: number;
  customer_code: string;
  pi: string;
  po: string;
  org_code: string;
  org_name: string;
  attach_no: string;
  /** K3 金蝶值 / MANUAL 页面填写 / EMPTY 待填写（必填） */
  attach_source: "K3" | "MANUAL" | "EMPTY";
  attach_k3: string;
  attach_manual: string | null;
  attach_k3_conflict: boolean;
  material_code: string;
  material_name: string;
  length_raw: string;
  /** 生效米数（字符串，原样写入 SN） */
  length: string;
  /** MANUAL 页面填写（可选改，优先）/ K3 金蝶值（原样使用）/ EMPTY 金蝶为空且未填写（规则带米数时须填写才能生成） */
  length_source: "K3" | "MANUAL" | "EMPTY";
  length_manual: string | null;
  /** 金蝶产品单位原值（FProductUnit） */
  unit_raw: string;
  /** 生效产品单位（规则带单位时写入 SN） */
  unit: string;
  /** MANUAL 页面填写（可选改，优先）/ K3 金蝶值（原样使用，含中文（条、个等）也照用）/
   *  EMPTY 金蝶为空且未填写（规则带单位时须填写才能生成） */
  unit_source: "K3" | "MANUAL" | "EMPTY";
  unit_manual: string | null;
  qty: number;
  generated_qty: number;
  own_generated_qty: number;
  allocated_qty: number;
  /** 附件项次、米数、产品单位能否修改：null 可改 / GENERATED 已生成未分配（撤销生成后可改）/ ALLOCATED 已分配 */
  edit_lock: "GENERATED" | "ALLOCATED" | null;
  quota: number;
  rule: LineRule | null;
  /** 金蝶修改日期 */
  modify_time: string | null;
  synced_at: string;
  /** 生成页才有：不能生成的原因代码 */
  issues?: string[];
}

export interface OrderBill extends BillState {
  bill_no: string;
  customer_code: string;
  po: string;
  pi: string;
  org_code: string;
  org_name: string;
  /** 金蝶单据状态：快照只含已审核，固定为 C */
  doc_status: "C";
  /** 金蝶修改日期：列表按它从新到旧 */
  modify_time: string | null;
  total_qty: number;
  line_count: number;
  generated_qty: number;
  quota: number;
}

export interface SnItem {
  id: number;
  sn: string;
  seq_text: string;
  seq_dec: string;
  seq_pi_no: string;
  pi_no: string;
  bill_no: string;
  bill_seq: number;
  material_code: string;
  length: string;
  customer_code: string;
  factory_code: string | null;
  status: string;
  delivery_mode: string | null;
  delivery_batch_no: string | null;
  suspend: string | null;
  rev: number;
  mes_holder: string | null;
  push_type: string | null;
  pi_changed: boolean;
  transferred_in: boolean;
  created_at: string;
  allocated_at: string | null;
  delivered_at: string | null;
}

export interface GenJob {
  id: number;
  gen_batch: string;
  pi_no: string;
  scope: string;
  qty: number;
  start_seq_dec: number;
  end_seq_dec: number;
  start_sn: string;
  end_sn: string;
  status: "RUNNING" | "SUCCESS" | "FAILED" | "CANCELED" | "REVOKED";
  done_qty: number;
  current_sn: string | null;
  error_code: string | null;
  error_msg: string | null;
  created_by: string;
  cancel_by: string | null;
  cancel_at: string | null;
  revoke_reason: string | null;
  created_at: string;
  started_at: string | null;
  finished_at: string | null;
  lines: { bill_no: string; seq: number; material_code: string; length: string; qty: number; start_sn: string; end_sn: string; rule_label: string }[];
  bills: string[];
  revocable?: boolean;
  percent?: number;
  speed_per_sec?: number | null;
  eta_seconds?: number | null;
}

export interface DeliveryBatch {
  id: number;
  batch_no: string;
  source: "PUSH" | "PULL";
  batch_type: "NEW" | "SAME_ORG_TRANSFER" | "TRANSFER_IN" | "REMAP" | "TRANSFER_OUT";
  factory_code: string;
  pi_no: string;
  qty: number;
  items_digest: string;
  result: "UNCONFIRMED" | "SUCCESS" | "FAILED" | "VOIDED";
  confirm_method: string | null;
  confirmed_at: string | null;
  fail_code: string | null;
  fail_reason: string | null;
  voidable: boolean;
  accepted_qty: number;
  push_state: string;
  retry_count: number;
  receipt_timeouts: number;
  next_retry_at: string | null;
  received_at: string | null;
  push_count: number;
  repush_count: number;
  last_push_at: string | null;
  pulled_at: string | null;
  transfer_kind: string | null;
  transfer_doc_no: string | null;
  rebuilt_from: string | null;
  rebuilt_to: string | null;
  manual_reason: string | null;
  manual_basis: string | null;
  manual_by: string | null;
  manual_at: string | null;
  created_by: string;
  created_at: string;
}
