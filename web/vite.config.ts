import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// 后端端口。本机 8080 被占时用 MP_API_PORT 覆盖，无需改代码
const apiPort = process.env.MP_API_PORT ?? '8080'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    // 同源代理转发，后端无需加 CORS 配置
    proxy: {
      '/api': {
        target: `http://localhost:${apiPort}`,
        changeOrigin: true,
      },
    },
  },
  build: {
    // 产物拷进 mp-gateway 静态目录由 gateway 托管（V1/V2 单进程阶段）
    outDir: 'dist',
    emptyOutDir: true,
  },
})
