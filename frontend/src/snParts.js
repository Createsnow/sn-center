/**
 * 生成页「选规则 → 自定义」的 SN 组成（F-08）：SN 由一串组件按顺序拼成，保存时换算成现有自定义规则的字段，
 * 请求体（/rules/preview、/rules/line-select 的 custom）与后台逻辑都不变。
 *
 * 组件 kind：
 * - TEXT 固定文本 / SEP 分隔符 / PI / BILL 单据编号 / DATE 日期：保存时取值，成为固定字符；
 * - SEQ 流水（进制、位数、起始号）：必须且只有一个；
 * - LEN 米数（补 0 位数）、UNIT 单位：各最多一个，生成时取物料行的值。
 *
 * 按位置换算（后台编码：前缀 + 流水 + 米数前分隔符 + 米数 + 米数后分隔符 + 单位 + 后缀）：
 * 流水之前 → prefix；流水与米数之间 → sep_before；米数与单位之间 → sep_after；最后一个米数 / 单位 / 流水之后 → suffix。
 * 所以米数、单位须在流水之后、单位在米数之后；不带米数时流水与单位之间不能有组件。
 *
 * @typedef {{ id: number, kind: string, text?: string, cut?: "ALL"|"LAST"|"FIRST", n?: number, fmt?: string,
 *   date?: string, base?: number, len?: number, start?: number|null, pad?: number }} Part
 * @typedef {{ pi?: string, billNo?: string }} PartCtx
 * @typedef {{ prefix: string, base: number, seq_len: number, with_length: boolean, length_pad: number,
 *   sep_before: string, sep_after: string, with_unit: boolean, suffix: string, seq_start: number|null }} CustomSpec
 */

export const KINDS = ["TEXT", "SEP", "PI", "BILL", "DATE", "SEQ", "LEN", "UNIT"];
/** 只能有一个的组件 */
export const SINGLE = ["SEQ", "LEN", "UNIT"];
/** 分隔符选项（不含空格：前缀、后缀首尾的空格后台会去掉） */
export const SEPS = ["-", "_", ".", "/"];
export const DATE_FMTS = ["YY", "YYMM", "YYMMDD", "YYYYMMDD", "YYWW", "YYYYWW"];
export const CUTS = ["ALL", "LAST", "FIRST"];
/** 与原页面一致：前缀最多 32 位（输入框 maxlength）；米数前后分隔符最多 16 位（后台 encoding.MAX_SEP） */
export const PREFIX_MAX = 32;
export const SEP_MAX = 16;
export const TEXT_MAX = 32;
export const SEQ_LEN_MAX = 12;
export const PAD_MAX = 32;

let uid = 0;
const withId = (p) => ({ ...p, id: ++uid });

const pad2 = (n) => String(n).padStart(2, "0");

/** 按时区偏移（分钟）取当天日期 YYYY-MM-DD。 */
export function todayIn(offsetMin, now = Date.now()) {
  const d = new Date(now + (Number(offsetMin) || 0) * 60_000);
  return `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())}`;
}

/** ISO 周（周一为一周第一天，含 1 月 4 日的周为第 1 周）：{ year, week }，year 为该周所属的周年。 */
export function isoWeek(y, m, d) {
  const t = new Date(Date.UTC(y, m - 1, d));
  const day = t.getUTCDay() || 7;
  t.setUTCDate(t.getUTCDate() + 4 - day);
  const y0 = Date.UTC(t.getUTCFullYear(), 0, 1);
  return { year: t.getUTCFullYear(), week: Math.ceil(((t.getTime() - y0) / 86_400_000 + 1) / 7) };
}

