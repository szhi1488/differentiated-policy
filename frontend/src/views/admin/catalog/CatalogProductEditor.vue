<script setup>
import { reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import { CATALOG_CONFIG_PATTERN, dictionaryOptions } from '../../../utils/catalog-dictionary'

const props = defineProps({
  product: { type: Object, default: null },
  entries: { type: Array, required: true },
  statuses: { type: Object, required: true },
})
const emit = defineEmits(['save', 'close'])

const initial = {
  name: props.product?.name ?? '',
  brandId: props.product?.brandId ?? '',
  seriesId: props.product?.seriesId ?? '',
  model: props.product?.model ?? '',
  config: props.product?.config ?? '',
  colorId: props.product?.colorId ?? '',
  version: props.product?.version ?? '',
  status: props.product?.status ?? 'pending',
}
const draft = reactive({ ...initial })
const form = ref()
const open = ref(true)
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
  config: [
    required('配置'),
    {
      pattern: CATALOG_CONFIG_PATTERN,
      message: '请填写内存+存储容量，例如 12+256',
      trigger: 'blur',
    },
  ],
  colorId: { required: true, message: '请选择颜色', trigger: 'change' },
  version: required('版本'),
  status: { required: true, message: '请选择商品状态', trigger: 'change' },
}

function changeBrand(brandId) {
  draft.brandId = brandId
  draft.seriesId = ''
  form.value?.clearValidate('seriesId')
}

function save() {
  form.value?.validate((valid) => {
    if (!valid) {
      return
    }
    emit('save', props.product?.id ?? null, {
      name: draft.name.trim(),
      brandId: draft.brandId,
      seriesId: draft.seriesId,
      model: draft.model.trim(),
      config: draft.config.replace(/\s/g, ''),
      colorId: draft.colorId,
      version: draft.version.trim(),
      status: draft.status,
    })
    open.value = false
  })
}

async function close(done) {
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
    @closed="emit('close')"
  >
    <div class="catalog-product-editor">
      <div v-if="product" class="catalog-product-editor-identity">
        <strong>{{ product.name }}</strong>
        <span>货号 {{ product.id }} · SKU {{ product.sku }}</span>
      </div>
      <p class="catalog-product-editor-note">
        {{
          product
            ? '修改仅保留在当前预览页面。'
            : '保存时生成示例货号和 SKU，仅保留在当前预览页面。'
        }}
      </p>
      <el-form
        ref="form"
        :model="draft"
        :rules="rules"
        label-position="top"
        class="catalog-product-editor-form"
        @submit.prevent="save"
      >
        <el-form-item label="商品名称" prop="name" class="catalog-product-editor-wide">
          <el-input v-model="draft.name" maxlength="80" />
        </el-form-item>
        <el-form-item label="品牌" prop="brandId">
          <el-select
            :model-value="draft.brandId"
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
          <el-input v-model="draft.model" maxlength="40" />
        </el-form-item>
        <el-form-item label="配置（GB）" prop="config">
          <el-input v-model.trim="draft.config" maxlength="12" placeholder="例如 12+256" />
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
          <el-input v-model="draft.version" maxlength="30" />
        </el-form-item>
        <el-form-item label="商品状态" prop="status" class="catalog-product-editor-wide">
          <el-select v-model="draft.status">
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
      <el-button @click="close()">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.catalog-product-editor-identity {
  display: grid;
  gap: 4px;
  padding: 12px;
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
.catalog-product-editor-note {
  margin: 10px 0 14px;
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
