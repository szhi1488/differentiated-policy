import { computed } from 'vue'
import {
  readCatalogDictionary,
  removeCatalogDictionaryEntry,
  saveCatalogDictionaryEntry,
} from '../api/catalog'
import { useLatestQuery } from './useLatestQuery'

export function useCatalogDictionary() {
  const query = useLatestQuery()
  const entries = computed(() =>
    (query.data.value ?? []).map((row) => ({
      ...row,
      serverId: row.id,
      id: row.type + '-' + row.id,
      parentId: row.parentId ? 'brand-' + row.parentId : null,
    })),
  )

  function load() {
    return query.run(readCatalogDictionary)
  }

  async function save(entry) {
    await saveCatalogDictionaryEntry({
      ...entry,
      id: entry.serverId,
      parentId: entry.parentId?.slice('brand-'.length) || null,
    })
    await load()
  }

  async function remove(entry) {
    await removeCatalogDictionaryEntry({ ...entry, id: entry.serverId })
    await load()
  }

  return { entries, loading: query.loading, error: query.error, load, save, remove }
}
