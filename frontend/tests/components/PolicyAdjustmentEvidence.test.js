// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { ElDatePicker } from 'element-plus'
import PolicySubmissionCoverage from '../../src/views/apply/components/PolicySubmissionCoverage.vue'
import PolicySubmissionContent from '../../src/views/apply/components/PolicySubmissionContent.vue'
import PolicyEndAdjustments from '../../src/components/PolicyEndAdjustments.vue'
import AttachmentDownload from '../../src/components/AttachmentDownload.vue'
import { endOption } from '../fixtures/policy-applicability'

const extension = {
  ...endOption,
  canExtend: true,
  canShorten: false,
  earliestEnd: null,
  latestEnd: null,
  target: { ...endOption.target, previousEnd: '2096-12-31T23:59:59+08:00' },
}
const file = { type: 'FILE', id: 'proof-1', originalName: '活动延期.pdf' }
let wrapper
afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})
it('同一原单详情同时列出首次及当前截止、历次改期和各自的证明', () => {
  const initialEnd = '2026-12-30T00:00:00+08:00'
  const extendedEnd = '2026-12-31T00:00:00+08:00'
  const currentEnd = '2026-12-29T12:30:00+08:00'
  const common = { actorName: '经办人', changedAt: '2026-12-01T08:00:00+08:00' }
  wrapper = mount(PolicySubmissionContent, {
    props: {
      detail: {
        id: '24',
        submissionVersion: 2,
        applicantName: '申请人',
        form: {
          perspective: 'RETAIL',
          selectedPlatforms: ['KS'],
          sceneTypes: ['NATIONAL'],
          attachmentInfo: [],
          goodsForm: { cards: [] },
          endTime: initialEnd,
        },
        goods: [],
        timeHistory: {
          initialEnd,
          currentEnd,
          changes: [
            {
              ...common,
              id: '8',
              direction: 'SHORTEN',
              previousEnd: extendedEnd,
              requestedEnd: currentEnd,
              actualEnd: currentEnd,
              effectiveAt: currentEnd,
              reason: '平台活动收官',
              attachmentInfo: [],
            },
            {
              ...common,
              id: '7',
              direction: 'EXTEND',
              previousEnd: initialEnd,
              requestedEnd: extendedEnd,
              actualEnd: extendedEnd,
              effectiveAt: '2026-12-30T00:00:01+08:00',
              reason: '活动延续',
              attachmentInfo: [file],
            },
          ],
        },
      },
    },
  })
  expect(wrapper.text()).toContain('首次获批截止2026-12-30 00:00:00')
  expect(wrapper.text()).toContain('当前截止2026-12-29 12:30:00')
  const records = wrapper.get('[aria-label="时间调整记录"]').findAll('article')
  expect(records).toHaveLength(2)
  expect(records[0].text()).toContain('提前结束')
  expect(records[0].text()).toContain('平台活动收官')
  expect(records[1].text()).toContain('活动延续')
  expect(records[1].text()).toContain('经办人')
  expect(wrapper.getComponent(AttachmentDownload).props('url')).toBe(
    '/api/policy-reviews/24/time-changes/7/attachments/proof-1/content?submissionVersion=2',
  )
})
const button = (title) => wrapper.findAll('button').find((b) => b.text() === title)
async function openEditor() {
  const base = { scopes: [], ranges: [], options: [extension] }
  const recalculate = vi.fn(async () => ({ ...base, confirmationToken: 'time-change' }))
  wrapper = mount(PolicySubmissionCoverage, {
    props: { preview: { ...base, recalculate } },
    attachTo: document.body,
  })
  await flushPromises()
  await button('调整政策时间').trigger('click')
  await flushPromises()
  await wrapper.get('.end-options input[type=checkbox]').setValue(true)
  await button('添加所选政策').trigger('click')
  await wrapper.get('textarea').setValue('延续活动')
  return recalculate
}
async function selectProof() {
  const picker = wrapper.get('input[type=file]')
  Object.defineProperty(picker.element, 'files', {
    configurable: true,
    value: [new File(['pdf'], file.originalName)],
  })
  await picker.trigger('change')
}

it('仅可延期时限制新增区间，上传中及失败阻止确认，重试后按目标保留文件', async () => {
  const recalculate = await openEditor()
  const picker = wrapper.getComponent(ElDatePicker)
  for (const end of ['2096-12-30T00:00:00', '2096-12-31T23:59:59']) {
    picker.vm.$emit('update:modelValue', end)
    await flushPromises()
    expect(button('核对调整').element.disabled).toBe(true)
  }
  picker.vm.$emit('update:modelValue', '2097-01-01T00:00:00')
  await flushPromises()
  expect(button('核对调整').element.disabled).toBe(false)
  let finishUpload
  const fetch = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishUpload = resolve
        }),
    )
    .mockResolvedValueOnce(Response.json(file))
  vi.stubGlobal('fetch', fetch)
  await selectProof()
  expect(button('核对调整').element.disabled).toBe(true)
  finishUpload(Response.json({ message: '上传失败' }, { status: 503 }))
  await flushPromises()
  expect(button('核对调整').element.disabled).toBe(true)
  await button('重试').trigger('click')
  await flushPromises()
  await button('核对调整').trigger('click')
  expect(wrapper.get('.policy-end-confirm-dialog').text()).toContain(file.originalName)
  await button('确认调整').trigger('click')
  await flushPromises()
  expect(recalculate).toHaveBeenCalledWith([
    {
      target: extension.target,
      endTime: '2097-01-01T00:00:00+08:00',
      reason: '延续活动',
      attachmentInfo: [file],
    },
  ])
  await button('修改时间调整').trigger('click')
  await flushPromises()
  expect(wrapper.get('.end-rows').text()).toContain(file.originalName)
  await button('取消').trigger('click')
  expect(fetch).toHaveBeenCalledTimes(2)
  await button('确认整单并提交').trigger('click')
  await button('确认提交').trigger('click')
  expect(wrapper.emitted('confirm')[0][0].endAdjustments[0].attachmentInfo).toEqual([file])
})

it('取消时释放本次新上传的文件，包括关闭后才收到的上传结果', async () => {
  await openEditor()
  let finishUpload
  const fetch = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishUpload = resolve
        }),
    )
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
  vi.stubGlobal('fetch', fetch)
  await selectProof()
  await button('取消').trigger('click')
  finishUpload(Response.json(file))
  await flushPromises()
  expect(fetch).toHaveBeenCalledTimes(2)
  expect(fetch.mock.calls[1][0]).toBe('/api/attachments/' + file.id)
  expect(fetch.mock.calls[1][1].method).toBe('DELETE')
})

it.each([
  [true, '48', '/api/admin/policies/9/audit-logs/48/attachments/proof-1/content'],
  [false, null, '/api/policies/9/attachments/proof-1/content'],
  [false, '48', '/api/policies/9/audit-logs/48/attachments/proof-1/content'],
])('随单调整明细按发起单和查看的轮次提供文件 (%s, %s)', (admin, logId, url) => {
  const change = {
    target: extension.target,
    endTime: '2097-01-10T12:00:00+08:00',
    reason: '延续活动',
    attachmentInfo: [file],
  }
  wrapper = mount(PolicyEndAdjustments, {
    props: { admin, logId, policyId: '9', changes: [change] },
  })
  expect(wrapper.findAll('article')).toHaveLength(1)
  expect(wrapper.text()).toContain('延长至')
  expect(wrapper.text()).toContain(change.reason)
  expect(wrapper.getComponent(AttachmentDownload).props('url')).toBe(url)
})
