import { createRequestId } from './request-id'

export function createSubsidy() {
  return {
    key: createRequestId(),
    mode: 'FIXED',
    fixedAmount: '',
    maxAmount: '',
    ratio: '',
    priceBase: '',
  }
}

/** 名称组合也用于已失效商品的回填展示。 */
function policyModelParts({ brand, series, model, version }) {
  return [brand, series, model, version]
}

export function policyModelKey(goods) {
  return goods.spuId ?? JSON.stringify(policyModelParts(goods))
}

export function policyGoodsKey(goods) {
  return goods.id ?? JSON.stringify([...policyModelParts(goods), goods.config])
}

/** 所选配置共用一组平台数量与补贴方案。 */
export function createPolicyModel() {
  return {
    key: createRequestId(),
    goods: [],
    followsOrderPlatforms: true,
    platforms: {},
    subsidies: [createSubsidy()],
  }
}

/** 编辑模型保留备注、说明、链接和文件。 */
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
    model: null,
    specialGoods: [],
  }
}
export function changeChannel(form, channel) {
  form.channel = channel
  form.platforms = []
  form.model = null
  form.specialGoods = []
  form.verifyType = channel === 'RETAIL' ? 'SALES' : null
  form.scene = channel === 'RETAIL' ? 'NATIONAL_SUB' : null
  form.estimated = channel === 'RETAIL' ? false : null
}
export function changeSubsidy(subsidy, mode) {
  subsidy.mode = mode
  subsidy.fixedAmount = ''
  subsidy.maxAmount = ''
  subsidy.ratio = ''
  subsidy.priceBase = ''
}
