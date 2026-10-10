import { describe, it, expect, vi } from 'vitest';
import { loadCatalog } from './catalog';

describe('continuous prototype catalogs', () => {
  it('loads later pages without rounding business totals or showing a truncated list', async () => {
    const read = vi.fn(async (page: number) => ({
      items: page === 1 ? ['a', 'b'] : ['c'],
      total: '3',
      page,
      size: 2,
    }));
    expect(await loadCatalog(read, new AbortController().signal)).toEqual(['a', 'b', 'c']);
    expect(read.mock.calls).toEqual([[1], [2]]);
    await expect(
      loadCatalog(
        async (page) => ({ items: [], total: '9007199254740993', page, size: 100 }),
        new AbortController().signal,
      ),
    ).rejects.toThrow('Incomplete catalog');
  });
  it('cancels after an in-flight page before requesting more data', async () => {
    const controller = new AbortController();
    const read = vi.fn(async (page: number) => {
      controller.abort();
      return { items: ['a'], total: '2', page, size: 1 };
    });
    await expect(loadCatalog(read, controller.signal)).rejects.toThrow();
    expect(read).toHaveBeenCalledTimes(1);
  });
  it('rejects a changing total and limits remote customer suggestions to the prototype cap', async () => {
    await expect(
      loadCatalog(
        async (page) => ({ items: ['a'], total: page === 1 ? '3' : '4', page, size: 1 }),
        new AbortController().signal,
      ),
    ).rejects.toThrow('changed');
    const read = vi.fn(async (page: number) => ({
      items: Array.from({ length: 100 }, (_, i) => `${page}-${i}`),
      total: '400',
      page,
      size: 100,
    }));
    expect(await loadCatalog(read, new AbortController().signal, 200)).toHaveLength(200);
    expect(read).toHaveBeenCalledTimes(2);
  });
});
