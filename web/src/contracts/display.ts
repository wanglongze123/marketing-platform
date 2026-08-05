/**
 * 状态 → 展示的映射，以及展示态派生。
 *
 * ⚠️ 三点约定：
 *
 * 1. 五个枚举各自一张映射表，**不合并**。合并后把明细的 SUCCESS 当主单状态渲染，
 *    页面会显示「已到账」而主单其实还在 GRANTING。
 * 2. 映射表类型是 Record<枚举, Display>，即**全键必填**。后端加一个枚举值时
 *    `npm run typecheck` 立刻报缺键，而不是运行时静默显示空白。
 * 3. 展示态由三条子状态线派生，**只在此处派生一次**。后端不落 biz_status ——
 *    「支付成功且退款中」这类组合无法由单一枚举表达。
 */
import type {
  GrantStatus,
  ItemGrantStatus,
  OpStatus,
  PayStatus,
  RefundStatus,
} from './enums'
import type { QueryOrderResp, OrderListItem } from './dto'

/** tone 决定颜色，不直接写颜色值 —— 主题切换时只改一处 */
export type Tone = 'ok' | 'wait' | 'error' | 'idle' | 'unknown'

export interface Display {
  /** 面向用户的中文文案 */
  label: string
  tone: Tone
}

export const PAY_STATUS_DISPLAY: Record<PayStatus, Display> = {
  WAIT_PAY: { label: '待支付', tone: 'wait' },
  CLOSING: { label: '关单中', tone: 'unknown' },
  PAY_SUCCESS: { label: '支付成功', tone: 'ok' },
  PAY_FAILED: { label: '支付失败', tone: 'error' },
  CLOSED: { label: '已关闭', tone: 'idle' },
}

export const GRANT_STATUS_DISPLAY: Record<GrantStatus, Display> = {
  NOT_START: { label: '未开始', tone: 'idle' },
  GRANTING: { label: '发放中', tone: 'wait' },
  GRANT_SUCCESS: { label: '已到账', tone: 'ok' },
  GRANT_FAILED: { label: '发放失败', tone: 'error' },
  // 不叫「失败」：结果未知时下游可能已发放，须以原幂等号查单收敛
  GRANT_UNKNOWN: { label: '结果确认中', tone: 'unknown' },
}

export const ITEM_GRANT_STATUS_DISPLAY: Record<ItemGrantStatus, Display> = {
  NOT_START: { label: '未发放', tone: 'idle' },
  GRANTING: { label: '发放中', tone: 'wait' },
  SUCCESS: { label: '已发放', tone: 'ok' },
  FAILED: { label: '发放失败', tone: 'error' },
  UNKNOWN: { label: '结果确认中', tone: 'unknown' },
}

export const REFUND_STATUS_DISPLAY: Record<RefundStatus, Display> = {
  NONE: { label: '无退款', tone: 'idle' },
  REVOKING: { label: '回收中', tone: 'wait' },
  REVOKE_FAILED: { label: '回收失败', tone: 'error' },
  REFUNDING: { label: '退款中', tone: 'wait' },
  REFUND_SUCCESS: { label: '已退款', tone: 'ok' },
  REFUND_FAILED: { label: '退款失败', tone: 'error' },
}

export const OP_STATUS_DISPLAY: Record<OpStatus, Display> = {
  INIT: { label: '已受理', tone: 'idle' },
  PROCESSING: { label: '处理中', tone: 'wait' },
  SUCCESS: { label: '成功', tone: 'ok' },
  FAILED: { label: '失败', tone: 'error' },
  UNKNOWN: { label: '结果未知', tone: 'unknown' },
}

/** 操作类型的中文名，用于操作记录时间线 */
export const OP_TYPE_LABEL: Record<string, string> = {
  CREATE_TRADE: '创建订单',
  GRANT_BENEFIT: '权益发放',
  CLOSE_ORDER: '关闭订单',
  REVOKE_BENEFIT: '权益回收',
  CREATE_REFUND: '发起退款',
  PAY_CALLBACK: '支付通知',
  REFUND_CALLBACK: '退款通知',
  MANUAL_REPAIR: '人工修复',
  RECONCILE: '对账',
}

