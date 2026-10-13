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
  role: string;
  factory_code: string | null;
  factory_name: string | null;
  status: string;
  /** 内置 admin 账户（唯一能管理账户） */
  super_admin: boolean;
  must_change_pwd: boolean;
  lang: string | null;
  last_login_at: string | null;
  created_at: string;
  created_by: string | null;
  disabled_at: string | null;
  disabled_by: string | null;
}

export interface Factory {
  factory_code: string;
  factory_name: string;
  enabled: boolean;
  source: string;
  updated_at?: string;
  /** 绑定该厂的启用账户数（工厂列表才有） */
  bound_users?: number;
}

export interface SnItem {
  id: number;
  sn: string;
  seq_text: string;
  seq_dec: number;
  seq_pi_no: string;
  pi_no: string;
  customer_code: string;
  material_code: string;
  factory_code: string | null;
  bill_no: string;
  status: string;
  source: string;
  batch_no: string | null;
  print_no: string | null;
  created_at: string;
  allocated_at: string | null;
  acquired_at: string | null;
  printed_at: string | null;
}

export interface RuleInfo {
  rule_code: string;
  rule_name: string;
  bind_scope: string;
  bind_value: string;
  category_scope: string;
  category_value: string;
  version_id: number;
  version: number;
  prefix: string;
  suffix: string;
  base: number;
  seq_len: number;
  charset: string;
  max_seq: number;
  sample_sn: string;
}

export interface Segment {
  line_seq: number;
  material_code: string;
  material_name: string;
  qty: number;
  start_seq: number;
  end_seq: number;
  start_sn: string;
  end_sn: string;
}

export interface GenJob {
  id: number;
  bill_no: string;
  pi_no: string;
  /** 生成批次：同一次生成（整张 PI 的各订单）共用 */
  gen_batch: string;
  factory_code: string;
  qty: number;
  start_sn: string;
  end_sn: string;
  status: "RUNNING" | "SUCCESS" | "FAILED" | "CANCELED" | "REVOKED";
  done_qty: number;
  error_code: string | null;
  error_msg: string | null;
  created_by: string;
  created_at: string;
  finished_at: string | null;
  cancel_by?: string | null;
  cancel_at?: string | null;
  /** 按 PI 的上下文里才有：属于可撤销的那次生成（PI 流水排在最后、号全部仍待分配） */
  revocable?: boolean;
}

/** 一次生成（整张 PI）的整体进度。 */
export interface GenBatch {
  batch: string;
  pi_no: string;
  status: "RUNNING" | "SUCCESS" | "FAILED" | "CANCELED" | "REVOKED";
  qty: number;
  done_qty: number;
  bills: number;
  start_sn: string;
  end_sn: string;
  current_sn: string | null;
  created_by: string;
  created_at: string;
  finished_at: string | null;
  cancel_by: string | null;
  cancel_at: string | null;
  error_code: string | null;
  error_msg: string | null;
}
