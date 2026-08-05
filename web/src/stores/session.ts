/**
 * 当前用户与活动上下文。
 *
 * ⚠️ 只存「身份」这类跨页共享的少量状态。**订单状态一律回后端查，不进 store** ——
 * 缓存会与「三条子状态线独立推进」冲突：某一线在服务端变了而缓存没更新时，
 * 页面显示的组合态是不存在的。
 *
 * 后端无鉴权体系，故用输入框切 userId 模拟身份，与之前的单页面一致。
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'

const LS_USER = 'mp.session.userId'

/** seed 数据。V1 无活动列表接口，故活动上下文暂时固定 */
export const SEED_ACTIVITY_ID = 'ACT_DEMO_001'
export const SEED_ACTIVITY_NAME = '权益售卖演示活动'
export const SEED_SKU_ID = 'SKU_DEMO_001'

export const useSessionStore = defineStore('session', () => {
  const userId = ref(localStorage.getItem(LS_USER) ?? 'U001')

  function setUserId(v: string) {
    const next = v.trim() || 'U001'
    userId.value = next
    localStorage.setItem(LS_USER, next)
  }

  return { userId, setUserId }
})
