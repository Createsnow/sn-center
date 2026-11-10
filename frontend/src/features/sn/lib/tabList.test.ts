import { describe, expect, it } from 'vitest';
import { closeTab, HOME, moveTab, restoreTabs, visit, withHome } from './tabList';

describe('tabList', () => {
  it('keeps home first and reopens on new query from another page', () => {
    let tabs = withHome([]);
    tabs = visit(tabs, { path: '/sn/query', fullPath: '/sn/query', hasQuery: false }, HOME);
    expect(tabs.map((t) => t.path)).toEqual([HOME, '/sn/query']);
    const ver = tabs[1]!.ver;
    tabs = visit(tabs, { path: '/sn/query', fullPath: '/sn/query?pi=1', hasQuery: true }, HOME);
    expect(tabs[1]!.ver).toBeGreaterThan(ver);
  });

  it('closes to the neighbour and never closes home', () => {
    const tabs = withHome([
      { path: '/sn/a', fullPath: '/sn/a', ver: 2 },
      { path: '/sn/b', fullPath: '/sn/b', ver: 3 },
    ]);
    expect(closeTab(tabs, '/sn/a', '/sn/a').next?.path).toBe('/sn/b');
    expect(closeTab(tabs, HOME, HOME).tabs).toBe(tabs);
    expect(moveTab(tabs, 2, 0)).toBe(tabs);
  });

  it('restores only the same account and allowed pages', () => {
    const saved = {
      uid: '1',
      tabs: [
        { path: '/sn/a', fullPath: '/sn/a', ver: 1 },
        { path: '/sn/x', fullPath: '/sn/x', ver: 2 },
      ],
    };
    expect(restoreTabs(saved, '1', (p) => p === '/sn/a').map((t) => t.path)).toEqual([
      HOME,
      '/sn/a',
    ]);
    expect(restoreTabs(saved, '2', () => true).map((t) => t.path)).toEqual([HOME]);
  });
});
