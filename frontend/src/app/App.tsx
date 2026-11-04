import { ConfigProvider, Layout, Result, Select } from 'antd';
import enUS from 'antd/locale/en_US';
import viVN from 'antd/locale/vi_VN';
import zhCN from 'antd/locale/zh_CN';
import { useTranslation } from 'react-i18next';
import { Navigate, Route, Routes } from 'react-router-dom';
import { isLanguage, languages, type Language } from '../i18n';

const antdLocales = { 'zh-CN': zhCN, en: enUS, vi: viVN } as const;
const languageLabels: Record<Language, string> = {
  'zh-CN': '中文',
  en: 'English',
  vi: 'Tiếng Việt',
};

// 应用外壳：只负责全局 Provider、布局与路由装配；功能页面放在 src/features/{feature}/pages 并在此按路由懒加载。
export function App() {
  const { t, i18n } = useTranslation();
  const language = isLanguage(i18n.language) ? i18n.language : 'zh-CN';
  return (
    <ConfigProvider locale={antdLocales[language]}>
      <Layout className="app-layout">
        <Layout.Header className="app-header">
          <span className="brand">{t('appName')}</span>
          <Select
            aria-label={t('language')}
            value={language}
            options={languages.map((value) => ({ value, label: languageLabels[value] }))}
            onChange={(value) => void i18n.changeLanguage(value)}
          />
        </Layout.Header>
        <Layout.Content className="app-content">
          <Routes>
            <Route
              path="/"
              element={
                <Result status="info" title={t('shell.title')} subTitle={t('shell.description')} />
              }
            />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Layout.Content>
      </Layout>
    </ConfigProvider>
  );
}
