/**
 * 后端尚未提供的能力清单。
 *
 * 为什么集中在一处、而不是在各页面里写死文案：
 *
 * 1. 页面上的「待开发」标记必须与真实情况一致。散落各处时，后端上线了某个接口
 *    而某个页面的标记忘了改，就会出现「明明能用却标着待开发」或更糟的反向情况。
 * 2. 后端实现某项能力后，删掉这里的一条 + 在 api 层加真实函数即可，页面不改。
 * 3. 这份清单同时是给后端看的排期依据 —— 每条都写了为什么需要它。
 *
 * ⚠️ 纪律：不为未实现的接口造 mock 数据当成能用的功能。占位页面显示的是
 * 「这里将来有什么 + 为什么现在没有」，而不是假数据。假数据会被当成已完成功能，
 * 最后在演示时暴露。
 */

/** 后端阶段。与《分阶段方案》一致 */
export type BackendPhase = 'V2' | 'V3' | 'unplanned'

export interface PendingCapability {
  /** 稳定标识，页面用它引用而不复制文案 */
  key: string
  /** 能力名 */
  name: string
  /** 计划的端点形状。后端实现时可直接参照，避免字段名分叉 */
  endpoint: string
  phase: BackendPhase
  /** 为什么需要它 —— 缺了它前端只能怎样将就 */
  why: string
}

export const PENDING: Record<string, PendingCapability> = {
  activityList: {
    key: 'activityList',
    name: '活动列表查询',
    endpoint: 'GET /api/activity/activities',
    phase: 'unplanned',
    why: '活动是平台层的入口与两个玩法的分岔点。没有它，运营端无法看到有哪些活动，只能靠 seed SQL 里的固定 ID。',
  },
  activityDetail: {
    key: 'activityDetail',
    name: '活动详情与配置版本',
    endpoint: 'GET /api/activity/activities/{activityId}',
    phase: 'unplanned',
    why: '订单冻结了 configVersion，但没有接口能查这个版本对应的配置内容，排查「为什么按这个价履约」时断线。',
  },
  activityCreate: {
    key: 'activityCreate',
    name: '活动创建与发布',
    endpoint: 'POST /api/activity/activities · POST .../publish',
    phase: 'unplanned',
    why: '《分阶段方案》§4.6 明确列为范围外，配置由 seed SQL 初始化。发布前六项校验（BR-C-04）也随之未实现。',
  },
  skuManage: {
    key: 'skuManage',
    name: 'SKU / 权益包管理',
    endpoint: 'POST · PUT /api/benefit/skus',
    phase: 'unplanned',
    why: '同上，范围外。只读的 GET /api/benefit/sku/{skuId} 已实现，故商品展示不受影响，缺的是编辑能力。',
  },
  qualification: {
    key: 'qualification',
    name: '资格决策试算',
    endpoint: 'POST /api/activity/qualification/probe',
    phase: 'unplanned',
    why: '人群/城市/渠道/风控多维判定，返回标准原因码。只读无副作用，是运营配好活动后的自检入口。',
  },
  preConsult: {
    key: 'preConsult',
    name: '预咨询与试算',
    endpoint: 'POST /api/benefit/pre-consult',
    phase: 'V2',
    why: '签发咨询凭证、试算价格与库存限购。缺它时收银台只能直接下单，无法先给用户看「能不能买、多少钱」。',
  },
  closeOrder: {
    key: 'closeOrder',
    name: '订单关闭',
    endpoint: 'POST /api/benefit/order/{bizNo}/close',
    phase: 'V2',
    why: '支付超时关单，依赖可靠任务表。缺它时 WAIT_PAY 的单会一直停在待支付。',
  },
  taskBoard: {
    key: 'taskBoard',
    name: '可靠任务看板',
    endpoint: 'GET /api/ops/tasks',
    phase: 'V2',
    why: '租约、重试次数、故障接管情况。benefit_task 表 V2 才建。',
  },
  refund: {
    key: 'refund',
    name: '退款准入与权益回收',
    endpoint: 'POST /api/benefit/order/{bizNo}/refund',
    phase: 'V3',
    why: '逆向链路：退款准入判定 → 权益回收 → 退款执行，回收与退款先后可审计。依赖发放链路稳定，排在 V3。',
  },
  benefitUsage: {
    key: 'benefitUsage',
    name: '权益使用状态',
    endpoint: 'GET /api/benefit/rights',
    phase: 'V3',
    why: '用户视角的「我的权益」及其使用/过期状态（FR-B07）。当前只能看到发放结果，看不到后续使用情况。',
  },
  fission: {
    key: 'fission',
    name: '裂变玩法全链路',
    endpoint: '13 个接口，见《PRD》第 6 章',
    phase: 'V3',
    why: 'mp-fission 与 mp-api-fission 目前只有 pom.xml，模块是空的。关系状态机、好友过滤、双向发奖全部未实现。',
  },
  reconcile: {
    key: 'reconcile',
    name: '对账与资损哨兵',
    endpoint: 'GET /api/ops/reconcile',
    phase: 'V3',
    why: '14 项定时比对，资损哨兵指标由对账产出。表都还没建。',
  },
}

/** 阶段 → 徽章文案 */
export const PHASE_LABEL: Record<BackendPhase, string> = {
  V2: '后端 V2 实现',
  V3: '后端 V3 实现',
  unplanned: '端点待开放',
}
