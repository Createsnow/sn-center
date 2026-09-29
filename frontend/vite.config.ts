import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";
import AutoImport from "unplugin-auto-import/vite";
import Components from "unplugin-vue-components/vite";
import { ElementPlusResolver } from "unplugin-vue-components/resolvers";
import { fileURLToPath, URL } from "node:url";

const envDir = fileURLToPath(new URL("..", import.meta.url));

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, envDir, "");
  const host = env.VITE_DEV_HOST || "127.0.0.1";
  const port = Number(env.VITE_DEV_PORT) || 5173;
  const proxyTarget = `http://127.0.0.1:${env.SN_PORT || "8000"}`;
  const proxy = {
    "/api": { target: proxyTarget, changeOrigin: true },
    "/health": { target: proxyTarget, changeOrigin: true },
    "/docs": { target: proxyTarget, changeOrigin: true },
    "/swagger-ui": { target: proxyTarget, changeOrigin: true },
    "/openapi.json": { target: proxyTarget, changeOrigin: true },
  };
  return {
    envDir,
    plugins: [
      vue(),
      AutoImport({ resolvers: [ElementPlusResolver()] }),
      Components({ resolvers: [ElementPlusResolver({ importStyle: "css" })] }),
    ],
    resolve: {
      alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
    },
    server: { host, port, proxy },
    preview: { host, port },
  };
});