/** 日期 YYYY-MM-DD 按格式取值；日期不合法返回 ""。 */
export function formatDate(fmt, date) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date || "");
  if (!m) return "";
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const t = new Date(Date.UTC(y, mo - 1, d));
  if (t.getUTCFullYear() !== y || t.getUTCMonth() !== mo - 1 || t.getUTCDate() !== d) return "";
  const Y = m[1];
  const w = isoWeek(y, mo, d);
  const WY = String(w.year);
  switch (fmt) {
    case "YY": return Y.slice(2);
    case "YYMM": return Y.slice(2) + m[2];
    case "YYMMDD": return Y.slice(2) + m[2] + m[3];
    case "YYYYMMDD": return Y + m[2] + m[3];
    case "YYWW": return WY.slice(2) + pad2(w.week);
    case "YYYYWW": return WY + pad2(w.week);
    default: return "";
  }
}

function cutText(v, cut, n) {
  const s = v || "";
  const k = Math.max(1, Number(n) || 1);
  if (cut === "LAST") return s.slice(-k);
  if (cut === "FIRST") return s.slice(0, k);
  return s;
}

/** 新组件（默认值）；today 为日期组件的默认日期。 */
export function newPart(kind, today = "") {
  switch (kind) {
    case "TEXT": return withId({ kind, text: "" });
    case "SEP": return withId({ kind, text: "-" });
    case "PI": return withId({ kind, cut: "ALL", n: 7 });
    case "BILL": return withId({ kind, cut: "LAST", n: 7 });
    case "DATE": return withId({ kind, fmt: "YYMMDD", date: today });
    case "SEQ": return withId({ kind, base: 10, len: 4, start: null });
    case "LEN": return withId({ kind, pad: 0 });
    case "UNIT": return withId({ kind });
    default: throw new Error(`unknown part ${kind}`);
  }
}

/** 默认配置：单据编号后 7 位 + 4 位 10 进制流水。 */
export function defaultParts() {
  return [newPart("BILL"), newPart("SEQ")];
}

/** 保存时就定值的组件取值（SEQ / LEN / UNIT 返回 null）。 */
export function partValue(p, ctx = {}) {
  switch (p.kind) {
    case "TEXT": case "SEP": return p.text || "";
    case "PI": return cutText(ctx.pi, p.cut, p.n);
    case "BILL": return cutText(ctx.billNo, p.cut, p.n);
    case "DATE": return formatDate(p.fmt, p.date);
    default: return null;
  }
}

/** 固定文本组件（如本 PI 已用的前缀）。 */
export function textPart(text) {
  return withId({ kind: "TEXT", text: text || "" });
}

/** 一段固定文本 → 组件（单个分隔符为 SEP，其余为 TEXT，空为无）。 */
function textParts(s) {
  const v = s || "";
  if (!v) return [];
  return [withId(SEPS.includes(v) ? { kind: "SEP", text: v } : { kind: "TEXT", text: v })];
}

/**
 * 现有自定义规则字段 → 组件（反向拆回）：head 为流水之前的组件（前缀），之后按流水、米数前分隔符、米数、
 * 米数后分隔符、单位、后缀排列。
 */
export function fromSpec(spec, head) {
  const s = spec || {};
  const seq = withId({ kind: "SEQ", base: Number(s.base) || 10, len: Number(s.seq_len) || 4, start: s.seq_start ?? null });
  const out = [...head, seq];
  if (s.with_length) {
    out.push(...textParts(s.sep_before), withId({ kind: "LEN", pad: Number(s.length_pad) || 0 }), ...textParts(s.sep_after));
  }
  if (s.with_unit) out.push(withId({ kind: "UNIT" }));
  out.push(...textParts(s.suffix));
  return out;
}

/**
 * 组件 → 现有自定义规则字段与校验错误。errors 为 [{ code, ...params }]（code 对应词条 generate.rule.err.*），
 * 有错误时 spec 仍尽量给出，但不能保存。
 * @returns {{ spec: CustomSpec, errors: { code: string, [k: string]: unknown }[] }}
 */
