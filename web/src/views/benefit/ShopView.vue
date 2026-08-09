<script setup lang="ts">
/**
 * 权益商城 · 商品页 + 收银台。
 *
 * 全部接真实接口：商品信息取 GET /sku/{skuId}（不再硬编码 seed 值），
 * 下单 POST /trade，支付 POST /pay-callback，结果 GET /order/{bizNo}。
 *
 * ⚠️ 支付结果的三态处理见 payAndSettle()：unknown 分支不显示「失败」。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  MOCK_MERCHANT_ID,
  createTrade,
  newClientReqNo,
  newNotifySeq,
  preConsult,
  queryOrder,
  querySku,
  simulatePayNotify,
} from '@/api/benefit'
import { SEED_ACTIVITY_ID, SEED_SKU_ID, useSessionStore } from '@/stores/session'
import { remember } from '@/stores/orderTracker'
import { deriveDisplayState, toYuan } from '@/contracts/display'
import { ERROR_CODE_TEXT } from '@/contracts/errorCode'
import type { QueryOrderResp, QuerySkuResp } from '@/contracts/dto'
import FulfillmentTable from '@/components/FulfillmentTable.vue'

const router = useRouter()
const session = useSessionStore()

const sku = ref<QuerySkuResp | null>(null)
const loadError = ref('')
const loading = ref(true)

onMounted(async () => {
  const r = await querySku(SEED_SKU_ID)
  loading.value = false
  if (r.kind === 'ok') sku.value = r.data
  else loadError.value = r.message
})

const discount = computed(() => {
  if (!sku.value || !sku.value.listPrice) return null
  const d = (sku.value.salePrice / sku.value.listPrice) * 10
  return d >= 10 ? null : d.toFixed(1).replace(/\.0$/, '')
})

const onSale = computed(() => sku.value?.saleStatus === 'ON_SALE')

/** 购买份数上限，与后端 MAX_QUANTITY 一致。端上先挡一道，越界后端仍判 4001 */
const MAX_QUANTITY = 99

const quantity = ref(1)

/**
 * 页面预估的应付，仅用于下单前展示。
 *
 * 收银台的「应付金额」不用它，用后端返回的 orderAmount —— 单价可能在下单前被运营改过，
 * 以页面算出来的数收款就成了「显示一个价、收另一个价」。这与 dealPrice 取凭证值同源。
 */
const estimatedAmount = computed(() => (sku.value?.salePrice ?? 0) * quantity.value)

const stepQuantity = (delta: number) => {
  const next = quantity.value + delta
  if (next < 1 || next > MAX_QUANTITY) return
  quantity.value = next
}

/** 权益项 ID → 面向用户的名字与图标 */
const ITEM_META: Record<string, { name: string; icon: string }> = {
  ITEM_DEMO_A: { name: '会员月卡 · 30 天', icon: '🎟️' },
  ITEM_DEMO_B: { name: '满减优惠券 · 1 张', icon: '🎫' },
}
const itemMeta = (id: string) => ITEM_META[id] ?? { name: id, icon: '🎁' }

// ------------------------------------------------------------------
// 收银台
// ------------------------------------------------------------------

type Stage = 'closed' | 'confirm' | 'processing' | 'result'
const stage = ref<Stage>('closed')
const busy = ref(false)
const processingText = ref('支付结果处理中…')

/** 等待异步履约的上限与轮询间隔。超时不判失败，只是停止等待 */
const GRANT_WAIT_MS = 15_000
const GRANT_POLL_MS = 1_000

const bizNo = ref('')
const tradeNo = ref<string | null>(null)
const amount = ref(0)
/** 凭证里的成交价，应付以它为准 */
const dealPrice = ref(0)
const order = ref<QueryOrderResp | null>(null)
/** 非 ok 结果的提示。区分「确定失败」与「结果未知」 */
const notice = ref<{ tone: 'error' | 'unknown'; text: string } | null>(null)

