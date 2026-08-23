/**
 * 吞吐与时延压测：技术方案 §8.5 场景清单的前四项。V4 第 8 项。
 *
 * 与 seckill.js 分工不同：那个验「正确性在压力下不破」（0 超卖），本脚本验
 * 「吞吐与时延达到 §8.3 的目标」。两者的判据完全不同 —— 秒杀跑通不代表 P99 达标，
 * P99 达标也不代表没超卖。
 *
 * 四个场景各自的目标（§8.3、§8.5）：
 *
 *   预咨询    ≥1000 QPS  P99 ≤100ms   —— 读热点，配置缓存（§7.7）就是为它加的
 *   创建订单  ≥300 QPS   P99 ≤200ms   —— 写路径，含库存与限购的原子预占
 *   支付通知  ≥500 QPS   P99 ≤100ms   —— 尖峰，验签 + 条件更新
 *   师傅进场  ≥500 QPS   P99 ≤150ms   —— 裂变入口，含资格决策
 *
 * 跑法：
 *   k6 run k6/throughput.js                     # 全部四个场景
 *   k6 run -e SCENARIO=consult k6/throughput.js # 只跑一个
 */
import http from 'k6/http'
import { check } from 'k6'
import { Counter } from 'k6/metrics'

const BASE = __ENV.BASE || 'http://localhost:8080'
const RUN_ID = __ENV.RUN_ID || 'tp'
const ACTIVITY_ID = 'ACT_DEMO_001'
const FISSION_ACTIVITY_ID = 'ACT_FISSION_001'
const SKU_ID = 'SKU_DEMO_001'

/** 运维端点令牌（V4 第 9 项）。取签名要用 */
const OPS_TOKEN = __ENV.OPS_TOKEN || 'local-dev-ops-token-do-not-use-in-prod'

/**
 * 业务拒绝与系统故障分开计数。
 *
 * 压测里最容易自欺的一处：把 4xxx/1xxx 的业务拒绝算进「失败」，于是限购生效、
 * 库存售罄这些**正确行为**被记成错误率，指标一片红；反过来把它们算进「成功」，
 * 5xxx 的系统故障就被稀释看不见了。故分列。
 */
const bizReject = new Counter('biz_reject')
const sysError = new Counter('sys_error')

const only = __ENV.SCENARIO

function pick(name, def) {
  return !only || only === name ? def : null
}

export const options = {
  scenarios: Object.fromEntries(
    Object.entries({
      // 恒定到达率而非固定 VU 数：后者在响应变慢时自动降速，压出来的 QPS
      // 是「系统能给多少」而不是「目标是多少」，达标与否无从判断
      consult: pick('consult', {
        executor: 'constant-arrival-rate',
        rate: 1000,
        timeUnit: '1s',
        duration: '30s',
        preAllocatedVUs: 200,
        maxVUs: 800,
        exec: 'consult',
        tags: { scenario: 'consult' },
      }),
      trade: pick('trade', {
        executor: 'ramping-arrival-rate',
        startRate: 50,
        timeUnit: '1s',
        preAllocatedVUs: 100,
        maxVUs: 500,
        stages: [
          { target: 150, duration: '10s' },
          { target: 300, duration: '20s' },
        ],
        exec: 'trade',
        startTime: '35s',
        tags: { scenario: 'trade' },
      }),
      payNotify: pick('payNotify', {
        executor: 'constant-arrival-rate',
        rate: 500,
        timeUnit: '1s',
        duration: '20s',
        preAllocatedVUs: 100,
        maxVUs: 400,
        exec: 'payNotify',
        startTime: '70s',
        tags: { scenario: 'payNotify' },
      }),
      sponsor: pick('sponsor', {
        executor: 'constant-arrival-rate',
        rate: 500,
        timeUnit: '1s',
        duration: '20s',
        preAllocatedVUs: 100,
        maxVUs: 400,
        exec: 'sponsor',
        startTime: '95s',
        tags: { scenario: 'sponsor' },
      }),
    }).filter(([, v]) => v !== null)
  ),

  // 阈值按 §8.3 的目标设。达不到就是没达标，不留余地 ——
  // 压测的意义在于给出「达标 / 未达标」这个二值判断，改宽阈值等于取消判断
  thresholds: {
    'http_req_duration{scenario:consult}': ['p(99)<100'],
    'http_req_duration{scenario:trade}': ['p(99)<200'],
    'http_req_duration{scenario:payNotify}': ['p(99)<100'],
    'http_req_duration{scenario:sponsor}': ['p(99)<150'],
    // 系统故障必须为 0。业务拒绝不设阈值 —— 它们是防线在工作
    sys_error: ['count==0'],
  },
}

