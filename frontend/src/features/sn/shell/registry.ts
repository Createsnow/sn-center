import { lazy, type ComponentType, type LazyExoticComponent } from 'react';

type Page = LazyExoticComponent<ComponentType>;

/** 菜单路径 → 页面（按需加载）；建设中的模块用占位页。 */
export const PAGES: Record<string, Page> = {
  '/sn': lazy(() => import('../pages/Dashboard')),
  '/sn/orders': lazy(() => import('../pages/Orders')),
  '/sn/generate': lazy(() => import('../pages/Generate')),
  '/sn/sn-import': lazy(() => import('../pages/SnImport')),
  '/sn/allocate': lazy(() => import('../pages/Allocate')),
  '/sn/rules': lazy(() => import('../pages/Rules')),
  '/sn/transfer': lazy(() => import('../pages/Transfer')),
  '/sn/query': lazy(() => import('../pages/Query')),
  '/sn/audit': lazy(() => import('../pages/Audit')),
  '/sn/users': lazy(() => import('../pages/Users')),
  '/sn/user-groups': lazy(() => import('../pages/UserGroups')),
  '/sn/factories': lazy(() => import('../pages/Factories')),
  '/sn/departments': lazy(() => import('../pages/Departments')),
};

export const PLACEHOLDER: Page = lazy(() => import('../pages/Placeholder'));
