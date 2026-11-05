import type { ReactNode } from 'react';

// 功能路由注册：每个功能在 src/features/{feature}/routes.tsx 导出 routes，App 自动汇总，
// 新增或修改一个功能不需要改动 App.tsx 等共享文件。路由必须位于 /{feature} 前缀下，不能占用其他功能的路径。

export interface FeatureRoute {
  /** /{feature} 或 /{feature}/...；嵌套路由使用 /{feature}/*。 */
  path: string;
  element: ReactNode;
}

export interface FeatureRouteModule {
  routes: readonly FeatureRoute[];
}

const ROUTE_FILE = /\/features\/([a-z][a-z0-9-]*)\/routes\.tsx$/;

export function collectFeatureRoutes(
  modules: Readonly<Record<string, FeatureRouteModule>>,
): FeatureRoute[] {
  const routes: FeatureRoute[] = [];
  const seen = new Set<string>();
  for (const [file, module] of Object.entries(modules)) {
    const feature = ROUTE_FILE.exec(file)?.[1];
    if (!feature) {
      throw new Error(`Route module ${file} must be src/features/{feature}/routes.tsx`);
    }
    for (const route of module.routes) {
      if (route.path !== `/${feature}` && !route.path.startsWith(`/${feature}/`)) {
        throw new Error(`Feature ${feature} registers ${route.path} outside /${feature}`);
      }
      if (seen.has(route.path)) {
        throw new Error(`Route ${route.path} is registered twice`);
      }
      seen.add(route.path);
      routes.push(route);
    }
  }
  return routes;
}
