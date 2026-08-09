<script setup lang="ts">
/**
 * 商品配置。查询接 GET /api/benefit/sku/{skuId}（真实），编辑能力未实现。
 *
 * V1 无「SKU 列表」接口，只能按 skuId 单查，故这里是查询框而非列表。
 */
import { onMounted, ref } from 'vue'
import { querySku } from '@/api/benefit'
import { SEED_SKU_ID } from '@/stores/session'
import { toYuan } from '@/contracts/display'
import type { QuerySkuResp } from '@/contracts/dto'
import PendingNotice from '@/components/PendingNotice.vue'

const skuId = ref(SEED_SKU_ID)
const sku = ref<QuerySkuResp | null>(null)
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  const r = await querySku(skuId.value.trim())
  loading.value = false
  if (r.kind === 'ok') {
    sku.value = r.data
  } else {
    sku.value = null
    error.value = r.message
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        查询商品
        <span class="sub">GET /api/benefit/sku/{skuId}</span>
      </div>
      <div class="card-body">
        <div class="q">
          <div>
            <label>skuId</label>
            <input v-model="skuId" @keyup.enter="load" />
          </div>
          <button class="primary" :disabled="loading" @click="load">
            {{ loading ? '查询中…' : '查询' }}
          </button>
        </div>
        <p class="note" style="margin-top: 12px">
          V1 只提供按 skuId 单查，没有 SKU 列表接口，故此处是查询框而非表格。seed 数据里
          只有 <code>SKU_DEMO_001</code> 一个商品。
        </p>
      </div>
    </div>

    <div v-if="error" class="alert error" style="margin-top: 16px">
      <div>{{ error }}</div>
    </div>

    <template v-if="sku">
      <div class="card" style="margin-top: 16px">
        <div class="card-head">
          SKU
          <span class="sub">{{ sku.skuId }}</span>
        </div>
        <div class="card-body">
          <dl class="kv">
            <dt>名称</dt>
            <dd>{{ sku.skuName }}</dd>
            <dt>类型</dt>
            <dd>{{ sku.skuType }}</dd>
            <dt>售卖状态</dt>
            <dd>
              {{ sku.saleStatus }}
              <span v-if="sku.saleStatus !== 'ON_SALE'" class="muted">（非 ON_SALE 不可下单）</span>
            </dd>
            <dt>划线价</dt>
            <dd>¥{{ toYuan(sku.listPrice) }}（{{ sku.listPrice }} 分）</dd>
            <dt>售卖价</dt>
            <dd>¥{{ toYuan(sku.salePrice) }}（{{ sku.salePrice }} 分）</dd>
            <dt>所属活动</dt>
            <dd>{{ sku.activityId }}</dd>
            <dt>权益包</dt>
            <dd>{{ sku.benefitPackageId }} · v{{ sku.packageVersion }}</dd>
          </dl>
          <p class="note" style="margin-top: 13px">
            金额单位是<b>分</b>（整数），不使用浮点。下单应付以服务端重算为准，此处的
            售卖价仅供展示 —— 端侧传值一律只作提示。
          </p>
        </div>
      </div>

      <div class="card" style="margin-top: 16px">
        <div class="card-head">
          权益项
          <span class="sub">{{ sku.items.length }} 项，按 grantOrder 升序</span>
        </div>
        <div class="table-wrap" style="border: none; border-radius: 0">
          <table>
            <thead>
              <tr>
                <th>benefitItemId</th>
                <th>类型</th>
                <th>供应方</th>
                <th>供应方商品</th>
                <th>核心</th>
                <th>发放顺序</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="it in sku.items" :key="it.benefitItemId">
                <td class="mono">{{ it.benefitItemId }}</td>
                <td class="mono">{{ it.benefitType }}</td>
                <td class="mono">{{ it.providerType }}</td>
                <td class="mono">{{ it.providerProductId }}</td>
                <td>{{ it.core ? '是' : '否' }}</td>
                <td>{{ it.grantOrder ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="card-body">
          <p class="note">
            两个权益项刻意分属不同 <code>providerType</code>：履约按供应方分组，每组一个
            <code>grantOpNo</code>。只有存在两组时分组逻辑才会被真实走到 —— 若两项同属一个
            供应方，分组代码写错也测不出来。
          </p>
        </div>
      </div>
    </template>

    <div class="card" style="margin-top: 16px">
      <div class="card-head">编辑与新建</div>
      <div class="card-body">
        <PendingNotice
          capability="skuManage"
          detail="SKU / 权益包 / 权益项 的三级维护表单将在这里。当前配置由 V1190__seed_sku.sql 初始化。"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.q {
  display: flex;
  gap: 11px;
  align-items: end;
}
.q > div {
  flex: 0 0 320px;
}
</style>
