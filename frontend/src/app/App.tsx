import { App as AntApp, ConfigProvider, Spin } from 'antd';
import enUS from 'antd/locale/en_US';
import viVN from 'antd/locale/vi_VN';
import zhCN from 'antd/locale/zh_CN';
import { Suspense } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, Route, Routes } from 'react-router-dom';
import { isLanguage } from '../i18n';
import { collectFeatureRoutes, type FeatureRouteModule } from '../routing/featureRoutes';

const antdLocales = { 'zh-CN': zhCN, en: enUS, vi: viVN } as const;

// 功能路由由各功能的 src/features/{feature}/routes.tsx 导出并在此自动汇总；新增功能不修改本文件。
const featureRoutes = collectFeatureRoutes(
  import.meta.glob<FeatureRouteModule>('../features/*/routes.tsx', { eager: true }),
);

// 应用外壳：只负责全局 Provider 与路由装配；页面布局（菜单、页签、语言切换）由功能自己提供，
// 与原系统一致整屏铺满。根路径进入溯码中台。
export function App() {
  const { i18n } = useTranslation();
  const language = isLanguage(i18n.language) ? i18n.language : 'zh-CN';
  return (
    <ConfigProvider
      locale={antdLocales[language]}
      theme={{ token: { colorPrimary: '#1e4a8a', borderRadius: 8, colorSuccess: '#3f7d5a', colorWarning: '#c27803', colorError: '#b42318' } }}
    >
      <AntApp>
        <Suspense fallback={<Spin />}>
          <Routes>
            {featureRoutes.map((route) => (
              <Route key={route.path} path={route.path} element={route.element} />
            ))}
            <Route path="/" element={<Navigate to="/sn" replace />} />
            <Route path="*" element={<Navigate to="/sn" replace />} />
          </Routes>
        </Suspense>
      </AntApp>
    </ConfigProvider>
  );
}
