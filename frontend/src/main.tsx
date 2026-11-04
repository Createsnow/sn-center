import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { App } from './app/App';
import { queryClient } from './app/queryClient';
import { refreshSession } from './api/client';
import './i18n';
import 'antd/dist/reset.css';
import './styles.css';

// 页面恢复只发起一次刷新；失败状态由入口区分未登录与依赖不可用，禁止静默重试旧刷新凭据。
void refreshSession().catch(() => {
  /* App 根据 session.status 呈现恢复结果。 */
});
const root = document.getElementById('root');
if (!root) {
  throw new Error('Missing application root');
}
createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
