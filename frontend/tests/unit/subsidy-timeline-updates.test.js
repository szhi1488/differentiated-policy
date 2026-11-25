// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { reviewAdminPolicy } from '../../src/api/admin-policies'
import { applyPolicyTimeAdjustments, previewPolicyTimeAdjustments } from '../../src/api/policies'
import { SUBSIDY_UPDATE_KEY } from '../../src/api/subsidy-timeline'

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

it('独立调整和采购通过后通知其他查询页，预览、驳回及失败请求不发布', async () => {
  const set = vi.spyOn(Storage.prototype, 'setItem')
  const remove = vi.spyOn(Storage.prototype, 'removeItem')
  const fetch = vi.fn(async () => Response.json({}))
  vi.stubGlobal('fetch', fetch)
  const endAdjustments = [{ target: { policyId: '27' }, reason: '促销提前结束' }]
  const adjust = () => applyPolicyTimeAdjustments({ endAdjustments }, 'adjust-27')
  const review = (decision) =>
    reviewAdminPolicy('30', { recordVersion: 3, submissionVersion: 2, decision })
  await previewPolicyTimeAdjustments(endAdjustments)
  await review('REJECT')
  for (const write of [adjust, () => review('APPROVE')]) {
    fetch.mockResolvedValueOnce(Response.json({ message: '政策已变更' }, { status: 409 }))
    await expect(write()).rejects.toMatchObject({ status: 409 })
  }
  expect(set).not.toHaveBeenCalled()
  expect(remove).not.toHaveBeenCalled()

  fetch.mockResolvedValueOnce(Response.json([{ id: '27', status: 'APPROVED' }]))
  await adjust()
  expect(set.mock.calls).toEqual([[SUBSIDY_UPDATE_KEY, '1']])
  expect(remove.mock.calls).toEqual([[SUBSIDY_UPDATE_KEY]])
  await review('APPROVE')
  expect(set.mock.calls).toEqual([
    [SUBSIDY_UPDATE_KEY, '1'],
    [SUBSIDY_UPDATE_KEY, '1'],
  ])
  expect(remove.mock.calls).toEqual([[SUBSIDY_UPDATE_KEY], [SUBSIDY_UPDATE_KEY]])
})
