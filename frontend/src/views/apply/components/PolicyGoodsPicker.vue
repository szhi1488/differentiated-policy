<script setup>
import { usePolicyGoodsPicker } from '../../../composables/usePolicyGoodsPicker'
import { catalogConfigLabel } from '../../../utils/catalog-dictionary'

const props = defineProps({
  special: { type: Boolean, default: false },
  addedKeys: { type: Array, default: () => [] },
})
const emit = defineEmits(['close', 'confirm'])
const {
  brandId,
  seriesId,
  spuId,
  config,
  keyword,
  page,
  total,
  rows,
  brands,
  series,
  models,
  configOptions,
  optionsBusy,
  listBusy,
  selecting,
  optionsError,
  listError,
  selectionError,
  added,
  selected,
  changeBrand,
  changeSeries,
  changeModel,
  changeConfig,
  toggle,
  applySearch,
  loadOptions,
  loadPage,
  selectConfigurations,
  pageSize,
} = usePolicyGoodsPicker(props)
</script>

<template>
  <el-drawer
    :model-value="true"
    append-to-body
    size="min(790px, 100vw)"
    class="policy-goods-picker"
    :title="special ? '指定适用机型' : '选择手机型号'"
    @close="$emit('close')"
  >
    <div class="policy-picker-filters">
      <el-select
        :model-value="brandId"
        placeholder="全部品牌"
        aria-label="商品品牌"
        clearable
        :disabled="selecting"
        :loading="optionsBusy"
        @update:model-value="changeBrand"
      >
        <el-option v-for="item in brands" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-select
        :model-value="seriesId"
        placeholder="全部系列"
        aria-label="商品系列"
        clearable
        :disabled="selecting"
        :loading="optionsBusy"
        @update:model-value="changeSeries"
      >
        <el-option v-for="item in series" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-select
        :model-value="spuId"
        placeholder="全部机型"
        aria-label="商品机型"
        clearable
        :disabled="selecting"
        :loading="optionsBusy"
        @update:model-value="changeModel"
      >
        <el-option
          v-for="item in models"
          :key="item.id"
          :label="item.edition ? `${item.name}（${item.edition}）` : item.name"
          :value="item.id"
        />
      </el-select>
      <el-select
        :model-value="config"
        placeholder="请选择配置"
        aria-label="商品配置"
        multiple
        collapse-tags
        collapse-tags-tooltip
        clearable
        :disabled="selecting"
        :loading="optionsBusy"
        @update:model-value="changeConfig"
      >
        <el-option
          v-for="item in configOptions"
          :key="item.key"
          :label="item.label"
          :value="item.key"
          :disabled="item.disabled"
        />
      </el-select>
    </div>

    <div v-if="optionsError" class="policy-picker-error" role="alert">
      {{ optionsError }}
      <el-button link type="primary" @click="loadOptions">重试筛选项</el-button>
    </div>
    <div class="policy-picker-summary">
      <span>{{ listBusy ? '正在查询商品…' : `${total} 件符合条件` }}</span>
      <el-input
        v-model="keyword"
        class="policy-picker-search"
        aria-label="商品关键词"
        placeholder="商品名称或颜色"
        maxlength="128"
        clearable
        @clear="applySearch"
        @keydown.enter.stop.prevent="applySearch"
      >
        <template #append>
          <el-button @click="applySearch">查询</el-button>
        </template>
      </el-input>
    </div>

    <div v-if="listError" class="policy-picker-error" role="alert">
      {{ listError }}
      <el-button link type="primary" @click="loadPage">重试查询</el-button>
    </div>
    <div v-else-if="rows.length" class="policy-picker-list" role="group" aria-label="在售商品列表">
      <div
        v-for="item in rows"
        :key="item.id"
        class="policy-picker-row"
        :class="{ 'is-selected': selected.has(item.id) }"
      >
        <el-radio
          v-if="special"
          :model-value="[...selected.keys()][0]"
          :value="item.id"
          :disabled="added.has(item.id)"
          @update:model-value="toggle(item, true)"
        >
          <strong>{{ item.brand }} / {{ item.series }} / {{ item.model }}</strong>
        </el-radio>
        <el-checkbox
          v-else
          :model-value="selected.has(item.id)"
          :disabled="selecting || added.has(item.id)"
          @update:model-value="toggle(item, $event)"
        >
          <strong>{{ item.brand }} / {{ item.series }} / {{ item.model }}</strong>
        </el-checkbox>
        <span class="policy-picker-spec">
          {{
            [item.version, catalogConfigLabel(item.config), item.color].filter(Boolean).join(' · ')
          }}
        </span>
        <el-tag v-if="added.has(item.id)" size="small" type="info">已加入</el-tag>
      </div>
    </div>
    <el-empty v-else-if="!listBusy" :image-size="60" description="没有符合条件的在售商品" />
    <el-pagination
      v-if="total > pageSize"
      v-model:current-page="page"
      class="policy-picker-pagination"
      aria-label="商品分页"
      layout="prev, pager, next"
      :pager-count="5"
      :page-size="pageSize"
      :total="total"
      :disabled="listBusy"
    />

    <template #footer>
      <div v-if="selectionError" class="policy-picker-error" role="alert">
        {{ selectionError }}
        <el-button link type="primary" @click="selectConfigurations">重试勾选配置</el-button>
      </div>
      <div class="policy-picker-footer">
        <span aria-live="polite">
          {{ selecting ? '正在勾选配置内的在售商品…' : `已选 ${selected.size} 件` }}
        </span>
        <el-button @click="$emit('close')">取消</el-button>
        <el-button
          type="primary"
          :disabled="selecting || !!selectionError || !selected.size"
          @click="emit('confirm', [...selected.values()])"
        >
          加入政策
        </el-button>
      </div>
    </template>
  </el-drawer>
</template>
