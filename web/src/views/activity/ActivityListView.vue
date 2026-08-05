<script setup lang="ts">
/**
 * 活动列表 —— 平台层入口，也是两个玩法的分岔点。
 *
 * 后端无活动查询接口。这里不造假列表，而是展示「唯一已知的活动」（来自 seed SQL，
 * 通过已实现的 GET /sku/{skuId} 间接拿到 activityId），并说明缺的是什么。
 */
import { onMounted, ref } from 'vue'
import { querySku } from '@/api/benefit'
import { SEED_ACTIVITY_NAME, SEED_SKU_ID } from '@/stores/session'
import PendingNotice from '@/components/PendingNotice.vue'

const activityId = ref('')
const loading = ref(true)

onMounted(async () => {
  // activityId 从 SKU 反查 —— 这是目前唯一能从服务端拿到活动标识的途径
  const r = await querySku(SEED_SKU_ID)
  loading.value = false
  if (r.kind === 'ok') activityId.value = r.data.activityId
})
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        活动
        <span class="sub">数据来自 seed SQL，非活动查询接口</span>
      </div>
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else class="table-wrap" style="border: none; border-radius: 0">
        <table>
          <thead>
            <tr>
              <th>activityId</th>
              <th>名称</th>
              <th>玩法</th>
              <th>状态</th>
              <th>配置版本</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="activityId">
              <td class="mono">{{ activityId }}</td>
              <td>{{ SEED_ACTIVITY_NAME }}</td>
              <td><span class="play sell">权益售卖</span></td>
              <td><span class="online">ONLINE</span></td>
              <td>1</td>
              <td>
                <RouterLink class="link" to="/benefit/orders">看订单</RouterLink>
              </td>
            </tr>
            <tr>
              <td colspan="6" class="fission-row">
                <span class="play fission">裂变</span>
                <span class="muted">
                  无裂变活动 —— <code>mp-fission</code> 模块目前只有 pom.xml，后端未实现
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="card-body">
        <p class="note">
          这一行不是查出来的：<code>activityId</code> 是从
          <code>GET /api/benefit/sku/SKU_DEMO_001</code> 的响应里取的，名称与状态则来自
          <code>V1090__seed_activity.sql</code>。真正的活动列表需要下面的接口。
        </p>
      </div>
    </div>

    <div class="card" style="margin-top: 16px">
      <div class="card-head">缺少的能力</div>
      <div class="card-body stack">
        <PendingNotice
          capability="activityList"
          detail="这个页面本该是一张可按 playType / status 筛选的活动表，支持分页与上下线操作。"
        />
        <PendingNotice
          capability="activityDetail"
          detail="点进某个活动应能看到状态机流转历史与配置版本列表，含每个版本的奖励与价格快照。"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.play {
  font-size: 11.5px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 500;
}
.play.sell {
  background: var(--sell-soft);
  color: var(--sell);
}
.play.fission {
  background: var(--brand-soft);
  color: var(--brand);
}
.online {
  font-size: 11.5px;
  padding: 2px 8px;
  border-radius: 4px;
  background: var(--ok-soft);
  color: var(--ok);
  font-family: var(--mono);
}
.fission-row {
  display: flex;
  align-items: center;
  gap: 11px;
  font-size: 12.5px;
}
.link {
  color: var(--brand);
  font-size: 12.5px;
}
.stack {
  display: flex;
  flex-direction: column;
  gap: 13px;
}
</style>
