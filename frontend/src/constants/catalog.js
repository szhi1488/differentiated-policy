export const GOODS_BRANDS = ['OPPO手机', '一加手机']

export const GOODS_CLEAN_RESULTS = {
  AUTO_PASSED: { label: '自动通过', tone: 'passed', icon: 'check' },
  REVIEW_REQUIRED: { label: '待人工审核', tone: 'review', icon: 'alert' },
  ERROR: { label: '技术失败', tone: 'failed', icon: 'sync' },
  MANUAL_PASSED: { label: '人工通过', tone: 'passed' },
  EXCLUDED: { label: '已排除', tone: 'neutral' },
  PENDING: { label: '待清洗', tone: 'neutral' },
  PROCESSING: { label: '处理中', tone: 'neutral' },
}

export const GOODS_CLEAN_JOB_LABELS = {
  NOT_STARTED: '等待清洗',
  RUNNING: '清洗中',
  COMPLETED: '清洗完成',
  PARTIAL: '部分技术失败',
  FAILED: '清洗失败',
}

export const GOODS_CLEAN_LOG_ACTIONS = {
  START: '开始清洗',
  CLEAN_AUTO: '自动通过',
  CLEAN_REVIEW: '转人工审核',
  CLEAN_ERROR: '技术失败',
  CLEAN_RETRY: '重试',
  CORRECT: '人工通过',
  EXCLUDE: '确认排除',
  UPDATE: '更新源商品',
}

export const GOODS_SYNC_STATUS = {
  QUEUED: ['等待导入', 'info'],
  RUNNING: ['导入中', 'primary'],
  PAUSED: ['已暂停', 'warning'],
  SUCCEEDED: ['原文已导入', 'success'],
  CANCELLED: ['已结束', 'info'],
}

export const GOODS_SYNC_ERRORS = {
  CATALOG_INVALID_SKU: '吉客云未返回有效 SKU ID，任务已保留导入断点。',
  CATALOG_SKU_BRAND_CONFLICT: '同一个 SKU ID 已归属其他品牌，请核对吉客云分类。',
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

export const GOODS_SYNC_MODES = {
  FULL: '全量导入',
  CREATED: '按建档日期',
  MODIFIED: '按修改日期',
}

export const GOODS_SYNC_PHASES = {
  GOODS_MODIFIED: '商品修改时间',
  SKU_MODIFIED: 'SKU 修改时间',
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

export const SOURCE_GOODS_FIELDS = [
  ['skuId', 'SKU ID', 180],
  ['cateFullName', '完整分类路径', 240],
  ['createdDate', '建档日', 126],
  ['gmtModified', 'SKU 修改时间', 170],
  ['goodsGmtModified', '商品修改时间', 170],
  ['isBlockup', 'SKU 状态', 100],
  ['isPackageGood', '组合商品', 100],
]
