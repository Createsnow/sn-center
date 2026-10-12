/**
 * 把粘贴文本或 CSV / TXT 文件内容解析成 SN 清单：
 * 每行取第一列（逗号、制表符、分号分隔），去掉引号与空白，跳过空行与表头（sn / 完整SN）。
 * @param {string} text
 * @returns {{ sns: string[], duplicates: string[] }}
 */
export function parseSnList(text) {
  const sns = [];
  const seen = new Set();
  const duplicates = [];
  const lines = String(text || "").replace(/^﻿/, "").split(/\r?\n/);
  lines.forEach((line, i) => {
    let first = line.split(/[,\t;]/)[0] ?? "";
    first = first.trim().replace(/^"(.*)"$/, "$1").trim();
    if (!first) return;
    if (i === 0 && /^(sn|完整sn|序列号|serial)$/i.test(first)) return;
    if (seen.has(first)) {
      duplicates.push(first);
      return;
    }
    seen.add(first);
    sns.push(first);
  });
  return { sns, duplicates };
}

/**
 * 生成弹窗的数量上限。额度不超过默认上限时就是额度；超过时，未解除限制取默认上限，解除后取额度。
 * 都不超过单次硬上限 limits.max。
 * @param {number} quota 可生成额度
 * @param {{ default?: number, max?: number }} limits 后台给出的默认上限与硬上限
 * @param {boolean} unlocked 是否勾选「解除限制」
 */
export function genQtyCap(quota, limits, unlocked) {
  const q = Math.max(0, Math.floor(Number(quota) || 0));
  const def = Number(limits?.default) > 0 ? Number(limits.default) : q;
  const max = Number(limits?.max) > 0 ? Number(limits.max) : q;
  return Math.min(q > def && !unlocked ? def : q, max);
}

/**
 * 把输入的数量纠正到 1 ~ cap 的整数；超过上限时改为上限并标记 corrected（页面据此提示）。
 * @param {unknown} input
 * @param {number} cap
 * @returns {{ qty: number, corrected: boolean }}
 */
export function clampQty(input, cap) {
  const c = Math.max(0, Math.floor(Number(cap) || 0));
  const n = Math.floor(Number(input));
  if (!Number.isFinite(n) || n < 1) return { qty: Math.min(1, c), corrected: false };
  if (n > c) return { qty: c, corrected: true };
  return { qty: n, corrected: false };
}
