// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElMessageBox } from 'element-plus'
import { usePolicyForm } from '../../src/composables/usePolicyForm'
import * as policies from '../../src/api/policies'
import { policyDraft } from '../../src/utils/policy-draft'
import { createForm } from '../../src/utils/policy-form'

afterEach(() => vi.restoreAllMocks())

describe('申请表单默认确认框', () => {
  it('未注入时使用 Element Plus，新建申请取消保留内容，确认后才清空', async () => {
    const nativeConfirm = vi.spyOn(window, 'confirm')
    const confirm = vi
      .spyOn(ElMessageBox, 'confirm')
      .mockRejectedValueOnce('cancel')
      .mockResolvedValueOnce('confirm')
    const state = usePolicyForm()
    state.updateField('remark', '尚未保存')

    expect(await state.reset()).toBe(false)
    expect(state.form.value.remark).toBe('尚未保存')
    expect(state.confirming.value).toBe(false)
    expect(confirm).toHaveBeenLastCalledWith(
      '确定清空当前填写的内容并打开新申请吗？',
      '确认操作',
      expect.objectContaining({
        confirmButtonText: '新建申请',
        cancelButtonText: '保留内容',
        closeOnHashChange: false,
      }),
    )
    expect(await state.reset()).toBe(true)
    expect(state.form.value).toEqual(createForm())
    expect(nativeConfirm).not.toHaveBeenCalled()
  })

  it('读取服务端版本使用对应文案，取消不发请求且保留输入', async () => {
    const confirm = vi
      .spyOn(ElMessageBox, 'confirm')
      .mockRejectedValueOnce('cancel')
      .mockResolvedValueOnce('confirm')
    const read = vi.spyOn(policies, 'readPolicy').mockResolvedValue({
      id: '1',
      status: 'DRAFT',
      recordVersion: 2,
      form: policyDraft({ ...createForm(), remark: '服务端内容' }),
      goods: [],
    })
    const state = usePolicyForm()
    state.updateField('remark', '保留本地输入')
    state.conflict.value = true

    await state.reloadDraft('1')
    expect(state.form.value.remark).toBe('保留本地输入')
    expect(read).not.toHaveBeenCalled()
    expect(confirm).toHaveBeenLastCalledWith(
      '读取服务端版本会替换当前填写内容，是否继续？',
      '确认操作',
      expect.objectContaining({
        confirmButtonText: '读取服务端版本',
        cancelButtonText: '保留内容',
      }),
    )

    await state.reloadDraft('1')
    expect(read).toHaveBeenCalledOnce()
    expect(state.form.value.remark).toBe('服务端内容')
    expect(state.conflict.value).toBe(false)
  })

  it('打开另一申请使用对应文案，关闭弹窗仍保留当前输入', async () => {
    const confirm = vi
      .spyOn(ElMessageBox, 'confirm')
      .mockRejectedValueOnce('close')
      .mockResolvedValueOnce('confirm')
    const state = usePolicyForm()
    state.updateField('remark', '当前申请')

    expect(await state.confirmPolicyReplacement()).toBe(false)
    expect(state.form.value.remark).toBe('当前申请')
    expect(state.confirming.value).toBe(false)
    expect(confirm).toHaveBeenLastCalledWith(
      '打开该申请会替换当前未保存的内容，是否继续？',
      '确认操作',
      expect.objectContaining({
        confirmButtonText: '打开申请',
        cancelButtonText: '保留内容',
        closeOnHashChange: false,
      }),
    )
    expect(await state.confirmPolicyReplacement()).toBe(true)
  })
})
