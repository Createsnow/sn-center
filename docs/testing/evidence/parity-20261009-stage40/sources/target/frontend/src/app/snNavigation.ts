import type { SnContext } from '../api/snQuery';

const groups = [
  {
    name: 'groupWork',
    items: [
      {
        component: 'sn.dashboard',
        path: '/',
        name: 'dashboard',
        icon: 'Odometer',
        permission: 'sn:dashboard:view',
      },
    ],
  },
  {
    name: 'groupHq',
    items: [
      {
        component: 'sn.orders',
        path: '/orders',
        name: 'orders',
        icon: 'Tickets',
        permission: 'sn:order:list',
      },
      {
        component: 'sn.generate',
        path: '/generate',
        name: 'generate',
        icon: 'Finished',
        permission: 'sn:generation:view',
      },
      {
        component: 'sn.pi-init',
        path: '/pi-init',
        name: 'piInit',
        icon: 'Upload',
        permission: 'sn:pi-init:view',
      },
      {
        component: 'sn.rules',
        path: '/rules',
        name: 'rules',
        icon: 'SetUp',
        permission: 'sn:rule:list',
      },
    ],
  },
  {
    name: 'groupFactory',
    items: [
      {
        component: 'sn.acquire',
        path: '/acquire',
        name: 'acquire',
        icon: 'Printer',
        permission: 'sn:acquire:view',
      },
      {
        component: 'sn.transfer',
        path: '/transfer',
        name: 'transfer',
        icon: 'Switch',
        permission: 'sn:transfer:view',
      },
    ],
  },
  {
    name: 'groupQuery',
    items: [
      {
        component: 'sn.query',
        path: '/query',
        name: 'query',
        icon: 'Search',
        permission: 'sn:query:view',
      },
      {
        component: 'sn.audit',
        path: '/audit',
        name: 'audit',
        icon: 'Notebook',
        permission: 'sn:audit:view',
      },
    ],
  },
  {
    name: 'groupSystem',
    items: [
      {
        component: 'system.users',
        path: '/users',
        name: 'users',
        icon: 'User',
        permission: 'system:user:list',
      },
      {
        component: 'sn.factories',
        path: '/factories',
        name: 'factories',
        icon: 'OfficeBuilding',
        permission: 'sn:factory:list',
      },
    ],
  },
] as const;

/** 左侧导航和首页快捷入口共用同一顺序，并与当前权限求交集。 */
export function snNavigation(context: SnContext | undefined, permissions: string[]) {
  return groups
    .map((group) => ({
      ...group,
      items: group.items.filter(
        (item) =>
          permissions.includes(item.permission) &&
          (item.component !== 'sn.factories' || context?.role === 'admin'),
      ),
    }))
    .filter((group) => group.items.length);
}
