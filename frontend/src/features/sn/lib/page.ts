import { createContext, useContext } from 'react';

/** 页签内的页面上下文：页面读取自己页签的地址参数（切换页签时不受其他页签地址影响）。 */
export interface PageContext {
  path: string;
  query: URLSearchParams;
  active: boolean;
  /** 打开站内页面（新开或切到已有页签）。 */
  open: (to: string) => void;
}

export const PageCtx = createContext<PageContext>({
  path: '/sn',
  query: new URLSearchParams(),
  active: true,
  open: () => undefined,
});

export function usePage(): PageContext {
  return useContext(PageCtx);
}
