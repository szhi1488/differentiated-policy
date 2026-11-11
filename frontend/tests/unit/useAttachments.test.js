// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { deleteAttachment, uploadAttachment } from '../../src/api/attachments'
import { useAttachments } from '../../src/composables/useAttachments'

vi.mock('../../src/api/attachments', () => ({
  uploadAttachment: vi.fn(),
  deleteAttachment: vi.fn(),
}))

afterEach(() => vi.resetAllMocks())
const pdf = () => new File(['%PDF-1.7'], '政策.pdf', { type: 'application/pdf' })

describe('附件上传交互', () => {
  it('等待真实响应显示成功，期间阻止重复选择，移除时删除服务端文件', async () => {
    let complete
    uploadAttachment.mockReturnValue(new Promise((resolve) => (complete = resolve)))
    const state = useAttachments()
    const uploading = state.addFiles([pdf()])
    expect(state.attachmentsBusy.value).toBe(true)
    await state.addFiles([pdf()])
    expect(uploadAttachment).toHaveBeenCalledOnce()
    complete({ id: 'server-id', originalName: '政策.pdf' })
    await uploading
    expect(state.files.value[0].status).toBe('uploaded')
    expect(state.attachmentsBusy.value).toBe(false)
    await state.removeFile(state.files.value[0])
    expect(deleteAttachment).toHaveBeenCalledWith('server-id')
    expect(state.files.value).toEqual([])
  })

  it('上传失败保留文件供手动重试，删除失败保留下载标识', async () => {
    uploadAttachment.mockRejectedValueOnce(new Error('登录已过期'))
    const state = useAttachments()
    await state.addFiles([pdf()])
    const entry = state.files.value[0]
    expect(entry.status).toBe('failed')
    expect(entry.error).toBe('登录已过期')
    uploadAttachment.mockResolvedValueOnce({ id: 'retried' })
    await state.retryFile(entry)
    expect(entry.status).toBe('uploaded')
    deleteAttachment.mockRejectedValueOnce(new Error('存储不可用'))
    expect(await state.clearFiles()).toBe(false)
    expect(state.files.value[0].attachment.id).toBe('retried')
    expect(entry.error).toBe('存储不可用')
    deleteAttachment.mockRejectedValueOnce({ status: 404 })
    expect(await state.clearFiles()).toBe(true)
    expect(state.files.value).toEqual([])
  })

  it('拒绝空文件、超限数量/大小和不支持的格式', async () => {
    const state = useAttachments()
    await state.addFiles(Array.from({ length: 11 }, pdf))
    expect(state.attachmentNotice.value).toContain('10')
    const big = pdf()
    Object.defineProperty(big, 'size', { value: 20 * 1024 * 1024 + 1 })
    await state.addFiles([big])
    expect(state.attachmentNotice.value).toContain('20MB')
    await state.addFiles([new File([], '空.pdf'), new File(['code'], 'test.exe')])
    expect(state.files.value).toEqual([])
    expect(uploadAttachment).not.toHaveBeenCalled()
  })
})
