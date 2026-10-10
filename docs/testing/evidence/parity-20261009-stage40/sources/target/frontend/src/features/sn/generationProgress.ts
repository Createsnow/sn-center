import type { SnWork } from '../../api/snWork';

/** 原始数量始终使用精确整数；仅将已限制在 0..100 的显示百分比转为 number。 */
export function generationPercent(work: Pick<SnWork, 'quantity' | 'processed'>) {
  const total = BigInt(work.quantity);
  if (!total) return 0;
  const done = BigInt(work.processed);
  return Number(((done > total ? total : done) * 200n + total) / (2n * total));
}
