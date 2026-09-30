/** CSV/TXT与粘贴沿用旧页首列规则；只把物理首行的已知列名当标题，SN永不转Number。 */
export function parseSnList(input: string) {
  const sns: string[] = [],
    duplicates: string[] = [],
    seen = new Set<string>();
  input
    .replace(/^\uFEFF/, '')
    .split(/\r?\n/)
    .forEach((line, index) => {
      const first =
        line
          .split(/[,;\t]/)[0]
          ?.trim()
          .replace(/^["']|["']$/g, '')
          .trim() ?? '';
      if (
        !first ||
        (index === 0 && ['sn', '完整sn', '序列号', 'serial'].includes(first.toLowerCase()))
      )
        return;
      if (seen.has(first)) duplicates.push(first);
      else {
        seen.add(first);
        sns.push(first);
      }
    });
  return { sns, duplicates };
}
export const positiveDecimal = (value: string) =>
  /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n;
export function suggestedQuantity(quota: string, limit: string) {
  if (!positiveDecimal(limit)) return '1';
  const available = BigInt(quota),
    suggestion = BigInt(limit);
  return (available < 1n ? 1n : available < suggestion ? available : suggestion).toString();
}
