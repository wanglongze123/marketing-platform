/**
 * 本地下单记录。
 *
 * 为什么需要它：`GET /api/benefit/orders` 已实现，用户端本可直接查列表。但下单
 * 瞬间到列表可见之间，端侧需要记住「我刚下的是哪一单」以便跳转结果页；另外
 * 「我的订单」在 userId 被切换后仍要能翻回历史单号。
 *
 * ⚠️ 只存定位所需的最小信息（bizNo + 时间）。**状态一律回后端查，不缓存** ——
 * 缓存状态会与「三条子状态线独立推进」冲突。
 */
const LS_KEY = 'mp.recentOrders.v2'
const MAX = 60

export interface RecentOrder {
  bizNo: string
  userId: string
  /** 本地记录时间，仅用于「刚下的单」排序；权威时间取后端 createTime */
  at: string
}

function load(): RecentOrder[] {
  try {
    const raw = JSON.parse(localStorage.getItem(LS_KEY) ?? '[]')
    return Array.isArray(raw) ? (raw as RecentOrder[]) : []
  } catch {
    return []
  }
}

function save(list: RecentOrder[]) {
  localStorage.setItem(LS_KEY, JSON.stringify(list.slice(0, MAX)))
}

export function remember(bizNo: string, userId: string) {
  const list = load().filter((o) => o.bizNo !== bizNo)
  list.unshift({ bizNo, userId, at: new Date().toISOString() })
  save(list)
}

export function recentOf(userId: string): RecentOrder[] {
  return load().filter((o) => o.userId === userId)
}

export function clearOf(userId: string) {
  save(load().filter((o) => o.userId !== userId))
}
