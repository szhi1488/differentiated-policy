export const RETAIL_PLATFORMS = [
  ['KUAISHOU', '快手'],
  ['PDD', '拼多多'],
  ['JD', '京东'],
  ['DOUYIN', '抖音'],
  ['TMALL', '天猫'],
  ['TAOBAO', '淘宝'],
  ['ZHAOSHANG', '招商'],
  ['DEWU', '得物'],
  ['UNICOM', '联通'],
  ['GOV_ENTERPRISE', '政企'],
]
export const BILLION_PLATFORMS = RETAIL_PLATFORMS.filter(([code]) =>
  ['PDD', 'TAOBAO', 'UNICOM'].includes(code),
)
export const CHANNEL_LABELS = { RETAIL: '零售渠道', BILLION: '百亿渠道' }
export const POLICY_STATUS_LABELS = {
  DRAFT: '草稿',
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
}
export const POLICY_STATUS_TYPES = {
  DRAFT: 'info',
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
}
export const policyIsEditable = (status) => status === 'DRAFT' || status === 'REJECTED'
export const PLATFORM_LABELS = Object.fromEntries(RETAIL_PLATFORMS)
export const VERIFY_LABELS = { SALES: '销售维度', ACTIVATION: '激活维度' }
export const SCENE_LABELS = { NATIONAL_SUB: '国补', LOCAL_SUB: '地补', SELF_SUB: '自补' }
export const PRICE_BASE_LABELS = {
  RETAIL_PRICE: '零售价',
  POLICY_PRICE: '政策价',
  HAND_PRICE: '到手价',
  LOCAL_ACTUAL: '地补实际',
  MERCHANT_CONTROL: '商家配控',
}
export const PRICE_BASES = Object.entries(PRICE_BASE_LABELS)
