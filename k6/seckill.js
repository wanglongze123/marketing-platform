/**
 * 秒杀压测：500 VU 瞬时抢 100 库存（PRD AC-03、《分阶段方案》§5.7 退出标准 14）。
 *
 * 验的是**正确性在压力下不破**，不是吞吐量。判据只有一条：
 *
 *     售出数 === 库存总量，且可售余量 === 0、不为负
 *
 * 超卖的失效形态恰恰是「全部成功」—— 每个请求都拿到订单、都没有异常，只是卖出去的
 * 比有的多。故 k6 内的 check 只能证明「接口没报错」，真正的判据在压测后查库
 * （见 verify.sql），两者缺一不可。
 *
 * 跑法见 k6/README.md。
 */
import http from 'k6/http'
import { check } from 'k6'
import { Counter } from 'k6/metrics'

const BASE = __ENV.BASE || 'http://localhost:8080'

/** 与 seed 数据一致（V1090 / V1190） */
const ACTIVITY_ID = 'ACT_DEMO_001'
const SKU_ID = 'SKU_DEMO_001'

/**
 * 本轮的标记，用于压测后按 client_req_no 前缀圈定数据。
 *
 * 不用时间戳做幂等键的一部分——那会让「重跑一次」与「上一轮的残留」混在一起。
 * 每轮一个 RUN_ID，查库时按它过滤，两轮数据互不干扰。
 */
const RUN_ID = __ENV.RUN_ID || 'k6run'

// 分类计数：光看 http_req_failed 分不清「库存不足」与「系统故障」——
// 前者是本压测的预期结果（500 个抢 100 个，必然有 400 个抢不到）
const soldOut = new Counter('biz_stock_not_enough')
const created = new Counter('biz_order_created')
const lockBusy = new Counter('biz_lock_busy')
const unexpected = new Counter('biz_unexpected')

export const options = {
  scenarios: {
    seckill: {
      // shared-iterations：500 个 VU 共抢 500 次，模拟「瞬时涌入」而非持续加压。
      // 用 constant-arrival-rate 会把请求摊平到时间轴上，压不出并发峰值
      executor: 'shared-iterations',
      vus: 500,
      iterations: 500,
      maxDuration: '2m',
    },
  },
  thresholds: {
    // 系统故障必须为 0。业务拒绝（库存不足）不算故障，故单独计数而非用 http_req_failed
    biz_unexpected: ['count==0'],
  },
}

export default function () {
  // 每个 VU 一个独立用户：限购是按用户维度的，共用用户会让第 3 单起全被限购挡下，
  // 压的就不是库存了
  const userId = `U_${RUN_ID}_${__VU}_${__ITER}`

  // ① 预咨询拿凭证。PR-4 之后 createTrade 必须带 consultToken
  const consultRes = http.post(
    `${BASE}/api/benefit/consult`,
    JSON.stringify({ userId, activityId: ACTIVITY_ID, skuId: SKU_ID }),
    { headers: { 'Content-Type': 'application/json' } },
  )
  if (consultRes.status !== 200) {
    unexpected.add(1)
    return
  }
  const consultBody = consultRes.json()
  if (consultBody.code !== 0) {
    unexpected.add(1)
    return
  }
  const token = consultBody.data.consultToken

  // ② 下单
  const tradeRes = http.post(
    `${BASE}/api/benefit/trade`,
    JSON.stringify({
      userId,
      activityId: ACTIVITY_ID,
      skuId: SKU_ID,
      clientReqNo: `REQ_${RUN_ID}_${__VU}_${__ITER}`,
      quantity: 1,
      consultToken: token,
    }),
    { headers: { 'Content-Type': 'application/json' } },
  )

  const body = tradeRes.json()
  const code = String(body.code)

  // 允许的结果只有三种。任何其他响应都是系统故障 ——
  // 特别是 5xx 与超时：它们意味着「结果未知」，而未知在压测里等同于失败，
  // 因为无法判断那笔究竟占没占库存
  if (code === '0') {
    created.add(1)
  } else if (code === '1712') {
    soldOut.add(1)
  } else if (code === '5002') {
    // 抢不到锁。它不是失败，是「稍后重试」—— 真实客户端会重试，
    // 压测里记下来即可，用于对比两组的锁竞争程度
    lockBusy.add(1)
  } else {
    unexpected.add(1)
  }

  check(tradeRes, {
    'no unexpected status': () => tradeRes.status === 200,
    'code is 0 / 1712 / 5002': () => ['0', '1712', '5002'].includes(code),
  })
}
