/** 菜单按业务模块分组（与原系统相同）；soon = 建设中的占位页。路径都在本功能前缀 /sn 下。 */
export interface MenuItem {
  path: string;
  title: string;
  icon: string;
  roles: string[];
  superAdmin?: boolean;
  soon?: boolean;
}
export interface MenuGroup {
  group: string;
  icon?: string;
  items: MenuItem[];
}

const ADMIN = ['admin'];
const OPS = ['admin', 'operator'];
const AUDIT = ['admin', 'auditor'];
const ALL = ['admin', 'operator', 'auditor'];

export const MENU: MenuGroup[] = [
  { group: 'nav.groupWork', items: [{ path: '/sn', title: 'nav.dashboard', icon: 'dashboard', roles: ALL }] },
  {
    group: 'nav.groupOrder',
    icon: 'orders',
    items: [{ path: '/sn/orders', title: 'nav.orders', icon: 'orders', roles: OPS }],
  },
  {
    group: 'nav.groupSn',
    icon: 'sn',
    items: [
      { path: '/sn/generate', title: 'nav.generate', icon: 'generate', roles: OPS },
      { path: '/sn/sn-import', title: 'nav.snImport', icon: 'upload', roles: OPS },
      { path: '/sn/allocate', title: 'nav.allocate', icon: 'allocate', roles: OPS },
      { path: '/sn/transfer', title: 'nav.transfer', icon: 'transfer', roles: OPS },
      { path: '/sn/query', title: 'nav.query', icon: 'search', roles: ALL },
      { path: '/sn/rules', title: 'nav.rules', icon: 'rules', roles: OPS },
    ],
  },
  {
    group: 'nav.groupBox',
    icon: 'box',
    items: [
      { path: '/sn/box/generate', title: 'nav.boxGenerate', icon: 'generate', roles: OPS, soon: true },
      { path: '/sn/box/transfer', title: 'nav.boxTransfer', icon: 'transfer', roles: OPS, soon: true },
      { path: '/sn/box/query', title: 'nav.boxQuery', icon: 'search', roles: ALL, soon: true },
      { path: '/sn/box/rules', title: 'nav.boxRules', icon: 'rules', roles: OPS, soon: true },
    ],
  },
  {
    group: 'nav.groupPallet',
    icon: 'pallet',
    items: [
      { path: '/sn/pallet/generate', title: 'nav.palletGenerate', icon: 'generate', roles: OPS, soon: true },
      { path: '/sn/pallet/transfer', title: 'nav.palletTransfer', icon: 'transfer', roles: OPS, soon: true },
      { path: '/sn/pallet/query', title: 'nav.palletQuery', icon: 'search', roles: ALL, soon: true },
    ],
  },
  {
    group: 'nav.groupLog',
    icon: 'audit',
    items: [{ path: '/sn/audit', title: 'nav.audit', icon: 'audit', roles: AUDIT }],
  },
  {
    group: 'nav.groupAccount',
    icon: 'user',
    items: [
      { path: '/sn/users', title: 'nav.users', icon: 'user', roles: ADMIN, superAdmin: true },
      { path: '/sn/user-groups', title: 'nav.userGroups', icon: 'team', roles: ADMIN, superAdmin: true },
    ],
  },
  {
    group: 'nav.groupOrg',
    icon: 'org',
    items: [
      { path: '/sn/factories', title: 'nav.factories', icon: 'org', roles: ADMIN },
      { path: '/sn/departments', title: 'nav.departments', icon: 'school', roles: OPS },
    ],
  },
];

export const MENU_ITEMS = MENU.flatMap((g) => g.items.map((m) => ({ ...m, group: g.group })));

export function menuItem(path: string) {
  return MENU_ITEMS.find((m) => m.path === path);
}

export function canOpen(path: string, role: string, superAdmin: boolean): boolean {
  const m = menuItem(path);
  return !!m && m.roles.includes(role) && (!m.superAdmin || superAdmin);
}