// ------------------------------------------------------------------
// 展示态派生
// ------------------------------------------------------------------

/** 主流程四步。用于订单详情的进度条 */
export type FlowStep = 'created' | 'paid' | 'granting' | 'done'

export interface DisplayState {
  /** 一句话概括当前处境，面向用户 */
  summary: string
  tone: Tone
  /** 已完成的步骤 */
  done: Record<FlowStep, boolean>
  /** 是否处于「结果未知」——UI 据此显示收敛提示而非失败 */
  pending: boolean
  /** 是否可以去支付 */
  payable: boolean
}

type OrderLike = Pick<
  QueryOrderResp | OrderListItem,
  'payStatus' | 'grantStatus' | 'refundStatus'
>

/**
 * 由三条子状态线派生展示态。
 *
 * 判定顺序即优先级：退款相关最高（它推翻「已完成」的观感），其次结果未知
 * （不能说失败），再是支付线，最后才是发放线。
 */
export function deriveDisplayState(o: OrderLike): DisplayState {
  const paid = o.payStatus === 'PAY_SUCCESS'
  const granting =
    o.grantStatus === 'GRANTING' ||
    o.grantStatus === 'GRANT_SUCCESS' ||
    o.grantStatus === 'GRANT_FAILED' ||
    o.grantStatus === 'GRANT_UNKNOWN'

  const done: Record<FlowStep, boolean> = {
    created: true,
    paid,
    granting,
    done: o.grantStatus === 'GRANT_SUCCESS',
  }

  // 退款线优先：它会推翻「已完成」的观感
  if (o.refundStatus !== 'NONE') {
    return {
      summary: REFUND_STATUS_DISPLAY[o.refundStatus].label,
      tone: REFUND_STATUS_DISPLAY[o.refundStatus].tone,
      done,
      pending: o.refundStatus === 'REVOKING' || o.refundStatus === 'REFUNDING',
      payable: false,
    }
  }

  // 结果未知：不得报失败
  if (o.grantStatus === 'GRANT_UNKNOWN') {
    return {
      summary: '权益发放结果确认中',
      tone: 'unknown',
      done,
      pending: true,
      payable: false,
    }
  }

  if (o.payStatus === 'CLOSING') {
    return { summary: '订单关闭中', tone: 'unknown', done, pending: true, payable: false }
  }
  if (o.payStatus === 'CLOSED') {
    return { summary: '订单已关闭', tone: 'idle', done, pending: false, payable: false }
  }
  if (o.payStatus === 'PAY_FAILED') {
    return { summary: '支付失败', tone: 'error', done, pending: false, payable: true }
  }
  if (o.payStatus === 'WAIT_PAY') {
    return { summary: '待支付', tone: 'wait', done, pending: false, payable: true }
  }

  // 已支付，看发放线
  switch (o.grantStatus) {
    case 'GRANT_SUCCESS':
      return { summary: '已完成', tone: 'ok', done, pending: false, payable: false }
    case 'GRANT_FAILED':
      return { summary: '权益发放失败', tone: 'error', done, pending: false, payable: false }
    case 'GRANTING':
      return { summary: '权益发放中', tone: 'wait', done, pending: true, payable: false }
    case 'NOT_START':
      return { summary: '等待发放', tone: 'wait', done, pending: true, payable: false }
  }
}

/** 分 → 元，用于展示。金额计算一律在服务端，前端只格式化 */
export function toYuan(cents: number | null | undefined): string {
  if (cents == null) return '—'
  return (cents / 100).toFixed(2)
}

/** ISO 本地时间 → 紧凑展示。后端返回形如 2026-08-05T23:31:19.581 */
export function formatTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
