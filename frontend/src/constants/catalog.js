export const GOODS_BRANDS = ['OPPO手机', '一加手机']

export const GOODS_SYNC_STATUS = {
  QUEUED: ['等待导入', 'info'],
  RUNNING: ['导入中', 'primary'],
  PAUSED: ['已暂停', 'warning'],
  SUCCEEDED: ['原文已导入', 'success'],
  CANCELLED: ['已结束', 'info'],
}

export const GOODS_SYNC_ERRORS = {
  JACKYUN_RATE_LIMITED: '吉客云限制了请求频率，冷却结束后可继续。',
  JACKYUN_BUSINESS_ERROR: '吉客云拒绝了请求，请检查接口授权或账套配置。',
  JACKYUN_HTTP_ERROR: '吉客云响应异常，请检查服务状态。',
  JACKYUN_TRANSPORT_ERROR: '连接吉客云失败，请检查网络。',
  JACKYUN_INVALID_RESPONSE: '吉客云返回的数据不符合约定，请联系运维核对。',
  CATALOG_FIELD_TOO_LONG: '商品原文超过字段长度上限，请联系运维核对。',
  CATALOG_EMPTY_SOURCE: '吉客云未返回来源字段，请联系运维核对。',
  CATALOG_PAGE_WRITE_FAILED: '本页写库失败，已保留上一次成功的断点。',
  CATALOG_PAGE_LIMIT: '达到导入页数上限，请联系运维核对后继续。',
}

export const RAW_GOODS_FIELDS = [
  ['goodsName', '商品名称', 184],
  ['goodsNo', '货号', 120],
  ['skuName', 'SKU 名称', 134],
  ['cateName', '分类名称', 122],
  ['goodsField4', '自定义字段 4', 112],
  ['goodsField1', '自定义字段 1', 112],
  ['goodsField5', '自定义字段 5', 112],
]
