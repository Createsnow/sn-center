import {
  BankOutlined,
  BarcodeOutlined,
  CheckOutlined,
  GlobalOutlined,
  LockOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MoreOutlined,
} from '@ant-design/icons';
import { Avatar, Button, Dropdown, Menu, Spin, Tag, Tooltip, type MenuProps } from 'antd';
import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAccount, useAuth } from '../lib/auth';
import { canOpen, MENU, menuItem } from '../lib/menu';
import { PageCtx } from '../lib/page';
import { str } from '../lib/row';
import * as tl from '../lib/tabList';
import { ICONS } from './icons';
import { PAGES, PLACEHOLDER } from './registry';
import { TabBar } from './TabBar';

const LOCALES = [
  { value: 'zh-CN', label: '简体中文', short: '中' },
  { value: 'en', label: 'English', short: 'EN' },
  { value: 'vi', label: 'Tiếng Việt', short: 'VI' },
] as const;
const TABS_KEY = 'sn_tabs';

function readTabs(): unknown {
  try {
    return JSON.parse(sessionStorage.getItem(TABS_KEY) ?? 'null') as unknown;
  } catch {
    return null;
  }
}

/** 主框架：左侧按业务模块分组的菜单、顶栏页签、数据范围与语言；页签内的页面切换时保留（与原系统 keep-alive 相同）。 */
export function AppLayout() {
  const { t, i18n } = useTranslation('sn');
  const account = useAccount();
  const auth = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [collapsed, setCollapsed] = useState(() => window.innerWidth < 1100);
  const uid = str(account.profile?.id) || null;
  const allowed = useCallback(
    (path: string) => canOpen(path, account.role, account.isSuperAdmin),
    [account.role, account.isSuperAdmin],
  );
  const [tabs, setTabs] = useState<tl.Tab[]>(() => tl.restoreTabs(readTabs(), uid, allowed));
  const lastPath = useRef<string | null>(null);
  const path = location.pathname.replace(/\/+$/, '') || '/sn';
  const fullPath = path + location.search;

  useEffect(() => {
    if (uid === null) return;
    try {
      sessionStorage.setItem(TABS_KEY, JSON.stringify({ uid, tabs }));
    } catch {
      /* 存不了只是刷新后不恢复 */
    }
  }, [tabs, uid]);

  useEffect(() => {
    if (path !== tl.HOME && !allowed(path)) {
      navigate('/sn', { replace: true });
      return;
    }
    setTabs((cur) => tl.visit(cur, { path, fullPath, hasQuery: location.search.length > 1 }, lastPath.current));
    lastPath.current = path;
  }, [path, fullPath, location.search, allowed, navigate]);

  useEffect(() => {
    const onResize = () => setCollapsed(window.innerWidth < 1100);
    window.addEventListener('resize', onResize);
    void auth.refresh().catch(() => undefined);
    return () => window.removeEventListener('resize', onResize);
    // 只在进入主框架时刷新一次账户信息
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const menus = useMemo(
    () =>
      MENU.map((g) => ({
        ...g,
        items: g.items.filter((m) => m.roles.includes(account.role) && (!m.superAdmin || account.isSuperAdmin)),
      })).filter((g) => g.items.length),
    [account.role, account.isSuperAdmin],
  );
  const menuItems: MenuProps['items'] = menus.flatMap((g) =>
    !g.icon
      ? g.items.map((m) => ({ key: m.path, icon: ICONS[m.icon], label: t(m.title) }))
      : [
          {
            key: g.group,
            icon: ICONS[g.icon],
            label: t(g.group),
            children: g.items.map((m) => ({
              key: m.path,
              label: (
                <span style={{ display: 'flex', alignItems: 'center' }}>
                  <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis' }}>{t(m.title)}</span>
                  {m.soon ? <span className="sn-soon">{t('nav.soon')}</span> : null}
                </span>
              ),
            })),
          },
        ],
  );
  const currentGroup = MENU.find((g) => g.items.some((m) => m.path === path))?.group;
  const [openKeys, setOpenKeys] = useState<string[]>(() =>
    [...new Set([currentGroup, 'nav.groupSn'].filter((g): g is string => !!g))],
  );

  const open = useCallback(
    (to: string) => {
      const p = to.split('?')[0] ?? to;
      const tab = to.includes('?') ? undefined : tabs.find((x) => x.path === p);
      navigate(tab?.fullPath ?? to);
    },
    [navigate, tabs],
  );

  const onAction = (action: string, target: string) => {
    if (action === 'refresh') {
      setTabs((cur) => tl.refreshTab(cur, target));
      const tab = tabs.find((x) => x.path === target);
      if (tab && target !== path) navigate(tab.fullPath);
      return;
    }
    if (action === 'close') {
      const r = tl.closeTab(tabs, target, path);
      setTabs(r.tabs);
      if (r.next) navigate(r.next.fullPath);
      return;
    }
    const next =
      action === 'closeOthers'
        ? tl.closeOthers(tabs, target)
        : action === 'closeRight'
          ? tl.closeRight(tabs, target)
          : tl.closeAll(tabs);
    setTabs(next);
    if (!next.find((x) => x.path === path)) {
      const tab = next.find((x) => x.path === (action === 'closeAll' ? tl.HOME : target)) ?? next[0];
      if (tab) navigate(tab.fullPath);
    }
  };

  const scope = account.isAdmin
    ? { kind: 'hq', label: t('app.allFactories'), tip: t('app.scopeTip.hq'), readonly: false }
    : account.isOperator
      ? { kind: 'factory', label: account.scopeNames, tip: t('app.scopeTip.factory'), readonly: false }
      : { kind: 'factory', label: account.scopeNames, tip: t('app.scopeTip.queryFactory'), readonly: true };
  const roleShort = account.isSuperAdmin
    ? t('app.superAdmin')
    : i18n.exists(`app.roleShort.${account.role}`, { ns: 'sn' })
      ? t(`app.roleShort.${account.role}`)
      : account.role;
  const name = str(account.profile?.name);
  const initial = (name || '?').trim().slice(0, 1).toUpperCase();
  const locale = LOCALES.find((l) => l.value === i18n.language) ?? LOCALES[0];

  const logout = () => {
    try {
      sessionStorage.removeItem(TABS_KEY);
    } catch {
      /* ignore */
    }
    void auth.clear();
    navigate('/sn/login');
  };

  const meMenu: MenuProps = {
    items: [
      {
        key: 'card',
        disabled: true,
        label: (
          <div className="sn-card">
            <Avatar size={40} className={`sn-avatar ${scope.kind}`}>
              {initial}
            </Avatar>
            <div>
              <div className="sn-card-name">{name}</div>
              <div className="sn-card-line">
                {t('app.empNo')} {str(account.profile?.empNo)}
              </div>
              <div className="sn-card-line">{t('app.tz', { tz: account.tz })}</div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginTop: 8 }}>
                <Tag>{roleShort}</Tag>
                <Tag color={scope.kind === 'factory' ? 'warning' : 'default'}>{scope.label}</Tag>
                {scope.readonly ? <Tag>{t('app.readonly')}</Tag> : null}
              </div>
            </div>
          </div>
        ),
      },
      { type: 'divider' },
      { key: 'password', icon: <LockOutlined />, label: t('app.changePassword') },
      { key: 'logout', icon: <LogoutOutlined />, label: t('app.logout') },
    ],
    onClick: ({ key }) => {
      if (key === 'password') navigate('/sn/password');
      if (key === 'logout') logout();
    },
  };

  return (
    <div className="sn-shell">
      <aside className="sn-aside" style={{ width: collapsed ? 64 : 220 }}>
        <div className={`sn-brand${collapsed ? ' mini' : ''}`}>
          <BarcodeOutlined style={{ fontSize: 22 }} />
          {!collapsed ? (
            <div>
              <div className="sn-brand-title">{t('app.short')}</div>
              <div className="sn-brand-sub">{t('app.subtitle')}</div>
            </div>
          ) : null}
        </div>
        <div className="sn-nav">
          <Menu
            theme="dark"
            mode="inline"
            inlineCollapsed={collapsed}
            selectedKeys={[path]}
            openKeys={collapsed ? undefined : openKeys}
            onOpenChange={(keys) => setOpenKeys(keys)}
            items={menuItems}
            onClick={({ key }) => open(key)}
          />
        </div>
        <Dropdown trigger={['click']} placement={collapsed ? 'topRight' : 'topLeft'} menu={meMenu}>
          <div className={`sn-me${collapsed ? ' mini' : ''}`} title={collapsed ? `${name} · ${roleShort}` : undefined}>
            <Avatar size={32} className={`sn-avatar ${scope.kind}`}>
              {initial}
            </Avatar>
            {!collapsed ? (
              <>
                <span className="sn-me-text">
                  <span className="sn-me-name">{name}</span>
                  <span className="sn-me-role">{roleShort}</span>
                </span>
                <MoreOutlined style={{ color: '#64748b' }} />
              </>
            ) : null}
          </div>
        </Dropdown>
      </aside>
      <div className="sn-body">
        <header className="sn-header">
          <Button
            type="text"
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed(!collapsed)}
          />
          <TabBar
            tabs={tabs}
            active={path}
            onGo={(tab) => tab.path !== path && navigate(tab.fullPath)}
            onAction={onAction}
            onMove={(from, to) => setTabs((cur) => tl.moveTab(cur, from, to))}
          />
          <div className="sn-right">
            <Tooltip title={scope.tip} placement="bottom">
              <span className={`sn-scope ${scope.kind}`}>
                <BankOutlined />
                <span className="sn-scope-text">{scope.label}</span>
                {scope.readonly ? <span className="sn-scope-ro">{t('app.readonly')}</span> : null}
              </span>
            </Tooltip>
            <Dropdown
              trigger={['click']}
              menu={{
                items: LOCALES.map((l) => ({
                  key: l.value,
                  label: (
                    <span style={{ display: 'inline-flex', justifyContent: 'space-between', gap: 16, minWidth: 110 }}>
                      {l.label}
                      {l.value === locale.value ? <CheckOutlined /> : null}
                    </span>
                  ),
                })),
                onClick: ({ key }) => void auth.changeLocale(key as 'zh-CN' | 'en' | 'vi'),
              }}
            >
              <Button type="text" icon={<GlobalOutlined />} title={t('app.language')}>
                {locale.short}
              </Button>
            </Dropdown>
          </div>
        </header>
        <main className="sn-main">
          {tabs.map((tab) => {
            const Page = PAGES[tab.path] ?? (menuItem(tab.path) ? PLACEHOLDER : null);
            if (!Page) return null;
            const active = tab.path === path;
            // 首页不缓存：每次切回首页都重新加载，统计数字是最新的
            if (!active && tab.path === tl.HOME) return null;
            const query = new URLSearchParams(tab.fullPath.split('?')[1] ?? '');
            return (
              <div key={`${tab.path}#${tab.ver}`} style={{ display: active ? 'block' : 'none', height: '100%' }}>
                <PageCtx.Provider value={{ path: tab.path, query, active, open }}>
                  <Suspense fallback={<Spin />}>
                    <Page />
                  </Suspense>
                </PageCtx.Provider>
              </div>
            );
          })}
        </main>
      </div>
    </div>
  );
}
