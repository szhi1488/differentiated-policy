<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from '../../components/AppIcon.vue'

const props = defineProps({
  user: { type: Object, default: null },
  groups: { type: Array, required: true },
  roleLabel: { type: String, required: true },
  sidebarId: { type: String, required: true },
  menuExpanded: Boolean,
})
const emit = defineEmits(['toggle-menu', 'navigate'])
const route = useRoute()
const keyword = ref('')
const avatarFailed = ref(false)
const displayName = computed(() => props.user?.name || props.roleLabel)
const initial = computed(() => Array.from(displayName.value)[0])
const currentGroup = computed(() =>
  props.groups.find(({ links }) => links.some(({ name }) => name === route.name)),
)
const currentPage = computed(() =>
  currentGroup.value?.links.find(({ name }) => name === route.name),
)
const avatarUrl = computed(() => {
  const url = props.user?.avatarUrl
  if (typeof url !== 'string') {
    return ''
  }
  try {
    return new URL(url).protocol === 'https:' ? url : ''
  } catch {
    return ''
  }
})
watch(avatarUrl, () => (avatarFailed.value = false))
function suggestions(query, callback) {
  const search = query.trim().toLocaleLowerCase()
  callback(
    props.groups.flatMap((group) =>
      group.links
        .filter((link) => `${group.label} ${link.label}`.toLocaleLowerCase().includes(search))
        .map((link) => ({ ...link, value: link.label, group: group.label })),
    ),
  )
}
function navigate(item) {
  keyword.value = ''
  emit('navigate', item.name)
}
</script>

<template>
  <header class="workspace-topbar">
    <el-button
      class="workspace-menu-toggle"
      text
      :aria-label="menuExpanded ? '收起导航' : '展开导航'"
      :aria-expanded="menuExpanded"
      :aria-controls="sidebarId"
      @click="emit('toggle-menu')"
    >
      <AppIcon name="menu" :size="18" />
    </el-button>
    <el-breadcrumb class="workspace-breadcrumb" separator="/" aria-label="当前位置">
      <el-breadcrumb-item>{{ currentGroup?.label || roleLabel }}</el-breadcrumb-item>
      <el-breadcrumb-item v-if="currentPage">{{ currentPage.label }}</el-breadcrumb-item>
    </el-breadcrumb>

    <div class="workspace-topbar-actions">
      <div class="workspace-menu-search">
        <el-autocomplete
          v-model="keyword"
          popper-class="workspace-menu-search-popper"
          :fetch-suggestions="suggestions"
          :debounce="0"
          fit-input-width
          clearable
          placeholder="搜索菜单"
          aria-label="搜索工作台菜单"
          @select="navigate"
        >
          <template #prefix><AppIcon name="search" :size="15" /></template>
          <template #default="{ item }">
            <span>{{ item.value }}</span>
            <small class="workspace-search-group">{{ item.group }}</small>
          </template>
        </el-autocomplete>
      </div>
      <el-popover placement="bottom-end" :width="280" trigger="click">
        <template #reference>
          <el-button class="workspace-help-button" text aria-label="工作台使用帮助">
            <AppIcon name="help" :size="18" />
          </el-button>
        </template>
        <div class="workspace-topbar-panel">
          <strong>工作台使用帮助</strong>
          <p>搜索菜单可快速打开当前工作台页面，支持方向键选择、回车打开。</p>
          <p v-if="roleLabel === '申请人'">
            在「提交政策」填写申请，在「我的提交」查看进度和驳回原因，在「机型补贴查询」查看负责平台的已通过政策。切换菜单会保留当前填写内容，刷新前请先保存草稿。
          </p>
          <p v-else>在「商品」导入、清洗和维护商品，在「政策管理」查看及审核政策申请。</p>
        </div>
      </el-popover>
      <div class="workspace-user" :aria-label="`当前用户：${displayName}`">
        <span class="workspace-user-avatar" aria-hidden="true">
          <img
            v-if="avatarUrl && !avatarFailed"
            :src="avatarUrl"
            alt=""
            referrerpolicy="no-referrer"
            @error="avatarFailed = true"
          />
          <template v-else>{{ initial }}</template>
        </span>
        <span class="workspace-user-name">{{ displayName }}</span>
      </div>
    </div>
  </header>
</template>

<style scoped>
.workspace-topbar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 16px;
  min-height: 54px;
  padding: 8px 20px;
  background: #fff;
  border-bottom: 1px solid #e8eef8;
}
.workspace-topbar .el-button {
  margin: 0;
}
.workspace-menu-toggle,
.workspace-help-button {
  flex: none;
  width: 32px;
  padding: 6px;
  color: #52637e;
}
.workspace-breadcrumb {
  min-width: 0;
  font-size: 12px;
  line-height: 20px;
}
.workspace-topbar-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.workspace-menu-search {
  width: 220px;
}
.workspace-menu-search :deep(.el-autocomplete) {
  width: 100%;
}
.workspace-user {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  max-width: 180px;
  height: 36px;
  padding: 2px 4px;
  color: #25334b;
}
.workspace-user-avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  overflow: hidden;
  background: #e8effb;
  color: #4e6c9b;
  font-weight: 600;
}
.workspace-user-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.workspace-user-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.workspace-topbar-panel {
  line-height: 1.7;
  color: #52637e;
  overflow-wrap: anywhere;
}
.workspace-topbar-panel strong {
  color: #25334b;
}
.workspace-topbar-panel p {
  margin-top: 8px;
  font-size: 12px;
}
.workspace-search-group {
  margin-left: 12px;
  color: #8d9ab0;
}
@media (max-width: 900px) {
  .workspace-topbar {
    gap: 10px;
    padding-inline: 12px;
  }
  .workspace-topbar-actions {
    gap: 8px;
  }
  .workspace-menu-search {
    width: 170px;
  }
  .workspace-user {
    max-width: 110px;
  }
}
@media (max-width: 600px) {
  .workspace-topbar {
    flex-wrap: wrap;
    gap: 6px;
  }
  .workspace-breadcrumb {
    flex: 1;
  }
  .workspace-topbar-actions {
    display: contents;
  }
  .workspace-menu-search {
    order: 1;
    width: 100%;
  }
  .workspace-user {
    max-width: 120px;
  }
}
</style>
