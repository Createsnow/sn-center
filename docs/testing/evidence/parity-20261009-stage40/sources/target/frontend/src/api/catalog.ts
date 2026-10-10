import type { Page } from './sn';

/** 原型目录连续展示；分页接口保留精确总数，取消后不再读取下一页，禁止静默截断。 */
export async function loadCatalog<T>(
  read: (page: number) => Promise<Page<T>>,
  signal: AbortSignal,
  limit?: number,
): Promise<T[]> {
  const items: T[] = [];
  let total: string | undefined;
  for (let page = 1; page <= 10_000; page++) {
    signal.throwIfAborted();
    const result = await read(page);
    signal.throwIfAborted();
    if (total !== undefined && total !== result.total)
      throw new Error('Catalog changed during loading');
    total = result.total;
    items.push(...result.items);
    if (limit !== undefined && items.length >= limit) return items.slice(0, limit);
    if (BigInt(items.length) >= BigInt(total)) return items;
    if (result.items.length === 0) throw new Error('Incomplete catalog page');
  }
  throw new Error('Catalog exceeds supported page count');
}
