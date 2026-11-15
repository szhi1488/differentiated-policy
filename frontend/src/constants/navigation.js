export const ADMIN_NAV_GROUPS = [
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
    links: [{ name: 'admin-policies', label: '差异化政策' }],
  },
]

export const APPLICANT_NAV_GROUPS = [
  {
    id: 'applicant-policy-links',
    label: '政策提交',
    links: [
      { name: 'apply', label: '提交政策' },
      { name: 'submissions', label: '我的提交' },
    ],
  },
]
