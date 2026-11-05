import { describe, expect, it } from 'vitest';
import { collectFeatureRoutes } from './featureRoutes';

describe('collectFeatureRoutes', () => {
  it('collects routes under each feature prefix', () => {
    const routes = collectFeatureRoutes({
      '../features/order/routes.tsx': {
        routes: [
          { path: '/order', element: null },
          { path: '/order/*', element: null },
        ],
      },
      '../features/stock/routes.tsx': { routes: [{ path: '/stock/items', element: null }] },
    });
    expect(routes.map((route) => route.path)).toEqual(['/order', '/order/*', '/stock/items']);
  });

  it('rejects a route outside the feature prefix', () => {
    expect(() =>
      collectFeatureRoutes({
        '../features/stock/routes.tsx': { routes: [{ path: '/order/hijack', element: null }] },
      }),
    ).toThrow('outside /stock');
    expect(() =>
      collectFeatureRoutes({
        '../features/order/routes.tsx': { routes: [{ path: '/orderx', element: null }] },
      }),
    ).toThrow('outside /order');
  });

  it('rejects modules outside the feature routes convention', () => {
    expect(() =>
      collectFeatureRoutes({ '../features/order/pages/routes.tsx': { routes: [] } }),
    ).toThrow('must be src/features');
  });
});
