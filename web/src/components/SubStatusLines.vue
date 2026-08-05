<script setup lang="ts">
/**
 * 三条子状态线。
 *
 * 分开展示而非合并成一个状态 —— 后端三条线独立推进，「支付成功且退款中」这类
 * 组合无法由单一枚举表达（QueryOrderResp 的注释也写明不合并）。
 *
 * 每条线各用自己的映射表：payStatus 用 PAY_STATUS_DISPLAY、grantStatus 用
 * GRANT_STATUS_DISPLAY，不共用一张表。
 */
import StatusPill from './StatusPill.vue'
import {
  GRANT_STATUS_DISPLAY,
  PAY_STATUS_DISPLAY,
  REFUND_STATUS_DISPLAY,
} from '@/contracts/display'
import type { GrantStatus, PayStatus, RefundStatus } from '@/contracts/enums'

const props = defineProps<{
  payStatus: PayStatus
  grantStatus: GrantStatus
  refundStatus: RefundStatus
  /** 是否显示字段名，排查视图用 */
  showFieldNames?: boolean
}>()
</script>

<template>
  <div class="lines">
    <div class="line">
      <div class="k">
        支付线
        <code v-if="props.showFieldNames">payStatus</code>
      </div>
      <StatusPill :display="PAY_STATUS_DISPLAY[props.payStatus]" :raw="props.payStatus" />
    </div>
    <div class="line">
      <div class="k">
        发放线
        <code v-if="props.showFieldNames">grantStatus</code>
      </div>
      <StatusPill
        :display="GRANT_STATUS_DISPLAY[props.grantStatus]"
        :raw="props.grantStatus"
      />
    </div>
    <div class="line">
      <div class="k">
        退款线
        <code v-if="props.showFieldNames">refundStatus</code>
      </div>
      <StatusPill
        :display="REFUND_STATUS_DISPLAY[props.refundStatus]"
        :raw="props.refundStatus"
      />
    </div>
  </div>
</template>

<style scoped>
.lines {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
@media (max-width: 620px) {
  .lines {
    grid-template-columns: 1fr;
  }
}
.line {
  background: var(--surface-2);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  padding: 11px 13px;
}
.k {
  font-size: 12px;
  color: var(--text-3);
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.k code {
  font-size: 11px;
  opacity: 0.75;
}
</style>
