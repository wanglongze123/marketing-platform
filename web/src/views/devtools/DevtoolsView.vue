<script setup lang="ts">
/**
 * 调试台。场景回归 + 请求日志。
 *
 * 迁自旧 console.html 并扩充。请求日志由 http 层的 onRequest 订阅得到 ——
 * 业务代码不需要为了「能被记录」而改动。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { onRequest } from '@/api/http'
import type { RequestLog } from '@/api/http'
import { ERROR_CODE_DEV_TEXT } from '@/contracts/errorCode'
import { useSessionStore } from '@/stores/session'
import { SCENARIOS } from './scenarios'
import type { Ctx, StepResult } from './scenarios'

const session = useSessionStore()

type ScenarioState = {
  steps: StepResult[]
  status: 'idle' | 'running' | 'pass' | 'fail'
  open: boolean
}
const states = ref<Record<string, ScenarioState>>(
  Object.fromEntries(SCENARIOS.map((s) => [s.id, { steps: [], status: 'idle', open: false }]))
)
const running = ref(false)

/**
 * 为一次场景运行派生独立的下单用户。
 *
 * SKU 配了每人限购 2 份（V2190__seed_stock.sql），而十几个场景都要下单。若共用
 * 页面顶栏那个 userId，「全部运行」跑到第三个下单场景就必然 1713 —— 且报错指向
 * 「超出限购」，而失败的场景与限购无关，排查会先怀疑后端。
 *
 * 带时间戳而非只按 id 派生：否则第二次点「全部运行」时额度已被上一轮用光，
 * 表现为「第一次全绿、之后再点就红」。
 *
 * ⚠️ 必须短：userId 是业务幂等键 `user_activity_sku_clientReqNo` 的第一段，而
 * `play_op_record.idempotent_key` 只有 VARCHAR(64)。拼长了会在建单时触发
 * `Data too long`，经兜底分支变成 `5001 system error` —— 报错既不提用户名过长，
 * 也不指向前端，排查会先怀疑后端。故这里只取场景 id 的前 6 个字符 + 4 位时间戳。
 */
function scenarioUserId(id: string): string {
  const stamp = Date.now().toString(36).slice(-4).toUpperCase()
  return `T_${id.slice(0, 6)}_${stamp}`
}

async function runOne(id: string) {
  const scenario = SCENARIOS.find((s) => s.id === id)
  if (!scenario || running.value) return
  running.value = true

  const st = states.value[id]
  st.steps = []
  st.status = 'running'
  st.open = true

  let failed = false
  const ctx: Ctx = {
    userId: scenarioUserId(id),
    assert(cond, message) {
      if (!cond) throw new Error(message)
    },
    async step(label, fn, note) {
      const entry: StepResult = { label, state: 'running', note }
      st.steps.push(entry)
      try {
        const out = await fn()
        entry.state = 'pass'
        return out
      } catch (e) {
        entry.state = 'fail'
        entry.message = e instanceof Error ? e.message : String(e)
        failed = true
        throw e
      }
    },
  }

  try {
    await scenario.run(ctx)
  } catch {
    // 已在 step 内标记，停在首个失败步
  }

  st.status = failed ? 'fail' : 'pass'
  running.value = false
}

async function runAll() {
  for (const s of SCENARIOS) {
    // 串行：并发跑会让「造 3 单」之类的场景互相干扰
    await runOne(s.id)
  }
}

const summary = computed(() => {
  const all = Object.values(states.value)
  return {
    pass: all.filter((s) => s.status === 'pass').length,
    fail: all.filter((s) => s.status === 'fail').length,
    total: SCENARIOS.length,
  }
})

// ---- 请求日志 ----
const logs = ref<RequestLog[]>([])
const expanded = ref<number | null>(null)
let off: (() => void) | null = null

onMounted(() => {
  off = onRequest((log) => {
    logs.value.unshift(log)
    if (logs.value.length > 200) logs.value.pop()
  })
})
onUnmounted(() => off?.())

const logTone = (l: RequestLog) =>
  l.networkError ? 'error' : l.code === 0 ? 'ok' : 'wait'
const pretty = (v: unknown) => JSON.stringify(v, null, 2)
</script>

