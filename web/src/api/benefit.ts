/**
 * 权益售卖接口。
 *
 * 这里只封装**后端真实存在**的 6 个端点。未实现的能力不在此文件里造假函数 ——
 * 见 src/api/notImplemented.ts 的说明。
 */
import { httpGet, httpPost, toQuery } from './http'
import type { ApiResult } from './http'
import type {
  ConvergenceResp,
  CreateTradeReq,
  CreateTradeResp,
  OpRecordItem,
  PayCallbackReq,
  PayCallbackResp,
  PayNotifySignResp,
  PreConsultReq,
  PreConsultResp,
  QueryOrderPageParams,
  QueryOrderPageResp,
  QueryOrderResp,
  QuerySkuResp,
} from '@/contracts/dto'

/**
 * 预咨询：试算 + 签发咨询凭证。
 *
 * <b>必须先调本接口再下单</b> —— createTrade 无凭证一律 4003。凭证里签了成交价与
 * 包版本，下单时服务端逐字段比对；价格不符返回 1711。
 */
export const preConsult = (req: PreConsultReq) =>
  httpPost<PreConsultResp>('/api/benefit/consult', req)

export const createTrade = (req: CreateTradeReq) =>
  httpPost<CreateTradeResp>('/api/benefit/trade', req)

export const payCallback = (req: PayCallbackReq) =>
  httpPost<PayCallbackResp>('/api/benefit/pay-callback', req)

/**
 * 关闭订单（用户取消 / 运营清理）。超时关闭由 CLOSE_ORDER 任务触发，不走本端点。
 *
 * 已支付的单返回 1741；关单结果未定时进 CLOSING —— 端上须提示「处理中」而非「已关闭」，
 * 后者会让用户以为钱不会被扣。
 */
export const closeOrder = (bizNo: string) =>
  httpPost<{ status: string }>(
    `/api/benefit/close/${encodeURIComponent(bizNo)}`,
    undefined
  )

/** 收敛过程快照：操作记录 + 可靠任务当前值。排查异步履约用 */
export const queryConvergence = (bizNo: string) =>
  httpGet<ConvergenceResp>(`/api/benefit/convergence/${encodeURIComponent(bizNo)}`)

/**
 * 为支付通知取签名。
 *
 * ⚠️ 这是 mock 支付方的能力（/api/fault 下），**不是业务接口**。真实场景里签名由支付方
 * 算好随通知送来，端上永远不会调它。此处调用是因为本项目要在浏览器里模拟「支付方发通知」。
 * 《分阶段方案》§5.6 ⑥ 注明 /api/fault 全部端点 V3 必须下线。
 */
export const signPayNotify = (req: Omit<PayCallbackReq, 'sign'>) =>
  httpPost<PayNotifySignResp>('/api/fault/pay-notify/sign', req)

export const queryOrder = (bizNo: string) =>
  httpGet<QueryOrderResp>(`/api/benefit/order/${encodeURIComponent(bizNo)}`)

export const queryOrders = (params: QueryOrderPageParams = {}) =>
  httpGet<QueryOrderPageResp>(`/api/benefit/orders${toQuery({ ...params })}`)

export const querySku = (skuId: string) =>
  httpGet<QuerySkuResp>(`/api/benefit/sku/${encodeURIComponent(skuId)}`)

export const queryOpRecords = (bizNo: string) =>
  httpGet<OpRecordItem[]>(`/api/benefit/order/${encodeURIComponent(bizNo)}/op-records`)

/**
 * 模拟一次支付通知：先取签名，再带签名发回调。
 *
 * 两步封装在一起是因为它们必须成对 —— 分开写则每个调用点都要记得先签名，漏一处就是 4731。
 * 取签名失败时不发回调：没有签名的回调必然被拒，发出去只会多一条噪音日志。
 */
export async function simulatePayNotify(
  params: Omit<PayCallbackReq, 'sign'>
): Promise<ApiResult<PayCallbackResp>> {
  const signed = await signPayNotify(params)
  if (signed.kind !== 'ok') {
    // 原样透传失败类型：取签名失败同样分「确定失败」与「结果未知」
    return signed as Exclude<ApiResult<never>, { kind: 'ok' }>
  }
  return payCallback({ ...params, sign: signed.data.sign })
}

// ------------------------------------------------------------------
// 幂等键的可变部分
// ------------------------------------------------------------------

/**
 * 生成 clientReqNo。
 *
 * 幂等键 = userId + activityId + skuId + clientReqNo。同一次「购买意图」必须复用
 * 同一个值，重试才会命中幂等返回原单；换新值即建新单。故此函数只在用户发起新的
 * 购买动作时调用一次，不在每次 HTTP 重试时调用。
 */
/**
 * 模拟支付通知用的商户号。
 *
 * 必须与后端 `mp.pay.merchant-id`（application-local.yml: MCH_LOCAL_DEMO）一致 ——
 * 回调会逐字段比对商户号，不符即 1731 且**不推进任何状态**。
 *
 * 收在此处而非各页面各写一份：前端原先三个调用点各自硬编码 'M001'，与后端配置对不上，
 * 于是所有模拟支付一律 1731。而 1731 的文案是「支付金额或币种与应付不一致」，
 * 指向金额 —— 排查会先去核对金额，而金额一直是对的。
 *
 * 真实场景里这个值由支付方在通知报文里带来，端上不会自己填。此处是因为要在浏览器里
 * 模拟「支付方发通知」，与 signPayNotify 同属演示设施。
 */
export const MOCK_MERCHANT_ID = 'MCH_LOCAL_DEMO'

export const newClientReqNo = (): string =>
  'REQ' +
  Date.now().toString(36).toUpperCase() +
  Math.random().toString(36).slice(2, 6).toUpperCase()

/** 生成 notifySeq。真实场景由支付方携带，此处模拟支付通知才需要自造 */
export const newNotifySeq = (): string =>
  'NS' + Date.now().toString(36).slice(-6).toUpperCase() +
  Math.random().toString(36).slice(2, 4).toUpperCase()
