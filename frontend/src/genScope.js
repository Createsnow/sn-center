/**
 * 生成与分配页的范围选择（F-09 生成范围、F-11 分配范围）。
 * 勾选单据 = 整单；点选物料行 = 按物料行。两者只有一种生效（生成时的排号顺序见 genOrder）：
 * 已勾选单据时再点选物料行，先把已勾选的单据展开成它们的物料行（顺序不变）；已点选物料行时再勾选单据，
 * 把该单据的物料行按序号接在后面。都不选时不能选规则、不能生成。
 *
 * linesOf(billNo) 返回该单据的物料行 [{ bill_no, seq }]，按序号从小到大。
 * @typedef {{ bill_no: string, seq: number }} LineRef
 * @typedef {{ bills: string[], lines: LineRef[] }} Sel
 */

const same = (a, b) => a.bill_no === b.bill_no && a.seq === b.seq;
const ref = (l) => ({ bill_no: l.bill_no, seq: l.seq });

/** @returns {Sel} */
export function emptySel() {
  return { bills: [], lines: [] };
}

/** 选中的物料行（勾选的单据展开成物料行），按排号顺序。 */
export function selectedLines(sel, linesOf) {
  return sel.lines.length ? sel.lines : sel.bills.flatMap((no) => linesOf(no).map(ref));
}

/** 勾选 / 取消勾选单据。 */
export function toggleBill(sel, billNo, on, linesOf) {
  if (sel.lines.length) {
    const rest = sel.lines.filter((x) => x.bill_no !== billNo);
    return { bills: [], lines: on ? [...rest, ...linesOf(billNo).map(ref)] : rest };
  }
  const rest = sel.bills.filter((x) => x !== billNo);
  return { bills: on ? [...rest, billNo] : rest, lines: [] };
}

/** 点选 / 取消点选物料行；已勾选单据时先展开成物料行。 */
export function toggleLine(sel, line, on, linesOf) {
  const rest = selectedLines(sel, linesOf).filter((x) => !same(x, line));
  return { bills: [], lines: on ? [...rest, ref(line)] : rest };
}

/** 物料行的排号次序（从 0 起），没选中为 -1；整单勾选时不标物料行次序。 */
export function lineOrder(sel, line) {
  return sel.lines.findIndex((x) => same(x, line));
}

/** 单据勾选框：checked / indeterminate，以及整单勾选时的次序（从 0 起，没有为 -1）。 */
export function billMark(sel, billNo, linesOf) {
  if (!sel.lines.length) {
    const order = sel.bills.indexOf(billNo);
    return { checked: order >= 0, indeterminate: false, order };
  }
  const all = linesOf(billNo);
  const n = all.filter((l) => sel.lines.some((x) => same(x, l))).length;
  return { checked: n > 0 && n === all.length, indeterminate: n > 0 && n < all.length, order: -1 };
}

/** 发给后台的范围：{ scope, bills } / { scope, lines }；都没选时为 null（生成页不能选规则、不能生成）。 */
export function scopeBody(sel) {
  if (sel.lines.length) return { scope: "LINES", lines: sel.lines };
  if (sel.bills.length) return { scope: "BILLS", bills: sel.bills };
  return null;
}

/**
 * 本次生成数量（所选物料行整行额度之和）与单次上限：超过硬上限为 "MAX"（须少选几行），
 * 超过默认上限且没勾选「解除限制」为 "UNLOCK"，否则 null。
 */
export function genLimit(qty, limits, unlocked) {
  const q = Number(qty) || 0;
  if (Number(limits?.max) > 0 && q > Number(limits.max)) return "MAX";
  if (Number(limits?.default) > 0 && q > Number(limits.default) && !unlocked) return "UNLOCK";
  return null;
}

/**
 * 生成的流水来源（预演请求体的一部分）：mode = "MAX" 从最大号 + 1 起；"RANGE" 手动起止流水号（十进制），
 * 在空号段与未使用的号中取，不够时后台从最大号之后接着编。只填起号按单个号。
 * 返回 { body, error }：body 合并进预演请求；起止号不合法时 body 为 null、error 为 "SEQ_RANGE"。
 */
export function seqSource(mode, start, end) {
  if (mode !== "RANGE") return { body: { seq_mode: "MAX" }, error: null };
  const toInt = (v) => (v === "" || v === null || v === undefined ? null : Number(v));
  const a = toInt(start);
  const b = toInt(end) ?? a;
  if (!Number.isSafeInteger(a) || !Number.isSafeInteger(b) || a < 1 || b < a) return { body: null, error: "SEQ_RANGE" };
  return { body: { seq_mode: "RANGE", seq_start: a, seq_end: b }, error: null };
}

