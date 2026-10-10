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
}

export interface SnItem {
  id: number;
  sn: string;
  seq_text: string;
  seq_dec: number | null;
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
  factory_code: string;
  qty: number;
  start_sn: string;
  end_sn: string;
  status: "RUNNING" | "SUCCESS" | "FAILED" | "REVOKED";
  done_qty: number;
  error_code: string | null;
  error_msg: string | null;
  created_by: string;
  created_at: string;
  finished_at: string | null;
  /** 按 PI 的上下文里才有：可撤销（PI 流水排在最后、号全部仍待分配的成功任务） */
  revocable?: boolean;
}
