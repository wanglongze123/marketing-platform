<script setup lang="ts">
/**
 * 创建活动。
 *
 * 表单是真的（含 playType 选择后 playConfig 分叉成两套），但**提交入口禁用** ——
 * 后端无创建接口。做成可交互的表单而非一句「待开发」，是因为「两个玩法的私有配置
 * 长什么样、在哪里分叉」是这个平台的核心结构，先把它定下来对后端实现有参照价值。
 *
 * 字段取自《PRD》FR-C01 与 §6.1 / §7.1。
 */
import { computed, ref } from 'vue'
import { PLAY_TYPE } from '@/contracts/enums'
import type { PlayType } from '@/contracts/enums'
import PendingNotice from '@/components/PendingNotice.vue'

const playType = ref<PlayType>('BENEFIT_SELL')

const form = ref({
  name: '',
  scene: '',
  startTime: '',
  endTime: '',
  cityScope: '',
  channelScope: '',
})

/** 权益售卖私有配置，《PRD》§7.1 */
const sellConfig = ref({
  skuId: '',
  refundWindowDays: 7,
  allowPartialRefund: false,
})

/** 裂变私有配置，《PRD》§6.1 */
const fissionConfig = ref({
  targetCount: 3,
  roundDurationDays: 7,
  followerReward: '',
  sponsorReward: '',
})

const PLAY_LABEL: Record<PlayType, string> = {
  BENEFIT_SELL: '权益售卖',
  FISSION: '裂变',
}

/** 发布前六项校验，BR-C-04。此处只列出，不实现 —— 校验在服务端 */
const PUBLISH_CHECKS = [
  '有效期合法（endTime > startTime）',
  '至少一项可用奖励或商品',
  '供应方配置完整',
  '人群规则可解析',
  '频控与风控规则可解析',
  '玩法私有配置通过各自校验',
]

const fissionBlocked = computed(() => playType.value === 'FISSION')
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        基础信息
        <span class="sub">《PRD》FR-C01</span>
      </div>
      <div class="card-body">
        <div class="grid">
          <div>
            <label>活动名称 <em>必填</em></label>
            <input v-model="form.name" placeholder="≤128 字符" />
          </div>
          <div>
            <label>scene（场景路由）<em>必填</em></label>
            <input v-model="form.scene" placeholder="如 BENEFIT_SELL_DEMO" />
          </div>
          <div>
            <label>playType <em>必填</em></label>
            <select v-model="playType">
              <option v-for="p in PLAY_TYPE" :key="p" :value="p">
                {{ PLAY_LABEL[p] }}（{{ p }}）
              </option>
            </select>
          </div>
          <div>
            <label>activityId</label>
            <input value="系统生成" disabled />
          </div>
          <div>
            <label>startTime <em>必填</em></label>
            <input v-model="form.startTime" type="datetime-local" />
          </div>
          <div>
            <label>endTime <em>必填</em></label>
            <input v-model="form.endTime" type="datetime-local" />
          </div>
          <div>
            <label>cityScope（留空不限）</label>
            <input v-model="form.cityScope" placeholder="逗号分隔" />
          </div>
          <div>
            <label>channelScope（留空不限）</label>
            <input v-model="form.channelScope" placeholder="逗号分隔" />
          </div>
        </div>
      </div>
    </div>

    <!-- playConfig 按玩法分叉 -->
    <div class="card" style="margin-top: 16px">
      <div class="card-head">
        playConfig · {{ PLAY_LABEL[playType] }}私有配置
        <span class="sub">
          {{ playType === 'BENEFIT_SELL' ? '《PRD》§7.1' : '《PRD》§6.1' }}
        </span>
      </div>
      <div class="card-body">
        <div v-if="playType === 'BENEFIT_SELL'" class="grid">
          <div>
            <label>关联 skuId</label>
            <input v-model="sellConfig.skuId" placeholder="如 SKU_DEMO_001" />
          </div>
          <div>
            <label>退款窗口（天）</label>
            <input v-model.number="sellConfig.refundWindowDays" type="number" min="0" />
          </div>
          <div class="check">
            <label>
              <input v-model="sellConfig.allowPartialRefund" type="checkbox" class="cb" />
              允许部分退款
            </label>
          </div>
        </div>

        <div v-else class="grid">
          <div>
            <label>目标邀请人数</label>
            <input v-model.number="fissionConfig.targetCount" type="number" min="1" />
          </div>
          <div>
            <label>单轮时长（天）</label>
            <input v-model.number="fissionConfig.roundDurationDays" type="number" min="1" />
          </div>
          <div>
            <label>徒弟奖励</label>
            <input v-model="fissionConfig.followerReward" placeholder="奖励项标识" />
          </div>
          <div>
            <label>师傅返奖</label>
            <input v-model="fissionConfig.sponsorReward" placeholder="奖励项标识" />
          </div>
        </div>

        <div v-if="fissionBlocked" class="alert wait" style="margin-top: 14px">
          <div>
            裂变玩法的后端尚未实现（<code>mp-fission</code> 只有 pom.xml）。即便活动创建接口
            就绪，选了 <code>FISSION</code> 也无法真正投入运行。
          </div>
        </div>
      </div>
    </div>

    <div class="card" style="margin-top: 16px">
      <div class="card-head">
        发布前校验
        <span class="sub">BR-C-04，六项</span>
      </div>
      <div class="card-body">
        <ol class="checks">
          <li v-for="c in PUBLISH_CHECKS" :key="c">{{ c }}</li>
        </ol>
        <p class="note" style="margin-top: 12px">
          六项一律在服务端校验，前端不重复实现 —— 客户端校验只能改善提示体验，不能作为
          准入依据。校验通过后生成不可变配置版本，活动进入 <code>SCHEDULED</code>。
        </p>
      </div>
    </div>

    <div class="card" style="margin-top: 16px">
      <div class="card-body">
        <div class="submit">
          <button class="primary" disabled title="后端无创建接口">保存草稿</button>
          <button disabled title="后端无发布接口">提交发布</button>
          <span class="muted">按钮禁用：接口未实现，不做假提交</span>
        </div>
        <div style="margin-top: 15px">
          <PendingNotice capability="activityCreate" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
@media (max-width: 760px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
label em {
  font-style: normal;
  color: var(--error);
  font-size: 11px;
  margin-left: 3px;
}
.check {
  display: flex;
  align-items: center;
}
.check label {
  margin: 0;
  color: var(--text-2);
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 7px;
}
.cb {
  width: auto;
}
.checks {
  margin: 0;
  padding-left: 22px;
  font-size: 13px;
  color: var(--text-2);
}
.checks li {
  padding: 3px 0;
}
.submit {
  display: flex;
  align-items: center;
  gap: 11px;
  flex-wrap: wrap;
  font-size: 12.5px;
}
</style>
