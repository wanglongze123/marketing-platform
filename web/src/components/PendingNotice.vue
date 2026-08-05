<script setup lang="ts">
/**
 * 「后端未实现」占位块。
 *
 * 显示的是「这里将来有什么 + 为什么现在没有 + 计划的端点」，而不是假数据。
 * 文案一律取自 PENDING 清单，页面不复制 —— 后端上线后删清单里的一条即可。
 */
import { PENDING, PHASE_LABEL } from '@/api/notImplemented'

const props = defineProps<{
  /** PENDING 里的 key */
  capability: string
  /** 补充说明这个页面原本要用它做什么 */
  detail?: string
}>()

const cap = PENDING[props.capability]
</script>

<template>
  <div v-if="cap" class="pending">
    <div class="head">
      <span class="badge" :class="cap.phase">{{ PHASE_LABEL[cap.phase] }}</span>
      <b>{{ cap.name }}</b>
    </div>
    <p v-if="props.detail" class="detail">{{ props.detail }}</p>
    <p class="why">{{ cap.why }}</p>
    <div class="endpoint">
      <span class="muted">计划端点</span>
      <code>{{ cap.endpoint }}</code>
    </div>
  </div>
</template>

<style scoped>
.pending {
  border: 1px dashed var(--line);
  border-radius: var(--radius-sm);
  padding: 15px 17px;
  background: var(--surface-2);
}
.head {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 9px;
}
.head b {
  font-size: 14px;
}
.badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 600;
  white-space: nowrap;
}
.badge.V2 {
  background: var(--wait-soft);
  color: var(--wait);
}
.badge.V3 {
  background: var(--unknown-soft);
  color: var(--unknown);
}
.badge.unplanned {
  background: var(--idle-soft);
  color: var(--idle);
}
.detail {
  margin: 0 0 7px;
  font-size: 13px;
  color: var(--text-2);
}
.why {
  margin: 0 0 11px;
  font-size: 12.5px;
  color: var(--text-3);
  line-height: 1.65;
}
.endpoint {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 12px;
  flex-wrap: wrap;
}
.endpoint code {
  background: var(--surface);
  border: 1px solid var(--line-soft);
  padding: 3px 8px;
  border-radius: 4px;
  font-size: 11.5px;
  color: var(--text-2);
}
</style>
