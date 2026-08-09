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
    /**
     * 直接产出到 mp-gateway 的静态目录，由 gateway 托管（V1~V3 单进程阶段）。
     *
     * 原先产物落在 web/dist 而没有任何一步把它同步过去，于是 8080 上跑的一直是
     * V1 时代手写的那份 static/index.html —— 它不传 consultToken（V2 起必填，4003）、
     * 支付通知不带签名（V2 PR-6b 起验签，4731），后端契约往前走了两个版本，
     * 演示页却停在原地，且**没有任何检查会发现这件事**：两份实现各自都「能跑」，
     * 只有真正点开 8080 下单才会看到 4003。
     *
     * 输出到源码树而非构建产物目录，是这一阶段的有意取舍：gateway 是个 fat jar，
     * 静态资源要在打包前就位。V4 拆服务、前端独立部署时，这里改回 dist。
     */
    outDir: '../mp-gateway/src/main/resources/static',
    // 不清空：console.html 是手写的调试控制台，与 Vue 工程并存，清空会连它一起删
    emptyOutDir: false,
  },
})
