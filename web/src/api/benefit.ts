/**
 * 权益售卖接口。
 *
 * 这里只封装**后端真实存在**的 6 个端点。未实现的能力不在此文件里造假函数 ——
 * 见 src/api/notImplemented.ts 的说明。
 */
import { httpGet, httpPost, toQuery } from './http'
import type {
  CreateTradeReq,
  CreateTradeResp,
  OpRecordItem,
  PayCallbackReq,
  PayCallbackResp,
  QueryOrderPageParams,
  QueryOrderPageResp,
  QueryOrderResp,
  QuerySkuResp,
} from '@/contracts/dto'

export const createTrade = (req: CreateTradeReq) =>
  httpPost<CreateTradeResp>('/api/benefit/trade', req)

export const payCallback = (req: PayCallbackReq) =>
  httpPost<PayCallbackResp>('/api/benefit/pay-callback', req)

export const queryOrder = (bizNo: string) =>
  httpGet<QueryOrderResp>(`/api/benefit/order/${encodeURIComponent(bizNo)}`)

export const queryOrders = (params: QueryOrderPageParams = {}) =>
  httpGet<QueryOrderPageResp>(`/api/benefit/orders${toQuery({ ...params })}`)

export const querySku = (skuId: string) =>
  httpGet<QuerySkuResp>(`/api/benefit/sku/${encodeURIComponent(skuId)}`)

export const queryOpRecords = (bizNo: string) =>
  httpGet<OpRecordItem[]>(`/api/benefit/order/${encodeURIComponent(bizNo)}/op-records`)

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
export const newClientReqNo = (): string =>
  'REQ' +
  Date.now().toString(36).toUpperCase() +
  Math.random().toString(36).slice(2, 6).toUpperCase()

/** 生成 notifySeq。真实场景由支付方携带，此处模拟支付通知才需要自造 */
export const newNotifySeq = (): string =>
  'NS' + Date.now().toString(36).slice(-6).toUpperCase() +
  Math.random().toString(36).slice(2, 4).toUpperCase()