<template>
  <div class="wrap">
    <!-- 场景 -->
    <div>
      <div class="card">
        <div class="card-head">
          场景回归
          <span class="sub">每步带预期断言</span>
          <span class="right">
            <span v-if="summary.pass || summary.fail" class="tally">
              <b class="ok">{{ summary.pass }}</b> / {{ summary.total }} 通过
              <b v-if="summary.fail" class="err">{{ summary.fail }} 失败</b>
            </span>
            <button class="sm primary" :disabled="running" @click="runAll">
              {{ running ? '运行中…' : '全部运行' }}
            </button>
          </span>
        </div>

        <div v-for="s in SCENARIOS" :key="s.id" class="scenario">
          <div class="sc-head">
            <span class="status" :class="states[s.id].status">
              {{
                states[s.id].status === 'idle'
                  ? '未跑'
                  : states[s.id].status === 'running'
                    ? '运行中'
                    : states[s.id].status === 'pass'
                      ? '通过'
                      : '失败'
              }}
            </span>
            <div class="sc-txt" @click="states[s.id].open = !states[s.id].open">
              <b>{{ s.name }}</b>
              <div class="muted">{{ s.desc }}</div>
            </div>
            <button class="sm" :disabled="running" @click="runOne(s.id)">运行</button>
          </div>

          <ol v-if="states[s.id].open && states[s.id].steps.length" class="steps">
            <li v-for="(st, i) in states[s.id].steps" :key="i" :class="st.state">
              <span class="mark">
                {{ st.state === 'pass' ? '✓' : st.state === 'fail' ? '✕' : '◌' }}
              </span>
              <div class="body">
                <div>{{ st.label }}</div>
                <div v-if="st.message" class="err-msg">{{ st.message }}</div>
                <div v-if="st.note" class="why">{{ st.note }}</div>
              </div>
            </li>
          </ol>
        </div>
      </div>

      <p class="note" style="margin-top: 14px">
        场景使用当前用户 <code>{{ session.userId }}</code> 下真实订单，会写库。分页场景会自建
        独立 userId 以免干扰其他断言。
      </p>
    </div>

    <!-- 请求日志 -->
    <div>
      <div class="card">
        <div class="card-head">
          请求日志
          <span class="sub">{{ logs.length }} 条</span>
          <span class="right">
            <button class="sm ghost" @click="logs = []">清空</button>
          </span>
        </div>
        <div v-if="!logs.length" class="empty">还没有请求</div>
        <div v-else class="logs">
          <div v-for="l in logs" :key="l.id" class="log">
            <div class="log-head" @click="expanded = expanded === l.id ? null : l.id">
              <span class="method">{{ l.method }}</span>
              <span class="url">{{ l.url }}</span>
              <span class="code" :class="logTone(l)">
                {{ l.networkError ? 'ERR' : `code ${l.code}` }}
              </span>
              <span class="ms">{{ l.ms }}ms</span>
            </div>
            <div v-if="expanded === l.id" class="log-body">
              <template v-if="l.requestBody">
                <h4>Request</h4>
                <pre>{{ pretty(l.requestBody) }}</pre>
              </template>
              <h4>Response</h4>
              <pre>{{ l.networkError ?? pretty(l.raw) }}</pre>
              <template v-if="l.code != null && ERROR_CODE_DEV_TEXT[l.code]">
                <h4>code {{ l.code }}</h4>
                <div class="note">{{ ERROR_CODE_DEV_TEXT[l.code] }}</div>
              </template>
              <template v-if="l.traceId">
                <h4>traceId</h4>
                <pre>{{ l.traceId }}</pre>
              </template>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wrap {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  align-items: start;
}
@media (max-width: 1080px) {
  .wrap {
    grid-template-columns: 1fr;
  }
}
.tally {
  font-size: 12.5px;
  color: var(--text-3);
  font-weight: 400;
}
.tally .ok {
  color: var(--ok);
}
.tally .err {
  color: var(--error);
  margin-left: 7px;
}

.scenario {
  border-bottom: 1px solid var(--line-soft);
}
.scenario:last-child {
  border-bottom: none;
}
.sc-head {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 10px 17px;
}
.sc-head:hover {
  background: var(--surface-2);
}
.sc-txt {
  flex: 1;
  min-width: 0;
  cursor: pointer;
}
.sc-txt b {
  font-size: 13.5px;
}
.sc-txt .muted {
  font-size: 11.5px;
}
.status {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  font-family: var(--mono);
  white-space: nowrap;
  flex: none;
}
.status.idle {
  background: var(--idle-soft);
  color: var(--idle);
}
.status.running {
  background: var(--wait-soft);
  color: var(--wait);
}
.status.pass {
  background: var(--ok-soft);
  color: var(--ok);
}
.status.fail {
  background: var(--error-soft);
  color: var(--error);
}

.steps {
  list-style: none;
  margin: 0;
  padding: 0 17px 13px 17px;
}
.steps li {
  display: flex;
  gap: 9px;
  padding: 4px 0;
  font-size: 12px;
  font-family: var(--mono);
  color: var(--text-2);
}
.steps .mark {
  width: 14px;
  flex: none;
  text-align: center;
}
.steps li.pass .mark {
  color: var(--ok);
}
.steps li.fail .mark {
  color: var(--error);
}
.steps li.running .mark {
  color: var(--wait);
}
.steps .body {
  flex: 1;
  min-width: 0;
  word-break: break-word;
}
.err-msg {
  color: var(--error);
  margin-top: 3px;
}
.why {
  color: var(--text-3);
  margin-top: 3px;
  font-family: -apple-system, 'Segoe UI', 'Microsoft YaHei', sans-serif;
  font-size: 11.5px;
  line-height: 1.6;
}

.logs {
  max-height: 620px;
  overflow-y: auto;
}
.log {
  border-bottom: 1px solid var(--line-soft);
}
.log:last-child {
  border-bottom: none;
}
.log-head {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px 15px;
  cursor: pointer;
  font-family: var(--mono);
  font-size: 11.5px;
}
.log-head:hover {
  background: var(--surface-2);
}
.method {
  color: var(--brand);
  font-weight: 600;
  min-width: 34px;
}
.url {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-2);
}
.code {
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 600;
  font-size: 10.5px;
  white-space: nowrap;
}
.code.ok {
  background: var(--ok-soft);
  color: var(--ok);
}
.code.wait {
  background: var(--wait-soft);
  color: var(--wait);
}
.code.error {
  background: var(--error-soft);
  color: var(--error);
}
.ms {
  color: var(--text-3);
  font-size: 10.5px;
}
.log-body {
  padding: 0 15px 12px;
}
.log-body h4 {
  margin: 9px 0 4px;
  font-size: 10.5px;
  color: var(--text-3);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  font-weight: 600;
}
</style>
