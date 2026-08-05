<script setup lang="ts">
/**
 * 资格决策试算。后端无接口，表单可填但提交禁用。
 *
 * 保留输入区是为了固定入参形状（《PRD》FR-C02）：用户标识、场景、活动、客户端上下文。
 */
import { ref } from 'vue'
import PendingNotice from '@/components/PendingNotice.vue'
import { useSessionStore } from '@/stores/session'
import { SEED_ACTIVITY_ID } from '@/stores/session'

const session = useSessionStore()
const form = ref({
  userId: session.userId,
  scene: 'BENEFIT_SELL_DEMO',
  activityId: SEED_ACTIVITY_ID,
  cityCode: '',
  channel: '',
})

/** 决策维度，《PRD》FR-C02。返回标准原因码，只读无副作用 */
const DIMENSIONS = [
  { name: '人群规则', detail: 'crowdRule 命中判定' },
  { name: '城市范围', detail: 'cityScope，为空表示不限' },
  { name: '渠道范围', detail: 'channelScope，为空表示不限' },
  { name: '风控规则', detail: '频控、黑名单、风险等级' },
]
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        试算入参
        <span class="sub">《PRD》FR-C02</span>
      </div>
      <div class="card-body">
        <div class="grid">
          <div>
            <label>userId</label>
            <input v-model="form.userId" />
          </div>
          <div>
            <label>scene</label>
            <input v-model="form.scene" />
          </div>
          <div>
            <label>activityId（可选）</label>
            <input v-model="form.activityId" />
          </div>
          <div>
            <label>cityCode</label>
            <input v-model="form.cityCode" placeholder="客户端传值仅作提示" />
          </div>
          <div>
            <label>channel</label>
            <input v-model="form.channel" placeholder="客户端传值仅作提示" />
          </div>
        </div>
        <div class="submit">
          <button class="primary" disabled title="后端无资格决策接口">试算</button>
          <span class="muted">按钮禁用：接口未实现</span>
        </div>
        <p class="note" style="margin-top: 13px">
          城市、渠道、身份、持有状态一律<b>以服务端可信上下文为准</b>，客户端传值仅作提示
          （《PRD》§2.2）。所以这个表单里的 cityCode / channel 不会决定结果。
        </p>
      </div>
    </div>

    <div class="card" style="margin-top: 16px">
      <div class="card-head">判定维度</div>
      <div class="card-body">
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>维度</th>
                <th>说明</th>
                <th>结果</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="d in DIMENSIONS" :key="d.name">
                <td>{{ d.name }}</td>
                <td class="muted">{{ d.detail }}</td>
                <td class="muted">—</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="note" style="margin-top: 13px">
          资格决策必须区分 <code>1xxx</code>（不符合条件，业务拒绝）与 <code>5xxx</code>
          （系统故障，结果未知）—— 两者的前端展示与重试策略完全不同。这一层判断已在
          请求层实现（三态 <code>ApiResult</code>），接口就绪后无需改调用点。
        </p>
      </div>
    </div>

    <div class="card" style="margin-top: 16px">
      <div class="card-body">
        <PendingNotice capability="qualification" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
@media (max-width: 760px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
.submit {
  display: flex;
  align-items: center;
  gap: 11px;
  margin-top: 16px;
  font-size: 12.5px;
}
</style>
