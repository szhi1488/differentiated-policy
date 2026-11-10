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
