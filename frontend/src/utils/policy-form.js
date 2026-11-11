/** @returns {{key:string, model:string, singleModel:string, spec:string, color:string, quantity:string, mode:string, fixedAmount:string, maxAmount:string, ratio:string, priceBase:string}} 自由文本编辑占位，尚非目录 ID 或出站 DTO。 */
export function createDetail() {
  return {
    key: crypto.randomUUID(),
    model: '',
    singleModel: '',
    spec: '',
    color: '',
    quantity: '',
    mode: 'FIXED',
    fixedAmount: '',
    maxAmount: '',
    ratio: '',
    priceBase: '',
  }
}
/** 编辑模型保留备注、说明、链接和文件；DTO 映射待对应契约确认，不直接提交整个表单。 */
export function createForm() {
  return {
    channel: 'RETAIL',
    platforms: [],
    verifyType: 'SALES',
    scene: 'NATIONAL_SUB',
    estimated: false,
    startTime: '',
    endTime: '',
    remark: '',
    explanation: '',
    links: '',
    details: {},
  }
}
export function changeChannel(form, channel) {
  form.channel = channel
  form.platforms = []
  form.details = {}
  form.verifyType = channel === 'RETAIL' ? 'SALES' : null
  form.scene = channel === 'RETAIL' ? 'NATIONAL_SUB' : null
  form.estimated = channel === 'RETAIL' ? false : null
}
export function changeSubsidy(detail, mode) {
  detail.mode = mode
  detail.fixedAmount = ''
  detail.maxAmount = ''
  detail.ratio = ''
  detail.priceBase = ''
}

/** 数量或金额为 0 也视为已填写，取消平台时应提示用户。 */
export function hasDetailInput(detail) {
  return [
    'model',
    'singleModel',
    'spec',
    'color',
    'quantity',
    'fixedAmount',
    'maxAmount',
    'ratio',
    'priceBase',
  ].some((field) => detail[field] !== '' && detail[field] !== null && detail[field] !== undefined)
}
