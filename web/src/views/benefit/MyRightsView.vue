<script setup lang="ts">
/**
 * 我的权益（用户视角）。
 *
 * 权益「使用状态」（FR-B07）是 V3 能力，无接口。但已发放的权益是可以从订单查到的
 * —— 用 GET /orders + GET /order/{bizNo} 组合出「我拿到过哪些权益」，只是拿不到
 * 「用没用、什么时候过期」。这里如实区分这两件事。
 */
import { onMounted, ref, watch } from 'vue'
import { queryOrder, queryOrders } from '@/api/benefit'
import { useSessionStore } from '@/stores/session'
import { ITEM_GRANT_STATUS_DISPLAY, formatTime } from '@/contracts/display'
import type { FulfillmentItem } from '@/contracts/dto'
import PendingNotice from '@/components/PendingNotice.vue'
import StatusPill from '@/components/StatusPill.vue'

const session = useSessionStore()
const loading = ref(false)
const rights = ref<{ item: FulfillmentItem; bizNo: string; at: string }[]>([])

const ITEM_META: Record<string, { name: string; icon: string }> = {
  ITEM_DEMO_A: { name: '会员月卡 · 30 天', icon: '🎟️' },
  ITEM_DEMO_B: { name: '满减优惠券 · 1 张', icon: '🎫' },
}
const meta = (id: string) => ITEM_META[id] ?? { name: id, icon: '🎁' }

async function load() {
  loading.value = true
  rights.value = []

  // 先取已支付成功的订单，再逐单取履约明细。没有「按用户查权益」接口，
  // 这是用现有两个接口能拼出的最接近的东西
  const list = await queryOrders({
    userId: session.userId,
    payStatus: 'PAY_SUCCESS',
    size: 20,
  })
  if (list.kind !== 'ok') {
    loading.value = false
    return
  }

  const details = await Promise.all(list.data.items.map((o) => queryOrder(o.bizNo)))
  const acc: typeof rights.value = []
  details.forEach((d, i) => {
    if (d.kind !== 'ok') return
    d.data.fulfillments.forEach((f) => {
      acc.push({ item: f, bizNo: d.data.bizNo, at: list.data.items[i].createTime })
    })
  })
  rights.value = acc
  loading.value = false
}

onMounted(load)
watch(() => session.userId, load)
</script>

<template>
  <div>
    <h2 class="section-title">
      我的权益
      <span v-if="rights.length" class="count">{{ rights.length }} 项</span>
      <span class="right">
        <button class="sm ghost" :disabled="loading" @click="load">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
      </span>
    </h2>

    <div class="card">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!rights.length" class="empty">
        <span class="icon">🎫</span>
        {{ session.userId }} 还没有已发放的权益
      </div>
      <div v-else class="grid">
        <div v-for="(r, i) in rights" :key="`${r.bizNo}-${r.item.benefitItemId}-${i}`" class="right-card">
          <div class="top">
            <span class="ico">{{ meta(r.item.benefitItemId).icon }}</span>
            <StatusPill
              :display="ITEM_GRANT_STATUS_DISPLAY[r.item.grantStatus]"
              :raw="r.item.grantStatus"
            />
          </div>
          <div class="nm">{{ meta(r.item.benefitItemId).name }}</div>
          <div class="meta">
            <div>供应方 <code>{{ r.item.providerType }}</code></div>
            <div>下游单号 <code>{{ r.item.providerOrderNo ?? '—' }}</code></div>
            <div>获得时间 {{ formatTime(r.at) }}</div>
          </div>
          <RouterLink class="link" :to="`/benefit/orders/${encodeURIComponent(r.bizNo)}`">
            查看来源订单 ›
          </RouterLink>
        </div>
      </div>
    </div>

    <div class="card" style="margin-top: 16px">
      <div class="card-head">使用状态</div>
      <div class="card-body">
        <PendingNotice
          capability="benefitUsage"
          detail="上面展示的是「发放结果」——权益有没有成功发出去。用没用、何时过期、剩余次数属于「使用状态」，需要单独的接口。"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(238px, 1fr));
  gap: 14px;
  padding: 17px;
}
.right-card {
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  padding: 14px;
  background: var(--surface-2);
}
.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.ico {
  font-size: 22px;
}
.nm {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 9px;
}
.meta {
  font-size: 11.5px;
  color: var(--text-3);
  line-height: 1.8;
}
.meta code {
  font-size: 11px;
  color: var(--text-2);
}
.link {
  display: inline-block;
  margin-top: 11px;
  font-size: 12px;
  color: var(--brand);
}
</style>
