// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElDatePicker, ElSelect, ElUpload } from 'element-plus'
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
    wrapper = mount(ApplicantWorkspace, { attachTo: document.body })
    await wrapper
      .findAll('.el-check-tag')
      .find((item) => item.text() === '京东')
      .trigger('click')
    await flushPromises()
    await wrapper.get('[aria-label="京东第1行涉及型号"]').setValue('测试机型')
    await wrapper.get('[aria-label="第1行固定补贴金额"]').setValue('0.10')
    await button('我的提交').trigger('click')
    expect(wrapper.get('.policy-form').isVisible()).toBe(false)
    await button('提交政策').trigger('click')
    expect(wrapper.get('[aria-label="京东第1行涉及型号"]').element.value).toBe('测试机型')
    await wrapper
      .findAll('.el-check-tag')
      .find((item) => item.text() === '拼多多')
      .trigger('click')
    await flushPromises()
    expect(wrapper.get('[aria-label="拼多多第1行涉及型号"]').element.value).toBe('')
    await wrapper.get('#tab-JD').trigger('click')
    await flushPromises()
    expect(wrapper.get('[aria-label="第1行固定补贴金额"]').element.value).toBe('0.10')
    wrapper
      .findAllComponents(ElSelect)
      .find((item) => item.find('input[aria-label="第1行补贴方式"]').exists())
      .vm.$emit('update:modelValue', 'RATIO')
    await flushPromises()
    expect(wrapper.find('[aria-label="第1行固定补贴金额"]').exists()).toBe(false)
    wrapper
      .findAllComponents(ElSelect)
      .find((item) => item.find('input[aria-label="第1行补贴方式"]').exists())
      .vm.$emit('update:modelValue', 'FIXED')
    await flushPromises()
    expect(wrapper.get('[aria-label="第1行固定补贴金额"]').element.value).toBe('')
  })

  it('同一表单切换百亿后仅显示百亿平台与公共字段', async () => {
    wrapper = mount(ApplicantWorkspace, { attachTo: document.body })
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'BILLION')
    await flushPromises()
    expect(wrapper.findAll('.el-check-tag').map((item) => item.text())).toEqual([
      '拼多多',
      '淘宝',
      '联通',
    ])
    expect(wrapper.findAll('.el-radio-group')).toHaveLength(0)
    expect(wrapper.findAll('.policy-form')).toHaveLength(1)
  })

  it('取消渠道清除时保留内容，确认后切换渠道并清空明细', async () => {
    wrapper = mount(ApplicantWorkspace, { attachTo: document.body })
    await wrapper
      .findAll('.el-check-tag')
      .find((item) => item.text() === '京东')
      .trigger('click')
    await flushPromises()
    await wrapper.get('[aria-label="第1行数量"]').setValue('0')
    const channel = wrapper.findComponent(ElSelect)
    channel.vm.$emit('update:modelValue', 'BILLION')
    await flushPromises()
    const cancel = [...document.querySelectorAll('.el-message-box button')].find((item) =>
      item.textContent.includes('保留内容'),
    )
    cancel.click()
    await flushPromises()
    expect(channel.props('modelValue')).toBe('RETAIL')
    expect(wrapper.get('[aria-label="第1行数量"]').element.value).toBe('0')
    channel.vm.$emit('update:modelValue', 'BILLION')
    await flushPromises()
    const confirm = [...document.querySelectorAll('.el-message-box button')].findLast((item) =>
      item.textContent.includes('确认清除'),
    )
    confirm.click()
    await flushPromises()
    expect(channel.props('modelValue')).toBe('BILLION')
    expect(wrapper.findAll('.el-radio-group')).toHaveLength(0)
    expect(wrapper.find('[aria-label="第1行数量"]').exists()).toBe(false)
  })

  it('日期精确到秒且可清空，附件仅暂存并能移除，不伪报提交成功', async () => {
    wrapper = mount(ApplicantWorkspace, { attachTo: document.body })
    const picker = wrapper.findComponent(ElDatePicker)
    const range = ['2026-11-11T09:10:11', '2026-11-12T23:59:58']
    picker.vm.$emit('update:modelValue', range)
    await flushPromises()
    expect(picker.props('modelValue')).toEqual(range)
    picker.vm.$emit('update:modelValue', null)
    await flushPromises()
    expect(picker.props('modelValue')).toEqual([])
    const upload = wrapper.findComponent(ElUpload)
    const file = new File(['测试附件'], '说明.txt', { type: 'text/plain' })
    file.uid = 123
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()
    expect(upload.props('autoUpload')).toBe(false)
    expect(wrapper.text()).toContain('说明.txt')
    await wrapper.get('.el-upload-list__item .el-icon--close').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('说明.txt')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('.el-alert').text()).toContain('当前填写内容未提交')
  })
})
