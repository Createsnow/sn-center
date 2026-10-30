/** 转移类型：BILL 转单 / ORG 转组织。 */
export type Kind = "BILL" | "ORG";
export const KINDS: Kind[] = ["BILL", "ORG"];

/** 转单、转组织目标候选：快照中的物料行。 */
export interface Target {
  org_code: string;
  org_name: string;
  customer_code: string;
  pi: string;
  bill_no: string;
  seq: number;
  material_code: string;
  material_name: string;
  attach_no: string;
  length: string;
}

/** 来源候选：PI 下一张单据及各物料的号数；rows 按物料 + 生产组织细分，带号上的客户、附件项次、米数。 */
export interface SourceBill {
  bill_no: string;
  qty: number;
  materials: { material_code: string; qty: number }[];
  rows: { material_code: string; factory_code: string; qty: number; customers: string[]; attach_nos: string[]; lengths: string[] }[];
}

/** 快速定位的结果：SN 带出 PI、单据、物料、生产组织与 SN；单据编号带出 PI、单据；PI 只带 PI。 */
export type Located =
  | { by: "SN"; pi: string; bill_no: string; material_code: string; factory_code: string; sn: string }
  | { by: "BILL"; pi: string; bill_no: string; material_code?: undefined; factory_code?: undefined; sn?: undefined }
  | { by: "PI"; pi: string; bill_no?: undefined; material_code?: undefined; factory_code?: undefined; sn?: undefined };

/** 新建转移的确认框汇总；kind 为系统判断的类型。 */
export interface Preview {
  kind: Kind;
  total: number;
  transferable: number;
  by_status: Record<string, number>;
  blocked: Record<string, number>;
  delivered: number;
  generated_changes: { bill_no: string; seq: number; before: number; after: number }[];
  to_pi: string;
  to_org: string;
  to_bill: string;
  to_seq: number;
  to_material: string;
  scope: string;
  other_org: number;
  xo_qty: number;
}

/** 号段显示：单枚只显示一枚。 */
export function snRange(d: { from_sn?: string; from_end_sn?: string }) {
  return !d.from_end_sn || d.from_end_sn === d.from_sn ? d.from_sn : `${d.from_sn} ~ ${d.from_end_sn}`;
}
