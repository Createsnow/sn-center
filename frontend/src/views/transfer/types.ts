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

/** 来源候选：PI 下一张单据及各料号 + 附件项次（同级）的号数；rows 再按生产组织细分，带号上的客户、米数。 */
export interface SourceBill {
  bill_no: string;
  qty: number;
  materials: { material_code: string; attach_no: string; qty: number }[];
  rows: {
    material_code: string; attach_no: string; factory_code: string; qty: number; customers: string[]; attach_nos: string[]; lengths: string[];
    /** 号所在的 ERP行号（导入的号为导入记录的当前行号） */
    seqs: number[];
  }[];
}

/** 新建转移料号明细的一行：来源单据在来源生产组织下的一个「料号 + 附件项次」。 */
export interface SourceGroup {
  key: string;
  material_code: string;
  attach_no: string;
  qty: number;
  customers: string[];
  lengths: string[];
  /** 组内号所在的 ERP行号（从小到大） */
  seqs: number[];
}

/** 快速定位的结果：SN 带出 PI、单据、料号、附件项次、生产组织与 SN；单据编号带出 PI、单据；PI 只带 PI。 */
export type Located =
  | { by: "SN"; pi: string; bill_no: string; material_code: string; attach_no: string; factory_code: string; sn: string }
  | { by: "BILL"; pi: string; bill_no: string; material_code?: undefined; attach_no?: undefined; factory_code?: undefined; sn?: undefined }
  | { by: "PI"; pi: string; bill_no?: undefined; material_code?: undefined; attach_no?: undefined; factory_code?: undefined; sn?: undefined };

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
  /** 各行都转到同一物料行时为该行序号，否则为空（见 lines） */
  to_seq: number | null;
  to_material: string;
  /** 各组（料号 + 附件项次 + 来源序号）对应的目标序号与枚数 */
  lines: {
    material_code: string; attach_no: string; from_seq: number; to_seq: number; length: string; total: number; transferable: number;
  }[];
  scope: string;
  other_org: number;
  xo_qty: number;
}

/** 号段显示：单枚只显示一枚。 */
export function snRange(d: { from_sn?: string; from_end_sn?: string }) {
  return !d.from_end_sn || d.from_end_sn === d.from_sn ? d.from_sn : `${d.from_sn} ~ ${d.from_end_sn}`;
}
