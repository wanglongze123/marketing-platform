<script setup lang="ts">
/**
 * 裂变玩法三个页面共用一个组件 —— 后端整个模块为空，三个页面的内容都只是
 * 「将来有什么」的说明，没必要拆成三个几乎相同的文件。
 *
 * 页面结构与《PRD》第 6 章的 FR-F01~F10 对齐，供后端实现时参照。
 */
import PendingNotice from '@/components/PendingNotice.vue'

const props = defineProps<{ section?: string }>()

const SECTIONS: Record<
  string,
  { title: string; desc: string; features: { fr: string; name: string; detail: string }[] }
> = {
  rounds: {
    title: '轮次管理',
    desc: '按用户与场景维度自动开轮，支持手动开轮与轮次查询。',
    features: [
      { fr: 'FR-F01', name: '师傅进场', detail: '资格判定通过后建立或复用当前轮次' },
      { fr: 'FR-F02', name: '轮次管理', detail: '自动开轮、手动开轮、轮次进度查询' },
      { fr: 'FR-F09', name: '关系过期治理', detail: 'granting_until 过期豁免，非终态支持过期与取消' },
    ],
  },
  relations: {
    title: '关系查询',
    desc: '关系状态机 INVITED → CONNECTED → JOINED → DONE，非终态支持过期与取消。',
    features: [
      { fr: 'FR-F03', name: '获取好友', detail: '拉取可分享好友列表' },
      { fr: 'FR-F04', name: '好友过滤', detail: '按已建立关系、活动规则、风控规则逐层过滤；下推优化 page×N → page×1' },
      { fr: 'FR-F05', name: '分享与建联', detail: '生成邀请凭证，建立 INVITED 关系' },
      { fr: 'FR-F06', name: '徒弟加入', detail: 'active_flag 部分唯一索引防重复建联' },
      { fr: 'FR-F08', name: '关系查询', detail: '按师傅查徒弟列表与各自状态' },
    ],
  },
  rewards: {
    title: '奖励记录',
    desc: '徒弟完成确权后，同源派生两个幂等键分别发放徒弟奖励与师傅返奖。',
    features: [
      { fr: 'FR-F07', name: '徒弟完成与双向发奖', detail: 'followerGrantNo 与 sponsorFlowNo 同源派生，各自幂等' },
      { fr: 'FR-F10', name: '奖励查询', detail: '按用户查双向发奖记录与发放状态' },
    ],
  },
}

const sec = SECTIONS[props.section ?? 'rounds'] ?? SECTIONS.rounds
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        {{ sec.title }}
        <span class="sub">《PRD》第 6 章</span>
      </div>
      <div class="card-body">
        <p class="desc">{{ sec.desc }}</p>

        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th style="width: 88px">需求编号</th>
                <th style="width: 170px">能力</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="f in sec.features" :key="f.fr">
                <td class="mono">{{ f.fr }}</td>
                <td>{{ f.name }}</td>
                <td class="muted">{{ f.detail }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <div class="card" style="margin-top: 16px">
      <div class="card-body">
        <PendingNotice
          capability="fission"
          detail="裂变的 13 个接口全部未实现，所以本页没有任何真实数据可展示。"
        />
        <p class="note" style="margin-top: 14px">
          裂变排在 V3 的理由见《分阶段方案》§1.2：它的价值在于验证「公共能力是否真的公共」
          —— V3 退出标准第 1 条要求<b>裂变接入后 reward 接口签名零改动</b>。若为了让前端有
          页面可点而提前实现裂变，就失去了这个检验点。
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.desc {
  margin: 0 0 15px;
  font-size: 13.5px;
  color: var(--text-2);
}
</style>
