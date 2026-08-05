/**
 * 后端 DTO 的 TS 镜像。字段名与 mp-api-benefit 的 DTO 一一对应。
 *
 * 金额一律为「分」（整数），不使用浮点 —— 与《PRD》§2.2 一致。展示时才换算。
 */
import type {
  GrantStatus,
  ItemGrantStatus,
  OpType,
  PayStatus,
  RefundStatus,
} from './enums'

/** 统一响应壳。对应 mp-common 的 ApiResponse */
export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T | null
  traceId: string
}

/** 下单入参。clientReqNo 参与幂等键，重试时必须保持不变 */
export interface CreateTradeReq {
  userId: string
  activityId: string
  skuId: string
  clientReqNo: string
  /** V1 冻结为 1，传其他值后端返回 4001 */
  quantity: number
}

export interface CreateTradeResp {
  bizNo: string
  /** 支付单号。建单瞬间可能为 null —— 支付方尚未返回 */
  tradeNo: string | null
  payStatus: string
  /** 应付，分 */
  orderAmount: number
}

/** 支付回调入参。按 outTradeNo（= bizNo）定位主单，不用 tradeNo */
export interface PayCallbackReq {
  outTradeNo: string
  tradeNo: string
  /** 通知流水号，参与幂等键 tradeNo + '_' + notifySeq */
  notifySeq: string
  /** V1 仅接受 SUCCESS / FAILED */
  payStatus: 'SUCCESS' | 'FAILED'
  payAmount: number
  currency: string
  merchantId: string
}

export interface PayCallbackResp {
  /** 四分类原值，V1 恒为 SUCCESS */
  status: string
}

/** 履约明细项。注意用 ItemGrantStatus（无前缀），不是主单的 GrantStatus */
export interface FulfillmentItem {
  fulfillmentNo: string
  benefitItemId: string
  providerType: string
  providerOrderNo: string | null
  grantStatus: ItemGrantStatus
}

/** 订单详情。三条子状态线各自返回，展示态由前端派生、不落库 */
export interface QueryOrderResp {
  bizNo: string
  userId: string
  activityId: string
  skuId: string
  payStatus: PayStatus
  grantStatus: GrantStatus
  refundStatus: RefundStatus
  orderAmount: number
  payAmount: number | null
  tradeNo: string | null
  /** 下单时冻结的配置版本，履约与退款一律读快照 */
  configVersion: number
  fulfillments: FulfillmentItem[]
}

/** 订单列表行。不含履约明细 —— 列表不展示它，带上会让每行多一次关联查询 */
export interface OrderListItem {
  bizNo: string
  skuId: string
  activityId: string
  payStatus: PayStatus
  grantStatus: GrantStatus
  refundStatus: RefundStatus
  orderAmount: number
  payAmount: number | null
  tradeNo: string | null
  /** ISO 本地时间，如 2026-08-05T23:31:19.581 */
  createTime: string
}

export interface QueryOrderPageResp {
  items: OrderListItem[]
  /** 符合条件的总行数，不受分页影响 */
  total: number
  page: number
  size: number
}

export interface QueryOrderPageParams {
  userId?: string
  activityId?: string
  payStatus?: PayStatus
  grantStatus?: GrantStatus
  page?: number
  /** 后端上限 100，超出被收口 */
  size?: number
}

/** 权益项配置视图。与履约明细不同：这是「卖什么」，那是「发成了什么」 */
export interface BenefitItemView {
  benefitItemId: string
  benefitType: string
  providerType: string
  providerProductId: string
  /** 核心权益，发放失败时整单不可用 */
  core: boolean
  grantOrder: number | null
}

export interface QuerySkuResp {
  skuId: string
  activityId: string
  skuName: string
  skuType: string
  /** ON_SALE 之外应禁用下单入口 */
  saleStatus: string
  /** 划线价，分 */
  listPrice: number
  /** 售卖价，分。应付以服务端重算为准，此值仅供展示 */
  salePrice: number
  benefitPackageId: string
  packageVersion: number | null
  items: BenefitItemView[]
}

/**
 * 操作记录行。
 *
 * status（本地执行态 OpStatus）与 downstreamResult（下游四分类 RetStatus）分列，
 * 两者刻意拼写不同（FAILED vs FAIL），合并展示会掩盖「本地记为失败、下游实际成功」。
 * 类型用 string 而非枚举：这是排查视图，需如实回显库里的值。
 */
export interface OpRecordItem {
  opNo: string
  opType: OpType | string
  /** 至多一次的操作恒空串；可多次的取外部单号 */
  opSeq: string
  status: string
  downstreamResult: string | null
  retryCount: number | null
  errorCode: string | null
  outOrderNo: string | null
  parentOpNo: string | null
  createTime: string
  finishTime: string | null
}
