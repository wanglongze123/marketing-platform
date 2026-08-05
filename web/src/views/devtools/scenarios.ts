/**
 * 场景回归。每步声明预期并断言，跑完给通过/失败。
 *
 * 迁自旧 console.html —— 它是目前唯一能一键验证后端正确性的东西，不能在换前端时丢掉。
 * 相比旧版新增：只读端点的场景（列表分页、非法枚举、只读性）。
 */
import {
  createTrade,
  newClientReqNo,
  newNotifySeq,
  payCallback,
  queryOpRecords,
  queryOrder,
  queryOrders,
  querySku,
} from '@/api/benefit'
import { SEED_ACTIVITY_ID, SEED_SKU_ID } from '@/stores/session'
import { ERROR_CODE } from '@/contracts/errorCode'
import type { ApiResult } from '@/api/http'

export interface StepResult {
  label: string
  state: 'running' | 'pass' | 'fail'
  message?: string
  /** 补充说明，解释这一步为什么这样断言 */
  note?: string
}

export interface Ctx {
  step<T>(label: string, fn: () => Promise<T>, note?: string): Promise<T>
  assert(cond: boolean, message: string): void
  userId: string
}

export interface Scenario {
  id: string
  name: string
  desc: string
  run(ctx: Ctx): Promise<void>
}

class AssertError extends Error {}

/** 断言失败时抛出，由执行器捕获并标记该步 */
export const fail = (msg: string): never => {
  throw new AssertError(msg)
}

/** 取 ok 的 data，非 ok 直接断言失败并带上 kind 与 message */
function expectOk<T>(r: ApiResult<T>, what: string): T {
  if (r.kind !== 'ok') {
    fail(`${what} 未成功：kind=${r.kind} code=${'code' in r ? r.code : '—'} ${'message' in r ? r.message : ''}`)
  }
  return (r as { kind: 'ok'; data: T }).data
}

/** 断言是 rejected 且 code 匹配 */
function expectRejected<T>(r: ApiResult<T>, code: number, what: string) {
  if (r.kind !== 'rejected') {
    fail(`${what} 期望 rejected(${code})，实际 kind=${r.kind}`)
  }
  const actual = (r as { code: number }).code
  if (actual !== code) fail(`${what} 期望 code=${code}，实际 ${actual}`)
}

const trade = (userId: string, clientReqNo: string, quantity = 1) =>
  createTrade({
    userId,
    activityId: SEED_ACTIVITY_ID,
    skuId: SEED_SKU_ID,
    clientReqNo,
    quantity,
  })

const callback = (
  bizNo: string,
  amount: number,
  opts: { payStatus?: 'SUCCESS' | 'FAILED'; currency?: string } = {}
) =>
  payCallback({
    outTradeNo: bizNo,
    tradeNo: `PAY_${bizNo}`,
    notifySeq: newNotifySeq(),
    payStatus: opts.payStatus ?? 'SUCCESS',
    payAmount: amount,
    currency: opts.currency ?? 'CNY',
    merchantId: 'M001',
  })

