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

/** 玩法类型。裂变后端尚未实现（mp-fission 目前只有 pom.xml） */
export const PLAY_TYPE = ['BENEFIT_SELL', 'FISSION'] as const
export type PlayType = (typeof PLAY_TYPE)[number]
