<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { GROUP_LABEL, GROUP_ORDER, router } from '@/router'
import { useSessionStore } from '@/stores/session'
import { querySku } from '@/api/benefit'
import { SEED_SKU_ID } from '@/stores/session'
import { PENDING, PHASE_LABEL } from '@/api/notImplemented'

const route = useRoute()
const session = useSessionStore()

/** 侧边栏按 group 分组，隐藏 meta.hidden 的路由 */
const groups = computed(() =>
  GROUP_ORDER.map((g) => ({
    key: g,
    label: GROUP_LABEL[g],
    items: router
      .getRoutes()
      .filter((r) => r.meta?.group === g && !r.meta?.hidden && r.name)
      .map((r) => ({
        name: String(r.name),
        path: r.path,
        title: String(r.meta?.title ?? r.name),
        icon: String(r.meta?.icon ?? '·'),
        pending: r.meta?.pending as string | undefined,
      })),
  })).filter((g) => g.items.length)
)

/** 健康探测：用真实业务端点，拿到响应壳即说明 Web 层、Dubbo、DB 全通 */
const health = ref<'checking' | 'up' | 'down'>('checking')
onMounted(async () => {
  const r = await querySku(SEED_SKU_ID)
  // rejected 也算连通（服务在跑，只是业务拒绝）；unknown 才是连不上
  health.value = r.kind === 'unknown' ? 'down' : 'up'
})

const collapsed = ref(false)
const pendingBadge = (key?: string) =>
  key && PENDING[key] ? PHASE_LABEL[PENDING[key].phase] : null
</script>

<template>
  <div class="shell" :class="{ collapsed }">
    <aside class="side">
      <div class="brand">
        <span class="mark">营</span>
        <span v-show="!collapsed" class="name">营销活动平台</span>
      </div>

      <nav>
        <div v-for="g in groups" :key="g.key" class="group">
          <div v-show="!collapsed" class="group-label">{{ g.label }}</div>
          <RouterLink
            v-for="it in g.items"
            :key="it.name"
            :to="it.path"
            class="item"
            :class="{ active: route.name === it.name }"
            :title="collapsed ? it.title : undefined"
          >
            <span class="icon">{{ it.icon }}</span>
            <span v-show="!collapsed" class="label">{{ it.title }}</span>
            <span v-if="!collapsed && it.pending" class="tag">
              {{ pendingBadge(it.pending) }}
            </span>
          </RouterLink>
        </div>
      </nav>

      <button class="collapse" @click="collapsed = !collapsed">
        {{ collapsed ? '»' : '« 收起' }}
      </button>
    </aside>

    <div class="main">
      <header>
        <h1>{{ route.meta?.title ?? '' }}</h1>
        <span v-if="route.meta?.pending" class="head-tag">
          {{ pendingBadge(route.meta.pending as string) }}
        </span>

        <div class="right">
          <span class="health" :class="health">
            <i class="dot" />
            {{ health === 'up' ? '服务正常' : health === 'down' ? '无法连接后端' : '检测中…' }}
          </span>
          <div class="user" title="后端无鉴权体系，用此输入框模拟身份">
            <span class="avatar">{{ session.userId.slice(0, 1).toUpperCase() }}</span>
            <input
              :value="session.userId"
              spellcheck="false"
              @input="session.setUserId(($event.target as HTMLInputElement).value)"
            />
          </div>
        </div>
      </header>

      <main>
        <div v-if="health === 'down'" class="alert error offline">
          <div>
            无法连接后端。请确认 gateway 已启动：<code>mvn -pl mp-gateway spring-boot:run</code>。
            若后端不在 8080，用 <code>MP_API_PORT=8088 npm run dev</code> 指定端口。
          </div>
        </div>
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: 232px 1fr;
  min-height: 100vh;
}
.shell.collapsed {
  grid-template-columns: 60px 1fr;
}

.side {
  background: #131722;
  color: #d6dae3;
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
  height: 100vh;
  overflow-y: auto;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 15px 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
}
.brand .mark {
  width: 27px;
  height: 27px;
  border-radius: 7px;
  background: linear-gradient(135deg, var(--brand), #5b86ff);
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 700;
  color: #fff;
  flex: none;
}
.brand .name {
  font-weight: 600;
  font-size: 14.5px;
  color: #fff;
  white-space: nowrap;
}

nav {
  flex: 1;
  padding: 9px 0;
}
.group {
  margin-bottom: 13px;
}
.group-label {
  font-size: 11px;
  color: #6b7385;
  padding: 5px 16px;
  letter-spacing: 0.04em;
}
.item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 16px;
  font-size: 13.5px;
  color: #b9c0cd;
  border-left: 2px solid transparent;
}
.item:hover {
  background: rgba(255, 255, 255, 0.045);
  color: #fff;
}
.item.active {
  background: rgba(43, 92, 255, 0.16);
  border-left-color: var(--brand);
  color: #fff;
}
.item .icon {
  width: 17px;
  text-align: center;
  flex: none;
}
.item .label {
  flex: 1;
  white-space: nowrap;
}
.item .tag {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.09);
  color: #8b94a3;
  white-space: nowrap;
}
.collapse {
  margin: 9px;
  background: transparent;
  border-color: rgba(255, 255, 255, 0.1);
  color: #8b94a3;
  font-size: 12px;
}
.collapse:hover {
  color: #fff;
  border-color: rgba(255, 255, 255, 0.25);
}

.main {
  min-width: 0;
  display: flex;
  flex-direction: column;
}
header {
  background: var(--surface);
  border-bottom: 1px solid var(--line);
  padding: 0 22px;
  height: 55px;
  display: flex;
  align-items: center;
  gap: 11px;
  position: sticky;
  top: 0;
  z-index: 20;
}
header h1 {
  font-size: 15.5px;
  margin: 0;
  font-weight: 600;
}
.head-tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  background: var(--idle-soft);
  color: var(--idle);
}
header .right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 13px;
}
.health {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--text-3);
}
.health .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--idle);
}
.health.up .dot {
  background: var(--ok);
  box-shadow: 0 0 0 3px var(--ok-soft);
}
.health.down .dot {
  background: var(--error);
  box-shadow: 0 0 0 3px var(--error-soft);
}
.user {
  display: flex;
  align-items: center;
  gap: 7px;
  border: 1px solid var(--line);
  border-radius: 20px;
  padding: 4px 11px 4px 5px;
}
.user .avatar {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--brand-soft);
  color: var(--brand);
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 600;
}
.user input {
  border: none;
  width: 74px;
  padding: 0;
  font-family: var(--mono);
  font-size: 12.5px;
  background: transparent;
}
main {
  padding: 20px 22px 50px;
  max-width: 1180px;
  width: 100%;
}
.offline {
  margin-bottom: 16px;
}
.offline code {
  background: rgba(0, 0, 0, 0.06);
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 12px;
}
</style>
