/**
 * 路由结构对齐《PRD》§3.1 的功能结构：平台公共能力 → 两个玩法 → 各自页面。
 *
 * meta.pending 标注该页依赖的未实现能力（PENDING 的 key）。侧边栏据此显示徽章，
 * 页面内用 <PendingNotice> 说明。有真实接口的页面不带这个标记。
 */
import { createRouter, createWebHashHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/shop' },

  // ---- 终端用户视角：权益售卖 ----
  {
    path: '/shop',
    name: 'shop',
    component: () => import('@/views/benefit/ShopView.vue'),
    meta: { title: '权益商城', group: 'user', icon: '🛍️' },
  },
  {
    path: '/my-orders',
    name: 'myOrders',
    component: () => import('@/views/benefit/MyOrdersView.vue'),
    meta: { title: '我的订单', group: 'user', icon: '🧾' },
  },
  {
    path: '/my-rights',
    name: 'myRights',
    component: () => import('@/views/benefit/MyRightsView.vue'),
    meta: { title: '我的权益', group: 'user', icon: '🎫', pending: 'benefitUsage' },
  },

  // ---- 运营 / 客服视角：活动平台（玩法无关） ----
  {
    path: '/activities',
    name: 'activities',
    component: () => import('@/views/activity/ActivityListView.vue'),
    meta: { title: '活动列表', group: 'platform', icon: '📋', pending: 'activityList' },
  },
  {
    path: '/activities/new',
    name: 'activityCreate',
    component: () => import('@/views/activity/ActivityCreateView.vue'),
    meta: { title: '创建活动', group: 'platform', icon: '➕', pending: 'activityCreate' },
  },
  {
    path: '/qualification',
    name: 'qualification',
    component: () => import('@/views/activity/QualificationView.vue'),
    meta: { title: '资格决策试算', group: 'platform', icon: '🔍', pending: 'qualification' },
  },

  // ---- 权益售卖：运营 / 客服 ----
  {
    path: '/benefit/orders',
    name: 'benefitOrders',
    component: () => import('@/views/benefit/OrderAdminView.vue'),
    meta: { title: '订单管理', group: 'benefit', icon: '📦' },
  },
  {
    path: '/benefit/orders/:bizNo',
    name: 'benefitOrderDetail',
    component: () => import('@/views/benefit/OrderDetailView.vue'),
    meta: { title: '订单详情', group: 'benefit', hidden: true },
  },
  {
    path: '/benefit/skus',
    name: 'benefitSkus',
    component: () => import('@/views/benefit/SkuView.vue'),
    meta: { title: '商品配置', group: 'benefit', icon: '🏷️' },
  },

  // ---- 裂变玩法 ----
  {
    path: '/fission/rounds',
    name: 'fissionRounds',
    component: () => import('@/views/fission/FissionView.vue'),
    props: { section: 'rounds' },
    meta: { title: '轮次管理', group: 'fission', icon: '🔄', pending: 'fission' },
  },
  {
    path: '/fission/relations',
    name: 'fissionRelations',
    component: () => import('@/views/fission/FissionView.vue'),
    props: { section: 'relations' },
    meta: { title: '关系查询', group: 'fission', icon: '🔗', pending: 'fission' },
  },
  {
    path: '/fission/rewards',
    name: 'fissionRewards',
    component: () => import('@/views/fission/FissionView.vue'),
    props: { section: 'rewards' },
    meta: { title: '奖励记录', group: 'fission', icon: '🎁', pending: 'fission' },
  },

  // ---- 运维 ----
  {
    path: '/ops/tasks',
    name: 'opsTasks',
    component: () => import('@/views/ops/TaskBoardView.vue'),
    meta: { title: '任务看板', group: 'ops', icon: '⏱️', pending: 'taskBoard' },
  },
  {
    path: '/ops/reconcile',
    name: 'opsReconcile',
    component: () => import('@/views/ops/ReconcileView.vue'),
    meta: { title: '对账', group: 'ops', icon: '⚖️', pending: 'reconcile' },
  },

  // ---- 开发调试 ----
  {
    path: '/devtools',
    name: 'devtools',
    component: () => import('@/views/devtools/DevtoolsView.vue'),
    meta: { title: '调试台', group: 'dev', icon: '🧪' },
  },

  { path: '/:pathMatch(.*)*', redirect: '/shop' },
]

/**
 * 用 hash 模式：产物由 gateway 以静态资源托管，没有为前端路由配 fallback 到
 * index.html 的服务端规则。history 模式下刷新子路由会 404。
 */
export const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

export const GROUP_LABEL: Record<string, string> = {
  user: '权益售卖 · 用户端',
  platform: '活动平台',
  benefit: '权益售卖 · 运营',
  fission: '裂变玩法',
  ops: '运维',
  dev: '开发',
}

export const GROUP_ORDER = ['user', 'platform', 'benefit', 'fission', 'ops', 'dev']
