/**
 * 新建转移的物料编码明细（F-15、F-16）：物料编码与附件项次同级，「物料编码 + 附件项次」确定一组号。
 * 整单转移 = 来源单据下全部组；按物料编码转移 = 勾选一组或多组，每组可转全部（ALL）、号段（RANGE）或指定数量（QTY）。
 * 只有同物料编码、同附件项次才能转移：每组只能转到目标单据中物料编码、附件项次都相同的物料行；这样的行有多行时
 * 在米数相符的行里只有一行就自动选上，否则须选行号；同一单据内转（转组织）不选行号即留在原行。与后台一致。
 */
export type LineMode = 'ALL' | 'RANGE' | 'QTY';
export type TransferScope = 'BILL' | 'MATERIAL';
export interface SrcRow {
  materialCode: string;
  attachNo?: string | null;
  attachNos?: string[];
  factoryCode: string;
  qty: number;
  customers?: string[];
  lengths?: string[];
  seqs?: number[];
}
export interface Group {
  key: string;
  materialCode: string;
  attachNo: string;
  qty: number;
  customers: string[];
  lengths: string[];
  seqs: number[];
}
export interface TargetLine {
  seq: number;
  materialCode: string;
  attachNo?: string | null;
  length: string;
}
export interface GroupCfg {
  checked: boolean;
  mode: LineMode;
  startSn: string;
  endSn: string;
  qty: number | undefined;
  toSeq: number | undefined;
}
export type LineIssue = '' | 'NO_MATCH' | 'LENGTH' | 'PICK_SEQ' | 'NEED_SN' | 'BAD_QTY';

export const LINE_MODES: LineMode[] = ['ALL', 'RANGE', 'QTY'];

/** 组的键：物料编码 + 附件项次。 */
export const groupKey = (material: string, attach: string | null | undefined) => `${material}\u0001${attach ?? ''}`;

/** 来源单据在所选生产组织下的各组，按物料编码、附件项次排序；seqs 为组内号所在的 ERP行号（从小到大）。 */
export function groupsOf(rows: SrcRow[] | null | undefined, factory: string): Group[] {
  const by = new Map<string, Group>();
  for (const r of rows ?? []) {
    if (r.factoryCode !== factory) continue;
    const attach = r.attachNo ?? r.attachNos?.[0] ?? '';
    const key = groupKey(r.materialCode, attach);
    const g = by.get(key) ?? { key, materialCode: r.materialCode, attachNo: attach, qty: 0, customers: [], lengths: [], seqs: [] };
    g.qty += r.qty;
    for (const v of r.customers ?? []) if (!g.customers.includes(v)) g.customers.push(v);
    for (const v of r.lengths ?? []) if (!g.lengths.includes(v)) g.lengths.push(v);
    for (const v of r.seqs ?? []) if (!g.seqs.includes(v)) g.seqs.push(v);
    by.set(key, g);
  }
  for (const g of by.values()) g.seqs.sort((a, b) => a - b);
  return [...by.values()].sort((a, b) =>
    `${a.materialCode}\u0001${a.attachNo}`.localeCompare(`${b.materialCode}\u0001${b.attachNo}`),
  );
}

/** 新组的默认配置：未勾选、全部。 */
export function defaultCfg(): GroupCfg {
  return { checked: false, mode: 'ALL', startSn: '', endSn: '', qty: undefined, toSeq: undefined };
}

/** 目标单据中物料编码、附件项次都与该组相同的物料行。 */
export function candidates<T extends TargetLine>(lines: T[] | null | undefined, g: Group): T[] {
  return (lines ?? []).filter((l) => l.materialCode === g.materialCode && (l.attachNo ?? '') === g.attachNo);
}

/** 目标行米数与组内号的米数不同。全部转时组内每种米数都要相同；号段、数量只取一部分，目标米数是组内米数之一即可。 */
export function lengthBad(line: TargetLine, g: Group, mode: LineMode): boolean {
  const ls = g.lengths ?? [];
  return mode === 'ALL' ? ls.some((x) => x !== line.length) : ls.length > 0 && !ls.includes(line.length);
}

/** 目标行号：已选的仍是米数相符的候选行时保留；否则米数相符的候选行只有一行时自动选上，其余待选。 */
export function autoSeq(lines: TargetLine[], g: Group, cfg: GroupCfg): number | undefined {
  const fit = candidates(lines, g).filter((l) => !lengthBad(l, g, cfg.mode));
  if (fit.some((l) => l.seq === cfg.toSeq)) return cfg.toSeq;
  return fit.length === 1 ? fit[0]!.seq : undefined;
}

/** 本次参与转移的组：整单转移为全部组；按物料编码转移为勾选的组。 */
export function activeGroups(scope: TransferScope, groups: Group[], cfgs: Record<string, GroupCfg>): Group[] {
  return scope === 'BILL' ? groups : groups.filter((g) => cfgs[g.key]?.checked);
}

/** 一组的问题（空串 = 可以转）。sameBill = 目标单据就是来源单据（不选行号即留在原行）。 */
export function lineIssue(scope: TransferScope, g: Group, cfg: GroupCfg, lines: TargetLine[], sameBill: boolean): LineIssue {
  const mode = scope === 'BILL' ? 'ALL' : cfg.mode;
  if (mode === 'RANGE' && !String(cfg.startSn || '').trim()) return 'NEED_SN';
  if (mode === 'QTY' && !(Number.isInteger(cfg.qty) && (cfg.qty ?? 0) >= 1 && (cfg.qty ?? 0) <= g.qty)) return 'BAD_QTY';
  const cands = candidates(lines, g);
  if (!cands.length) return 'NO_MATCH';
  if (cfg.toSeq !== undefined) return cands.some((l) => l.seq === cfg.toSeq) ? '' : 'NO_MATCH';
  if (sameBill) return '';
  return cands.every((l) => lengthBad(l, g, mode)) ? 'LENGTH' : 'PICK_SEQ';
}

/** 请求体的 lines：整单转移只带各组的目标行号；按物料编码转移带勾选各组的方式、号段或数量与目标行号。 */
export function linesBody(scope: TransferScope, groups: Group[], cfgs: Record<string, GroupCfg>) {
  return activeGroups(scope, groups, cfgs).map((g) => {
    const c = cfgs[g.key] ?? defaultCfg();
    const mode = scope === 'BILL' ? 'ALL' : c.mode;
    return {
      materialCode: g.materialCode,
      attachNo: g.attachNo,
      mode,
      startSn: mode === 'RANGE' ? String(c.startSn).trim() : null,
      endSn: mode === 'RANGE' ? String(c.endSn || '').trim() || null : null,
      qty: mode === 'QTY' ? (c.qty ?? null) : null,
      toSeq: c.toSeq ?? null,
    };
  });
}

/** 所选枚数：全部按组枚数，数量按所填；号段的枚数在确认框里给出（只填起号即 1 枚），返回 null 表示未知。 */
export function selectedQty(scope: TransferScope, groups: Group[], cfgs: Record<string, GroupCfg>): number | null {
  let n = 0;
  for (const g of activeGroups(scope, groups, cfgs)) {
    const c = cfgs[g.key] ?? defaultCfg();
    const mode = scope === 'BILL' ? 'ALL' : c.mode;
    if (mode === 'ALL') n += g.qty;
    else if (mode === 'QTY') n += Number.isInteger(c.qty) ? (c.qty ?? 0) : 0;
    else if (String(c.startSn || '').trim() && !String(c.endSn || '').trim()) n += 1;
    else return null;
  }
  return n;
}