/**
 * 「选规则」的默认前缀。rulePrefix 为生成详情的 rule_prefix（见后台 rules.pi_prefix），lines 为所选物料行（按点选顺序）。
 * - 本 PI 已生成的号还在（rulePrefix.custom 非空）：沿用已用的前缀 rulePrefix.prefix，之后各张单据都用它；
 * - 否则：点选的第一个单据编码后 7 位（只保存了规则、还没生成的不算已用）；
 * - 都取不到：PI 第一张单据编码后 7 位（rulePrefix.prefix）。
 */
export function defaultRulePrefix(rulePrefix, lines) {
  if (rulePrefix?.custom) return rulePrefix.prefix;
  return (lines?.[0]?.bill_no ?? "").slice(-7) || rulePrefix?.prefix || "";
}

const ATTACH_NUM = /^\d+(\.\d+)?$/;

/** 附件项次按数字比大小（"2" 在 "10" 前，"01" 与 "1" 相同，不限位数）；不是数字的排在数字之后、按文字比。
 *  与后台 generate.attach_order 一致。返回 -1 / 0 / 1。 */
export function compareAttach(a, b) {
  const x = String(a ?? "").trim();
  const y = String(b ?? "").trim();
  const nx = ATTACH_NUM.test(x);
  const ny = ATTACH_NUM.test(y);
  if (nx !== ny) return nx ? -1 : 1;
  if (!nx) return x < y ? -1 : x > y ? 1 : 0;
  const split = (v) => {
    const [i, f = ""] = v.split(".");
    return [i.replace(/^0+/, ""), f.replace(/0+$/, "")];
  };
  const [ix, fx] = split(x);
  const [iy, fy] = split(y);
  if (ix.length !== iy.length) return ix.length < iy.length ? -1 : 1;
  if (ix !== iy) return ix < iy ? -1 : 1;
  const n = Math.max(fx.length, fy.length);
  const px = fx.padEnd(n, "0");
  const py = fy.padEnd(n, "0");
  return px < py ? -1 : px > py ? 1 : 0;
}

/**
 * 生成的排号顺序（与后台 generate._gen_order 一致）：单据按勾选 / 点选先后（第一次出现的先后）；
 * 单据内按附件项次从小到大（compareAttach），项次相同按行号（单据序号）从小到大。
 * attachOf(line) 取物料行的附件项次。返回新数组。
 */
export function genOrder(lines, attachOf) {
  const rank = new Map();
  for (const l of lines) if (!rank.has(l.bill_no)) rank.set(l.bill_no, rank.size);
  return [...lines].sort(
    (a, b) => rank.get(a.bill_no) - rank.get(b.bill_no) || compareAttach(attachOf(a), attachOf(b)) || a.seq - b.seq,
  );
}

/**
 * 生成页、分配页（待分配页签）单据下物料行的显示顺序：按附件项次（line.attach_no；生成页为已保存的生效值，
 * 分配页为号上的值）从小到大（compareAttach），附件项次为空的排在最后，项次相同按行号（单据序号）从小到大。
 * 只管显示，不影响勾选、排号与分配范围。返回新数组。
 */
export function lineSort(lines) {
  const attach = (l) => String(l.attach_no ?? "").trim();
  return [...lines].sort((a, b) => {
    const x = attach(a);
    const y = attach(b);
    if (!x !== !y) return x ? -1 : 1;
    return compareAttach(x, y) || a.seq - b.seq;
  });
}

/**
 * 同一单据内附件项次相同（按数字比相同）的物料行：[{ bill_no, attach, lines }]，lines 按行号从小到大；
 * 附件项次为空的不算。按 lines 中的先后列出。
 */
export function dupAttach(lines, attachOf) {
  const groups = [];
  for (const l of lines) {
    const v = String(attachOf(l) ?? "").trim();
    if (!v) continue;
    const g = groups.find((x) => x.bill_no === l.bill_no && compareAttach(x.attach, v) === 0);
    if (g) g.lines.push(l);
    else groups.push({ bill_no: l.bill_no, attach: v, lines: [l] });
  }
  return groups
    .filter((g) => g.lines.length > 1)
    .map((g) => ({ ...g, lines: [...g.lines].sort((a, b) => a.seq - b.seq) }));
}
