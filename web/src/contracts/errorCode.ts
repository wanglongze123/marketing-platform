/**
 * 错误码。镜像 mp-common/.../ErrorCode.java，本项目不新造码。
 *
 * ⚠️ 分区决定处置方式，不是「非 0 即失败」：
 *
 *   1xxx  业务规则拒绝  —— 终态失败，不重试，可展示给用户
 *   4xxx  入参/凭证非法 —— 终态失败，不重试（通常是端侧 bug 或凭证过期）
 *   5xxx  系统异常      —— 结果未知，按查单收敛，**不得显示「失败」**
 *
 * 5xxx 的语义是「结果未知」：下游可能已经执行了。前端若显示「失败，请重试」，
 * 用户会重复下单。这条在《开发规范》里写明「前端展示与重试策略完全不同」。
 */

export const ERROR_CODE = {
  SUCCESS: 0,
  /** 价格不一致：凭证成交价 ≠ 服务端重算价。V2 引入比价后使用 */
  PRICE_MISMATCH: 1711,
  /** 库存不足。V2 引入 */
  STOCK_NOT_ENOUGH: 1712,
  /** 超出限购额度。V2 引入 */
  QUOTA_EXCEEDED: 1713,
  /** 支付金额/币种/商户不一致，触发 P0 告警且不改任何状态 */
  PAY_AMOUNT_MISMATCH: 1731,
  /** 必填参数缺失或取值非法 */
  INVALID_PARAM: 4001,
  /** 凭证签名非法或已过期。V2 引入 */
  INVALID_TOKEN: 4003,
  /** 下游超时/未知，映射为 RetStatus.UNKNOWN */
  DOWNSTREAM_UNKNOWN: 5001,
} as const

export type ErrorCodeValue = (typeof ERROR_CODE)[keyof typeof ERROR_CODE]

/** 面向用户的释义。措辞避免对 5xxx 说「失败」 */
export const ERROR_CODE_TEXT: Record<number, string> = {
  0: '成功',
  1711: '价格已变动，请刷新后重试',
  1712: '库存不足',
  1713: '已超出限购数量',
  1731: '支付金额或币种与应付不一致，订单状态未变更',
  4001: '请求参数不合法',
  4003: '凭证已过期，请刷新页面',
  5001: '处理结果确认中，请稍后查看订单状态',
}

/** 面向开发的释义，含该码的处置约定。用于调试台与开发者面板 */
export const ERROR_CODE_DEV_TEXT: Record<number, string> = {
  0: 'success',
  1711: '价格不一致（V2 起用）',
  1712: '库存不足（V2 起用）',
  1713: '超出限购（V2 起用）',
  1731: '支付金额/币种不一致 —— 不推进任何状态，触发 P0',
  4001: '入参缺失或非法',
  4003: '凭证非法或过期（V2 起用）',
  5001: '下游超时/未知 —— 按 UNKNOWN 查单收敛，不得判失败',
}

/** 1xxx：业务规则拒绝，终态，可展示原因给用户 */
export const isBusinessRejection = (code: number): boolean =>
  code >= 1000 && code < 2000

/** 4xxx：入参/凭证非法，终态，不重试 */
export const isInvalidRequest = (code: number): boolean =>
  code >= 4000 && code < 5000

/**
 * 5xxx：结果未知。
 *
 * 调用方必须按查单收敛处理，不得据此向用户报告失败。
 */
export const isUnknownResult = (code: number): boolean =>
  code >= 5000 && code < 6000
