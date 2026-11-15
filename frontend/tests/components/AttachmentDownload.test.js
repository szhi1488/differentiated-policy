// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import AttachmentDownload from '../../src/components/AttachmentDownload.vue'
import { setUnauthorizedHandler } from '../../src/api/request'

const path = '/api/admin/policies/5/audit-logs/2/attachments/file-id/content'
let wrapper
let createObjectURL
let revokeObjectURL
let save

beforeEach(() => {
  createObjectURL = vi.fn(() => 'blob:http://localhost/download')
  revokeObjectURL = vi.fn()
  class DownloadURL extends URL {}
  DownloadURL.createObjectURL = createObjectURL
  DownloadURL.revokeObjectURL = revokeObjectURL
  vi.stubGlobal('URL', DownloadURL)
  save = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () {
    expect(this.download).toBe('政策说明.pdf')
    expect(this.href).toBe('blob:http://localhost/download')
    expect(this.target).toBe('')
  })
})

afterEach(() => {
  ElMessage.closeAll()
  wrapper?.unmount()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  setUnauthorizedHandler(() => {})
})

it('从当前页面读取附件后保存，加载期间合并重复点击并释放临时地址', async () => {
  vi.useFakeTimers()
  let resolve
  const blob = new Blob(['%PDF-1.7 测试正文'])
  const fetch = vi.fn(
    () =>
      new Promise((done) => {
        resolve = done
      }),
  )
  vi.stubGlobal('fetch', fetch)
  wrapper = mount(AttachmentDownload, { props: { url: path, filename: '政策说明.pdf' } })
  await wrapper.get('[role="button"]').trigger('click')
  await wrapper.get('[role="button"]').trigger('keydown', { key: 'Enter' })
  expect(fetch).toHaveBeenCalledOnce()
  expect(fetch).toHaveBeenCalledWith(path, expect.objectContaining({ credentials: 'same-origin' }))
  expect(save).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('下载中')
  resolve({ ok: true, status: 200, blob: async () => blob })
  await flushPromises()
  expect(createObjectURL).toHaveBeenCalledWith(blob)
  expect(save).toHaveBeenCalledOnce()
  await vi.advanceTimersByTimeAsync(1000)
  expect(revokeObjectURL).toHaveBeenCalledWith('blob:http://localhost/download')
})

it.each(
  [401, 403, 404].flatMap((status) => [
    [status, false],
    [status, true],
  ]),
)('HTTP %i 失败按弹出模式 %s 展示实际信息', async (status, popupError) => {
  const expired = vi.fn()
  setUnauthorizedHandler(expired)
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: false,
      status,
      json: async () => ({
        code: status === 401 ? 'LOGIN_REQUIRED' : 'ATTACHMENT_NOT_FOUND',
        message: '附件读取失败，请核对登录或权限。',
      }),
    })),
  )
  wrapper = mount(AttachmentDownload, {
    props: { url: path, filename: '政策说明.pdf', popupError },
  })
  await wrapper.get('[role="button"]').trigger('click')
  await flushPromises()
  if (popupError) {
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '附件读取失败，请核对登录或权限。',
    )
  } else {
    expect(wrapper.get('[role="alert"]').text()).toBe('附件读取失败，请核对登录或权限。')
  }
  expect(save).not.toHaveBeenCalled()
  expect(createObjectURL).not.toHaveBeenCalled()
  expect(expired).toHaveBeenCalledTimes(status === 401 ? 1 : 0)
})

it('切换页面后中止下载，迟到的文件响应不会继续保存', async () => {
  let resolve
  const fetch = vi.fn(
    () =>
      new Promise((done) => {
        resolve = done
      }),
  )
  vi.stubGlobal('fetch', fetch)
  wrapper = mount(AttachmentDownload, { props: { url: path, filename: '政策说明.pdf' } })
  await wrapper.get('[role="button"]').trigger('click')
  const signal = fetch.mock.calls[0][1].signal
  wrapper.unmount()
  wrapper = null
  expect(signal.aborted).toBe(true)
  resolve({ ok: true, status: 200, blob: async () => new Blob(['文件正文']) })
  await flushPromises()
  expect(save).not.toHaveBeenCalled()
})
