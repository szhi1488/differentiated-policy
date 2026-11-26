export const CATALOG_RAM_PATTERN = /^[1-9]\d{0,7}\s*(?:GB?)?$/i
export const CATALOG_ROM_PATTERN = /^[1-9]\d{0,7}\s*(?:GB?|TB?)?$/i

export function catalogConfigLabel(config) {
  return config
    .split('+')
    .map((value, index) => (index === 1 && value === '1024' ? '1T' : value + 'GB'))
    .join('+')
}

export function catalogGoodsNameLabel(name) {
  return name.replace(/(\b\d+(?:\s*GB?)?\s*\+\s*)1024\s*GB?\b/gi, (_, ram) => ram + '1T')
}

export function dictionaryOptions(entries, type, parentId) {
  return entries.filter((entry) => entry.type === type && entry.parentId === parentId)
}

export function indexDictionary(entries) {
  return new Map(entries.map((entry) => [entry.id, entry]))
}
