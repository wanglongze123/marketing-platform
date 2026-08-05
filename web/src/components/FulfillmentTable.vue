<script setup lang="ts">
/**
 * 履约明细表。
 *
 * 用 ITEM_GRANT_STATUS_DISPLAY（明细态，无前缀），不是主单的 GRANT_STATUS_DISPLAY。
 * 两张表的列都叫 grant_status 但取值不同 —— 用错映射表时 label 会是 undefined，
 * 因为 Record<ItemGrantStatus, Display> 里没有 GRANT_SUCCESS 这个键。
 */
import StatusPill from './StatusPill.vue'
import { ITEM_GRANT_STATUS_DISPLAY } from '@/contracts/display'
import type { FulfillmentItem } from '@/contracts/dto'

const props = defineProps<{
  items: FulfillmentItem[]
  /** 展示技术细节（下游单号、权益项 ID），运营/排查视图用 */
  technical?: boolean
}>()

/** 权益项 ID → 面向用户的名字。seed 数据里的两项，其余回落到 ID */
const ITEM_NAME: Record<string, string> = {
  ITEM_DEMO_A: '会员月卡 · 30 天',
  ITEM_DEMO_B: '满减优惠券 · 1 张',
}
const itemName = (id: string) => ITEM_NAME[id] ?? id
</script>

<template>
  <div v-if="props.items.length" class="table-wrap">
    <table>
      <thead>
        <tr>
          <th>权益项</th>
          <th>供应方</th>
          <th v-if="props.technical">下游单号</th>
          <th>状态</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="f in props.items" :key="f.fulfillmentNo || f.benefitItemId">
          <td>
            {{ itemName(f.benefitItemId) }}
            <div v-if="props.technical" class="sub mono">{{ f.benefitItemId }}</div>
          </td>
          <td class="mono">{{ f.providerType }}</td>
          <td v-if="props.technical" class="mono">{{ f.providerOrderNo ?? '—' }}</td>
          <td>
            <StatusPill
              :display="ITEM_GRANT_STATUS_DISPLAY[f.grantStatus]"
              :raw="f.grantStatus"
            />
          </td>
        </tr>
      </tbody>
    </table>
  </div>
  <p v-else class="note">暂无履约记录 —— 支付成功后才会产生。</p>
</template>

<style scoped>
.sub {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 2px;
}
</style>