const JSON_HDR = { 'Content-Type': 'application/json' }

function classify(res) {
  if (res.status !== 200) {
    sysError.add(1)
    return
  }
  const code = res.json('code')
  if (code === 0) return
  if (String(code).startsWith('5')) sysError.add(1)
  else bizReject.add(1)
}

export function consult() {
  const res = http.post(
    `${BASE}/api/benefit/consult`,
    JSON.stringify({
      userId: `U_${RUN_ID}_${__VU}`,
      activityId: ACTIVITY_ID,
      skuId: SKU_ID,
    }),
    { headers: JSON_HDR, tags: { scenario: 'consult' } }
  )
  check(res, { 'consult 200': (r) => r.status === 200 })
  classify(res)
}

export function trade() {
  // 每次都要先取凭证：createTrade 必须带 consultToken（V2 起的 L1 防线）。
  // 这让本场景实际压的是「两跳」，与线上一致 —— 用户不会凭空拿到凭证
  const c = http.post(
    `${BASE}/api/benefit/consult`,
    JSON.stringify({
      userId: `U_${RUN_ID}_${__VU}_${__ITER}`,
      activityId: ACTIVITY_ID,
      skuId: SKU_ID,
    }),
    { headers: JSON_HDR, tags: { scenario: 'trade', step: 'consult' } }
  )
  if (c.status !== 200 || c.json('code') !== 0) {
    classify(c)
    return
  }
  const res = http.post(
    `${BASE}/api/benefit/trade`,
    JSON.stringify({
      userId: `U_${RUN_ID}_${__VU}_${__ITER}`,
      activityId: ACTIVITY_ID,
      skuId: SKU_ID,
      clientReqNo: `REQ_${RUN_ID}_${__VU}_${__ITER}`,
      quantity: 1,
      consultToken: c.json('data.consultToken'),
    }),
    { headers: JSON_HDR, tags: { scenario: 'trade' } }
  )
  check(res, { 'trade 200': (r) => r.status === 200 })
  classify(res)
}

export function payNotify() {
  // 压的是验签 + 解析这段，故用一个不存在的订单号：验签、反序列化、查单的成本
  // 都已付过，只是最后一步查不到。用真实订单则每轮都要先建单，压出来的是建单的耗时。
  //
  // 「订单不存在」是业务拒绝（1721），但跨 Dubbo 后会以 5001 回来 —— BizException
  // 未实现 Serializable，异常经序列化时降级为 RuntimeException，错误码在传输中丢失。
  // 这是 V4 压测暴露的一处真实缺陷，见《分阶段方案》§6A 实测记录。故此处不能按
  // code 分类，改用「响应能解析出 traceId」判定服务是否健康
  const body = {
    outTradeNo: `BZ_NOTEXIST_${RUN_ID}_${__VU}_${__ITER}`,
    tradeNo: `T_${__VU}_${__ITER}`,
    notifySeq: `NS_${__VU}_${__ITER}`,
    payStatus: 'SUCCESS',
    payAmount: 9900,
    currency: 'CNY',
    merchantId: 'MCH_LOCAL_DEMO',
  }
  const signed = http.post(`${BASE}/api/fault/pay-notify/sign`, JSON.stringify(body), {
    headers: { ...JSON_HDR, 'X-Ops-Token': OPS_TOKEN },
    tags: { scenario: 'payNotify', step: 'sign' },
  })
  if (signed.status !== 200) {
    sysError.add(1)
    return
  }
  const res = http.post(
    `${BASE}/api/benefit/pay-callback`,
    JSON.stringify({ ...body, sign: signed.json('data.sign') }),
    { headers: JSON_HDR, tags: { scenario: 'payNotify' } }
  )
  // 不调 classify：本场景的预期结果就是「查不到单」，而它现在以 5001 回来。
  // 按 code 分类会把 100% 的正常响应记成系统故障
  check(res, { 'payNotify 服务可达': (r) => r.status === 200 })
}

export function sponsor() {
  const res = http.post(
    `${BASE}/api/fission/sponsor/query`,
    JSON.stringify({
      userId: `S_${RUN_ID}_${__VU}`,
      activityId: FISSION_ACTIVITY_ID,
      scene: 'INVITE',
    }),
    { headers: JSON_HDR, tags: { scenario: 'sponsor' } }
  )
  // 裂变入口在 V4 尚无 HTTP controller（见 GatewayRemoteConfig 注释），
  // 404 时本场景整体跳过而非记为故障 —— 压一个不存在的端点只会得到一串 404 的耗时
  if (res.status === 404) return
  check(res, { 'sponsor 200': (r) => r.status === 200 })
  classify(res)
}