export const SCENARIOS: Scenario[] = [
  {
    id: 'happy',
    name: '正常全链路',
    desc: '下单 → 回调 SUCCESS → 两供应方各发一次 → GRANT_SUCCESS',
    async run(c) {
      const created = await c.step('下单，期望成功且 payStatus=WAIT_PAY', async () => {
        const d = expectOk(await trade(c.userId, newClientReqNo()), '下单')
        c.assert(d.payStatus === 'WAIT_PAY', `payStatus=${d.payStatus}`)
        c.assert(!!d.tradeNo, 'tradeNo 未回填')
        return d
      })

      await c.step('回调 SUCCESS，期望受理', async () => {
        expectOk(await callback(created.bizNo, created.orderAmount), '支付回调')
      })

      const order = await c.step(
        '查单，期望 PAY_SUCCESS + GRANT_SUCCESS',
        async () => {
          const o = expectOk(await queryOrder(created.bizNo), '查单')
          c.assert(o.payStatus === 'PAY_SUCCESS', `payStatus=${o.payStatus}`)
          c.assert(o.grantStatus === 'GRANT_SUCCESS', `grantStatus=${o.grantStatus}`)
          return o
        }
      )

      await c.step(
        '期望 2 条履约明细、跨 2 个供应方、均已回填下游单号',
        async () => {
          const ff = order.fulfillments
          c.assert(ff.length === 2, `明细 ${ff.length} 条`)
          const providers = new Set(ff.map((f) => f.providerType))
          c.assert(providers.size === 2, `供应方 ${[...providers].join(',')}`)
          c.assert(
            ff.every((f) => f.grantStatus === 'SUCCESS'),
            '存在非 SUCCESS 明细'
          )
          c.assert(ff.every((f) => !!f.providerOrderNo), '存在未回填下游单号的明细')
        },
        '两项刻意分属不同供应方 —— 只有存在两组时分组逻辑才会被真实走到'
      )
    },
  },

  {
    id: 'idempotent',
    name: '幂等重放',
    desc: '同一 clientReqNo 再下一次，期望返回同一 bizNo',
    async run(c) {
      const reqNo = newClientReqNo()
      const first = await c.step('首次下单', async () =>
        expectOk(await trade(c.userId, reqNo), '首次下单')
      )
      await c.step(
        '同 clientReqNo 重放，期望同一 bizNo',
        async () => {
          const again = expectOk(await trade(c.userId, reqNo), '重放下单')
          c.assert(
            again.bizNo === first.bizNo,
            `bizNo ${first.bizNo} → ${again.bizNo}`
          )
        },
        '幂等键 = userId + activityId + skuId + clientReqNo，由 uk_idempotent 保证'
      )
    },
  },

  {
    id: 'duplicateCallback',
    name: '重复支付回调',
    desc: '已成功单再收一次通知，期望状态与履约行数都不变',
    async run(c) {
      const created = await c.step('下单 + 首次回调', async () => {
        const d = expectOk(await trade(c.userId, newClientReqNo()), '下单')
        expectOk(await callback(d.bizNo, d.orderAmount), '首次回调')
        return d
      })

      const before = await c.step('记录首次结果', async () => {
        const o = expectOk(await queryOrder(created.bizNo), '查单')
        c.assert(o.grantStatus === 'GRANT_SUCCESS', `grantStatus=${o.grantStatus}`)
        return o
      })

      await c.step(
        '再发一次 SUCCESS 通知（新 notifySeq），期望仍被受理',
        async () => {
          expectOk(await callback(created.bizNo, created.orderAmount), '重复回调')
        },
        '重复通知不是错误：affected_rows=0 直接 ACK，不抛异常、不重试、不打 ERROR'
      )

      await c.step(
        '期望状态与履约行数不变（未重复发放）',
        async () => {
          const after = expectOk(await queryOrder(created.bizNo), '查单')
          c.assert(after.payStatus === before.payStatus, `payStatus 变了`)
          c.assert(after.grantStatus === before.grantStatus, `grantStatus 变了`)
          c.assert(
            after.fulfillments.length === before.fulfillments.length,
            `履约行数 ${before.fulfillments.length} → ${after.fulfillments.length}`
          )
        },
        '第二次被主单条件更新的前置状态拦下；履约明细走 upsert 不新增行'
      )
    },
  },

  {
    id: 'amountMismatch',
    name: '支付金额不符',
    desc: '回调金额改 1 分，期望 1731 且不推进任何状态',
    async run(c) {
      const created = await c.step('下单', async () =>
        expectOk(await trade(c.userId, newClientReqNo()), '下单')
      )
      await c.step('回调 payAmount=1，期望 code=1731', async () => {
        expectRejected(
          await callback(created.bizNo, 1),
          ERROR_CODE.PAY_AMOUNT_MISMATCH,
          '金额不符回调'
        )
      })
      await c.step(
        '期望 payStatus 仍 WAIT_PAY、无履约记录',
        async () => {
          const o = expectOk(await queryOrder(created.bizNo), '查单')
          c.assert(o.payStatus === 'WAIT_PAY', `payStatus=${o.payStatus}`)
          c.assert(o.grantStatus === 'NOT_START', `grantStatus=${o.grantStatus}`)
          c.assert(o.fulfillments.length === 0, '产生了履约记录')
        },
        '仅验签不足以证明金额正确 —— 校验失败一律不推进状态'
      )
    },
  },

  {
    id: 'currencyMismatch',
    name: '币种不符',
    desc: '回调 currency=USD，期望同样 1731',
    async run(c) {
      const created = await c.step('下单', async () =>
        expectOk(await trade(c.userId, newClientReqNo()), '下单')
      )
      await c.step('回调 currency=USD，期望 code=1731', async () => {
        expectRejected(
          await callback(created.bizNo, created.orderAmount, { currency: 'USD' }),
          ERROR_CODE.PAY_AMOUNT_MISMATCH,
          '币种不符回调'
        )
      })
      await c.step('期望状态未变', async () => {
        const o = expectOk(await queryOrder(created.bizNo), '查单')
        c.assert(o.payStatus === 'WAIT_PAY', `payStatus=${o.payStatus}`)
      })
    },
  },

  {
    id: 'payFailed',
    name: '支付失败',
    desc: '回调 FAILED，期望 PAY_FAILED 且不触发履约',
    async run(c) {
      const created = await c.step('下单', async () =>
        expectOk(await trade(c.userId, newClientReqNo()), '下单')
      )
      await c.step('回调 FAILED，期望受理', async () => {
        expectOk(
          await callback(created.bizNo, created.orderAmount, { payStatus: 'FAILED' }),
          '失败回调'
        )
      })
      await c.step(
        '期望 PAY_FAILED + grantStatus 仍 NOT_START',
        async () => {
          const o = expectOk(await queryOrder(created.bizNo), '查单')
          c.assert(o.payStatus === 'PAY_FAILED', `payStatus=${o.payStatus}`)
          c.assert(o.grantStatus === 'NOT_START', `grantStatus=${o.grantStatus}`)
          c.assert(o.fulfillments.length === 0, '产生了履约记录')
        },
        '仅推进到 PAY_SUCCESS 才触发履约'
      )
    },
  },

  {
    id: 'rejections',
    name: '入参拒绝',
    desc: 'quantity≠1、活动/SKU/订单不存在，期望均 4001',
    async run(c) {
      await c.step(
        'quantity=3，期望 4001',
        async () => {
          expectRejected(
            await trade(c.userId, newClientReqNo(), 3),
            ERROR_CODE.INVALID_PARAM,
            'quantity=3'
          )
        },
        'V1 冻结 quantity=1，显式拒绝而非默默忽略 —— 否则付一份钱得一份权益且无报错'
      )
      await c.step('活动不存在，期望 4001', async () => {
        expectRejected(
          await createTrade({
            userId: c.userId,
            activityId: 'ACT_NOT_EXIST',
            skuId: SEED_SKU_ID,
            clientReqNo: newClientReqNo(),
            quantity: 1,
          }),
          ERROR_CODE.INVALID_PARAM,
          '活动不存在'
        )
      })
      await c.step('SKU 不存在，期望 4001', async () => {
        expectRejected(
          await createTrade({
            userId: c.userId,
            activityId: SEED_ACTIVITY_ID,
            skuId: 'SKU_NOT_EXIST',
            clientReqNo: newClientReqNo(),
            quantity: 1,
          }),
          ERROR_CODE.INVALID_PARAM,
          'SKU 不存在'
        )
      })
      await c.step('查不存在的订单，期望 4001', async () => {
        expectRejected(
          await queryOrder(`BZ_NOT_EXIST_${Date.now()}`),
          ERROR_CODE.INVALID_PARAM,
          '订单不存在'
        )
      })
      await c.step('查不存在的 SKU 详情，期望 4001', async () => {
        expectRejected(await querySku('SKU_NOT_EXIST'), ERROR_CODE.INVALID_PARAM, 'SKU 详情')
      })
    },
  },

  {
    id: 'pagination',
    name: '列表分页',
    desc: '造 3 单取 size=2，期望分页真实生效',
    async run(c) {
      const user = `${c.userId}_pg${Date.now().toString(36).slice(-4)}`
      await c.step('用独立 userId 造 3 单', async () => {
        for (let i = 0; i < 3; i++) {
          expectOk(await trade(user, newClientReqNo()), `第 ${i + 1} 单`)
        }
      })
      await c.step(
        'size=2，期望 items=2 而 total=3',
        async () => {
          const p = expectOk(await queryOrders({ userId: user, page: 1, size: 2 }), '第一页')
          c.assert(p.items.length === 2, `items=${p.items.length}`)
          c.assert(p.total === 3, `total=${p.total}`)
        },
        '未注册 PaginationInnerInterceptor 时 selectPage 会返回全表且 total 恒 0'
      )
      await c.step('第二页期望 1 条且与第一页不重复', async () => {
        const p1 = expectOk(await queryOrders({ userId: user, page: 1, size: 2 }), '第一页')
        const p2 = expectOk(await queryOrders({ userId: user, page: 2, size: 2 }), '第二页')
        c.assert(p2.items.length === 1, `第二页 ${p2.items.length} 条`)
        const overlap = p2.items.filter((b) => p1.items.some((a) => a.bizNo === b.bizNo))
        c.assert(overlap.length === 0, '两页出现重复行')
      })
      await c.step(
        'size=100000 期望被收口到 100',
        async () => {
          const p = expectOk(await queryOrders({ userId: user, size: 100000 }), '超大 size')
          c.assert(p.size === 100, `size=${p.size}`)
        },
        '上限兜底，不信任调用方传值'
      )
    },
  },

  {
    id: 'enumFilter',
    name: '状态筛选枚举',
    desc: '主单发放态只接受带 GRANT_ 前缀的取值',
    async run(c) {
      await c.step('grantStatus=NOT_START 期望成功', async () => {
        expectOk(await queryOrders({ userId: c.userId, grantStatus: 'NOT_START' }), '合法取值')
      })
      await c.step(
        'grantStatus=SUCCESS 期望 4001',
        async () => {
          // SUCCESS 属 ItemGrantStatus，不是主单枚举的取值。
          // 绕过 TS 类型检查故意传错，验证后端会拒绝
          const r = await queryOrders({
            userId: c.userId,
            grantStatus: 'SUCCESS' as never,
          })
          expectRejected(r, ERROR_CODE.INVALID_PARAM, '明细态当主单态')
        },
        '两张表的列都叫 grant_status 但取值不同，串用必须被发现而非静默返回空列表'
      )
      await c.step(
        'payStatus=NOT_A_STATUS 期望 4001',
        async () => {
          const r = await queryOrders({ userId: c.userId, payStatus: 'NOT_A_STATUS' as never })
          expectRejected(r, ERROR_CODE.INVALID_PARAM, '非法枚举')
        },
        '非法值拒绝而非当「查不到」回空列表 —— 后者会让拼错的枚举名被误读成「没有订单」'
      )
    },
  },

  {
    id: 'readOnly',
    name: '只读接口无副作用',
    desc: '查询前后状态、操作记录、履约行数均不变',
    async run(c) {
      const created = await c.step('下单 + 回调 SUCCESS', async () => {
        const d = expectOk(await trade(c.userId, newClientReqNo()), '下单')
        expectOk(await callback(d.bizNo, d.orderAmount), '回调')
        return d
      })

      const before = await c.step('记录基线', async () => {
        const o = expectOk(await queryOrder(created.bizNo), '查单')
        const ops = expectOk(await queryOpRecords(created.bizNo), '操作记录')
        c.assert(o.fulfillments.length === 2, `履约 ${o.fulfillments.length} 条`)
        c.assert(ops.length >= 3, `操作记录 ${ops.length} 条`)
        return { grant: o.grantStatus, ff: o.fulfillments.length, ops: ops.length }
      })

      await c.step(
        '每个只读端点各调两次后，基线不变',
        async () => {
          for (let i = 0; i < 2; i++) {
            await queryOrders({ userId: c.userId })
            await querySku(SEED_SKU_ID)
            await queryOpRecords(created.bizNo)
            await queryOrder(created.bizNo)
          }
          const o = expectOk(await queryOrder(created.bizNo), '查单')
          const ops = expectOk(await queryOpRecords(created.bizNo), '操作记录')
          c.assert(o.grantStatus === before.grant, 'grantStatus 变了')
          c.assert(o.fulfillments.length === before.ff, '履约行数变了')
          c.assert(ops.length === before.ops, `操作记录行数 ${before.ops} → ${ops.length}`)
        },
        '只读接口若意外落操作记录，会污染幂等键空间与对账口径'
      )
    },
  },

  {
    id: 'opRecords',
    name: '操作记录分列',
    desc: '本地执行态与下游四分类分列返回，不合并',
    async run(c) {
      const created = await c.step('下单 + 回调', async () => {
        const d = expectOk(await trade(c.userId, newClientReqNo()), '下单')
        expectOk(await callback(d.bizNo, d.orderAmount), '回调')
        return d
      })
      await c.step(
        'GRANT_BENEFIT 记录的 status=SUCCESS 且 downstreamResult=SUCCESS',
        async () => {
          const ops = expectOk(await queryOpRecords(created.bizNo), '操作记录')
          const grant = ops.find((r) => r.opType === 'GRANT_BENEFIT')
          c.assert(!!grant, '缺少 GRANT_BENEFIT 记录')
          c.assert(grant!.status === 'SUCCESS', `status=${grant!.status}`)
          c.assert(
            grant!.downstreamResult === 'SUCCESS',
            `downstreamResult=${grant!.downstreamResult}`
          )
        },
        'status 是本地判断（OpStatus.SUCCESS），downstreamResult 是下游回报（RetStatus.SUCCESS）。合并会掩盖「本地记为失败、下游实际成功」'
      )
      await c.step('三类操作记录齐全', async () => {
        const ops = expectOk(await queryOpRecords(created.bizNo), '操作记录')
        for (const t of ['CREATE_TRADE', 'PAY_CALLBACK', 'GRANT_BENEFIT']) {
          c.assert(ops.some((r) => r.opType === t), `缺少 ${t}`)
        }
      })
      await c.step('查不存在单的操作记录，期望 4001', async () => {
        expectRejected(
          await queryOpRecords(`BZ_NOT_EXIST_${Date.now()}`),
          ERROR_CODE.INVALID_PARAM,
          '不存在的单'
        )
      })
    },
  },
]
