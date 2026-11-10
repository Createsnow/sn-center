import type { Row } from '../../../api/sn';

// 服务端文档字段读取：ID、计数以十进制字符串传输，显示原样；需要计算时用 num。

export function str(v: unknown): string {
  if (v === null || v === undefined) {
    return '';
  }
  return typeof v === 'string' ? v : String(v);
}

export function num(v: unknown): number {
  if (typeof v === 'number') {
    return v;
  }
  if (typeof v === 'string' && v !== '') {
    const n = Number(v);
    return Number.isFinite(n) ? n : 0;
  }
  return 0;
}

export function bool(v: unknown): boolean {
  return v === true || v === 1 || v === '1' || v === 'true';
}

export function rows(v: unknown): Row[] {
  return Array.isArray(v) ? (v as Row[]) : [];
}

export function obj(v: unknown): Row {
  return typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Row) : {};
}

export function strs(v: unknown): string[] {
  return Array.isArray(v) ? v.map(str) : [];
}

/** 千分位显示数字（字符串原样分组，不经浮点）。 */
export function fmtNum(v: unknown): string {
  const s = str(v);
  if (!/^-?\d+$/.test(s)) {
    return s === '' ? '0' : s;
  }
  return s.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}
