// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import { ElSelect } from 'element-plus'
import PolicyDetailsTable from '../../src/views/apply/components/PolicyDetailsTable.vue'
import { createDetail } from '../../src/utils/policy-form'
it('输入、切补贴和删除仅发业务意图，不修改父级深层对象', async () => {
  const row = Object.freeze({ ...createDetail(), key: 'stable', fixedAmount: '0.10' })
  const wrapper = mount(PolicyDetailsTable, {
    props: { rows: Object.freeze([row]), platformName: '京东', hasPlatform: true },
  })
  await flushPromises()
  await wrapper.get('[aria-label="京东第1行涉及型号"]').setValue('新名称')
  wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'RATIO')
  await wrapper.get('[aria-label="删除第1行机型"]').trigger('click')
  expect(wrapper.emitted('update-detail')).toEqual([
    ['stable', 'model', '新名称'],
    ['stable', 'mode', 'RATIO'],
  ])
  expect(wrapper.emitted('remove-detail')).toEqual([['stable']])
  expect(row.model).toBe('')
  expect(row.fixedAmount).toBe('0.10')
  wrapper.unmount()
})
