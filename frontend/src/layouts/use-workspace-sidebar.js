import { computed, onScopeDispose, ref } from 'vue'

/** 桌面收起状态与手机临时菜单分开维护，切换尺寸时关闭遮罩。 */
export function useWorkspaceSidebar(breakpoint) {
  const media = window.matchMedia(`(max-width: ${breakpoint}px)`)
  const narrow = ref(media.matches)
  const collapsed = ref(false)
  const menuOpen = ref(false)
  function resize(event) {
    narrow.value = event.matches
    menuOpen.value = false
  }
  media.addEventListener('change', resize)
  onScopeDispose(() => media.removeEventListener('change', resize))
  const expanded = computed(() => (narrow.value ? menuOpen.value : !collapsed.value))
  function toggle() {
    if (narrow.value) {
      menuOpen.value = !menuOpen.value
    } else {
      collapsed.value = !collapsed.value
    }
  }
  return { collapsed, menuOpen, expanded, toggle }
}
