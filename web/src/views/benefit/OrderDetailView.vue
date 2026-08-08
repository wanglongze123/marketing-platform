<script setup lang="ts">
/**
 * 订单详情。接三个真实端点：
 *   GET /order/{bizNo}            三子状态 + 履约明细
 *   GET /order/{bizNo}/op-records 操作记录时间线
 *   POST /pay-callback            待支付时可在此模拟支付
 *
 * 退款入口是 V3 能力，用 PendingNotice 占位。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  closeOrder,
  newNotifySeq,
  queryConvergence,
  queryOpRecords,
  queryOrder,
  simulatePayNotify,
} from '@/api/benefit'
import {
  OP_STATUS_DISPLAY,
  OP_TYPE_LABEL,
  TASK_STATUS_DISPLAY,
  TASK_TYPE_LABEL,
  deriveDisplayState,
  formatTime,
  toYuan,
} from '@/contracts/display'
import { ERROR_CODE_TEXT } from '@/contracts/errorCode'
import type { ConvergenceResp, OpRecordItem, QueryOrderResp } from '@/contracts/dto'
import { TASK_STATUS } from '@/contracts/enums'
import type { TaskStatus } from '@/contracts/enums'
import type { OpStatus } from '@/contracts/enums'
import { OP_STATUS } from '@/contracts/enums'
import FulfillmentTable from '@/components/FulfillmentTable.vue'
import PendingNotice from '@/components/PendingNotice.vue'
import StatusPill from '@/components/StatusPill.vue'
import SubStatusLines from '@/components/SubStatusLines.vue'

const route = useRoute()
const bizNo = computed(() => String(route.params.bizNo ?? ''))

const order = ref<QueryOrderResp | null>(null)
const records = ref<OpRecordItem[]>([])
const loading = ref(true)
const error = ref('')
const notice = ref<{ tone: 'error' | 'unknown' | 'ok'; text: string } | null>(null)
const paying = ref(false)
const closing = ref(false)
const convergence = ref<ConvergenceResp | null>(null)

async function load() {
  loading.value = true
  error.value = ''
  const [o, r, c] = await Promise.all([
    queryOrder(bizNo.value),
    queryOpRecords(bizNo.value),
    queryConvergence(bizNo.value),
  ])
  loading.value = false

  if (o.kind === 'ok') order.value = o.data
  else {
    order.value = null
    error.value = o.message
  }
  records.value = r.kind === 'ok' ? r.data : []
  convergence.value = c.kind === 'ok' ? c.data : null
}

onMounted(load)
watch(bizNo, load)

const state = computed(() => (order.value ? deriveDisplayState(order.value) : null))

const steps = [
  { key: 'created', label: '建单' },
  { key: 'paid', label: '支付' },
  { key: 'granting', label: '履约' },
  { key: 'done', label: '完成' },
] as const

/** 当前进行到的步骤：第一个未完成的 */
const currentStep = computed(() => {
  if (!state.value) return -1
  return steps.findIndex((s) => !state.value!.done[s.key])
})

/** 本地执行态是枚举内取值时用映射表，否则原样显示（排查视图不能吞掉脏数据） */
const isKnownOpStatus = (s: string): s is OpStatus =>
  (OP_STATUS as readonly string[]).includes(s)

const isKnownTaskStatus = (s: string): s is TaskStatus =>
  (TASK_STATUS as readonly string[]).includes(s)

async function simulatePay(payStatus: 'SUCCESS' | 'FAILED') {
  if (!order.value || paying.value) return
  paying.value = true
  notice.value = null

  // 先取签名再回调 —— V2 起 /pay-callback 验签，无 sign 返回 4731
  const cb = await simulatePayNotify({
    outTradeNo: order.value.bizNo,
    tradeNo: order.value.tradeNo ?? `PAY_${order.value.bizNo}`,
    notifySeq: newNotifySeq(),
    payStatus,
    payAmount: order.value.orderAmount,
    currency: 'CNY',
    merchantId: 'M001',
  })

  if (cb.kind === 'rejected') {
    notice.value = { tone: 'error', text: ERROR_CODE_TEXT[cb.code] ?? cb.message }
  } else if (cb.kind === 'unknown') {
    // 不说失败：结果未知时下游可能已处理
    notice.value = { tone: 'unknown', text: `${cb.message}。请稍后刷新确认最终状态。` }
  } else {
    notice.value = { tone: 'ok', text: '支付通知已受理' }
  }

  paying.value = false
  await load()
}

