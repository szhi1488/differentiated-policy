import { describe, expect, it } from 'vitest'
import { feishuFailure, getFeishuErrorCode } from './feishu-error'

describe('safe Feishu error messages', () => {
  it('extracts numeric error codes only', () => {
    expect(getFeishuErrorCode({ errno: 2700002, errCode: 2000 })).toBe('2700002')
    expect(getFeishuErrorCode({ errCode: '333448' })).toBe('333448')
    expect(getFeishuErrorCode({ errno: 'private-token', code: 'not-a-number' })).toBeNull()
  })

  it('shows outer and inner codes with an actionable redirect hint', () => {
    const error = feishuFailure('飞书免登授权', {
      errno: 2700002, errString: 'Authorization terminated unexpectedly. Error code: 2000; error message: private-data',
      ticket: 'private-ticket', code: 'private-code',
    })
    expect(error.message).toContain('2700002，内层错误码 2000')
    expect(error.message).toContain('重定向 URL')
    expect(error.message).not.toMatch(/private-data|private-ticket|private-code/)
  })

  it('does not expose raw messages, URLs or signatures', () => {
    const error = feishuFailure('飞书 JS-SDK 鉴权', {
      errCode: 333441, errorMessage: 'signature: private-signature, jsticket: private-ticket, url: https://example.com/?secret=private',
    })
    expect(error.message).toContain('333441')
    expect(error.message).not.toMatch(/private|https:/)
  })
})
