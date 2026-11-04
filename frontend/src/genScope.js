/**
 * 生成与分配页的范围选择（F-09 生成范围、F-11 分配范围）。
 * 勾选单据 = 整单（按勾选顺序，单据内按序号）；点选物料行 = 按物料行（按点选顺序）。两者只有一种生效：
 * 已勾选单据时再点选物料行，先把已勾选的单据展开成它们的物料行（顺序不变）；已点选物料行时再勾选单据，
 * 把该单据的物料行按序号接在后面。都不选 = 整张 PI。
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

/** 发给后台的范围：{ scope, bills } / { scope, lines }；都没选时为 null（由调用方决定整张 PI 怎么取）。 */
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
