import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 15173,
    strictPort: true,
    // Windows 写文件会先截断；等写入稳定，避免 HMR 缓存空模块。
    watch: { awaitWriteFinish: { stabilityThreshold: 200, pollInterval: 50 } },
    proxy: {
      '/api': {
        target: process.env.ADTEK_API_TARGET ?? 'http://127.0.0.1:18080',
        changeOrigin: false,
      },
    },
  },
  build: { target: 'es2023', sourcemap: false },
});
