<script setup lang="ts">
/**
 * 状态徽章。只接受已解析好的 Display，不自己查映射表 ——
 * 查表的责任在调用方，这样「用错枚举的映射表」在调用处就能看出来。
 */
import type { Display } from '@/contracts/display'

const props = defineProps<{
  display: Display
  /** 附带原始枚举值，排查时有用 */
  raw?: string
}>()
</script>

<template>
  <span class="pill" :class="props.display.tone" :title="props.raw">
    <i class="dot" />
    {{ props.display.label }}
  </span>
</template>

<style scoped>
.pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 9px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}
.ok {
  background: var(--ok-soft);
  color: var(--ok);
}
.wait {
  background: var(--wait-soft);
  color: var(--wait);
}
.error {
  background: var(--error-soft);
  color: var(--error);
}
.unknown {
  background: var(--unknown-soft);
  color: var(--unknown);
}
.idle {
  background: var(--idle-soft);
  color: var(--idle);
}
</style>
