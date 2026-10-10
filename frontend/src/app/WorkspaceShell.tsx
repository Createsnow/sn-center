import { useMutation } from '@tanstack/react-query';
import { Button, Dropdown, Tag } from 'antd';
import { useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router-dom';
import { authApi } from '../api/client';
import type { UserProfile } from '../api/contract';
import type { MenuEntry } from '../api/users';
import { ApiErrorAlert } from '../components/ApiErrorAlert';
import { LanguageSelect } from '../components/LanguageSelect';
import { PrototypeIcon } from '../components/prototype/PrototypeIcon';

import { snNavigation } from './snNavigation';
import { useSnContext } from '../features/sn/useSnContext';

/** 展示顺序遵循原型；可见性仍取后端当前菜单及资格，不能用固定导航放宽权限。 */
export function WorkspaceShell({
  profile,
  menus,
  onLogout,
  loggingOut,
  children,
}: {
  profile: UserProfile;
  menus: MenuEntry[];
  onLogout: () => void;
  loggingOut: boolean;
  children: ReactNode;
}) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const context = useSnContext(profile);
  const [collapsed, setCollapsed] = useState(() => window.innerWidth < 1100);
  const language = useMutation({ mutationFn: authApi.language });
  useEffect(() => {
    const resize = () => setCollapsed(window.innerWidth < 1100);
    window.addEventListener('resize', resize);
    return () => window.removeEventListener('resize', resize);
  }, []);
  const available = snNavigation(context.data, profile.permissions)
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => menus.some((menu) => menu.component === item.component)),
    }))
    .filter((group) => group.items.length);
  const path =
    location.pathname === '/dashboard'
      ? '/'
      : location.pathname === '/system/users'
        ? '/users'
        : location.pathname;
  const current = available.flatMap((group) => group.items).find((item) => item.path === path);
  const currentTitle = current
    ? t('prototype:nav.' + current.name)
    : path === '/password' || path === '/change-password'
      ? t('prototype:password.title')
      : t(menus.find((menu) => menu.path === path)?.name ?? 'profile');
  return (
    <div className={'workspace prototype-workspace' + (collapsed ? ' is-collapsed' : '')}>
      <aside className="prototype-sidebar">
        <div className="prototype-brand">
          <PrototypeIcon name="Stamp" />
          {!collapsed && (
            <div>
              <div className="prototype-brand-title">{t('prototype:app.short')}</div>
              <div className="prototype-brand-sub">{t('prototype:app.subtitle')}</div>
            </div>
          )}
        </div>
        <nav
          className="prototype-navigation"
          role="menubar"
          aria-orientation="vertical"
          aria-label={t('workingSpace')}
          onKeyDown={(event) => {
            if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
            const items = Array.from(
              event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="menuitem"]'),
            );
            const currentIndex = items.findIndex((item) => item === document.activeElement);
            const next =
              event.key === 'Home'
                ? 0
                : event.key === 'End'
                  ? items.length - 1
                  : (currentIndex + (event.key === 'ArrowDown' ? 1 : -1) + items.length) %
                    items.length;
            event.preventDefault();
            items[next]?.focus();
          }}
        >
          {available.map((group) => (
            <div key={group.name} role="group" aria-label={t('prototype:nav.' + group.name)}>
              {!collapsed && (
                <div className="prototype-menu-group">{t('prototype:nav.' + group.name)}</div>
              )}
              {group.items.map((item) => (
                <button
                  key={item.component}
                  role="menuitem"
                  type="button"
                  className={'prototype-menu-item' + (path === item.path ? ' is-active' : '')}
                  aria-current={path === item.path ? 'page' : undefined}
                  aria-label={t('prototype:nav.' + item.name)}
                  title={collapsed ? t('prototype:nav.' + item.name) : undefined}
                  onClick={() => {
                    void navigate(item.path);
                  }}
                >
                  <PrototypeIcon name={item.icon} />
                  {!collapsed && <span>{t('prototype:nav.' + item.name)}</span>}
                </button>
              ))}
            </div>
          ))}
        </nav>
      </aside>
      <div className="prototype-body">
        <header className="prototype-header">
          <div className="prototype-header-left">
            <Button
              type="text"
              aria-label={t(collapsed ? 'shell.expand' : 'shell.collapse')}
              icon={<PrototypeIcon name={collapsed ? 'Expand' : 'Fold'} />}
              onClick={() => setCollapsed(!collapsed)}
            />
            <nav className="prototype-breadcrumb" aria-label={t('workingSpace')}>
              <span className="prototype-breadcrumb-item">
                <span className="prototype-breadcrumb-text">{t('prototype:app.short')}</span>
                <span className="prototype-breadcrumb-separator" aria-hidden="true">
                  /
                </span>
              </span>
              <span className="prototype-breadcrumb-item">
                <span className="prototype-breadcrumb-text" aria-current="page">
                  {currentTitle}
                </span>
              </span>
            </nav>
          </div>
          <div className="prototype-header-right">
            {context.data && !context.data.allFactories ? (
              <Tag className="prototype-factory-tag">
                <span>
                  <PrototypeIcon name="OfficeBuilding" />{' '}
                  {context.data.factories.map((f) => f.name || f.code).join('、')}
                </span>
              </Tag>
            ) : context.data?.role === 'admin' ? (
              <Tag>{t('prototype:app.hq')}</Tag>
            ) : null}
            <div className="prototype-language">
              <LanguageSelect
                compact
                suffixIcon={<PrototypeIcon name="ArrowDown" />}
                value={profile.language}
                disabled={language.isPending}
                onChange={(value) => {
                  void i18n.changeLanguage(value);
                  language.mutate(value);
                }}
              />
            </div>
            <Dropdown
              trigger={['click']}
              menu={{
                items: [
                  {
                    key: 'identity',
                    label: `${t('prototype:app.empNo')}：${profile.username}`,
                    disabled: true,
                  },
                  { type: 'divider' },
                  { key: '/password', label: t('prototype:app.changePassword') },
                  { key: 'logout', label: t('prototype:app.logout'), disabled: loggingOut },
                ],
                onClick: ({ key }) => {
                  if (key === 'logout') onLogout();
                  else if (key.startsWith('/')) void navigate(key);
                },
              }}
            >
              <button className="prototype-user" type="button" aria-label={profile.displayName}>
                <span className="prototype-avatar">{profile.displayName.slice(0, 1)}</span>
                <span className="prototype-user-name">
                  {profile.displayName}
                  {context.data ? ` · ${t('prototype:roles.' + context.data.role)}` : ''}
                </span>
                <PrototypeIcon name="ArrowDown" />
              </button>
            </Dropdown>
          </div>
        </header>
        <main className="workspace-content prototype-main">
          <ApiErrorAlert error={language.error ?? context.error} />
          {children}
        </main>
      </div>
    </div>
  );
}
