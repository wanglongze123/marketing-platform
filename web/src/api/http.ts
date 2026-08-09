/**
 * 请求层。响应壳解析只在这里发生，业务代码不碰 code。
 *
 * ⚠️ 核心设计：结果分三态，而非「成功 / 失败」两态。
 *
 *   ok       —— 确定成功
 *   rejected —— 确定失败（1xxx 业务拒绝 / 4xxx 入参非法），可展示原因、不重试
 *   unknown  —— **结果未知**（5xxx，或 code=0 但 data.status 为 PROCESSING/UNKNOWN）
 *
 * 为什么 unknown 必须独立成一类：《分阶段方案》§4.3 的转换规则里，
 * PROCESSING / UNKNOWN 也返回 code=0，靠 data.status 标记处理中。理由是
 * 「结果未定时告诉用户失败会引发重复下单」。V1 下游固定成功，只有 SUCCESS
 * 一行被真实走到，所以写 `if (code === 0) 成功` 现在不出错 —— 但 V2 注入
 * timeout / PROCESSING 后就是错的。这一层从第一天就按三态实现，V2 无需改调用点。
 *
 * 类型上强制调用方处理 unknown 分支：discriminated union 使
 * `if (r.kind === 'ok') ... else 显示失败` 覆盖不到 unknown，会被 tsc 发现。
 */
import { isUnknownResult } from '@/contracts/errorCode'
import type { ApiEnvelope } from '@/contracts/dto'

export type ApiResult<T> =
  | { kind: 'ok'; data: T; traceId: string }
  | { kind: 'rejected'; code: number; message: string; traceId: string }
  | { kind: 'unknown'; code: number; message: string; traceId: string }

/** 一次请求的记录，供开发者面板与调试台展示 */
export interface RequestLog {
  id: number
  method: string
  url: string
  requestBody?: unknown
  status: number | null
  /** 原始响应体，未解析成 ApiResult 前的样子 */
  raw: unknown
  code: number | null
  traceId: string | null
  ms: number
  /** 网络层错误（服务未启动、断网），与业务失败区分 */
  networkError?: string
  at: string
}

type LogListener = (log: RequestLog) => void

const listeners = new Set<LogListener>()
let logSeq = 0

/** 订阅请求日志。返回取消订阅函数 */
export function onRequest(fn: LogListener): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

function emit(log: RequestLog) {
  listeners.forEach((fn) => fn(log))
}

/**
 * data.status 表示「处理中」的取值。
 *
 * 取 RetStatus 的两个非终态值。V1 恒为 SUCCESS，故此判断当前不会命中 ——
 * 留在这里是为了 V2 注入故障时无需改调用点。
 */
const PENDING_STATUSES = new Set(['PROCESSING', 'UNKNOWN'])

function hasPendingStatus(data: unknown): boolean {
  if (data == null || typeof data !== 'object') return false
  const status = (data as { status?: unknown }).status
  return typeof status === 'string' && PENDING_STATUSES.has(status)
}

async function request<T>(
  method: 'GET' | 'POST',
  path: string,
  body?: unknown
): Promise<ApiResult<T>> {
  const started = performance.now()
  const base: Omit<RequestLog, 'code' | 'traceId' | 'raw' | 'status' | 'ms'> = {
    id: ++logSeq,
    method,
    url: path,
    requestBody: body,
    at: new Date().toISOString(),
  }

  let res: Response
  try {
    res = await fetch(path, {
      method,
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e)
    emit({
      ...base,
      status: null,
      raw: null,
      code: null,
      traceId: null,
      ms: Math.round(performance.now() - started),
      networkError: message,
    })
    // 网络层失败同样是「结果未知」：请求可能已到达服务端并被处理
    return { kind: 'unknown', code: -1, message: `网络请求失败：${message}`, traceId: '' }
  }

  const text = await res.text()
  let envelope: ApiEnvelope<T> | null = null
  let raw: unknown = text
  try {
    raw = JSON.parse(text)
    envelope = raw as ApiEnvelope<T>
  } catch {
    envelope = null
  }

  const ms = Math.round(performance.now() - started)
  const code = envelope?.code ?? null
  const traceId = envelope?.traceId ?? ''
  emit({ ...base, status: res.status, raw, code, traceId, ms })

  // 响应体不是约定的壳：视为结果未知，不当失败
  if (envelope == null || typeof envelope.code !== 'number') {
    return {
      kind: 'unknown',
      code: -1,
      message: `响应格式异常（HTTP ${res.status}）`,
      traceId,
    }
  }

  if (envelope.code === 0) {
    // code=0 但 data.status 标记处理中 → 归入 unknown，不报成功
    if (hasPendingStatus(envelope.data)) {
      const status = (envelope.data as { status: string }).status
      return { kind: 'unknown', code: 0, message: `处理中（${status}）`, traceId }
    }
    return { kind: 'ok', data: envelope.data as T, traceId }
  }

  // 5xxx 结果未知，其余（1xxx/4xxx）是确定失败
  const kind = isUnknownResult(envelope.code) ? 'unknown' : 'rejected'
  return { kind, code: envelope.code, message: envelope.message, traceId }
}

export const httpGet = <T>(path: string) => request<T>('GET', path)
export const httpPost = <T>(path: string, body: unknown) => request<T>('POST', path, body)

/** 拼查询串，跳过 undefined / 空串 —— 空值意为「不筛该项」，不应出现在 URL 里 */
export function toQuery(params: Record<string, string | number | undefined>): string {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === '') continue
    q.set(k, String(v))
  }
  const s = q.toString()
  return s ? `?${s}` : ''
}
