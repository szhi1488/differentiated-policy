<script setup>
import { GOODS_CLEAN_LOG_ACTIONS } from '../../../constants/catalog'

defineProps({
  open: Boolean,
  title: { type: String, required: true },
  loading: Boolean,
  error: { type: String, default: '' },
  data: { type: Object, default: null },
  page: { type: Number, required: true },
  size: { type: Number, required: true },
})
const emit = defineEmits(['close', 'reload', 'update:page'])
</script>

<template>
  <el-dialog
    :model-value="open"
    :title="title"
    append-to-body
    width="min(860px, calc(100vw - 24px))"
    class="cleaning-dialog"
    @update:model-value="(value) => !value && emit('close')"
  >
    <div v-if="error" class="cleaning-inline-error">
      <el-alert :title="error" type="error" :closable="false" show-icon />
      <el-button link type="primary" @click="emit('reload')">重新读取</el-button>
    </div>
    <el-table
      v-else
      v-loading="loading"
      :data="data?.items ?? []"
      row-key="id"
      empty-text="暂无处理记录"
    >
      <el-table-column prop="createdAt" label="时间" width="166" />
      <el-table-column label="操作者" width="112" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.actorType === 'SYSTEM' ? '系统' : row.operatorName || '管理员' }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          {{ GOODS_CLEAN_LOG_ACTIONS[row.action] ?? row.action }}
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="处理说明" min-width="210" show-overflow-tooltip />
    </el-table>
    <template #footer>
      <el-pagination
        v-if="data?.total"
        :current-page="page"
        :page-size="size"
        :total="data.total"
        :pager-count="5"
        size="small"
        layout="prev, pager, next"
        @update:current-page="emit('update:page', $event)"
      />
    </template>
  </el-dialog>
</template>
