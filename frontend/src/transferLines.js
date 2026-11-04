/**
 * 新建转移的料号明细（F-15、F-16）：料号（物料编码）与附件项次同级，「料号 + 附件项次」确定一组号。
 * 整单转移 = 来源单据下全部组；按料号转移 = 勾选一组或多组，每组可转全部（ALL）、号段（RANGE）或指定数量（QTY）。
 * 只有同料号、同附件项次才能转移：每组只能转到目标单据中料号、附件项次都相同的物料行；这样的行有多行时
 * 在米数相符的行里只有一行就自动选上，否则须选序号；同一单据内转（转组织）不选序号即留在原行。与后台 transfers._dest_line 一致。
 *
 * @typedef {{ material_code: string, attach_no: string, factory_code: string, qty: number, customers: string[], lengths: string[] }} SrcRow
 * @typedef {{ key: string, material_code: string, attach_no: string, qty: number, customers: string[], lengths: string[] }} Group
 * @typedef {{ seq: number, material_code: string, attach_no: string, length: string }} TargetLine
 * @typedef {{ checked: boolean, mode: "ALL" | "RANGE" | "QTY", start_sn: string, end_sn: string, qty: number | undefined, to_seq: number | undefined }} GroupCfg
 */

export const LINE_MODES = ["ALL", "RANGE", "QTY"];

/** 组的键：料号 + 附件项次。 */
export const groupKey = (material, attach) => `${material}\u0001${attach ?? ""}`;

/** 来源单据在所选生产组织下的各组（料号 + 附件项次），按料号、附件项次排序。 @returns {Group[]} */
export function groupsOf(rows, factory) {
  const by = new Map();
  for (const r of rows || []) {
    if (r.factory_code !== factory) continue;
    const attach = r.attach_no ?? (r.attach_nos && r.attach_nos[0]) ?? "";
    const key = groupKey(r.material_code, attach);
    const g = by.get(key) ?? { key, material_code: r.material_code, attach_no: attach, qty: 0, customers: [], lengths: [] };
    g.qty += r.qty;
    for (const k of ["customers", "lengths"]) for (const v of r[k] || []) if (!g[k].includes(v)) g[k].push(v);
    by.set(key, g);
  }
  return [...by.values()].sort((a, b) => (a.material_code + "\u0001" + a.attach_no).localeCompare(b.material_code + "\u0001" + b.attach_no));
}

/** 新组的默认配置：未勾选、全部。 @returns {GroupCfg} */
export function defaultCfg() {
  return { checked: false, mode: "ALL", start_sn: "", end_sn: "", qty: undefined, to_seq: undefined };
}

/** 目标单据中料号、附件项次都与该组相同的物料行。 */
export function candidates(lines, g) {
  return (lines || []).filter((l) => l.material_code === g.material_code && (l.attach_no ?? "") === g.attach_no);
}

/** 目标行米数与组内号的米数不同（SN 已含米数，后台会拒绝）。全部转时组内每种米数都要相同；号段、数量只取一部分，
 *  目标米数是组内米数之一即可（不符时后台拒绝）。 */
export function lengthBad(line, g, mode) {
  const ls = g.lengths || [];
  return mode === "ALL" ? ls.some((x) => x !== line.length) : ls.length > 0 && !ls.includes(line.length);
}

/** 目标序号：已选的仍是米数相符的候选行时保留；否则米数相符的候选行只有一行时自动选上，其余待选。 */
export function autoSeq(lines, g, cfg) {
  const fit = candidates(lines, g).filter((l) => !lengthBad(l, g, cfg.mode));
  if (fit.some((l) => l.seq === cfg.to_seq)) return cfg.to_seq;
  return fit.length === 1 ? fit[0].seq : undefined;
}

/** 本次参与转移的组：整单转移为全部组（全部号）；按料号转移为勾选的组。 */
export function activeGroups(scope, groups, cfgs) {
  return scope === "BILL" ? groups : groups.filter((g) => cfgs[g.key]?.checked);
}

/** 一组的问题（空串 = 可以转）：NO_MATCH 目标单据没有同料号、同附件项次的行 / LENGTH 米数都不同 / PICK_SEQ 须选目标序号 /
 *  NEED_SN 号段须填起号 / BAD_QTY 数量须为 1 ～ 该组枚数的整数。sameBill = 目标单据就是来源单据（不选序号即留在原行）。 */
export function lineIssue(scope, g, cfg, lines, sameBill) {
  const mode = scope === "BILL" ? "ALL" : cfg.mode;
  if (mode === "RANGE" && !String(cfg.start_sn || "").trim()) return "NEED_SN";
  if (mode === "QTY" && !(Number.isInteger(cfg.qty) && cfg.qty >= 1 && cfg.qty <= g.qty)) return "BAD_QTY";
  const cands = candidates(lines, g);
  if (!cands.length) return "NO_MATCH";
  if (cfg.to_seq !== undefined) return cands.some((l) => l.seq === cfg.to_seq) ? "" : "NO_MATCH";
  if (sameBill) return "";
  return cands.every((l) => lengthBad(l, g, mode)) ? "LENGTH" : "PICK_SEQ";
}

/** 请求体的 lines：整单转移只带各组的目标序号；按料号转移带勾选各组的方式、号段或数量与目标序号。 */
export function linesBody(scope, groups, cfgs) {
  return activeGroups(scope, groups, cfgs).map((g) => {
    const c = cfgs[g.key] || defaultCfg();
    const mode = scope === "BILL" ? "ALL" : c.mode;
    return {
      material_code: g.material_code,
      attach_no: g.attach_no,
      mode,
      start_sn: mode === "RANGE" ? String(c.start_sn).trim() : null,
      end_sn: mode === "RANGE" ? String(c.end_sn || "").trim() || null : null,
      qty: mode === "QTY" ? c.qty : null,
      to_seq: c.to_seq ?? null,
    };
  });
}

/** 所选枚数：全部按组枚数，数量按所填；号段的枚数在确认框里给出（只填起号即 1 枚），返回 null 表示未知。 */
export function selectedQty(scope, groups, cfgs) {
  let n = 0;
  for (const g of activeGroups(scope, groups, cfgs)) {
    const c = cfgs[g.key] || defaultCfg();
    const mode = scope === "BILL" ? "ALL" : c.mode;
    if (mode === "ALL") n += g.qty;
    else if (mode === "QTY") n += Number.isInteger(c.qty) ? c.qty : 0;
    else if (String(c.start_sn || "").trim() && !String(c.end_sn || "").trim()) n += 1;
    else return null;
  }
  return n;
}