/** 关闭订单。已支付返回 1741；结果未定进 CLOSING，须提示「处理中」而非「已关闭」 */
async function doClose() {
  if (!order.value || closing.value) return
  closing.value = true
  notice.value = null

  const r = await closeOrder(order.value.bizNo)
  if (r.kind === 'rejected') {
    notice.value = { tone: 'error', text: ERROR_CODE_TEXT[r.code] ?? r.message }
  } else if (r.kind === 'unknown') {
    notice.value = {
      tone: 'unknown',
      text: `${r.message}。关单结果未确认，订单可能进入「关单中」，请刷新查看。`,
    }
  } else {
    notice.value = { tone: 'ok', text: `关单已受理（${r.data.status}）` }
  }

  closing.value = false
  await load()
}
</script>

<template>
  <div>
    <div class="back">
      <RouterLink to="/benefit/orders">← 订单管理</RouterLink>
      <RouterLink to="/my-orders">我的订单</RouterLink>
      <button class="sm ghost" :disabled="loading" @click="load">
        {{ loading ? '刷新中…' : '刷新' }}
      </button>
    </div>

    <div v-if="loading && !order" class="card"><div class="empty">加载中…</div></div>

    <div v-else-if="error" class="card">
      <div class="card-body">
        <div class="alert error"><div>{{ error }}</div></div>
      </div>
    </div>

    <template v-else-if="order">
      <div v-if="notice" class="alert" :class="notice.tone" style="margin-bottom: 15px">
        <div>{{ notice.text }}</div>
      </div>

      <!-- 概览 -->
      <div class="card">
        <div class="card-head">
          订单概览
          <span class="sub">{{ order.bizNo }}</span>
          <span class="right">
            <span v-if="state" class="summary" :class="state.tone">{{ state.summary }}</span>
          </span>
        </div>
        <div class="card-body">
          <div class="flow">
            <template v-for="(s, i) in steps" :key="s.key">
              <span
                class="step"
                :class="{
                  done: state?.done[s.key],
                  cur: i === currentStep,
                }"
              >
                {{ s.label }}
              </span>
              <span v-if="i < steps.length - 1" class="arrow">→</span>
            </template>
          </div>

          <SubStatusLines
            :pay-status="order.payStatus"
            :grant-status="order.grantStatus"
            :refund-status="order.refundStatus"
            show-field-names
          />

          <p v-if="state?.pending" class="note pending-note">
            当前处于「结果未确定」状态。这不等于失败 —— 下游可能已经执行成功，须以原幂等号
            查单收敛后才能判定。V2 引入故障注入与任务调度后，此处会自动轮询收敛。
          </p>

          <dl class="kv" style="margin-top: 15px">
            <dt>订单号</dt>
            <dd>{{ order.bizNo }}</dd>
            <dt>支付单号</dt>
            <dd>{{ order.tradeNo ?? '—（建单后回填）' }}</dd>
            <dt>userId</dt>
            <dd>{{ order.userId }}</dd>
            <dt>活动 / SKU</dt>
            <dd>{{ order.activityId }} · {{ order.skuId }}</dd>
            <dt>应付 / 实付</dt>
            <dd>¥{{ toYuan(order.orderAmount) }} / ¥{{ toYuan(order.payAmount) }}</dd>
            <dt>配置版本</dt>
            <dd>{{ order.configVersion }}（下单时冻结的快照，履约与退款一律读它）</dd>
          </dl>

          <div v-if="state?.payable" class="pay-actions">
            <button class="primary" :disabled="paying" @click="simulatePay('SUCCESS')">
              模拟支付成功
            </button>
            <button :disabled="paying" @click="simulatePay('FAILED')">模拟支付失败</button>
            <button
              v-if="order.payStatus === 'WAIT_PAY'"
              :disabled="closing"
              @click="doClose"
            >
              关闭订单
            </button>
            <span class="muted" style="font-size: 12px">
              先取签名再发 POST /pay-callback —— V2 起验签，无 sign 返回 4731
            </span>
          </div>
        </div>
      </div>

      <!-- 履约明细 -->
      <div class="card" style="margin-top: 16px">
        <div class="card-head">
          履约明细
          <span class="sub">按 providerType 分组，每组一个 grantOpNo</span>
        </div>
        <div class="card-body">
          <FulfillmentTable :items="order.fulfillments" technical />
          <p v-if="order.fulfillments.length" class="note" style="margin-top: 12px">
            明细状态用 <code>ItemGrantStatus</code>（无前缀），主单用
            <code>GrantStatus</code>（带 <code>GRANT_</code> 前缀）。两张表的列同名但是不同层级
            —— 一个供应方一次调用、一个幂等键，组合权益跨多供应方时天然拆成 N 次调用。
          </p>
        </div>
      </div>

      <!-- 操作记录 -->
      <div class="card" style="margin-top: 16px">
        <div class="card-head">
          操作记录
          <span class="sub">GET /order/{bizNo}/op-records</span>
        </div>
        <div class="card-body">
          <div v-if="!records.length" class="note">
            无操作记录。这本身是异常信号 —— 建单必落 CREATE_TRADE 记录。
          </div>
          <ol v-else class="timeline">
            <li v-for="r in records" :key="r.opNo">
              <div class="dot" />
              <div class="content">
                <div class="line1">
                  <b>{{ OP_TYPE_LABEL[r.opType] ?? r.opType }}</b>
                  <StatusPill
                    v-if="isKnownOpStatus(r.status)"
                    :display="OP_STATUS_DISPLAY[r.status]"
                    :raw="r.status"
                  />
                  <span v-else class="raw">{{ r.status }}</span>
                  <span class="time muted">{{ formatTime(r.createTime) }}</span>
                </div>
                <div class="line2">
                  <span class="mono">{{ r.opNo }}</span>
                </div>
                <div class="fields">
                  <span v-if="r.opSeq">
                    <i>opSeq</i>
                    <code>{{ r.opSeq }}</code>
                  </span>
                  <span>
                    <i>本地态 status</i>
                    <code>{{ r.status }}</code>
                  </span>
                  <span>
                    <i>下游 downstreamResult</i>
                    <code>{{ r.downstreamResult ?? 'null' }}</code>
                  </span>
                  <span v-if="r.retryCount">
                    <i>重试</i>
                    <code>{{ r.retryCount }}</code>
                  </span>
                  <span v-if="r.errorCode">
                    <i>errorCode</i>
                    <code>{{ r.errorCode }}</code>
                  </span>
                  <span v-if="r.finishTime">
                    <i>完成</i>
                    <code>{{ formatTime(r.finishTime) }}</code>
                  </span>
                </div>
              </div>
            </li>
          </ol>
          <p class="note" style="margin-top: 13px">
            <code>status</code>（本地执行态 <code>OpStatus</code>，失败值 <code>FAILED</code>）与
            <code>downstreamResult</code>（下游四分类 <code>RetStatus</code>，失败值
            <code>FAIL</code>）分列展示，刻意不合并 —— 合并会掩盖「本地记为失败、下游实际成功」
            这类差异，而那正是排查时要找的东西。
          </p>
        </div>
      </div>

      <!-- 可靠任务 -->
      <div class="card" style="margin-top: 16px">
        <div class="card-head">
          可靠任务
          <span class="sub">GET /convergence/{bizNo}</span>
        </div>
        <div class="card-body">
          <div v-if="!convergence || !convergence.tasks.length" class="note">
            暂无任务。V2 起履约由 <code>GRANT</code> 任务驱动 —— 支付成功后才会产生。
          </div>
          <div v-else class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>任务</th>
                  <th>状态</th>
                  <th>重试</th>
                  <th>下次执行</th>
                  <th>租约持有者</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="t in convergence.tasks" :key="t.opNo + t.taskType">
                  <td>
                    {{ TASK_TYPE_LABEL[t.taskType] ?? t.taskType }}
                    <div class="sub-cell mono">{{ t.opNo }}</div>
                  </td>
                  <td>
                    <StatusPill
                      v-if="isKnownTaskStatus(t.status)"
                      :display="TASK_STATUS_DISPLAY[t.status]"
                      :raw="t.status"
                    />
                    <span v-else class="raw">{{ t.status }}</span>
                  </td>
                  <td class="mono">{{ t.retryCount ?? 0 }}</td>
                  <td class="mono">{{ t.nextTime ?? '—' }}</td>
                  <td class="mono">{{ t.leaseOwner ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="note" style="margin-top: 13px">
            履约<b>异步</b>：支付回调返回时发放线还是 <code>NOT_START</code>，由调度器抢占任务后推进。
            重试按退避序列（短 1s→5s→30s，长 30s→2m→10m），耗尽仍未成功则转
            <code>DEAD</code> 等人工介入 —— 死信不等于业务失败，下游可能已成功。
          </p>
        </div>
      </div>

      <!-- 退款 -->
      <div class="card" style="margin-top: 16px">
        <div class="card-head">逆向链路</div>
        <div class="card-body">
          <PendingNotice
            capability="refund"
            detail="退款按钮将出现在这里：先判定准入，再回收权益，最后执行退款，三步各自留痕可审计。"
          />
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.back {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 14px;
  font-size: 13px;
}
.back a {
  color: var(--brand);
}
.back button {
  margin-left: auto;
}
.summary {
  font-size: 13px;
  font-weight: 500;
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

.flow {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.step {
  padding: 4px 12px;
  border-radius: 5px;
  border: 1px solid var(--line);
  color: var(--text-3);
  font-size: 12.5px;
  background: var(--surface-2);
}
.step.done {
  border-color: rgba(0, 168, 112, 0.4);
  color: var(--ok);
  background: var(--ok-soft);
}
.step.cur {
  border-color: var(--brand);
  color: var(--brand);
  background: var(--brand-soft);
}
.arrow {
  color: var(--text-3);
  font-size: 12px;
}
.pending-note {
  margin-top: 13px;
  border-left-color: var(--unknown);
}
.pay-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
  padding-top: 15px;
  border-top: 1px solid var(--line-soft);
  flex-wrap: wrap;
}

.timeline {
  list-style: none;
  margin: 0;
  padding: 0;
}
.timeline li {
  display: flex;
  gap: 13px;
  padding-bottom: 15px;
  position: relative;
}
.timeline li:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 15px;
  bottom: 0;
  width: 1px;
  background: var(--line);
}
.timeline .dot {
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: var(--surface);
  border: 2px solid var(--brand);
  flex: none;
  margin-top: 4px;
  z-index: 1;
}
.timeline .content {
  min-width: 0;
  flex: 1;
}
.line1 {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}
.line1 .time {
  font-size: 11.5px;
  margin-left: auto;
}
.line2 {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 2px;
  word-break: break-all;
}
.sub-cell {
  font-size: 10.5px;
  color: var(--text-3);
  margin-top: 2px;
  word-break: break-all;
}
.raw {
  font-family: var(--mono);
  font-size: 11.5px;
  background: var(--idle-soft);
  padding: 1px 6px;
  border-radius: 3px;
}
.fields {
  display: flex;
  flex-wrap: wrap;
  gap: 7px 15px;
  margin-top: 7px;
}
.fields span {
  font-size: 11.5px;
  display: flex;
  align-items: center;
  gap: 5px;
}
.fields i {
  color: var(--text-3);
  font-style: normal;
}
.fields code {
  background: var(--surface-2);
  border: 1px solid var(--line-soft);
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
}
</style>
