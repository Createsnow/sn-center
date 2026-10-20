import { reactive } from "vue";
import { api } from "@/api";

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
export type TargetField = "pi" | "customer" | "material";
type Suggest = (x: { value: string }[]) => void;

/** 按关键字取候选并给自动完成用；记下见过的 PI、客户、物料，不在其中的视为手工填写。 */
export function useTargets() {
  const known = reactive({ pi: new Set<string>(), customer: new Set<string>(), material: new Set<string>() });

  async function fetchTargets(q: string): Promise<Target[]> {
    const rows: Target[] = await api.orderTargets(q.trim(), 50);
    rows.forEach((r) => {
      known.pi.add(r.pi);
      known.customer.add(r.customer_code);
      known.material.add(r.material_code);
    });
    return rows;
  }

  async function suggestBills(q: string, cb: Suggest) {
    if (!q.trim()) return cb([]);
    const rows = await fetchTargets(q);
    cb([...new Set(rows.map((r) => r.bill_no))].map((v) => ({ value: v })));
  }

  async function suggest(field: TargetField, q: string, cb: Suggest) {
    if (!q.trim()) return cb([]);
    const rows = await fetchTargets(q);
    const pick = (r: Target) => (field === "pi" ? r.pi : field === "customer" ? r.customer_code : r.material_code);
    cb([...new Set(rows.map(pick).filter(Boolean))].map((v) => ({ value: v })));
  }

  const isKnown = (field: TargetField, v: string) => known[field].has(v);

  return { suggestBills, suggest, isKnown };
}

/** 转单 / 转组织的确认框汇总。 */
export interface Summary {
  total: number;
  transferable: number;
  by_status: Record<string, number>;
  blocked: Record<string, number>;
  delivered: number;
}
