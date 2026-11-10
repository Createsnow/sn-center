import { lazy } from 'react';
import type { FeatureRoute } from '../../routing/featureRoutes';

const SnApp = lazy(() => import('./SnApp'));

/** 溯码中台：全部页面在 /sn 前缀下（登录页、主框架与各功能页）。 */
export const routes: readonly FeatureRoute[] = [
  { path: '/sn', element: <SnApp /> },
  { path: '/sn/*', element: <SnApp /> },
];
