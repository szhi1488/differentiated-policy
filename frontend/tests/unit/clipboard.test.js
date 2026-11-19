// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { copyText } from '../../src/utils/clipboard'

const productNo = '00023  Reno17 中文-01'

afterEach(() => {
  vi.unstubAllGlobals()
  delete document.execCommand
  document.body.replaceChildren()
})

it('系统剪贴板可用时直接写入完整货号', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', { clipboard: { writeText } })
  document.execCommand = vi.fn()
  expect(await copyText(productNo)).toBe(true)
  expect(writeText).toHaveBeenCalledExactlyOnceWith(productNo)
  expect(document.execCommand).not.toHaveBeenCalled()
})

it.each([
  ['接口缺失', undefined],
  ['写入被拒绝', { writeText: () => Promise.reject(new Error('写入被拒绝')) }],
])('%s 时选中原文复制，并将焦点还给按钮', async (_, clipboard) => {
  vi.stubGlobal('navigator', { clipboard })
  const button = document.createElement('button')
  document.body.append(button)
  button.focus()
  let selectedText
  document.execCommand = vi.fn(() => {
    const field = document.activeElement
    selectedText = field.value.slice(field.selectionStart, field.selectionEnd)
    return true
  })
  expect(await copyText(productNo)).toBe(true)
  expect(document.execCommand).toHaveBeenCalledExactlyOnceWith('copy')
  expect(selectedText).toBe(productNo)
  expect(document.querySelector('textarea')).toBeNull()
  expect(document.activeElement).toBe(button)
})

it('兼容命令返回失败或抛错时清理临时输入框，并返回手动复制状态', async () => {
  vi.stubGlobal('navigator', {})
  expect(await copyText(productNo)).toBe(false)
  document.execCommand = vi
    .fn()
    .mockReturnValueOnce(false)
    .mockImplementationOnce(() => {
      throw new Error('禁止复制')
    })
  expect(await copyText(productNo)).toBe(false)
  expect(await copyText(productNo)).toBe(false)
  expect(document.querySelector('textarea')).toBeNull()
})
