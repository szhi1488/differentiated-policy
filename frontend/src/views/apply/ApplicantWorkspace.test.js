// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ApplicantWorkspace from './ApplicantWorkspace.vue'

let wrapper

afterEach(() => wrapper?.unmount())

function button(text) {
  const result = wrapper.findAll('button').find((item) => item.text() === text)
  if (!result) {
    throw new Error(`找不到按钮：${text}`)
  }
  return result
}

describe('申请人工作台组件协作', () => {
  it('切换菜单后保留填写内容，平台独立回显，切换补贴方式清理残值', async () => {
    wrapper = mount(ApplicantWorkspace)
    await button('京东').trigger('click')
    await wrapper.get('[aria-label="京东第1行涉及型号"]').setValue('测试机型')
    await wrapper.get('[aria-label="第1行固定补贴金额"]').setValue('0.10')
    await button('我的提交').trigger('click')
    expect(wrapper.get('.policy-form').isVisible()).toBe(false)
    await button('提交政策').trigger('click')
    expect(wrapper.get('[aria-label="京东第1行涉及型号"]').element.value).toBe('测试机型')
    await button('拼多多').trigger('click')
    expect(wrapper.get('[aria-label="拼多多第1行涉及型号"]').element.value).toBe('')
    await wrapper.get('.platform-detail-nav button:last-of-type').trigger('click')
    expect(wrapper.get('[aria-label="第1行固定补贴金额"]').element.value).toBe('0.10')
    await wrapper.get('[aria-label="第1行补贴方式"]').setValue('RATIO')
    expect(wrapper.find('[aria-label="第1行固定补贴金额"]').exists()).toBe(false)
    await wrapper.get('[aria-label="第1行补贴方式"]').setValue('FIXED')
    expect(wrapper.get('[aria-label="第1行固定补贴金额"]').element.value).toBe('')
  })

  it('同一表单切换百亿后仅显示百亿平台与公共字段', async () => {
    wrapper = mount(ApplicantWorkspace)
    await wrapper.get('.basic-grid select').setValue('BILLION')
    expect(wrapper.findAll('.platform-chip').map((item) => item.text())).toEqual([
      '拼多多',
      '淘宝',
      '联通',
    ])
    expect(wrapper.findAll('fieldset')).toHaveLength(0)
    expect(wrapper.findAll('.policy-form')).toHaveLength(1)
  })
})
