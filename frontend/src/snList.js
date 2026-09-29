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
 * 页面单次上限（默认 10000，可改）下的建议数量：min(额度, 上限)，额度或上限无效时为 0。
 * @param {number} quota
 * @param {number} limit
 */
export function suggestQty(quota, limit) {
  const q = Number(quota) || 0;
  const l = Number(limit) || 0;
  if (q <= 0 || l <= 0) return 0;
  return Math.min(q, l);
}
