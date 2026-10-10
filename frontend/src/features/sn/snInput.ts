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
          .replace(/^"(.*)"$/, '$1')
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
/** 初始化沿用原型可输入前导零/指数的习惯；以十进制字符串归一化，业务值不经过浮点。 */
export function normalizeStartSequence(input: string): string | undefined {
  const value = input.trim();
  if (/^0(?:x[\da-f]+|b[01]+|o[0-7]+)$/i.test(value)) {
    const integer = BigInt(value);
    return integer > 0n && integer <= 9223372036854775807n ? integer.toString() : undefined;
  }
  const decimal = value.replace(/^([+]?)(?=\.)/, (_, sign: string) => sign + '0');
  const parts = /^\+?(\d+)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/.exec(decimal);
  if (!parts || (parts[3] && parts[3].replace(/^[+-]?0*/, '').length > 6)) return undefined;
  let digits = (parts[1]! + (parts[2] ?? '')).replace(/^0+/, '');
  const shift = Number(parts[3] ?? 0) - (parts[2]?.length ?? 0);
  if (!digits || digits.length + shift < 1 || digits.length + shift > 19) return undefined;
  if (shift < 0) {
    if (!/^0+$/.test(digits.slice(shift))) return undefined;
    digits = digits.slice(0, shift);
  } else digits += '0'.repeat(shift);
  return BigInt(digits) <= 9223372036854775807n ? digits : undefined;
}
export const positiveDecimal = (value: string) =>
  /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n;
export function suggestedQuantity(quota: string, limit: string) {
  if (!positiveDecimal(limit)) return '1';
  const available = BigInt(quota),
    suggestion = BigInt(limit);
  return (available < 1n ? 1n : available < suggestion ? available : suggestion).toString();
}
