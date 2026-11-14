<script setup>
import { reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import {
  CATALOG_RAM_PATTERN,
  CATALOG_ROM_PATTERN,
  catalogConfigLabel,
  dictionaryOptions,
} from '../../../utils/catalog-dictionary'

const props = defineProps({
  product: { type: Object, default: null },
  entries: { type: Array, required: true },
  statuses: { type: Object, required: true },
  saveProduct: { type: Function, required: true },
  dictionaryLoading: Boolean,
  dictionaryError: { type: String, default: '' },
})
const emit = defineEmits(['saved', 'close', 'reload-dictionary'])
const sourceBound = Boolean(props.product?.sku)
const requiresStatus = !props.product || Boolean(props.product.status)
const [ram = '', rom = ''] = props.product
  ? catalogConfigLabel(props.product.config).split('+')
  : []

const initial = {
  name: props.product?.goodsName ?? '',
  brandId: props.product?.brandId ?? '',
  seriesId: props.product?.seriesId ?? '',
  model: props.product?.model ?? '',
  ram,
  rom,
  colorId: props.product?.colorId ?? '',
  version: props.product?.version ?? '',
  status: props.product ? props.product.status : 'pending',
}
const draft = reactive({ ...initial })
const form = ref()
const open = ref(true)
const saving = ref(false)
const saveError = ref('')
const required = (label) => ({
  required: true,
  whitespace: true,
  message: `请填写${label}`,
  trigger: 'blur',
})
const rules = {
  name: required('商品名称'),
  brandId: { required: true, message: '请选择品牌', trigger: 'change' },
  seriesId: { required: true, message: '请选择系列', trigger: 'change' },
  model: required('机型'),
  ram: [required('RAM'), { pattern: CATALOG_RAM_PATTERN, message: '例如 12GB', trigger: 'blur' }],
  rom: [
    required('ROM'),
    { pattern: CATALOG_ROM_PATTERN, message: '例如 256GB 或 1T', trigger: 'blur' },
  ],
  colorId: { required: true, message: '请选择颜色', trigger: 'change' },
  status: { required: requiresStatus, message: '请选择商品状态', trigger: 'change' },
}

function changeBrand(brandId) {
  draft.brandId = brandId
  draft.seriesId = ''
  form.value?.clearValidate('seriesId')
}

async function save() {
  if (saving.value || props.dictionaryLoading || props.dictionaryError) {
    return
  }
  saving.value = true
  saveError.value = ''
  try {
    if (!(await form.value?.validate().catch(() => false))) {
      return
    }
    await props.saveProduct({
      name: draft.name.trim(),
      brandId: draft.brandId.slice('brand-'.length),
      seriesId: draft.seriesId.slice('series-'.length),
      modelName: draft.model.trim(),
      edition: draft.version.trim(),
      ram: draft.ram.trim(),
      rom: draft.rom.trim(),
      colorId: draft.colorId.slice('color-'.length),
      status: props.statuses[draft.status]?.code ?? null,
    })
    emit('saved')
    open.value = false
  } catch (error) {
    saveError.value =
      props.product && ['network', 'timeout', 'parse'].includes(error.kind)
        ? '暂时无法确认保存结果。请保留当前输入，重新打开商品核对。'
        : error.message
  } finally {
    saving.value = false
  }
}

async function close(done) {
  if (saving.value) {
    return
  }
  if (Object.keys(initial).some((field) => draft[field] !== initial[field])) {
    try {
      await ElMessageBox.confirm('当前商品有未保存的修改，确定放弃吗？', '放弃修改', {
        confirmButtonText: '放弃修改',
        cancelButtonText: '继续编辑',
      })
    } catch {
      return
    }
  }
  if (typeof done === 'function') {
    done()
  } else {
    open.value = false
  }
}
</script>

<template>
  <el-drawer
    v-model="open"
    :title="product ? '编辑商品' : '新增商品'"
    direction="rtl"
    size="min(480px, 100%)"
    :close-on-click-modal="false"
    :before-close="close"
    :show-close="!saving"
    :close-on-press-escape="!saving"
    @closed="emit('close')"
  >
    <div class="catalog-product-editor">
      <div v-if="product" class="catalog-product-editor-identity">
        <strong>{{ product.name }}</strong>
        <span>SKU ID {{ product.sku ?? '—' }} · 货号 {{ product.productNo ?? '—' }}</span>
      </div>
      <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" show-icon />
      <el-alert v-if="dictionaryError" :title="dictionaryError" type="error" :closable="false">
        <el-button link type="primary" @click="emit('reload-dictionary')">重新读取字典</el-button>
      </el-alert>
      <el-form
        ref="form"
        :model="draft"
        :rules="rules"
        :disabled="saving"
        label-position="top"
        class="catalog-product-editor-form"
        @submit.prevent="save"
      >
        <el-form-item label="商品名称" prop="name" class="catalog-product-editor-wide">
          <el-input v-model="draft.name" maxlength="384" />
        </el-form-item>
        <el-form-item label="品牌" prop="brandId">
          <el-select
            :model-value="draft.brandId"
            :disabled="sourceBound"
            :loading="dictionaryLoading"
            placeholder="选择品牌"
            filterable
            @update:model-value="changeBrand"
          >
            <el-option
              v-for="item in dictionaryOptions(entries, 'brand', null)"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
          <span v-if="sourceBound" class="catalog-product-editor-note">已关联源商品</span>
        </el-form-item>
        <el-form-item label="系列" prop="seriesId">
          <el-select
            v-model="draft.seriesId"
            :disabled="!draft.brandId"
            placeholder="选择系列"
            no-data-text="请先维护该品牌的系列"
            filterable
          >
            <el-option
              v-for="item in dictionaryOptions(entries, 'series', draft.brandId)"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="机型" prop="model">
          <el-input v-model="draft.model" maxlength="100" />
        </el-form-item>
        <el-form-item label="RAM（运行内存）" prop="ram">
          <el-input v-model.trim="draft.ram" maxlength="20" placeholder="例如 12GB" />
        </el-form-item>
        <el-form-item label="ROM（存储容量）" prop="rom">
          <el-input v-model.trim="draft.rom" maxlength="20" placeholder="例如 256GB 或 1T" />
        </el-form-item>
        <el-form-item label="颜色" prop="colorId">
          <el-select
            v-model="draft.colorId"
            placeholder="选择颜色"
            no-data-text="请先在颜色表维护"
            filterable
          >
            <el-option
              v-for="item in dictionaryOptions(entries, 'color', null)"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="版本" prop="version">
          <el-input v-model="draft.version" maxlength="64" placeholder="可选" />
        </el-form-item>
        <el-form-item label="商品状态" prop="status" class="catalog-product-editor-wide">
          <el-select
            v-model="draft.status"
            placeholder="选择商品状态"
            :empty-values="[null, undefined]"
          >
            <el-option v-if="!requiresStatus" label="未知" value="" />
            <el-option
              v-for="(status, key) in statuses"
              :key="key"
              :label="status.label"
              :value="key"
            />
          </el-select>
        </el-form-item>
      </el-form>
    </div>
    <template #footer>
      <el-button :disabled="saving" @click="close()">取消</el-button>
      <el-button
        type="primary"
        :loading="saving"
        :disabled="dictionaryLoading || Boolean(dictionaryError)"
        @click="save"
      >
        保存
      </el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.catalog-product-editor-identity {
  display: grid;
  gap: 4px;
  padding: 12px;
  margin-bottom: 12px;
  background: #f1f6ff;
  border-radius: 8px;
  overflow-wrap: anywhere;
}
.catalog-product-editor-identity strong {
  color: #17263e;
}
.catalog-product-editor-identity span,
.catalog-product-editor-note {
  color: #536580;
  font-size: 12px;
}
.catalog-product-editor-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 14px;
}
.catalog-product-editor-form .el-select {
  width: 100%;
}
.catalog-product-editor-wide {
  grid-column: 1 / -1;
}
@media (max-width: 420px) {
  .catalog-product-editor-form {
    column-gap: 10px;
  }
}
</style>