async function startBuy() {
  if (!sku.value || busy.value) return
  busy.value = true
  notice.value = null

  // 第一步：预咨询。V2 起下单必须携带凭证，无凭证一律 4003。
  // 凭证里签了成交价与包版本，下单时服务端逐字段比对并重算比价
  const consult = await preConsult({
    userId: session.userId,
    activityId: sku.value.activityId || SEED_ACTIVITY_ID,
    skuId: sku.value.skuId,
  })
  if (consult.kind !== 'ok') {
    busy.value = false
    notice.value = {
      tone: consult.kind === 'unknown' ? 'unknown' : 'error',
      text: ERROR_CODE_TEXT[consult.code] ?? consult.message,
    }
    stage.value = 'result'
    order.value = null
    return
  }
  // 应付以凭证里的成交价为准，不用页面上展示的售卖价 ——
  // 两者不一致时下单会被比价拒绝（1711），而页面价可能已是旧数据
  dealPrice.value = consult.data.dealPrice

  // 第二步：下单
  const r = await createTrade({
    userId: session.userId,
    activityId: sku.value.activityId || SEED_ACTIVITY_ID,
    skuId: sku.value.skuId,
    // 同一次购买意图只生成一次，重试才会命中幂等
    clientReqNo: newClientReqNo(),
    quantity: quantity.value,
    consultToken: consult.data.consultToken,
  })
  busy.value = false

  if (r.kind === 'rejected') {
    notice.value = { tone: 'error', text: `${ERROR_CODE_TEXT[r.code] ?? r.message}` }
    stage.value = 'result'
    order.value = null
    return
  }
  if (r.kind === 'unknown') {
    // 下单结果未知：可能已建单。不能说失败，也不能让用户直接重下
    notice.value = {
      tone: 'unknown',
      text: `${r.message}。订单可能已创建，请到「我的订单」确认后再操作，避免重复下单。`,
    }
    stage.value = 'result'
    order.value = null
    return
  }

  bizNo.value = r.data.bizNo
  tradeNo.value = r.data.tradeNo
  amount.value = r.data.orderAmount
  remember(r.data.bizNo, session.userId)
  stage.value = 'confirm'
}

async function payAndSettle(payStatus: 'SUCCESS' | 'FAILED') {
  if (busy.value) return
  busy.value = true
  stage.value = 'processing'
  processingText.value =
    payStatus === 'SUCCESS' ? '支付结果处理中…' : '正在回传支付失败结果…'
  notice.value = null

  // 签名 + 回调。V2 起 /pay-callback 验签，无 sign 返回 4731
  const cb = await simulatePayNotify({
    outTradeNo: bizNo.value, // 按 bizNo 定位，不用 tradeNo
    tradeNo: tradeNo.value ?? `PAY_${bizNo.value}`,
    notifySeq: newNotifySeq(),
    payStatus,
    payAmount: amount.value,
    currency: 'CNY',
    merchantId: MOCK_MERCHANT_ID,
  })

  if (cb.kind === 'rejected') {
    notice.value = {
      tone: 'error',
      text: ERROR_CODE_TEXT[cb.code] ?? cb.message,
    }
  } else if (cb.kind === 'unknown') {
    // ⚠️ 这里不能说「支付失败」。结果未知时下游可能已处理，
    // 报失败会让用户重试并形成重复下单
    notice.value = {
      tone: 'unknown',
      text: `${cb.message}。已受理，正在确认最终结果，请稍后刷新订单状态。`,
    }
  }

  // 履约已转异步：回调返回时 grantStatus 仍是 NOT_START，由调度器驱动发放。
  // 立刻展示会让用户看到「支付成功但什么都没拿到」，故轮询等发放线走完
  const settled = await waitForGrant(bizNo.value)
  order.value = settled
  busy.value = false
  stage.value = 'result'
}

/**
 * 轮询直到发放线离开非终态，或超时。
 *
 * 超时不算失败 —— 返回最后一次查到的订单，由 deriveDisplayState 决定怎么说。
 * 「还在发放中」与「发放失败」是两回事，前者不能显示成后者。
 */
