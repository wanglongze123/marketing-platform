/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

/**
 * 扩展路由 meta 的形状，侧边栏与页头据此渲染。
 *
 * 必须 `import 'vue-router'` 后再 declare —— 缺了这行 import，declare module 会被当成
 * 「声明一个新模块」而非「扩充已有模块」，导致 vue-router 的所有真实导出
 * （useRoute / createRouter / RouteRecordRaw）全部报「无此导出」。
 */
import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    group?: string
    icon?: string
    hidden?: boolean
    /** PENDING 清单里的 key，标注该页依赖的未实现能力 */
    pending?: string
  }
}
