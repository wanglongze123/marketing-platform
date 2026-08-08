/**
 * 后端枚举镜像。逐个对应 mp-common/src/main/java/com/mp/common/enums/*.java。
 *
 * ⚠️ 五个枚举各自独立，不得合并、不得对齐取值。
 *
 * 后端 ShapeFreezeTest 断言 GrantStatus 与 ItemGrantStatus 的终态「完全不重叠」：
 * 两张表的列都叫 grant_status，但一个是主单汇总态（带 GRANT_ 前缀）、一个是履约明细
 * 单项态（无前缀），前缀是唯一的区分手段。前端若合成一张状态映射表，把明细的
 * SUCCESS 当主单状态渲染，页面会显示「已到账」而主单其实还在 GRANTING。
 *
 * 同理 RetStatus.FAIL（下游回报）与 OpStatus.FAILED（本地判断）刻意拼写不同，
 * 前端不得归一 —— 合并会掩盖「本地记为失败、下游实际成功」这类差异。
 *
 * 本文件与 Java 源码的一致性由 scripts/check-enums.mjs 机械校验，不靠人核对。
 */

/** 支付子状态。对应 PayStatus.java */
export const PAY_STATUS = [
  'WAIT_PAY',
  'CLOSING',
  'PAY_SUCCESS',
  'PAY_FAILED',
  'CLOSED',
] as const
export type PayStatus = (typeof PAY_STATUS)[number]

/** 主单汇总发放态，终态带 GRANT_ 前缀。对应 GrantStatus.java */
export const GRANT_STATUS = [
  'NOT_START',
  'GRANTING',
  'GRANT_SUCCESS',
  'GRANT_FAILED',
  'GRANT_UNKNOWN',
] as const
export type GrantStatus = (typeof GRANT_STATUS)[number]

/** 履约明细单项发放态，无前缀。对应 ItemGrantStatus.java */
export const ITEM_GRANT_STATUS = [
  'NOT_START',
  'GRANTING',
  'SUCCESS',
  'FAILED',
  'UNKNOWN',
] as const
export type ItemGrantStatus = (typeof ITEM_GRANT_STATUS)[number]

/** 退款子状态。V3 逆向链路引入后才会出现非 NONE 取值。对应 RefundStatus.java */
export const REFUND_STATUS = [
  'NONE',
  'REVOKING',
  'REVOKE_FAILED',
  'REFUNDING',
  'REFUND_SUCCESS',
  'REFUND_FAILED',
] as const
export type RefundStatus = (typeof REFUND_STATUS)[number]

/** 下游结果四分类。对应 RetStatus.java —— 失败值是 FAIL，不是 FAILED */
export const RET_STATUS = ['SUCCESS', 'FAIL', 'PROCESSING', 'UNKNOWN'] as const
export type RetStatus = (typeof RET_STATUS)[number]

/** 操作记录本地执行态。对应 OpStatus.java —— 失败值是 FAILED，不是 FAIL */
export const OP_STATUS = [
  'INIT',
  'PROCESSING',
  'SUCCESS',
  'FAILED',
  'UNKNOWN',
] as const
export type OpStatus = (typeof OP_STATUS)[number]

/** 操作类型。对应 OpType.java（顺序与 Java 一致：先「至多一次」再「可多次」） */
export const OP_TYPE = [
  'CREATE_TRADE',
  'GRANT_BENEFIT',
  'CLOSE_ORDER',
  'REVOKE_BENEFIT',
  'CREATE_REFUND',
  'PAY_CALLBACK',
  'REFUND_CALLBACK',
  'MANUAL_REPAIR',
  'RECONCILE',
] as const
export type OpType = (typeof OP_TYPE)[number]

/** 玩法类型 */
export const PLAY_TYPE = ['BENEFIT_SELL', 'FISSION'] as const
export type PlayType = (typeof PLAY_TYPE)[number]

// ------------------------------------------------------------------
// V2 / V3 后端引入的枚举
// ------------------------------------------------------------------

/** 活动状态机。对应 ActivityStatus.java */
export const ACTIVITY_STATUS = [
  'DRAFT',
  'SCHEDULED',
  'ONLINE',
  'PAUSED',
  'ENDED',
] as const
export type ActivityStatus = (typeof ACTIVITY_STATUS)[number]

/** 可靠任务执行态。对应 TaskStatus.java —— DEAD 是死信，需人工介入 */
export const TASK_STATUS = ['PENDING', 'DOING', 'DONE', 'DEAD'] as const
export type TaskStatus = (typeof TASK_STATUS)[number]

/** 任务类型。对应 TaskType.java。QUERY_* 是查单收敛任务，与主任务成对 */
export const TASK_TYPE = [
  'GRANT',
  'QUERY_GRANT',
  'CLOSE_ORDER',
  'QUERY_CLOSE',
  'STOCK_CONSUME',
  'STOCK_RELEASE',
  'QUOTA_RELEASE',
  'REFUND',
  'QUERY_REFUND',
  'REVOKE',
] as const
export type TaskType = (typeof TASK_TYPE)[number]

/** 裂变关系状态机。对应 RelationStatus.java */
export const RELATION_STATUS = [
  'INVITED',
  'CONNECTED',
  'JOINED',
  'DONE',
  'EXPIRED',
  'CANCEL',
] as const
export type RelationStatus = (typeof RELATION_STATUS)[number]

/** 库存占用态。对应 StockStatus.java */
export const STOCK_STATUS = ['NONE', 'LOCKED', 'CONSUMED', 'RELEASED'] as const
export type StockStatus = (typeof STOCK_STATUS)[number]

/** 限购额度占用态。对应 QuotaStatus.java —— 无 CONSUMED，额度不随发放消耗 */
export const QUOTA_STATUS = ['NONE', 'LOCKED', 'RELEASED'] as const
export type QuotaStatus = (typeof QUOTA_STATUS)[number]

/**
 * 资格决策原因码。对应 QualifyReason.java。
 *
 * CONTEXT_UNAVAILABLE 与其余不同：它是<b>系统故障</b>（映射 5201）而非「不符合条件」，
 * 前端不得当作「你没资格」展示 —— 展示与重试策略完全不同。
 */
export const QUALIFY_REASON = [
  'PASS',
  'ACTIVITY_UNAVAILABLE',
  'CITY_NOT_MATCH',
  'CHANNEL_NOT_MATCH',
  'CROWD_NOT_MATCH',
  'RISK_REJECTED',
  'CONTEXT_UNAVAILABLE',
] as const
export type QualifyReason = (typeof QUALIFY_REASON)[number]
