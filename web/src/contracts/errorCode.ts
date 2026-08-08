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

  // ---- 1xxx 业务规则拒绝 ----
  /** 资格决策未通过 */
  NOT_QUALIFIED: 1201,
  /** 无可参与的活动 */
  NO_AVAILABLE_ACTIVITY: 1601,
  /** 已有进行中的轮次 */
  GROUP_ALREADY_RUNNING: 1602,
  /** 无进行中的轮次 */
  GROUP_NOT_RUNNING: 1603,
  /** 师傅同时是徒弟，成环 */
  SPONSOR_IS_FOLLOWER: 1614,
  /** 价格不一致：凭证成交价 ≠ 服务端重算价 */
  PRICE_MISMATCH: 1711,
  /** 库存不足 */
  STOCK_NOT_ENOUGH: 1712,
  /** 超出限购额度 */
  QUOTA_EXCEEDED: 1713,
  /** 支付金额/币种/商户不一致，触发 P0 告警且不改任何状态 */
  PAY_AMOUNT_MISMATCH: 1731,
  /** 已支付的单拒绝关闭 */
  ORDER_ALREADY_PAID: 1741,

  // ---- 4xxx 入参/凭证非法 ----
  /** 必填参数缺失或取值非法 */
  INVALID_PARAM: 4001,
  /** 咨询凭证非法、缺失或已过期 */
  INVALID_TOKEN: 4003,
  /** 活动发布校验不通过 */
  PUBLISH_CHECK_FAILED: 4101,
  /** 活动有效期非法 */
  INVALID_ACTIVITY_PERIOD: 4102,
  /** 活动状态迁移非法 */
  INVALID_STATUS_TRANSITION: 4103,
  /** 裂变轮次时长超限 */
  GROUP_PERIOD_TOO_LONG: 4602,
  /** 支付通知验签失败 */
  PAY_NOTIFY_SIGN_INVALID: 4731,

  // ---- 5xxx 系统异常，结果未知 ----
  /** 下游超时/未知，映射为 RetStatus.UNKNOWN */
  DOWNSTREAM_UNKNOWN: 5001,
  /** 并发冲突，可原样重试 */
  CONCURRENT_CONFLICT: 5002,
  /** 资格上下文不可用 —— 是系统故障，不是「没资格」 */
  QUALIFY_CONTEXT_ERROR: 5201,
  /** 裂变查询故障 */
  FISSION_QUERY_ERROR: 5601,
} as const

export type ErrorCodeValue = (typeof ERROR_CODE)[keyof typeof ERROR_CODE]

/** 面向用户的释义。措辞避免对 5xxx 说「失败」 */
export const ERROR_CODE_TEXT: Record<number, string> = {
  0: '成功',
  1201: '很抱歉，你暂不符合参与条件',
  1601: '当前没有可参与的活动',
  1602: '你已有进行中的活动轮次',
  1603: '没有进行中的活动轮次',
  1614: '不能邀请自己的邀请人',
  1711: '价格已变动，请刷新后重试',
  1712: '库存不足',
  1713: '已超出限购数量',
  1731: '支付金额或币种与应付不一致，订单状态未变更',
  1741: '订单已支付，无法关闭',
  4001: '请求参数不合法',
  4003: '页面信息已过期，请刷新后重试',
  4101: '活动发布校验未通过',
  4102: '活动有效期不合法',
  4103: '当前状态不支持该操作',
  4602: '活动轮次时长超出上限',
  4731: '支付通知验签失败，订单状态未变更',
  5001: '处理结果确认中，请稍后查看订单状态',
  5002: '当前操作繁忙，请稍后重试',
  5201: '暂时无法校验参与资格，请稍后重试',
  5601: '暂时无法查询，请稍后重试',
}

/** 面向开发的释义，含该码的处置约定。用于调试台与开发者面板 */
export const ERROR_CODE_DEV_TEXT: Record<number, string> = {
  0: 'success',
  1201: '资格决策未通过，reason 见 QualifyReason',
  1601: '无可参与的活动',
  1602: '已有 RUNNING 轮次，active_flag 部分唯一索引拦下',
  1603: '无 RUNNING 轮次',
  1614: '师傅同时是徒弟 —— 关系成环',
  1711: '凭证成交价 ≠ 服务端重算价，拒绝且不留单据',
  1712: '库存不足，DB 原子扣减失败',
  1713: '超出限购额度',
  1731: '支付金额/币种不一致 —— 不推进任何状态，触发 P0',
  1741: '已支付的单拒绝关闭（BR-B-16）',
  4001: '入参缺失或非法',
  4003: '咨询凭证缺失/验签失败/已过期 —— 须先调 POST /consult',
  4101: '活动发布六项校验未过（BR-C-04）',
  4102: '有效期非法（endTime ≤ startTime）',
  4103: '活动状态机不允许此迁移',
  4602: '轮次时长超上限',
  4731: '支付通知验签失败 —— 手工 curl 须先调 /api/fault/pay-notify/sign 取签名',
  5001: '下游超时/未知 —— 按 UNKNOWN 查单收敛，不得判失败',
  5002: '并发冲突（锁竞争/版本冲突）—— 可原样重试',
  5201: '资格上下文不可用 —— 系统故障，不是「不符合条件」，两者展示与重试策略不同',
  5601: '裂变查询故障',
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
