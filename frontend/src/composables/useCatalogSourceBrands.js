import { computed, onScopeDispose, toValue, watch } from 'vue'
import { readCatalogSourceBrands } from '../api/catalog'
import { useLatestQuery } from './useLatestQuery'

export function useCatalogSourceBrands(enabled = true) {
  const query = useLatestQuery()
  const names = computed(() => query.data.value?.brandNames ?? [])
  const unassigned = computed(() => query.data.value !== null && !names.value.length)
  function reload() {
    return query.run(toValue(enabled) ? readCatalogSourceBrands : async () => null, {
      keepData: toValue(enabled),
    })
  }
  watch(() => toValue(enabled), reload, { immediate: true })
  function refreshOnFocus() {
    if (toValue(enabled)) {
      void reload()
    }
  }
  window.addEventListener('focus', refreshOnFocus)
  onScopeDispose(() => window.removeEventListener('focus', refreshOnFocus))
  return { names, unassigned, error: query.error, reload }
}
