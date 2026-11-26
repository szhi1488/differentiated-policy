export const USER_MANAGEMENT_SECTIONS = [
  { name: 'admin-users', label: '用户列表' },
  { name: 'roles', label: '角色管理' },
]

export const ADMIN_NAV_GROUPS = [
  {
    id: 'admin-system-links',
    label: '系统管理',
    icon: 'settings',
    links: [
      { name: 'admin-users', label: '用户管理', sections: USER_MANAGEMENT_SECTIONS },
      { name: 'admin-review-settings', label: '政策审核设置' },
      { name: 'teams', label: '平台分工' },
    ],
  },
  {
    id: 'admin-catalog-links',
    label: '商品',
    icon: 'box',
    links: [
      { name: 'catalog-sources', label: '源商品库' },
      { name: 'catalog-cleaning', label: '商品清洗与识别' },
      { name: 'catalog-center', label: '商品中心' },
    ],
  },
  {
    id: 'admin-policy-links',
    label: '政策管理',
    icon: 'document',
    links: [
      { name: 'admin-policies', label: '差异化政策' },
      { name: 'product-history', label: '我的初审记录' },
      { name: 'admin-model-subsidies', label: '机型补贴查询' },
    ],
  },
]

export const APPLICANT_NAV_GROUPS = [
  {
    id: 'applicant-policy-links',
    label: '政策提交',
    icon: 'document',
    links: [
      { name: 'apply', label: '提交政策' },
      { name: 'submissions', label: '我的提交' },
      { name: 'platform-policies', label: '平台政策' },
      { name: 'applicant-model-subsidies', label: '机型补贴查询' },
    ],
  },
]

export const MENU_PERMISSIONS = {
  apply: ['policy.apply'],
  submissions: ['policy.apply'],
  'platform-policies': ['policy.read.platform_submitted'],
  'applicant-model-subsidies': ['subsidy.read.platform'],
  'admin-model-subsidies': ['subsidy.read.company'],
  'catalog-sources': ['catalog.import'],
  'catalog-cleaning': ['catalog.clean'],
  'catalog-center': ['catalog.manage', 'catalog.import'],
  'admin-policies': ['policy.purchase.decide_assigned', 'policy.read.company_submitted'],
  'product-history': ['policy.product.read_own_decisions'],
  'admin-users': ['users.manage'],
  'admin-review-settings': ['review.manage'],
  teams: ['platforms.manage'],
  roles: ['roles.manage'],
}

const personalPolicyPages = new Set([
  'apply',
  'submissions',
  'platform-policies',
  'product-history',
])

// 工作台菜单、搜索与旧地址跳转共用同一套岗位入口。
export function workspacePageReplacement(user, name) {
  if (name === 'applicant-model-subsidies' && user?.permissions?.includes('subsidy.read.company')) {
    return 'admin-model-subsidies'
  }
  if (user?.roleCodes?.includes('ADMIN')) {
    if (name === 'catalog-sources' || name === 'catalog-cleaning') {
      return 'catalog-center'
    }
    if (personalPolicyPages.has(name)) {
      return 'admin-policies'
    }
  }
  return null
}

export function canOpen(user, name) {
  return (
    !workspacePageReplacement(user, name) &&
    (MENU_PERMISSIONS[name]?.some((permission) => user?.permissions?.includes(permission)) ?? false)
  )
}

export function visibleGroups(user, groups = [...ADMIN_NAV_GROUPS, ...APPLICANT_NAV_GROUPS]) {
  return groups
    .map((group) => ({
      ...group,
      links: group.links.filter((link) =>
        link.sections
          ? link.sections.some((section) => canOpen(user, section.name))
          : canOpen(user, link.name),
      ),
    }))
    .filter((group) => group.links.length)
}

export function homePage(user) {
  return (
    [
      user?.roleCodes?.includes('ADMIN') ? 'catalog-center' : 'catalog-sources',
      'apply',
      'admin-policies',
      'product-history',
      ...Object.keys(MENU_PERMISSIONS),
    ].find((name) => canOpen(user, name)) ?? 'no-access'
  )
}
