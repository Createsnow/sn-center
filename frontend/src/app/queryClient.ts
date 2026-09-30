import { QueryClient } from '@tanstack/react-query';

// 写命令与刷新凭据均不自动重试；页面通过明确的重试操作恢复读取失败。
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false, staleTime: 30_000 },
    mutations: { retry: false },
  },
});
