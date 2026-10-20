/** 查询条件里的字符串去首尾空格后再发给后端。 */
export const trimmed = (o: Record<string, unknown>) =>
  Object.fromEntries(Object.entries(o).map(([k, v]) => [k, typeof v === "string" ? v.trim() : v]));

/** 按前缀取词条（如 query.alertType.CONFLICT），没有词条时原样显示代码。 */
export function codeLabel(t: (key: string) => string, prefix: string, code?: string | null) {
  if (!code) return "";
  const key = `${prefix}.${code}`;
  const s = t(key);
  return s === key ? code : s;
}
