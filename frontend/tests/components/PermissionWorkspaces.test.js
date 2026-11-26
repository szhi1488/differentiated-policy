// @vitest-environment jsdom
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { ElCheckboxGroup, ElMessage, ElSelect } from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import RoleManagementPage from '../../src/views/admin/users/RoleManagementPage.vue'
import PlatformPoliciesPage from '../../src/views/apply/PlatformPoliciesPage.vue'
import PolicySubmissionContent from '../../src/views/apply/components/PolicySubmissionContent.vue'
import PolicyReviewMembers from '../../src/components/PolicyReviewMembers.vue'
import { historySnapshot } from '../fixtures/admin-policy-history'

let wrapper
const button = (label) => wrapper.findAll('button').find((b) => b.text() === label)
async function open(component, reply, stubs = {}) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path, init) => {
      const result = await reply(new URL(path, 'http://localhost'), init)
      return Response.json(result)
    }),
  )
  wrapper = mount(component, {
    attachTo: document.body,
    global: { stubs: { teleport: true, RouterLink: RouterLinkStub, ...stubs } },
  })
  await flushPromises()
}
afterEach(() => {
  wrapper?.unmount()
  ElMessage.closeAll()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

it('新角色按勾选的权限创建，后续编辑保持岗位编码并校验版本', async () => {
  let saved
  let written
  const permissions = [{ code: 'subsidy.read.company', name: '查询公司补贴' }]
  await open(RoleManagementPage, (url, init) => {
    if (init.method === 'POST' || init.method === 'PUT') {
      written = { path: url.pathname, method: init.method, body: JSON.parse(init.body) }
      saved = { ...written.body, id: '7', version: (saved?.version ?? 0) + 1, builtin: false }
      return saved
    }
    return { roles: saved ? [saved] : [], permissions }
  })
  await button('创建角色').trigger('click')
  const [name, code] = wrapper.findAll('.el-dialog input[type=text]')
  await name.setValue('补贴查询员')
  await code.setValue('SUBSIDY_READER')
  wrapper.getComponent(ElCheckboxGroup).vm.$emit('update:modelValue', ['subsidy.read.company'])
  await flushPromises()
  await button('保存').trigger('click')
  await flushPromises()
  expect(written).toEqual({
    path: '/api/admin/roles',
    method: 'POST',
    body: {
      roleCode: 'SUBSIDY_READER',
      roleName: '补贴查询员',
      enabled: true,
      permissions: ['subsidy.read.company'],
    },
  })
  await button('编辑').trigger('click')
  const [nextName, lockedCode] = wrapper.findAll('.el-dialog input[type=text]')
  expect(lockedCode.element.disabled).toBe(true)
  await nextName.setValue('补贴查询')
  await button('保存').trigger('click')
  await flushPromises()
  expect(written).toEqual({
    path: '/api/admin/roles/7',
    method: 'PUT',
    body: {
      roleCode: 'SUBSIDY_READER',
      roleName: '补贴查询',
      enabled: true,
      permissions: ['subsidy.read.company'],
      expectedVersion: 1,
    },
  })
})

it('平台成员从共享详情查看本轮初审和采购进度，使用本轮共享附件地址', async () => {
  const seen = []
  const item = {
    id: '21',
    policyCode: 'ZC-000021',
    perspective: 'RETAIL',
    status: 'PENDING',
    reviewStage: 'PURCHASE_REVIEW',
    selectedPlatforms: ['JD'],
    submissionVersion: 3,
  }
  const policy = {
    ...historySnapshot(item.submissionVersion),
    ...item,
    reviewProgress: {
      submissionVersion: item.submissionVersion,
      status: item.status,
      reviewStage: item.reviewStage,
      applicantName: '平台提交人',
      submittedAt: '2026-11-14T09:00:00+08:00',
      requiresProductReview: true,
      productReviewers: [
        {
          assignmentId: '31',
          name: '本轮初审人',
          memberStatus: 'HANDLED',
          decision: 'APPROVE',
          decidedAt: '2026-11-14T10:00:00+08:00',
        },
      ],
      purchaseReviewers: [{ assignmentId: '32', name: '本轮采购', memberStatus: 'ACTIVE' }],
    },
  }
  let detailReads = 0
  await open(
    PlatformPoliciesPage,
    (url) => {
      seen.push(url)
      if (url.pathname === '/api/policies/platforms') {
        return [{ perspective: 'RETAIL', platformCode: 'JD' }]
      }
      if (url.pathname === '/api/policies/shared') {
        return { items: [item], total: 1, page: 1, pageSize: 20 }
      }
      if (url.pathname === '/api/policies/shared/21') {
        return ++detailReads === 1 ? { ...policy, reviewProgress: null } : policy
      }
      throw Error('意外请求 ' + url)
    },
    { ElSelect: true },
  )
  expect(seen[0].pathname).toBe('/api/policies/shared')
  expect(seen.find((url) => url.pathname.endsWith('/platforms')).searchParams.get('usage')).toBe(
    'READ',
  )
  wrapper.findAllComponents(ElSelect)[0].vm.$emit('update:modelValue', 'RETAIL/JD')
  await flushPromises()
  expect(Object.fromEntries(seen.at(-1).searchParams)).toMatchObject({
    perspective: 'RETAIL',
    platformCode: 'JD',
  })
  await button('详情').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('暂时无法读取本轮审核进度')
  expect(wrapper.findComponent(PolicyReviewMembers).exists()).toBe(false)
  await button('重新读取详情').trigger('click')
  await flushPromises()
  const detail = wrapper.getComponent(PolicySubmissionContent)
  expect(detail.props('detail')).toEqual(policy)
  const progress = wrapper.getComponent(PolicyReviewMembers)
  expect(progress.props('round')).toEqual(policy.reviewProgress)
  for (const expected of ['本轮初审人', '2026-11-14 10:00:00', '本轮采购']) {
    expect(progress.text()).toContain(expected)
  }
  expect(seen.every(({ pathname }) => pathname.startsWith('/api/policies/'))).toBe(true)
  expect(detail.props('attachmentUrl')('35')).toBe(
    '/api/policies/shared/21/attachments/35/content?submissionVersion=3',
  )
  expect(wrapper.text()).not.toContain('保存草稿')
})
