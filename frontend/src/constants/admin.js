export const ADMIN_NAV = [
  {
    title: '工作台',
    items: [
      {
        path: '/admin/overview',
        label: '工作总览',
        icon: 'home',
        description: '集中查看政策审核与商品目录的处理概况',
      },
    ],
  },
  {
    title: '政策管理',
    items: [
      {
        path: '/admin/policies',
        label: '全部政策',
        icon: 'document',
        description: '查看政策申请、逐平台补贴明细与审核历史',
      },
      {
        path: '/admin/reviews',
        label: '待审核政策',
        icon: 'shield',
        description: '核对本轮提交内容、审核意见与候选影响',
      },
    ],
  },
  {
    title: '商品管理',
    items: [
      {
        path: '/admin/catalog/sync',
        label: '导入与清洗',
        icon: 'sync',
        description: '分别查看原始导入与自动清洗的处理阶段',
      },
      {
        path: '/admin/catalog/sources',
        label: '来源商品',
        icon: 'box',
        description: '保留七字段原文，追溯清洗候选与当前映射',
      },
      {
        path: '/admin/catalog/exceptions',
        label: '异常待审核',
        icon: 'alert',
        description: '聚焦缺失、歧义和冲突，明确商品无需重复审核',
      },
      {
        path: '/admin/catalog/variants',
        label: '标准规格与颜色',
        icon: 'layers',
        description: '按品牌、系列、型号与配置查看独立颜色集合',
      },
      {
        path: '/admin/catalog/logs',
        label: '操作历史',
        icon: 'history',
        description: '区分系统自动处理与管理员操作，保留过程证据',
      },
    ],
  },
  {
    title: '系统',
    items: [
      {
        path: '/admin/settings',
        label: '系统信息',
        icon: 'settings',
        description: '查看当前账号、接入边界与待配置事项',
      },
    ],
  },
]
export const ADMIN_PAGES = ADMIN_NAV.flatMap((group) => group.items)
export const POLICY_STATUS = {
  PENDING: { label: '待审核', type: 'warning' },
  APPROVED: { label: '已通过', type: 'success' },
  REJECTED: { label: '已驳回', type: 'danger' },
  DRAFT: { label: '草稿', type: 'info' },
}
export const CLEAN_STATUS = {
  PENDING: { label: '待清洗', type: 'info' },
  PROCESSING: { label: '清洗中', type: 'primary' },
  AUTO_PASSED: { label: '自动通过', type: 'success' },
  REVIEW_REQUIRED: { label: '待审核', type: 'warning' },
  MANUAL_PASSED: { label: '人工通过', type: 'success' },
  ERROR: { label: '技术失败', type: 'danger' },
  EXCLUDED: { label: '已排除', type: 'info' },
}
