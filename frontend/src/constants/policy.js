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
export const PRICE_BASES = ['零售价', '政策价', '到手价', '地补实际', '商家配控']
