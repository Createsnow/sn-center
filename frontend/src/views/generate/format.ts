/** 生成页各组件共用的数字与规则显示。 */
export const num = (n: number | string | null | undefined) => (n == null ? "—" : Number(n).toLocaleString());

/** 进度百分比（取整、不超过 100）。 */
export const pct = (b: { qty: number; done_qty: number }) => (b.qty ? Math.min(100, Math.floor((b.done_qty * 100) / b.qty)) : 0);

/** 规则标题：自定义规则显示「自定义」，规则库显示「编码 v版本」。 */
export function ruleTitle(t: (key: string) => string, r: { mode?: string; rule_code?: string; version?: number }) {
  if (r.mode === "CUSTOM") return t("generate.rule.custom");
  return `${r.rule_code} v${r.version}`;
}
