const time = (day, hour) => '2026-11-' + day + 'T' + hour + '+08:00'
export const direct = { status: 'DIRECT', canDirectSubmit: true, scopes: [] }

export function retailColorScope(goodsId, colorName, oldId = '118') {
  const [item] = matchedPreview(false, oldId).scopes
  item.scope.goodsId = goodsId
  for (const policy of item.policies) {
    const [product] = policy.selections[0].products
    Object.assign(product, { goodsId, colorName, goodsName: 'Find X8 ' + colorName })
  }
  return item
}

export function matchedPreview(billion = false, oldId = '118') {
  const product = {
    goodsId: '8001',
    spuId: '81',
    goodsName: 'Find X8 浮光白',
    modelName: 'Find X8',
    edition: '标准版',
    ram: '12GB',
    rom: '256GB',
    colorName: '浮光白',
  }
  const policy = (policyId, policyCode, proposed, selectedProduct) => ({
    policyId,
    policyCode,
    proposed,
    selections: [
      {
        cardType: billion && proposed ? 'SPECIFIED' : 'COMMON',
        products: [selectedProduct],
        quantity: billion && proposed ? 0 : null,
        subsidy: proposed
          ? { subsidyMode: 'FIXED', fixedAmount: '150.00', maxSubsidyAmount: '260.00' }
          : { subsidyMode: 'RATIO', ratio: '4.50', priceBase: 'RETAIL_PRICE' },
      },
    ],
  })
  const proposed = policy('321', 'ZC-000321', true, product)
  const prior = policy(
    oldId,
    'ZC-' + oldId.padStart(6, '0'),
    false,
    billion
      ? { ...product, goodsId: '8002', colorName: '墨黑', goodsName: 'Find X8 墨黑' }
      : product,
  )
  const later = policy('119', 'ZC-000119', false, product)
  const group = (sceneTypes, policyIds) => ({
    sceneTypes,
    verifyType: 'SALES',
    policyIds,
  })
  const scope = billion
    ? { platformCode: 'PDD', goodsId: null, spuId: '81', ram: '12GB', rom: '256GB' }
    : { platformCode: 'JD', goodsId: '8001', spuId: null, ram: null, rom: null }
  return {
    status: 'NEEDS_CONFIRMATION',
    canDirectSubmit: false,
    scopes: [
      {
        scope,
        policies: [proposed, prior, later],
        segments: [
          {
            from: time('01', '09:30:00'),
            through: time('07', '23:59:59'),
            action: billion ? 'STACK_OR_CHOOSE' : 'SELECT_RETAIL_POLICIES',
            groups: billion
              ? []
              : [group(['NATIONAL_SUB', 'LOCAL_SUB'], ['321']), group(['NATIONAL_SUB'], [oldId])],
            eligiblePolicyIds: ['321', oldId],
          },
          {
            from: time('12', '00:00:00'),
            through: time('22', '23:59:59'),
            action: billion ? 'STACK_OR_CHOOSE' : 'SELECT_RETAIL_POLICIES',
            groups: billion ? [] : [group(['NATIONAL_SUB', 'LOCAL_SUB'], ['321', '119'])],
            eligiblePolicyIds: ['321', '119'],
          },
        ],
      },
    ],
  }
}
