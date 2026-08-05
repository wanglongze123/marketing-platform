<script setup lang="ts">
/**
 * 可靠任务看板。V2 能力，benefit_task 表还没建。
 */
import PendingNotice from '@/components/PendingNotice.vue'

/** 看板将展示的列，取自《技术方案》§6.5 的任务表设计 */
const COLUMNS = [
  { name: 'taskId / bizNo', detail: '任务标识与关联业务单' },
  { name: 'taskType', detail: 'GRANT / CLOSE_ORDER / RECONCILE 等' },
  { name: 'status', detail: '待执行 / 执行中 / 成功 / 失败' },
  { name: 'leaseOwner / leaseUntil', detail: '租约持有者与到期时间，故障接管的依据' },
  { name: 'retryCount / nextRunAt', detail: '重试次数与下次执行时间（退避序列）' },
]
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        任务看板
        <span class="sub">《技术方案》§6.5</span>
      </div>
      <div class="card-body">
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th style="width: 230px">字段</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in COLUMNS" :key="c.name">
                <td class="mono">{{ c.name }}</td>
                <td class="muted">{{ c.detail }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="note" style="margin-top: 14px">
          V1 的履约是在支付回调里<b>同步</b>调用的（<code>payCallback</code> 事务提交后直接调
          <code>grantBenefit</code>）。V2 改为事务内落任务、由调度器驱动，届时这个看板才有数据。
          退避序列：短退避 1s→5s→30s，长退避 30s→2m→10m。
        </p>
      </div>
    </div>

    <div class="card" style="margin-top: 16px">
      <div class="card-body">
        <PendingNotice capability="taskBoard" />
      </div>
    </div>
  </div>
</template>