async function waitForGrant(no: string): Promise<QueryOrderResp | null> {
  const deadline = Date.now() + GRANT_WAIT_MS
  let last: QueryOrderResp | null = null

  for (;;) {
    const q = await queryOrder(no)
    if (q.kind === 'ok') {
      last = q.data
      const done =
        last.grantStatus !== 'NOT_START' && last.grantStatus !== 'GRANTING'
      // 支付没成功就不会有发放，无须再等
      if (done || last.payStatus !== 'PAY_SUCCESS') return last
    }
    if (Date.now() >= deadline) return last
    processingText.value = '支付成功，正在发放权益…'
    await sleep(GRANT_POLL_MS)
  }
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

const state = computed(() => (order.value ? deriveDisplayState(order.value) : null))

const resultIcon = computed(() => {
  if (notice.value?.tone === 'error') return { char: '✕', cls: 'error' }
  if (notice.value?.tone === 'unknown') return { char: '⋯', cls: 'unknown' }
  if (!state.value) return { char: '⋯', cls: 'unknown' }
  if (state.value.tone === 'ok') return { char: '✓', cls: 'ok' }
  if (state.value.tone === 'error') return { char: '✕', cls: 'error' }
  return { char: '⋯', cls: 'unknown' }
})

function close() {
  stage.value = 'closed'
  order.value = null
  notice.value = null
}

function gotoDetail() {
  const target = bizNo.value
  close()
  if (target) router.push(`/benefit/orders/${encodeURIComponent(target)}`)
}
</script>

<template>
  <div>
    <div v-if="loading" class="card"><div class="empty">加载商品…</div></div>

    <div v-else-if="loadError" class="card">
      <div class="card-body">
        <div class="alert error">
          <div>商品加载失败：{{ loadError }}</div>
        </div>
      </div>
    </div>

    <!-- 商品卡 -->
    <div v-else-if="sku" class="card product">
      <div class="visual">
        <span class="tag">限时活动</span>
        <div class="headline">{{ sku.skuName }}</div>
        <div class="sub">下单即时到账 · {{ sku.items.length }} 项权益分别发放</div>
      </div>

      <div class="info">
        <h2>{{ sku.skuName }}</h2>
        <div class="ids mono">{{ sku.skuId }} · {{ sku.activityId }}</div>

        <div class="price">
          <span class="now">¥<b>{{ toYuan(sku.salePrice) }}</b></span>
          <span v-if="sku.listPrice > sku.salePrice" class="was">
            ¥{{ toYuan(sku.listPrice) }}
          </span>
          <span v-if="discount" class="off">{{ discount }} 折</span>
        </div>

        <div class="includes">
          <div class="inc-title">包含 {{ sku.items.length }} 项权益</div>
          <div v-for="it in sku.items" :key="it.benefitItemId" class="inc">
            <span class="ico">{{ itemMeta(it.benefitItemId).icon }}</span>
            <div class="txt">
              <div class="nm">{{ itemMeta(it.benefitItemId).name }}</div>
              <div class="by mono">{{ it.providerType }} · {{ it.providerProductId }}</div>
            </div>
            <span v-if="it.core" class="core">核心权益</span>
          </div>
        </div>

        <div class="qty">
          <span class="qty-label">购买份数</span>
          <div class="stepper">
            <button
              class="step"
              :disabled="quantity <= 1 || busy"
              aria-label="减少份数"
              @click="stepQuantity(-1)"
            >
              −
            </button>
            <span class="qty-value mono" aria-live="polite">{{ quantity }}</span>
            <button
              class="step"
              :disabled="quantity >= MAX_QUANTITY || busy"
              aria-label="增加份数"
              @click="stepQuantity(1)"
            >
              ＋
            </button>
          </div>
          <span v-if="quantity > 1" class="qty-sum">
            合计 ¥<b>{{ toYuan(estimatedAmount) }}</b>
          </span>
          <span class="muted small">单笔最多 {{ MAX_QUANTITY }} 份</span>
        </div>

        <div class="buy">
          <button class="sell buy-btn" :disabled="!onSale || busy" @click="startBuy">
            {{ busy ? '处理中…' : onSale ? '立即购买' : '暂不可售' }}
          </button>
          <span class="muted tip">支付由 mock 支付方模拟，不产生真实扣款</span>
        </div>

        <div class="preconsult note">
          点「立即购买」会先调 <code>POST /consult</code> 取咨询凭证（服务端算定成交价并签名），
          再带凭证下单。支付通知须先取签名再发送 —— 无凭证返回 <code>4003</code>，无签名返回
          <code>4731</code>。
        </div>
      </div>
    </div>

    <!-- 收银台 -->
    <div v-if="stage !== 'closed'" class="mask" @click.self="stage === 'result' && close()">
      <div class="modal">
        <div class="modal-head">
          <span>{{ stage === 'result' ? '支付结果' : '收银台' }}</span>
          <button v-if="stage !== 'processing'" class="x" @click="close">×</button>
        </div>

        <!-- 确认支付 -->
        <div v-if="stage === 'confirm'" class="modal-body">
          <div class="pay-amount">
            <div class="lbl">应付金额</div>
            <div class="v"><span>¥</span>{{ toYuan(amount) }}</div>
          </div>
          <dl class="kv">
            <dt>商品</dt>
            <dd>{{ sku?.skuName }}</dd>
            <dt>份数</dt>
            <dd>{{ quantity }} 份</dd>
            <dt>订单号</dt>
            <dd>{{ bizNo }}</dd>
            <dt>支付单号</dt>
            <dd>{{ tradeNo ?? '—' }}</dd>
            <dt>凭证成交价</dt>
            <dd>
              ¥{{ toYuan(dealPrice) }} <span class="muted small">/ 份</span>
              <!--
                比对的是「单价 × 份数」与应付，不是单价与应付：多份订单下后者本就不等，
                拿它告警会在每一笔多份单上误报，而误报久了真正的比价失配就没人看了
              -->
              <span v-if="dealPrice * quantity !== amount" class="mismatch">
                与应付不一致，下单会被比价拒绝
              </span>
            </dd>
          </dl>
          <div class="method">
            <span class="ico">💳</span>
            <div>
              模拟支付
              <div class="muted small">mock 支付方 · 不产生真实扣款</div>
            </div>
            <span class="check">✓</span>
          </div>
          <div class="actions">
            <button :disabled="busy" @click="payAndSettle('FAILED')">模拟支付失败</button>
            <button class="primary" :disabled="busy" @click="payAndSettle('SUCCESS')">
              确认支付
            </button>
          </div>
        </div>

        <!-- 处理中 -->
        <div v-else-if="stage === 'processing'" class="modal-body">
          <div class="spinner" />
          <p class="center muted">{{ processingText }}</p>
          <p class="center note" style="margin-top: 12px">
            履约由调度器异步驱动 —— 支付回调返回时发放线还是
            <code>NOT_START</code>，这里在等它走完。
          </p>
        </div>

        <!-- 结果 -->
        <div v-else class="modal-body">
          <div class="result-icon" :class="resultIcon.cls">{{ resultIcon.char }}</div>
          <div class="result-title">
            {{ notice ? (notice.tone === 'error' ? '未能完成' : '处理中') : state?.summary }}
          </div>

          <div v-if="notice" class="alert" :class="notice.tone" style="margin-bottom: 14px">
            <div>{{ notice.text }}</div>
          </div>

          <template v-if="order">
            <div v-if="order.fulfillments.length" class="got">
              <div class="got-head">已发放权益 · 每个供应方一次调用</div>
              <div v-for="f in order.fulfillments" :key="f.fulfillmentNo" class="got-item">
                <span class="ico">{{ itemMeta(f.benefitItemId).icon }}</span>
                <div class="txt">
                  <div class="nm">{{ itemMeta(f.benefitItemId).name }}</div>
                  <div class="by mono">
                    {{ f.providerType }} · {{ f.providerOrderNo ?? '无下游单号' }}
                  </div>
                </div>
              </div>
            </div>
            <FulfillmentTable v-else :items="[]" />

            <dl class="kv" style="margin-top: 13px">
              <dt>订单号</dt>
              <dd>{{ order.bizNo }}</dd>
              <dt>实付</dt>
              <dd>¥{{ toYuan(order.payAmount) }}</dd>
            </dl>
          </template>

          <div class="actions" style="margin-top: 17px">
            <button v-if="bizNo" @click="gotoDetail">查看订单详情</button>
            <button class="primary" @click="close">完成</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.product {
  display: flex;
  overflow: hidden;
}
@media (max-width: 780px) {
  .product {
    flex-direction: column;
  }
}
.visual {
  flex: 0 0 272px;
  background: linear-gradient(150deg, #ff8a4c, #ff5f7e 55%, #a45cf6);
  padding: 26px;
  color: #fff;
  display: flex;
  flex-direction: column;
}
.visual .tag {
  align-self: flex-start;
  background: rgba(255, 255, 255, 0.22);
  padding: 3px 9px;
  border-radius: 5px;
  font-size: 11.5px;
}
.visual .headline {
  font-size: 24px;
  font-weight: 700;
  margin-top: auto;
  line-height: 1.35;
}
.visual .sub {
  font-size: 12.5px;
  opacity: 0.85;
  margin-top: 6px;
}

.info {
  flex: 1;
  padding: 24px 26px;
  min-width: 0;
}
.info h2 {
  margin: 0 0 4px;
  font-size: 20px;
}
.ids {
  font-size: 12px;
  color: var(--text-3);
}
.price {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 16px 0 0;
}
.price .now {
  color: var(--sell);
  font-size: 15px;
  font-weight: 600;
}
.price .now b {
  font-size: 30px;
  letter-spacing: -0.5px;
}
.price .was {
  color: var(--text-3);
  text-decoration: line-through;
}
.price .off {
  background: var(--sell-soft);
  color: var(--sell);
  font-size: 11.5px;
  padding: 2px 7px;
  border-radius: 4px;
  font-weight: 600;
}

.includes {
  border-top: 1px dashed var(--line);
  margin-top: 18px;
  padding-top: 15px;
}
.inc-title {
  font-size: 12.5px;
  color: var(--text-3);
  margin-bottom: 9px;
}
.inc {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 7px 0;
}
.inc .ico,
.got-item .ico {
  width: 33px;
  height: 33px;
  border-radius: 8px;
  background: var(--brand-soft);
  display: grid;
  place-items: center;
  font-size: 15px;
  flex: none;
}
.inc .nm,
.got-item .nm {
  font-size: 13.5px;
}
.inc .by,
.got-item .by {
  font-size: 11px;
  color: var(--text-3);
}
.inc .core {
  margin-left: auto;
  font-size: 11px;
  color: var(--sell);
  border: 1px solid rgba(255, 107, 44, 0.32);
  border-radius: 4px;
  padding: 1px 6px;
}

.qty {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 18px;
  flex-wrap: wrap;
}
.qty-label {
  font-size: 13px;
  color: var(--text-2);
}
.stepper {
  display: inline-flex;
  align-items: center;
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow: hidden;
}
.step {
  width: 32px;
  height: 32px;
  border: 0;
  background: var(--surface);
  color: var(--text-1);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
}
.step:hover:not(:disabled) {
  background: var(--brand-soft);
}
.step:disabled {
  color: var(--text-3);
  cursor: not-allowed;
}
.qty-value {
  min-width: 40px;
  text-align: center;
  font-size: 14px;
  border-left: 1px solid var(--line);
  border-right: 1px solid var(--line);
  padding: 6px 0;
}
.qty-sum {
  font-size: 13px;
  color: var(--sell);
}
.qty-sum b {
  font-size: 15px;
}

.buy {
  display: flex;
  align-items: center;
  gap: 13px;
  margin-top: 20px;
  flex-wrap: wrap;
}
.buy-btn {
  padding: 12px 40px;
  font-size: 16px;
  border-radius: 24px;
  box-shadow: 0 4px 14px rgba(255, 107, 44, 0.3);
}
.tip {
  font-size: 12px;
}
.preconsult {
  margin-top: 20px;
}

/* 收银台 */
.mask {
  position: fixed;
  inset: 0;
  background: rgba(20, 24, 33, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  z-index: 100;
}
.modal {
  background: var(--surface);
  border-radius: 14px;
  width: 100%;
  max-width: 430px;
  box-shadow: var(--shadow-lg);
  animation: pop 0.18s ease-out;
}
@keyframes pop {
  from {
    transform: translateY(10px) scale(0.985);
    opacity: 0;
  }
}
.modal-head {
  padding: 15px 19px;
  border-bottom: 1px solid var(--line-soft);
  display: flex;
  align-items: center;
  font-weight: 600;
}
.modal-head .x {
  margin-left: auto;
  border: none;
  background: transparent;
  font-size: 20px;
  line-height: 1;
  padding: 0 3px;
  color: var(--text-3);
}
.modal-body {
  padding: 19px;
}
.pay-amount {
  text-align: center;
  padding: 4px 0 17px;
}
.pay-amount .lbl {
  font-size: 12.5px;
  color: var(--text-3);
}
.pay-amount .v {
  font-size: 32px;
  font-weight: 700;
  color: var(--sell);
}
.pay-amount .v span {
  font-size: 19px;
}
.method {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  margin: 15px 0;
  font-size: 13.5px;
}
.method .ico {
  font-size: 18px;
}
.method .check {
  margin-left: auto;
  color: var(--ok);
  font-weight: 700;
}
.small {
  font-size: 11.5px;
}
.mismatch {
  color: var(--error);
  font-size: 11px;
  margin-left: 6px;
}
.actions {
  display: flex;
  gap: 10px;
}
.actions button {
  flex: 1;
  padding: 10px;
}
.spinner {
  width: 34px;
  height: 34px;
  margin: 22px auto 14px;
  border-radius: 50%;
  border: 3px solid var(--line);
  border-top-color: var(--brand);
  animation: spin 0.7s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.center {
  text-align: center;
}
.result-icon {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  margin: 2px auto 13px;
  display: grid;
  place-items: center;
  font-size: 27px;
  color: #fff;
}
.result-icon.ok {
  background: var(--ok);
}
.result-icon.error {
  background: var(--error);
}
.result-icon.unknown {
  background: var(--unknown);
}
.result-title {
  text-align: center;
  font-size: 17px;
  font-weight: 600;
  margin-bottom: 15px;
}
.got {
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.got-head {
  background: var(--surface-2);
  padding: 8px 13px;
  font-size: 11.5px;
  color: var(--text-3);
}
.got-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 13px;
  border-top: 1px solid var(--line-soft);
}
</style>
