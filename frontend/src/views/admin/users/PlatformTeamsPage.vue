<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import WorkspacePageHeading from '../../../components/WorkspacePageHeading.vue'
import { useLatestQuery } from '../../../composables/useLatestQuery'
import { useReviewerSelectionWrite } from '../../../composables/useReviewerSelectionWrite'
import { CHANNEL_LABELS, PLATFORM_LABELS } from '../../../constants/policy'
import {
  findPlatformCandidates,
  readPlatformOwners,
  updatePlatformTeam,
} from '../../../api/admin-users'
const { data, error, loading, run } = useLatestQuery()
const candidates = useLatestQuery()
const { saving, needsReload, error: writeError, reset, run: write } = useReviewerSelectionWrite()
const current = ref(null)
const members = ref([])
const submitter = ref('')
const query = ref('')
const applied = ref('')
const page = ref(1)
const load = () => run(readPlatformOwners)
void load()
function edit(team) {
  reset()
  current.value = team
  members.value = team.members.map((m) => ({ ...m }))
  submitter.value = members.value.find((m) => m.memberKind === 'SUBMITTER')?.userId ?? ''
  query.value = ''
  applySearch()
}
function search() {
  return candidates.run((signal) => findPlatformCandidates(applied.value, page.value, signal))
}
watch(page, () => {
  if (current.value) {
    void search()
  }
})
function applySearch() {
  applied.value = query.value.trim()
  if (page.value === 1) {
    void search()
  } else {
    page.value = 1
  }
}
function select(person, checked) {
  const id = person.id
  if (checked && !members.value.some((m) => m.userId === id)) {
    members.value.push({
      userId: id,
      name: person.name,
      memberKind: 'MEMBER',
      canSubmit: person.canSubmit,
    })
  }
  if (!checked) {
    members.value = members.value.filter((m) => m.userId !== id)
    if (submitter.value === id) {
      submitter.value = ''
    }
  }
}
const valid = computed(
  () =>
    !members.value.length || members.value.some((m) => m.userId === submitter.value && m.canSubmit),
)
function save() {
  if (!current.value || !valid.value) {
    return
  }
  return write(
    (signal) =>
      updatePlatformTeam(
        current.value,
        members.value.map((m) => ({
          userId: m.userId,
          memberKind: m.userId === submitter.value ? 'SUBMITTER' : 'MEMBER',
        })),
        signal,
      ),
    () => {
      current.value = null
      ElMessage.success('平台分工已保存')
      void load()
    },
  )
}
function closeEditor() {
  current.value = null
  void load()
}
</script>
<template>
  <section class="team-page">
    <header class="workspace-page-banner">
      <WorkspacePageHeading title="平台分工" icon="users" />
    </header>
    <section class="team-panel" v-loading="loading">
      <el-alert v-if="error" :title="error.message" type="error" :closable="false">
        <el-button link @click="load">重新读取</el-button>
      </el-alert>
      <el-table v-else :data="data ?? []" empty-text="尚无可用平台">
        <el-table-column label="渠道">
          <template #default="{ row }">{{ CHANNEL_LABELS[row.perspective] }}</template>
        </el-table-column>
        <el-table-column label="平台">
          <template #default="{ row }">{{ PLATFORM_LABELS[row.platformCode] }}</template>
        </el-table-column>
        <el-table-column label="指定提交人">
          <template #default="{ row }">
            {{ row.members.find((m) => m.memberKind === 'SUBMITTER')?.name ?? '待指定' }}
          </template>
        </el-table-column>
        <el-table-column label="共同成员" min-width="160">
          <template #default="{ row }">
            {{
              row.members
                .filter((m) => m.memberKind === 'MEMBER')
                .map((m) => m.name)
                .join('、') || '—'
            }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click="edit(row)">分配</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
    <el-dialog
      :model-value="!!current"
      :title="
        current
          ? CHANNEL_LABELS[current.perspective] + ' · ' + PLATFORM_LABELS[current.platformCode]
          : ''
      "
      width="min(620px,95%)"
      :close-on-click-modal="false"
      :show-close="!saving"
      :close-on-press-escape="!saving"
      @update:model-value="!$event && !saving && closeEditor()"
    >
      <template v-if="current">
        <el-form label-position="top" :disabled="saving">
          <el-form-item label="平台负责成员">
            <div class="team-selection">
              <el-tag
                v-for="m in members"
                :key="m.userId"
                closable
                @close="select({ id: m.userId }, false)"
              >
                {{ m.name }} · {{ m.userId }}
              </el-tag>
            </div>
          </el-form-item>
          <el-form-item label="指定提交人">
            <el-select v-model="submitter" placeholder="在已选成员中指定一人">
              <el-option
                v-for="m in members"
                :key="m.userId"
                :value="m.userId"
                :label="m.name + ' · ' + (m.canSubmit ? m.userId : '无提交权限')"
                :disabled="!m.canSubmit"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="查找成员">
            <el-input v-model="query" placeholder="姓名或部门" clearable @keyup.enter="applySearch">
              <template #append><el-button @click="applySearch">查询</el-button></template>
            </el-input>
          </el-form-item>
          <div v-loading="candidates.loading.value">
            <el-alert
              v-if="candidates.error.value"
              :title="candidates.error.value.message"
              type="error"
              :closable="false"
            >
              <el-button link @click="search">重试查询</el-button>
            </el-alert>
            <el-checkbox
              v-for="p in candidates.data.value?.items"
              :key="p.id"
              :model-value="members.some((m) => m.userId === p.id)"
              @change="select(p, $event)"
            >
              {{ p.name }} · {{ p.department || p.id }}
            </el-checkbox>
            <el-pagination
              v-if="candidates.data.value?.total > 20"
              v-model:current-page="page"
              :page-size="20"
              :total="candidates.data.value.total"
              layout="prev,pager,next"
            />
          </div>
        </el-form>
        <el-alert v-if="writeError" :title="writeError" type="error" :closable="false" />
      </template>
      <template #footer>
        <el-button :disabled="saving" @click="closeEditor">取消</el-button>
        <el-button type="primary" :disabled="!valid || needsReload" :loading="saving" @click="save">
          保存分工
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
<style scoped>
.team-page {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
}
.team-panel {
  min-width: 0;
  padding: 18px;
  background: white;
  border: 1px solid #dfe7f3;
  border-radius: 10px;
}
.team-selection {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
