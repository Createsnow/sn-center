import { useMutation, useQuery } from '@tanstack/react-query';
import { App as AntdApp, Button, ConfigProvider, Result, Spin } from 'antd';
import enUS from 'antd/locale/en_US';
import viVN from 'antd/locale/vi_VN';
import zhCN from 'antd/locale/zh_CN';
import { lazy, Suspense, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { authApi, refreshSession } from '../api/client';
import { ApiErrorAlert } from '../components/ApiErrorAlert';
import { LoginPage } from '../features/auth/pages/LoginPage';
import { PasswordPage } from '../features/auth/pages/PasswordPage';
import { ProfilePage } from '../features/auth/pages/ProfilePage';
import { useSession } from '../stores/session';
import { usersApi } from '../api/users';
import type { Kind } from '../api/configuration';
import { WorkspaceShell } from './WorkspaceShell';
import { useSnContext } from '../features/sn/useSnContext';

const UsersPage = lazy(() =>
  import('../features/users/pages/UsersPage').then((module) => ({ default: module.UsersPage })),
);
const SnUsersPage = lazy(() =>
  import('../features/sn/pages/SnUsersPage').then((m) => ({ default: m.SnUsersPage })),
);
const SnAuditPage = lazy(() =>
  import('../features/sn/pages/SnAuditPage').then((m) => ({ default: m.SnAuditPage })),
);
const AuditPage = lazy(() =>
  import('../features/audit/pages/AuditPage').then((module) => ({ default: module.AuditPage })),
);
const FactoriesPage = lazy(() =>
  import('../features/sn/pages/FactoriesPage').then((module) => ({
    default: module.FactoriesPage,
  })),
);
const OrdersPage = lazy(() =>
  import('../features/sn/pages/OrdersPage').then((module) => ({ default: module.OrdersPage })),
);
const RulesPage = lazy(() =>
  import('../features/sn/pages/RulesPage').then((module) => ({ default: module.RulesPage })),
);
const snRoutes: Record<string, string> = {
  'sn.acquire': '/acquire',
  'sn.transfer': '/transfer',
  'sn.query': '/query',
  'sn.dashboard': '/dashboard',
  'sn.audit': '/audit',
  'sn.pi-init': '/pi-init',
  'sn.generate': '/generate',
  'sn.factories': '/factories',
  'sn.orders': '/orders',
  'sn.rules': '/rules',
};
const PiInitPage = lazy(() =>
  import('../features/sn/pages/PiInitPage').then((m) => ({ default: m.PiInitPage })),
);
const AcquirePage = lazy(() =>
  import('../features/sn/pages/AcquirePage').then((m) => ({ default: m.AcquirePage })),
);
const TransferPage = lazy(() =>
  import('../features/sn/pages/TransferPage').then((m) => ({ default: m.TransferPage })),
);
const QueryPage = lazy(() =>
  import('../features/sn/pages/QueryPage').then((m) => ({ default: m.QueryPage })),
);
const DashboardPage = lazy(() =>
  import('../features/sn/pages/DashboardPage').then((m) => ({ default: m.DashboardPage })),
);
const GeneratePage = lazy(() =>
  import('../features/sn/pages/GeneratePage').then((m) => ({ default: m.GeneratePage })),
);
const ConfigurationPage = lazy(() =>
  import('../features/configuration/pages/ConfigurationPage').then((module) => ({
    default: module.ConfigurationPage,
  })),
);
const configurationRoutes: Record<string, { path: string; kind: Kind }> = {
  'system.roles': { path: '/system/roles', kind: 'roles' },
  'system.departments': { path: '/system/departments', kind: 'departments' },
  'system.menus': { path: '/system/menus', kind: 'menus' },
  'system.dictionaries': { path: '/system/dictionaries', kind: 'dictionary-types' },
};

function AuthenticatedWorkspace() {
  const location = useLocation();
  const navigate = useNavigate();
  const { t, i18n } = useTranslation();
  const profile = useQuery({ queryKey: ['identity', 'me'], queryFn: authApi.me });
  const snContext = useSnContext(profile.data);
  const menus = useQuery({
    queryKey: ['menus', profile.data?.id, profile.data?.authorizationVersion],
    queryFn: usersApi.menus,
    enabled: !!profile.data && !profile.data.mustChangePassword,
  });
  const availableMenus =
    menus.data?.filter(
      (menu) =>
        ((menu.component === 'system.users' && menu.path === '/system/users') ||
          (menu.component === 'system.audit' && menu.path === '/system/audit') ||
          snRoutes[menu.component] === menu.path ||
          (configurationRoutes[menu.component]?.path === menu.path &&
            profile.data?.permissions.includes('system:security:administer'))) &&
        profile.data?.permissions.includes(menu.permission) &&
        (menu.component !== 'sn.factories' || snContext.data?.role === 'admin'),
    ) ?? [];
  const logout = useMutation({
    mutationFn: authApi.logout,
    onSuccess: () => {
      void navigate('/login');
    },
  });
  useEffect(() => {
    if (profile.data) {
      void i18n.changeLanguage(profile.data.language);
    }
  }, [profile.data, i18n]);
  if (profile.isPending) {
    return (
      <div className="full-state">
        <Spin />
        <p>{t('loading')}</p>
      </div>
    );
  }
  if (!profile.data) {
    return (
      <div className="full-state">
        <ApiErrorAlert error={profile.error} />
        <Button
          onClick={() => {
            void profile.refetch();
          }}
        >
          {t('retry')}
        </Button>
      </div>
    );
  }
  if (
    profile.data.mustChangePassword ||
    ['/password', '/change-password'].includes(location.pathname)
  ) {
    return (
      <Routes>
        <Route
          path="/password"
          element={<PasswordPage mandatory={profile.data.mustChangePassword} />}
        />
        <Route
          path="/change-password"
          element={<PasswordPage mandatory={profile.data.mustChangePassword} />}
        />
        <Route path="*" element={<Navigate to="/password" replace />} />
      </Routes>
    );
  }
  return (
    <WorkspaceShell
      profile={profile.data}
      menus={availableMenus}
      onLogout={() => logout.mutate()}
      loggingOut={logout.isPending}
    >
      <ApiErrorAlert error={logout.error} />
      <ApiErrorAlert error={menus.error} />
      <Suspense fallback={<Spin />}>
        {menus.isPending ||
        (profile.data.permissions.includes('sn:dashboard:view') && snContext.isPending) ? (
          <Spin />
        ) : (
          <Routes>
            <Route path="/profile" element={<ProfilePage profile={profile.data} />} />
            {availableMenus.map((menu) => (
              <Route
                key={menu.id}
                path={menu.path}
                element={
                  menu.component === 'system.users' ? (
                    <UsersPage profile={profile.data} />
                  ) : menu.component === 'system.audit' ? (
                    <AuditPage profile={profile.data} />
                  ) : menu.component === 'sn.factories' ? (
                    <FactoriesPage profile={profile.data} />
                  ) : menu.component === 'sn.orders' ? (
                    <OrdersPage profile={profile.data} />
                  ) : menu.component === 'sn.rules' ? (
                    <RulesPage profile={profile.data} />
                  ) : menu.component === 'sn.pi-init' ? (
                    <PiInitPage profile={profile.data} />
                  ) : menu.component === 'sn.generate' ? (
                    <GeneratePage profile={profile.data} />
                  ) : menu.component === 'sn.query' ? (
                    <QueryPage profile={profile.data} />
                  ) : menu.component === 'sn.audit' ? (
                    <SnAuditPage profile={profile.data} />
                  ) : menu.component === 'sn.dashboard' ? (
                    <DashboardPage profile={profile.data} />
                  ) : menu.component === 'sn.transfer' ? (
                    <TransferPage profile={profile.data} />
                  ) : menu.component === 'sn.acquire' ? (
                    <AcquirePage profile={profile.data} />
                  ) : (
                    <ConfigurationPage
                      key={menu.component}
                      kind={configurationRoutes[menu.component]!.kind}
                      profile={profile.data}
                    />
                  )
                }
              />
            ))}
            <Route
              path="/"
              element={
                availableMenus.some((m) => m.component === 'sn.dashboard') ? (
                  <DashboardPage profile={profile.data} />
                ) : (
                  <Navigate to="/profile" replace />
                )
              }
            />
            <Route
              path="/users"
              element={
                availableMenus.some((m) => m.component === 'system.users') ? (
                  <SnUsersPage profile={profile.data} />
                ) : (
                  <Navigate to="/" replace />
                )
              }
            />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        )}
      </Suspense>
    </WorkspaceShell>
  );
}

export function App() {
  const location = useLocation();
  const { t, i18n } = useTranslation();
  const status = useSession((state) => state.status);
  return (
    <ConfigProvider
      button={{ autoInsertSpace: false }}
      locale={i18n.language === 'en' ? enUS : i18n.language === 'vi' ? viVN : zhCN}
      theme={{
        token: {
          colorPrimary: '#1e4a8a',
          colorText: '#0f172a',
          colorTextSecondary: '#64748b',
          colorBorder: '#dde4ef',
          colorSuccess: '#3f7d5a',
          colorWarning: '#c27803',
          colorError: '#b42318',
          borderRadius: 8,
          fontFamily:
            '"Source Han Sans SC", "Noto Sans SC", "PingFang SC", "Microsoft YaHei", ui-sans-serif, system-ui, sans-serif',
        },
      }}
    >
      <AntdApp component={false} message={{ top: 16 }}>
        {status === 'restoring' ? (
          <div className="full-state">
            <Spin />
            <p>{t('restoring')}</p>
          </div>
        ) : status === 'unavailable' ? (
          <Result
            status="warning"
            title={t('unavailableTitle')}
            subTitle={t('unavailableText')}
            extra={
              <Button
                type="primary"
                onClick={() => {
                  void refreshSession().catch(() => {
                    /* 状态与错误由会话入口保存。 */
                  });
                }}
              >
                {t('retry')}
              </Button>
            }
          />
        ) : status === 'signedIn' ? (
          <AuthenticatedWorkspace />
        ) : (
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route
              path="*"
              element={
                <Navigate
                  to={'/login?redirect=' + encodeURIComponent(location.pathname + location.search)}
                  replace
                />
              }
            />
          </Routes>
        )}
      </AntdApp>
    </ConfigProvider>
  );
}
