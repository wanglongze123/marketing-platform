<script setup lang="ts">
/**
 * 我的订单（用户视角）。接 GET /api/benefit/orders?userId=...
 *
 * 状态每次进页面都回后端查，不缓存 —— 三条子状态线各自独立推进，
 * 缓存出的组合态可能是服务端不存在的。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { queryOrders } from '@/api/benefit'
import { useSessionStore } from '@/stores/session'
import { deriveDisplayState, formatTime, toYuan } from '@/contracts/display'
import type { OrderListItem } from '@/contracts/dto'
import StatusPill from '@/components/StatusPill.vue'
import { PAY_STATUS_DISPLAY } from '@/contracts/display'

const session = useSessionStore()

const items = ref<OrderListItem[]>([])
const total = ref(0)
const page = ref(1)
const size = 10
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  const r = await queryOrders({ userId: session.userId, page: page.value, size })
  loading.value = false
  if (r.kind === 'ok') {
    items.value = r.data.items
    total.value = r.data.total
  } else {
    error.value = r.message
    items.value = []
    total.value = 0
  }
}

onMounted(load)
// 切换用户后重新加载，并回到第一页
watch(
  () => session.userId,
  () => {
    page.value = 1
    load()
  }
)
watch(page, load)

const pages = computed(() => Math.max(1, Math.ceil(total.value / size)))
const states = computed(() => items.value.map((o) => ({ o, s: deriveDisplayState(o) })))
</script>

<template>
  <div>
    <h2 class="section-title">
      我的订单
      <span v-if="total" class="count">共 {{ total }} 笔</span>
      <span class="right">
        <button class="sm ghost" :disabled="loading" @click="load">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
      </span>
    </h2>

    <div v-if="error" class="alert error" style="margin-bottom: 14px">
      <div>加载失败：{{ error }}</div>
    </div>

    <div class="card">
      <div v-if="loading && !items.length" class="empty">加载中…</div>

      <div v-else-if="!items.length" class="empty">
        <span class="icon">🧾</span>
        {{ session.userId }} 还没有订单，去
        <RouterLink to="/shop" class="link">权益商城</RouterLink>
        下一单
      </div>

      <template v-else>
        <RouterLink
          v-for="{ o, s } in states"
          :key="o.bizNo"
          class="order"
          :to="`/benefit/orders/${encodeURIComponent(o.bizNo)}`"
        >
          <span class="thumb">🎁</span>
          <div class="mid">
            <div class="no mono">{{ o.bizNo }}</div>
            <div class="when">{{ formatTime(o.createTime) }} · {{ o.skuId }}</div>
          </div>
          <StatusPill :display="PAY_STATUS_DISPLAY[o.payStatus]" :raw="o.payStatus" />
          <span class="summary" :class="s.tone">{{ s.summary }}</span>
          <span class="amt">¥{{ toYuan(o.orderAmount) }}</span>
          <span class="chev">›</span>
        </RouterLink>
      </template>
    </div>

    <div v-if="pages > 1" class="pager">
      <button class="sm" :disabled="page <= 1" @click="page--">上一页</button>
      <span class="muted">{{ page }} / {{ pages }}</span>
      <button class="sm" :disabled="page >= pages" @click="page++">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.order {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 14px 17px;
  border-bottom: 1px solid var(--line-soft);
}
.order:last-child {
  border-bottom: none;
}
.order:hover {
  background: var(--surface-2);
}
.thumb {
  font-size: 19px;
}
.mid {
  flex: 1;
  min-width: 0;
}
.no {
  font-size: 12.5px;
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.when {
  font-size: 11.5px;
  color: var(--text-3);
}
.summary {
  font-size: 12.5px;
}
.summary.ok {
  color: var(--ok);
}
.summary.error {
  color: var(--error);
}
.summary.wait {
  color: var(--wait);
}
.summary.unknown {
  color: var(--unknown);
}
.summary.idle {
  color: var(--text-3);
}
.amt {
  font-weight: 600;
  font-size: 14.5px;
}
.chev {
  color: var(--text-3);
}
.link {
  color: var(--brand);
  text-decoration: underline;
}
.pager {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: center;
  margin-top: 16px;
  font-size: 13px;
}
</style>
