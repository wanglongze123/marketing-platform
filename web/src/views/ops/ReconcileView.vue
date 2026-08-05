<script setup lang="ts">
/**
 * 对账。V3 能力，14 项比对与资损哨兵指标都还没有。
 */
import PendingNotice from '@/components/PendingNotice.vue'

/** 部分对账项，取自《技术方案》§6.8。资损哨兵指标由对账产出 */
const CHECKS = [
  { name: '已收款未发奖', severity: '资损', detail: 'pay_status=PAY_SUCCESS 且 grant_status 非终态超时' },
  { name: '已发奖未收款', severity: '资损', detail: '发放成功但主单未支付' },
  { name: '重复发放', severity: '资损', detail: '同一 grantOpNo 对应多条下游单号' },
  { name: '金额不一致', severity: '资损', detail: 'order_amount ≠ pay_amount' },
  { name: '已退款未回收', severity: '资损', detail: '退款成功但权益仍在外' },
  { name: '状态悬挂', severity: '一致性', detail: '中间态（GRANTING / CLOSING / REVOKING）滞留超阈值' },
]
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        对账项
        <span class="sub">《技术方案》§6.8 · 共 14 项</span>
      </div>
      <div class="card-body">
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th style="width: 170px">对账项</th>
                <th style="width: 90px">分类</th>
                <th>判定</th>
                <th style="width: 80px">结果</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in CHECKS" :key="c.name">
                <td>{{ c.name }}</td>
                <td>
                  <span class="sev" :class="c.severity === '资损' ? 'high' : 'mid'">
                    {{ c.severity }}
                  </span>
                </td>
                <td class="muted">{{ c.detail }}</td>
                <td class="muted">—</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="note" style="margin-top: 14px">
          表中列出 6 项作为示例，完整为 14 项。V3 退出标准第 3 条要求对账能检出<b>人为注入的
          差异</b>（如手工改库制造「已收款未发奖」）—— 能跑通不等于能发现问题。
        </p>
      </div>
    </div>

    <div class="card" style="margin-top: 16px">
      <div class="card-body">
        <PendingNotice capability="reconcile" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.sev {
  font-size: 11px;
  padding: 2px 7px;
  border-radius: 4px;
  white-space: nowrap;
}
.sev.high {
  background: var(--error-soft);
  color: var(--error);
}
.sev.mid {
  background: var(--wait-soft);
  color: var(--wait);
}
</style>
