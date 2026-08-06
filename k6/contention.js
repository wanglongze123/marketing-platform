/**
 * 幂等键竞争压测：读出 L2 锁在 L3 负载侧的价值（《分阶段方案》§5.7 退出标准 15）。
 *
 * **与 seckill.js 压的不是同一件事。** 那里每个 VU 用独立的 clientReqNo，不存在
 * 幂等键竞争 —— 实测两组的 duplicateKey 与 conditionalUpdateMiss 都是 0，锁与
 * 不锁没有区别可比。锁减少的是「多个请求抢同一个业务对象」时走到 L3 的次数，
 * 那需要专门构造。
 *
 * 本脚本让 N 个 VU 反复提交**同一批** clientReqNo 与同一条支付通知：
 *
 *   - 开锁组：并发被串行化在锁上，第二个请求进临界区时第一笔已提交，
 *             走「幂等命中返回原单」，不撞唯一索引
 *   - 去锁组：多个请求同时插入，撞 uk_idempotent → duplicateKey 上升；
 *             多条通知同时推进 → conditionalUpdateMiss 上升
 *
 * 正确性两组仍应完全一致 —— 差的只是「走到 L3 的次数」。
 */
import http from 'k6/http'
import { check } from 'k6'
import { Counter } from 'k6/metrics'

const BASE = __ENV.BASE || 'http://localhost:8080'
const RUN_ID = __ENV.RUN_ID || 'contend'

const ACTIVITY_ID = 'ACT_DEMO_001'
const SKU_ID = 'SKU_DEMO_001'

/** 竞争的业务对象数。取小值才压得出竞争 —— 值越大越接近 seckill.js 的形态 */
const KEYS = Number(__ENV.KEYS || 10)

const created = new Counter('biz_order_created')
const lockBusy = new Counter('biz_lock_busy')
const unexpected = new Counter('biz_unexpected')

export const options = {
  scenarios: {
    contention: {
      executor: 'shared-iterations',
      vus: 100,
      iterations: 500,
      maxDuration: '2m',
    },
  },
  thresholds: {
    biz_unexpected: ['count==0'],
  },
}

export default function () {
  // 关键：按迭代序号取模，让多个 VU 落到同一个 clientReqNo 上。
  // 同一个键被反复提交，才会产生幂等竞争
  const slot = __ITER % KEYS
  const userId = `U_${RUN_ID}_${slot}`
  const clientReqNo = `REQ_${RUN_ID}_${slot}`

  const consultRes = http.post(
    `${BASE}/api/benefit/consult`,
    JSON.stringify({ userId, activityId: ACTIVITY_ID, skuId: SKU_ID }),
    { headers: { 'Content-Type': 'application/json' } },
  )
  if (consultRes.status !== 200 || consultRes.json().code !== 0) {
    unexpected.add(1)
    return
  }
  const token = consultRes.json().data.consultToken

  const tradeRes = http.post(
    `${BASE}/api/benefit/trade`,
    JSON.stringify({
      userId,
      activityId: ACTIVITY_ID,
      skuId: SKU_ID,
      clientReqNo,
      quantity: 1,
      consultToken: token,
    }),
    { headers: { 'Content-Type': 'application/json' } },
  )

  const code = String(tradeRes.json().code)
  if (code === '0') {
    created.add(1)
  } else if (code === '5002') {
    lockBusy.add(1)
  } else if (code === '1712' || code === '1713') {
    // 库存不足 / 超限：本场景库存给足，出现即说明 KEYS 设得太大或库存没重置
    unexpected.add(1)
  } else {
    unexpected.add(1)
  }

  check(tradeRes, {
    'code is 0 or 5002': () => ['0', '5002'].includes(code),
  })
}