export function toSpec(parts, ctx = {}) {
  const errors = [];
  const join = (a) => a.map((p) => partValue(p, ctx) ?? "").join("");
  for (const k of SINGLE) {
    if (parts.filter((p) => p.kind === k).length > 1) errors.push({ code: "DUPLICATE", kind: k });
  }
  const iSeq = parts.findIndex((p) => p.kind === "SEQ");
  const seq = iSeq >= 0 ? parts[iSeq] : newPart("SEQ");
  if (iSeq < 0) errors.push({ code: "NO_SEQ" });
  const before = iSeq >= 0 ? parts.slice(0, iSeq) : parts;
  const after = iSeq >= 0 ? parts.slice(iSeq + 1) : [];
  if (iSeq >= 0 && before.some((p) => p.kind === "LEN" || p.kind === "UNIT")) errors.push({ code: "AFTER_SEQ" });
  const iLen = after.findIndex((p) => p.kind === "LEN");
  const iUnit = after.findIndex((p) => p.kind === "UNIT");
  let sepBefore = "";
  let sepAfter = "";
  let suffix = "";
  if (iLen >= 0 && iUnit >= 0 && iUnit < iLen) errors.push({ code: "UNIT_AFTER_LEN" });
  if (iLen >= 0) {
    sepBefore = join(after.slice(0, iLen));
    if (iUnit > iLen) {
      sepAfter = join(after.slice(iLen + 1, iUnit));
      suffix = join(after.slice(iUnit + 1));
    } else {
      suffix = join(after.slice(iLen + 1).filter((p) => p.kind !== "UNIT"));
    }
  } else if (iUnit >= 0) {
    if (join(after.slice(0, iUnit))) errors.push({ code: "UNIT_GAP" });
    suffix = join(after.slice(iUnit + 1));
  } else {
    suffix = join(after);
  }
  const prefix = join(before).trim();
  if (iSeq >= 0 && !prefix) errors.push({ code: "PREFIX_EMPTY" });
  if (prefix.length > PREFIX_MAX) errors.push({ code: "PREFIX_TOO_LONG", max: PREFIX_MAX });
  if (sepBefore.trim().length > SEP_MAX || sepAfter.trim().length > SEP_MAX) errors.push({ code: "SEP_TOO_LONG", max: SEP_MAX });
  const lenPart = parts.find((p) => p.kind === "LEN");
  const start = Number(seq.start);
  const spec = {
    prefix,
    base: Number(seq.base) || 10,
    seq_len: Number(seq.len) || 4,
    with_length: !!lenPart,
    length_pad: lenPart ? Number(lenPart.pad) || 0 : 0,
    sep_before: lenPart ? sepBefore.trim() : "",
    sep_after: lenPart ? sepAfter.trim() : "",
    with_unit: parts.some((p) => p.kind === "UNIT"),
    suffix: suffix.trim(),
    seq_start: Number.isSafeInteger(start) && start > 0 ? start : null,
  };
  return { spec, errors };
}

/** 上移 / 下移（d = -1 / 1）一个组件，返回新数组；越界时原样返回。 */
export function movePart(parts, i, d) {
  const j = i + d;
  if (i < 0 || j < 0 || i >= parts.length || j >= parts.length) return parts;
  const out = parts.slice();
  [out[i], out[j]] = [out[j], out[i]];
  return out;
}

/** 把 parts[from] 移到 parts[to] 之前（to 按原数组下标，to = length 为最后），返回新数组。 */
export function moveTo(parts, from, to) {
  const out = parts.slice();
  const [p] = out.splice(from, 1);
  out.splice(to > from ? to - 1 : to, 0, p);
  return out;
}

/** 在 at 处插入组件，返回新数组。 */
export function insertAt(parts, at, part) {
  const out = parts.slice();
  out.splice(at, 0, part);
  return out;
}

/** cand 相比 parts 多出的校验错误码（插入、移动、拖动前判断能否放下：已有的错误不算）。 */
export function newErrors(parts, cand, ctx = {}) {
  const cur = new Set(toSpec(parts, ctx).errors.map((e) => e.code));
  return [...new Set(toSpec(cand, ctx).errors.map((e) => e.code).filter((c) => !cur.has(c)))];
}

