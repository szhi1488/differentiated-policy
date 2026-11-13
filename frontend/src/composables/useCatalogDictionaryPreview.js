import { computed, shallowRef } from 'vue'
import {
  dictionaryRemovalReason,
  indexDictionary,
  validateDictionaryEntry,
} from '../utils/catalog-dictionary'

export function useCatalogDictionaryPreview(samples) {
  const initialEntries = []
  let sequence = 0
  function ensureEntry(type, name, parentId = null) {
    const existing = initialEntries.find(
      (entry) => entry.type === type && entry.parentId === parentId && entry.name === name,
    )
    if (existing) {
      return existing.id
    }
    const entry = { id: `preview-${++sequence}`, type, parentId, name }
    initialEntries.push(entry)
    return entry.id
  }
  const initialProducts = samples.map((row) => {
    const { brand, series, color, ...product } = row
    const brandId = ensureEntry('brand', brand)
    const seriesId = ensureEntry('series', series, brandId)
    const colorId = ensureEntry('color', color)
    return { ...product, brandId, seriesId, colorId }
  })
  const catalogEntries = shallowRef(initialEntries.filter((entry) => entry.type !== 'color'))
  const colorEntries = shallowRef(initialEntries.filter((entry) => entry.type === 'color'))
  const entries = computed(() => [...catalogEntries.value, ...colorEntries.value])
  const byId = computed(() => indexDictionary(entries.value))
  const tableFor = (type) => (type === 'color' ? colorEntries : catalogEntries)

  function projectProduct(product) {
    return {
      ...product,
      brand: byId.value.get(product.brandId).name,
      series: byId.value.get(product.seriesId).name,
      color: byId.value.get(product.colorId).name,
    }
  }

  function saveEntry(draft) {
    const current = draft.id ? byId.value.get(draft.id) : null
    if (draft.id && !current) {
      return '该字典项已不存在'
    }
    const entry = {
      id: current?.id,
      type: current?.type ?? draft.type,
      parentId: current ? current.parentId : draft.parentId,
      name: draft.name,
    }
    const error = validateDictionaryEntry(entries.value, entry)
    if (error) {
      return error
    }
    entry.name = entry.name.trim()
    const table = tableFor(entry.type)
    if (current) {
      table.value = table.value.map((item) => (item.id === entry.id ? entry : item))
    } else {
      entry.id = `preview-${++sequence}`
      table.value = [...table.value, entry]
    }
    return ''
  }

  function removeEntry(id, usedEntryIds) {
    const entry = byId.value.get(id)
    if (!entry) {
      return '该字典项已不存在'
    }
    const reason = dictionaryRemovalReason(entries.value, entry, usedEntryIds)
    if (reason) {
      return reason
    }
    const table = tableFor(entry.type)
    table.value = table.value.filter((item) => item.id !== id)
    return ''
  }

  return { entries, byId, initialProducts, projectProduct, saveEntry, removeEntry }
}
