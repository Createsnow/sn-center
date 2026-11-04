import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 15183,
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.ADTEK_API_TARGET ?? 'http://127.0.0.1:18090',
        changeOrigin: false,
      },
    },
  },
  build: { target: 'es2023', sourcemap: false },
});