/** 「+ 组件」（不指定位置）时的位置：流水放在米数 / 单位之前，米数放在单位之前，单位不带米数时放最后、放不下则紧跟流水，其余放最后。 */
export function autoIndex(parts, kind, ctx = {}) {
  const end = parts.length;
  if (kind === "SEQ" || kind === "LEN") {
    const i = parts.findIndex((p) => (kind === "SEQ" ? p.kind === "LEN" || p.kind === "UNIT" : p.kind === "UNIT"));
    if (i >= 0) return i;
  }
  if (kind === "UNIT" && !parts.some((p) => p.kind === "LEN") && newErrors(parts, insertAt(parts, end, newPart("UNIT")), ctx).length) {
    const s = parts.findIndex((p) => p.kind === "SEQ");
    if (s >= 0) return s + 1;
  }
  return end;
}

/**
 * 组件库「添加到 前缀 / 后缀」时的位置（target = "PRE" | "SUF"；流水之前为前缀，流水之后为后缀）：
 * 流水、米数、单位按 autoIndex 放（米数、单位只能在后缀）；其余加到前缀时放在流水前，加到后缀时放最后。
 */
export function addIndex(parts, kind, target, ctx = {}) {
  if (SINGLE.includes(kind)) return autoIndex(parts, kind, ctx);
  const s = parts.findIndex((p) => p.kind === "SEQ");
  return target === "PRE" && s >= 0 ? s : parts.length;
}

/** 在 at 处放不下 kind 的原因：TAKEN（流水 / 米数 / 单位已有）或校验错误码；放得下返回 ""。 */
export function blockReason(parts, kind, at, ctx = {}) {
  if (SINGLE.includes(kind) && parts.some((p) => p.kind === kind)) return "TAKEN";
  return newErrors(parts, insertAt(parts, at, newPart(kind)), ctx)[0] ?? "";
}

/** 常用格式（按已收集的格式）：key 对应词条 generate.rule.tpl.*；today 为日期组件的日期。 */
export const TEMPLATES = {
  BILL_SEQ: () => [newPart("BILL"), newPart("SEQ")],
  CS_WEEK: (today) => [
    { ...newPart("TEXT"), text: "CS" }, newPart("SEP"), { ...newPart("DATE", today), fmt: "YYWW" }, newPart("SEP"),
    newPart("SEQ"), newPart("SEP"), { ...newPart("LEN"), pad: 3 }, newPart("UNIT"),
  ],
  BILL_SX: () => [
    { ...newPart("BILL"), cut: "ALL" }, newPart("SEP"), { ...newPart("TEXT"), text: "SX" }, newPart("SEP"),
    { ...newPart("SEQ"), len: 3 }, newPart("SEP"), { ...newPart("LEN"), pad: 3 }, newPart("SEP"), newPart("UNIT"),
  ],
  DATE_SEQ: (today) => [newPart("DATE", today), { ...newPart("SEQ"), len: 5 }],
  WEEK_B32: (today) => [{ ...newPart("TEXT"), text: "81" }, { ...newPart("DATE", today), fmt: "YYWW" }, { ...newPart("SEQ"), base: 32 }],
};

/** 组成的特征（不含 id），用来标出当前与哪个常用格式相同。 */
export function partsSig(parts) {
  return JSON.stringify(parts.map(({ id, ...rest }) => rest));
}

/**
 * 示例号分段（按组件着色）：seqText 为流水文本，length / unit 为示例米数（已补 0）/ 单位。
 * cls：SEQ 为流水，LINE 为米数 / 单位，其余为空。拼起来与 sn 不一致时返回 null（调用方原样显示 sn）。
 */
export function sampleSegments(parts, ctx, sn, seqText, length, unit) {
  const segs = parts.map((p) => {
    if (p.kind === "SEQ") return { text: seqText, cls: "SEQ" };
    if (p.kind === "LEN") return { text: length, cls: "LINE" };
    if (p.kind === "UNIT") return { text: unit, cls: "LINE" };
    return { text: partValue(p, ctx) ?? "", cls: "" };
  });
  return segs.map((s) => s.text).join("") === sn ? segs.filter((s) => s.text) : null;
}
