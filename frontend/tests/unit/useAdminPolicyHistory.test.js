import { effectScope, nextTick, ref } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import { listAdminPolicyAuditLogs, readAdminPolicyAuditLog } from '../../src/api/admin-policies'
import { useAdminPolicyHistory } from '../../src/composables/useAdminPolicyHistory'
import { auditLogs, historySnapshot } from '../fixtures/admin-policy-history'

vi.mock('../../src/api/admin-policies', () => ({
  listAdminPolicyAuditLogs: vi.fn(),
  readAdminPolicyAuditLog: vi.fn(),
}))
let scope
afterEach(() => {
  scope?.stop()
  vi.resetAllMocks()
})
const settle = async () => {
  await nextTick()
  await Promise.resolve()
  await nextTick()
}
function setup() {
  scope = effectScope()
  const id = ref('9007199254740993')
  vi.mocked(listAdminPolicyAuditLogs).mockResolvedValue({ items: auditLogs, total: 12 })
  return { id, history: scope.run(() => useAdminPolicyHistory(id)) }
}

it('按服务端分页，切换申请回到第一页并保持字符串 ID', async () => {
  const { history, id } = setup()
  await settle()
  history.page.value = 2
  await settle()
  expect(listAdminPolicyAuditLogs).toHaveBeenLastCalledWith(
    id.value,
    { page: 2, pageSize: 10 },
    expect.any(AbortSignal),
  )
  history.selectedLogId.value = '901'
  await settle()
  id.value = '9007199254740994'
  await settle()
  expect(history.selectedLogId.value).toBe('')
  expect(history.page.value).toBe(1)
  expect(listAdminPolicyAuditLogs).toHaveBeenLastCalledWith(
    id.value,
    { page: 1, pageSize: 10 },
    expect.any(AbortSignal),
  )
  expect(history.detail.value).toBeNull()
})

it('切换申请后迟到的旧快照不能覆盖新记录，返回列表和卸载时取消读取', async () => {
  const { history, id } = setup()
  let resolveOld
  vi.mocked(readAdminPolicyAuditLog).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        resolveOld = resolve
      }),
  )
  history.selectedLogId.value = '901'
  await settle()
  const oldSignal = vi.mocked(readAdminPolicyAuditLog).mock.calls[0][2]
  id.value = '6'
  await settle()
  expect(oldSignal.aborted).toBe(true)
  resolveOld({ log: auditLogs[1], snapshot: historySnapshot(1) })
  await settle()
  expect(history.detail.value).toBeNull()
  vi.mocked(readAdminPolicyAuditLog).mockResolvedValue({
    log: auditLogs[0],
    snapshot: historySnapshot(2),
  })
  history.selectedLogId.value = '902'
  await settle()
  expect(history.detail.value.policy.reviewable).toBe(false)
  expect(history.detail.value.coverage).toBeNull()
  history.selectedLogId.value = ''
  await settle()
  expect(history.detail.value).toBeNull()
  scope.stop()
  expect(vi.mocked(listAdminPolicyAuditLogs).mock.calls.at(-1)[2].aborted).toBe(true)
})
