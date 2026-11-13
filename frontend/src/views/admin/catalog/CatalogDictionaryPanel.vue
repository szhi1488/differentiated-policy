<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import AppIcon from '../../../components/AppIcon.vue'
import { DICTIONARY_LABELS, DICTIONARY_LEVELS } from '../../../constants/catalog-dictionary'
import { dictionaryOptions, indexDictionary } from '../../../utils/catalog-dictionary'

const props = defineProps({
  entries: { type: Array, required: true },
  loading: Boolean,
  loadError: { type: String, default: '' },
  saveEntry: { type: Function, required: true },
  removeEntry: { type: Function, required: true },
})

const emit = defineEmits(['reload'])
const activeType = ref('brand')
const keyword = ref('')
const editing = ref(false)
const error = ref('')
const notice = ref('')
const draft = reactive({ serverId: '', name: '', expectedName: '', parentId: '' })
const busy = ref(false)
const operationError = ref('')
const activeLabel = computed(() => DICTIONARY_LABELS[activeType.value])
const isSeries = computed(() => activeType.value === 'series')
const byId = computed(() => indexDictionary(props.entries))
const tableRows = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return props.entries
    .filter((entry) => entry.type === activeType.value)
    .map((entry) => {
      return {
        ...entry,
        brandName: byId.value.get(entry.parentId)?.name ?? '',
        reason: entry.inUse ? '该字典项已被引用' : '',
      }
    })
    .filter((entry) => !query || `${entry.brandName} ${entry.name}`.toLowerCase().includes(query))
})

function resetSearch() {
  keyword.value = ''
  notice.value = ''
}

function openEditor(row) {
  draft.serverId = row?.serverId ?? ''
  draft.expectedName = row?.name ?? ''
  draft.name = row?.name ?? ''
  draft.parentId = row?.parentId ?? ''
  error.value = ''
  notice.value = ''
  editing.value = true
}

async function save() {
  if (busy.value) {
    return
  }
  const entry = {
    ...draft,
    type: activeType.value,
    parentId: isSeries.value ? draft.parentId : null,
    name: draft.name.trim(),
  }
  if (!entry.name || (isSeries.value && !entry.parentId)) {
    error.value = !entry.name ? '请填写名称' : '请选择所属品牌'
    return
  }
  busy.value = true
  error.value = ''
  try {
    await props.saveEntry(entry)
    notice.value = draft.serverId ? '已更新字典项' : '已新增字典项'
    editing.value = false
  } catch (cause) {
    error.value = cause.message
  } finally {
    busy.value = false
  }
}

async function remove(row) {
  if (busy.value || row.inUse) {
    return
  }
  busy.value = true
  operationError.value = ''
  notice.value = ''
  try {
    try {
      await ElMessageBox.confirm(`确定删除${activeLabel.value}「${row.name}」吗？`, '删除字典项', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
    await props.removeEntry(row)
    notice.value = '已删除字典项'
  } catch (cause) {
    operationError.value = cause.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="catalog-dictionary" aria-label="商品数据字典">
    <header class="catalog-dictionary-heading">
      <div>
        <h2>商品数据字典</h2>
        <p>系列按品牌维护，颜色可供所有商品选择。</p>
      </div>
    </header>
    <el-tabs v-model="activeType" aria-label="字典分类" @tab-change="resetSearch">
      <el-tab-pane
        v-for="[type, label] in DICTIONARY_LEVELS"
        :key="type"
        :label="type === 'color' ? '颜色表' : label"
        :name="type"
      />
    </el-tabs>

    <div class="catalog-dictionary-tools">
      <el-input
        v-model="keyword"
        :placeholder="isSeries ? '搜索系列或品牌' : `搜索${activeLabel}`"
        :aria-label="`搜索${activeLabel}`"
        clearable
      >
        <template #prefix><AppIcon name="search" :size="15" /></template>
      </el-input>
      <el-button
        type="primary"
        :disabled="busy || loading || Boolean(loadError)"
        @click="openEditor()"
      >
        <AppIcon name="plus" :size="15" />
        新增{{ activeLabel }}
      </el-button>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon>
      <el-button link type="primary" @click="emit('reload')">重新读取</el-button>
    </el-alert>
    <el-alert
      v-if="operationError"
      :title="operationError"
      type="error"
      show-icon
      @close="operationError = ''"
    />
    <el-table
      v-loading="loading"
      :data="tableRows"
      row-key="id"
      :aria-label="`${activeLabel}字典表`"
      :empty-text="loading ? '正在读取字典…' : loadError ? '字典读取失败' : `暂无${activeLabel}`"
    >
      <el-table-column
        prop="name"
        :label="`${activeLabel}名称`"
        min-width="116"
        show-overflow-tooltip
      />
      <el-table-column
        v-if="isSeries"
        prop="brandName"
        label="所属品牌"
        min-width="154"
        show-overflow-tooltip
      />
      <el-table-column v-if="activeType === 'color'" label="商品引用" width="86">
        <template #default="{ row }">{{ row.inUse ? '使用中' : '未使用' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="102">
        <template #default="{ row }">
          <el-tooltip :content="row.reason" :disabled="!row.inUse">
            <span>
              <el-button link type="primary" :disabled="busy || row.inUse" @click="openEditor(row)">
                编辑
              </el-button>
              <el-button link type="danger" :disabled="busy || row.inUse" @click="remove(row)">
                删除
              </el-button>
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>
    <footer class="catalog-dictionary-footer" aria-live="polite">
      共 {{ tableRows.length }} 项
      <span v-if="notice">· {{ notice }}</span>
    </footer>

    <el-dialog
      v-model="editing"
      :title="`${draft.serverId ? '编辑' : '新增'}${activeLabel}`"
      width="min(440px, calc(100% - 24px))"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      destroy-on-close
    >
      <el-form
        label-position="top"
        class="catalog-dictionary-form"
        :disabled="busy"
        @submit.prevent="save"
      >
        <el-form-item v-if="isSeries" label="所属品牌">
          <el-select
            v-model="draft.parentId"
            :disabled="Boolean(draft.serverId)"
            placeholder="选择品牌"
            no-data-text="请先新增品牌"
            filterable
            @change="error = ''"
          >
            <el-option
              v-for="option in dictionaryOptions(entries, 'brand', null)"
              :key="option.id"
              :label="option.name"
              :value="option.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="`${activeLabel}名称`" :error="error">
          <el-input
            v-model="draft.name"
            :maxlength="activeType === 'brand' ? 50 : 64"
            :placeholder="`输入${activeLabel}名称`"
            @input="error = ''"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="busy" @click="editing = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.catalog-dictionary {
  min-width: 0;
  padding: 14px;
  border: 1px solid #e3eaf3;
  border-radius: 9px;
  background: #fff;
}
.catalog-dictionary-heading,
.catalog-dictionary-tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}
.catalog-dictionary-heading h2 {
  font-size: 17px;
}
.catalog-dictionary-heading p,
.catalog-dictionary-footer {
  margin-top: 4px;
  color: #536580;
}
.catalog-dictionary-tools {
  margin-bottom: 9px;
}
.catalog-dictionary-tools .el-input {
  width: min(100%, 270px);
  flex: 0 1 270px;
}
.catalog-dictionary-form .el-select {
  width: 100%;
}
.catalog-dictionary-footer {
  padding-top: 6px;
}
</style>
