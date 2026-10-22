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
  length: string;
}

/** 来源候选：PI 下一张单据及各物料的号数。 */
export interface SourceBill {
  bill_no: string;
  qty: number;
  materials: { material_code: string; qty: number }[];
}

/** 转单 / 转组织的确认框汇总。 */
export interface Preview {
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
