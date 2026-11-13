import { DICTIONARY_LEVELS } from '../constants/catalog-dictionary'

export const CATALOG_CONFIG_PATTERN = /^[1-9]\d{0,2}\s*\+\s*[1-9]\d{0,3}$/

export function catalogConfigLabel(config) {
  return config
    .split('+')
    .map((value) => value + 'GB')
    .join('+')
}

export function dictionaryOptions(entries, type, parentId) {
  return entries.filter((entry) => entry.type === type && entry.parentId === parentId)
}

export function indexDictionary(entries) {
  return new Map(entries.map((entry) => [entry.id, entry]))
}

export function validateDictionaryEntry(entries, { id, type, parentId, name }) {
  const normalized = name.trim()
  if (!normalized) {
    return '请填写名称'
  }
  if (!DICTIONARY_LEVELS.some(([key]) => key === type)) {
    return '请选择字典类型'
  }
  if (
    type === 'series' &&
    !entries.some((entry) => entry.id === parentId && entry.type === 'brand')
  ) {
    return '请选择所属品牌'
  }
  if (type !== 'series' && parentId !== null) {
    return '该字典项无需所属品牌'
  }
  if (
    dictionaryOptions(entries, type, parentId).some(
      (entry) => entry.id !== id && entry.name.toLowerCase() === normalized.toLowerCase(),
    )
  ) {
    return type === 'series' ? '该品牌下已存在同名系列' : '已存在相同名称'
  }
  return ''
}

export function dictionaryRemovalReason(entries, entry, usedEntryIds) {
  if (entries.some((item) => item.parentId === entry.id)) {
    return '请先移除该品牌下的系列'
  }
  if (usedEntryIds.has(entry.id)) {
    return '该字典项正在被商品使用'
  }
  return ''
}
