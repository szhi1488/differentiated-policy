import { describe, expect, it } from 'vitest'
import { useCatalogCenterPreview } from '../../src/composables/useCatalogCenterPreview'
import { useCatalogDictionaryPreview } from '../../src/composables/useCatalogDictionaryPreview'
import { dictionaryOptions } from '../../src/utils/catalog-dictionary'

const base = {
  name: 'OPPO Reno12',
  brand: 'OPPO',
  series: 'Reno 系列',
  model: 'Reno12',
  color: '星云紫',
  source: 'Reno12',
  version: '大陆版',
  status: 'active',
}
const samples = [
  { ...base, id: 'a', sku: 'Reno12-a', config: '12+256' },
  { ...base, id: 'b', sku: 'Reno12-b', config: '16+512' },
]

describe('商品中心预览字典', () => {
  it('不同配置共用独立颜色，拒绝全局同名颜色和带上级的颜色', () => {
    const dictionary = useCatalogDictionaryPreview(samples)
    const [first, second] = dictionary.initialProducts
    expect(first.colorId).toBe(second.colorId)
    expect(dictionaryOptions(dictionary.entries.value, 'color', null)).toEqual([
      { id: first.colorId, type: 'color', parentId: null, name: '星云紫' },
    ])
    expect(dictionary.entries.value.map(({ type }) => type)).toEqual(['brand', 'series', 'color'])
    expect(dictionary.saveEntry({ type: 'color', name: ' 星云紫 ', parentId: null })).toBe(
      '已存在相同名称',
    )
    expect(dictionary.saveEntry({ type: 'color', name: '白色', parentId: first.seriesId })).toBe(
      '该字典项无需所属品牌',
    )
  })

  it('改色名同步到引用商品，编辑商品配置时可保留全局颜色', () => {
    const dictionary = useCatalogDictionaryPreview(samples)
    const preview = useCatalogCenterPreview(dictionary)
    const [first] = dictionary.initialProducts
    expect(dictionary.saveEntry({ id: first.colorId, name: '烟紫' })).toBe('')
    expect(preview.visibleRows.value.map((row) => row.color)).toEqual(['烟紫', '烟紫'])
    expect(dictionary.removeEntry(first.brandId, preview.usedEntryIds.value)).toBe(
      '请先移除该品牌下的系列',
    )
    expect(dictionary.removeEntry(first.colorId, preview.usedEntryIds.value)).toBe(
      '该字典项正在被商品使用',
    )
    preview.savePreviewProduct(first.id, {
      ...first,
      model: '新机型',
      config: '8+128',
    })
    expect(preview.visibleRows.value[0]).toMatchObject({
      model: '新机型',
      config: '8+128',
      color: '烟紫',
    })
    expect(samples[0].color).toBe('星云紫')
  })
})
