/**
 * 表格列宽按内容自适应：量出列下每个值（及表头）的实际像素宽度，取最大值作为列的最小宽度，
 * 内容单行完整显示、不截断不折行；表格总宽超出容器时横向滚动。
 * 尺寸与 Element Plus 小号表格一致：字号 12px，单元格左右内边距各 8px，表头加粗（600）；小号标签左右内边距 7px + 边框 1px。
 */

/** 与 .sn-mono 相同的等宽字体（含 0.2px 字间距）。 */
export const MONO = '"JetBrains Mono", Consolas, "Courier New", monospace';
const MONO_SPACING = 0.2;
const FONT_SIZE = 12;
/** 小号表格单元格左右内边距之和。 */
const CELL_PAD = 16;
/** 字体回退、亚像素取整、tabular-nums 的余量。 */
const SLACK = 8;
/** 小号标签（el-tag size=small）除文字外的宽度：左右内边距 7px×2 + 边框 1px×2。 */
const TAG_PAD = 16;
/** 小号按钮（el-button size=small）除文字外的宽度：左右内边距 11px×2 + 边框 1px×2。 */
const BUTTON_PAD = 24;

let ctx;
const cache = new Map();

function context() {
  if (ctx !== undefined) return ctx;
  ctx = typeof document === "undefined" ? null : document.createElement("canvas").getContext("2d");
  return ctx;
}

function sansFamily() {
  if (typeof document === "undefined" || !document.body) return "sans-serif";
  return getComputedStyle(document.body).fontFamily || "sans-serif";
}

/** 没有画布时（如 node 测试）的估算：全角字符按字号，其余按 0.6 倍字号。 */
function estimate(text, size) {
  let w = 0;
  for (const ch of text) w += /[⺀-￯]/.test(ch) ? size : size * 0.6;
  return w;
}

/**
 * 文字在表格里的像素宽度。
 * @param {unknown} text
 * @param {{ mono?: boolean, bold?: boolean, size?: number }} [opt]
 */
export function textWidth(text, opt = {}) {
  // 数字列用等宽数字（.num 的 tabular-nums），画布量不出来：数字一律按「0」量，略宽于比例数字，不会量窄
  const s = text == null ? "" : String(text).replace(/[1-9]/g, "0");
  if (!s) return 0;
  const { mono = false, bold = false, size = FONT_SIZE } = opt;
  const c = context();
  if (!c) return estimate(s, size) + (mono ? MONO_SPACING * s.length : 0);
  const font = `${bold ? 600 : 400} ${size}px ${mono ? MONO : sansFamily()}`;
  const key = `${font}\u0000${s}`;
  let w = cache.get(key);
  if (w === undefined) {
    if (cache.size > 20000) cache.clear();
    c.font = font;
    w = c.measureText(s).width + (mono ? MONO_SPACING * s.length : 0);
    cache.set(key, w);
  }
  return w;
}

/** 小号标签的像素宽度（文字为空时为 0）。 */
export const tagWidth = (text) => (text ? textWidth(text) + TAG_PAD : 0);

/** 小号按钮的像素宽度。 */
export const buttonWidth = (text) => textWidth(text) + BUTTON_PAD;

/**
 * 列宽：表头与各单元格内容宽度的最大值，加单元格内边距与余量，再限定在 [min, max]。
 * @param {string} header 表头文字
 * @param {Iterable<number>} widths 各单元格内容宽度（textWidth / tagWidth 之和）
 * @param {{ min?: number, max?: number }} [opt]
 */
export function colWidth(header, widths, opt = {}) {
  const { min = 0, max = Infinity } = opt;
  let w = textWidth(header, { bold: true });
  for (const x of widths) if (x > w) w = x;
  return Math.ceil(Math.min(max, Math.max(min, w + CELL_PAD + SLACK)));
}
