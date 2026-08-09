<script setup lang="ts">
/**
 * 订单管理（运营 / 客服视角）。接 GET /api/benefit/orders 的全部筛选项。
 *
 * 与「我的订单」的区别：不锁 userId、可按三条状态线筛选、展示技术字段。
 * 客服排查从这里进，按 bizNo 进详情看操作记录。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { queryOrders } from '@/api/benefit'
import {
  GRANT_STATUS_DISPLAY,
  PAY_STATUS_DISPLAY,
  REFUND_STATUS_DISPLAY,
  formatTime,
  toYuan,
} from '@/contracts/display'
import { GRANT_STATUS, PAY_STATUS } from '@/contracts/enums'
import type { GrantStatus, PayStatus } from '@/contracts/enums'
import type { OrderListItem } from '@/contracts/dto'
import StatusPill from '@/components/StatusPill.vue'

const items = ref<OrderListItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const error = ref('')

const userId = ref('')
const activityId = ref('')
const payStatus = ref<PayStatus | ''>('')
const grantStatus = ref<GrantStatus | ''>('')

/**
 * 请求序号，用于丢弃过期响应。
 *
 * 本页有筛选表单与翻页两个触发源，连点时先发的不保证先回 —— 旧结果晚到会覆盖新的，
 * 表现是「筛选条件与列表内容对不上」。运营看到的是一份不属于当前条件的数据，
 * 而页面本身没有任何异常提示。
 */
let reqSeq = 0

async function load() {
  const seq = ++reqSeq
  loading.value = true
  error.value = ''
  const r = await queryOrders({
    userId: userId.value || undefined,
    activityId: activityId.value || undefined,
    payStatus: payStatus.value || undefined,
    grantStatus: grantStatus.value || undefined,
    page: page.value,
    size: size.value,
  })
  if (seq !== reqSeq) return
  loading.value = false
  if (r.kind === 'ok') {
    items.value = r.data.items
    total.value = r.data.total
    // 后端对 size 有上限收口，回显服务端实际用的值
    size.value = r.data.size
  } else {
    error.value = r.message
    items.value = []
    total.value = 0
  }
}

onMounted(load)
watch(page, load)

function search() {
  page.value = 1
  load()
}
function reset() {
  userId.value = ''
  activityId.value = ''
  payStatus.value = ''
  grantStatus.value = ''
  search()
}

const pages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
</script>

<template>
  <div>
    <div class="card" style="margin-bottom: 16px">
      <div class="card-head">
        筛选
        <span class="sub">GET /api/benefit/orders</span>
      </div>
      <div class="card-body">
        <div class="filters">
          <div>
            <label>userId</label>
            <input v-model="userId" placeholder="留空不限" @keyup.enter="search" />
          </div>
          <div>
            <label>activityId</label>
            <input v-model="activityId" placeholder="留空不限" @keyup.enter="search" />
          </div>
          <div>
            <label>payStatus</label>
            <select v-model="payStatus">
              <option value="">全部</option>
              <option v-for="s in PAY_STATUS" :key="s" :value="s">
                {{ PAY_STATUS_DISPLAY[s].label }}（{{ s }}）
              </option>
            </select>
          </div>
          <div>
            <label>grantStatus</label>
            <select v-model="grantStatus">
              <option value="">全部</option>
              <option v-for="s in GRANT_STATUS" :key="s" :value="s">
                {{ GRANT_STATUS_DISPLAY[s].label }}（{{ s }}）
              </option>
            </select>
          </div>
          <div class="btns">
            <button class="primary" :disabled="loading" @click="search">查询</button>
            <button :disabled="loading" @click="reset">重置</button>
          </div>
        </div>
        <p class="note" style="margin-top: 13px">
          <code>grantStatus</code> 的取值带 <code>GRANT_</code> 前缀（主单汇总态）。履约明细用的是
          无前缀的 <code>ItemGrantStatus</code>，两者是不同层级，传错后端会返回
          <code>4001</code> 而不是静默返回空列表。
        </p>
      </div>
    </div>

    <div v-if="error" class="alert error" style="margin-bottom: 14px">
      <div>{{ error }}</div>
    </div>

    <div class="card">
      <div class="card-head">
        订单
        <span class="sub">{{ total }} 笔</span>
      </div>
      <div v-if="loading && !items.length" class="empty">加载中…</div>
      <div v-else-if="!items.length" class="empty">
        <span class="icon">📦</span>没有符合条件的订单
      </div>
      <div v-else class="table-wrap" style="border: none; border-radius: 0">
        <table>
          <thead>
            <tr>
              <th>订单号</th>
              <th>SKU</th>
              <th>支付线</th>
              <th>发放线</th>
              <th>退款线</th>
              <th>应付 / 实付</th>
              <th>创建时间</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="o in items" :key="o.bizNo">
              <td class="mono nowrap">{{ o.bizNo }}</td>
              <td class="mono">{{ o.skuId }}</td>
              <td>
                <StatusPill :display="PAY_STATUS_DISPLAY[o.payStatus]" :raw="o.payStatus" />
              </td>
              <td>
                <StatusPill
                  :display="GRANT_STATUS_DISPLAY[o.grantStatus]"
                  :raw="o.grantStatus"
                />
              </td>
              <td>
                <StatusPill
                  :display="REFUND_STATUS_DISPLAY[o.refundStatus]"
                  :raw="o.refundStatus"
                />
              </td>
              <td class="mono nowrap">
                ¥{{ toYuan(o.orderAmount) }} / ¥{{ toYuan(o.payAmount) }}
              </td>
              <td class="nowrap">{{ formatTime(o.createTime) }}</td>
              <td>
                <RouterLink
                  class="detail"
                  :to="`/benefit/orders/${encodeURIComponent(o.bizNo)}`"
                >
                  详情
                </RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="pages > 1" class="pager">
      <button class="sm" :disabled="page <= 1" @click="page--">上一页</button>
      <span class="muted">{{ page }} / {{ pages }}（每页 {{ size }}）</span>
      <button class="sm" :disabled="page >= pages" @click="page++">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.filters {
  display: grid;
  grid-template-columns: repeat(4, 1fr) auto;
  gap: 11px;
  align-items: end;
}
@media (max-width: 900px) {
  .filters {
    grid-template-columns: 1fr 1fr;
  }
}
.btns {
  display: flex;
  gap: 8px;
}
.nowrap {
  white-space: nowrap;
}
.detail {
  color: var(--brand);
  font-size: 12.5px;
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
