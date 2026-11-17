import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import * as policies from '../../src/api/policies'
import { usePolicyDraft } from '../../src/composables/usePolicyDraft'
import { policyDraft } from '../../src/utils/policy-draft'
import { createForm } from '../../src/utils/policy-form'

vi.mock('../../src/api/policies', () => ({
  createPolicyDraft: vi.fn(),
  savePolicyDraft: vi.fn(),
  submitPolicy: vi.fn(),
  deletePolicyDraft: vi.fn(),
  readPolicy: vi.fn(),
}))

const NativeAbortController = globalThis.AbortController
let controllers

function deferred() {
  let resolve
  let reject
  const promise = new Promise((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}

function detail(id) {
  return {
    id,
    status: 'DRAFT',
    recordVersion: 1,
    submissionVersion: 0,
    form: policyDraft(createForm()),
    goods: [],
  }
}

beforeEach(() => {
  vi.resetAllMocks()
  controllers = []
  vi.stubGlobal(
    'AbortController',
    class extends NativeAbortController {
      constructor() {
        super()
        controllers.push(this)
      }
    },
  )
})

afterEach(() => vi.unstubAllGlobals())

describe('政策草稿请求生命周期', () => {
  it.each(['load', 'save', 'submit', 'delete'])(
    '%s 请求取消后解除禁用且不显示错误',
    async (kind) => {
      const state = usePolicyDraft(ref(createForm()), ref(false))
      const request = deferred()
      policies.readPolicy.mockReturnValue(request.promise)
      policies.createPolicyDraft.mockReturnValue(request.promise)
      policies.deletePolicyDraft.mockReturnValue(request.promise)
      const pending =
        kind === 'load'
          ? state.load('1')
          : kind === 'delete'
            ? state.deleteDraft('1', 1)
            : kind === 'submit'
              ? state.submit()
              : state.saveDraft()

      expect(state.busy.value).toBe(true)
      expect(state.locked.value).toBe(true)
      controllers[0].abort()
      request.reject(new DOMException('请求已取消', 'AbortError'))
      await pending

      expect(state.loading.value).toBe(false)
      expect(state.writing.value).toBe('')
      expect(state.busy.value).toBe(false)
      expect(state.locked.value).toBe(false)
      expect(state.notice.value).toBe('')
      expect(state.loadError.value).toBe('')
      expect(state.record.value).toBeNull()
    },
  )

  it('旧读取晚结束时保留新读取的加载状态及最终内容', async () => {
    const state = usePolicyDraft(ref(createForm()), ref(false))
    const oldRequest = deferred()
    const newRequest = deferred()
    policies.readPolicy
      .mockReturnValueOnce(oldRequest.promise)
      .mockReturnValueOnce(newRequest.promise)
    const oldLoad = state.load('1')
    const newLoad = state.load('2')
    expect(controllers[0].signal.aborted).toBe(true)

    oldRequest.resolve(detail('1'))
    expect(await oldLoad).toBe(false)
    expect(state.loading.value).toBe(true)
    expect(state.locked.value).toBe(true)
    expect(state.record.value).toBeNull()

    newRequest.resolve(detail('2'))
    expect(await newLoad).toBe(true)
    expect(state.record.value.id).toBe('2')
    expect(state.busy.value).toBe(false)
  })

  it.each(['save', 'submit', 'delete'])(
    '清理 %s 后重新保存，旧请求晚结束不会解除新请求的禁用状态',
    async (kind) => {
      const state = usePolicyDraft(ref(createForm()), ref(false))
      const oldRequest = deferred()
      const newRequest = deferred()
      policies.createPolicyDraft.mockReturnValueOnce(
        kind === 'delete' ? newRequest.promise : oldRequest.promise,
      )
      if (kind !== 'delete') {
        policies.createPolicyDraft.mockReturnValueOnce(newRequest.promise)
      }
      policies.deletePolicyDraft.mockReturnValueOnce(oldRequest.promise)
      const oldWrite =
        kind === 'delete'
          ? state.deleteDraft('1', 1)
          : kind === 'submit'
            ? state.submit()
            : state.saveDraft()
      state.clear()
      expect(controllers[0].signal.aborted).toBe(true)
      expect(state.locked.value).toBe(false)
      const newWrite = state.saveDraft()

      oldRequest.resolve(detail('1'))
      await oldWrite
      expect(state.writing.value).toBe('save')
      expect(state.busy.value).toBe(true)
      expect(state.locked.value).toBe(true)
      expect(state.record.value).toBeNull()
      expect(policies.submitPolicy).not.toHaveBeenCalled()

      newRequest.resolve(detail('2'))
      expect(await newWrite).toBe(true)
      expect(state.record.value.id).toBe('2')
      expect(state.writing.value).toBe('')
      expect(state.locked.value).toBe(false)
    },
  )
})
