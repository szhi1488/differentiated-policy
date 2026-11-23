import {
  decimalText,
  policyApiTime,
  policyTime,
  selectedPolicyScenes,
  stringId,
} from './policy-adapter'
import { createForm, createSubsidy } from './policy-form'

export function applicantGood(item) {
  return {
    id: stringId(item.goodsId),
    key: item.goodsId,
    brandId: stringId(item.brandId),
    seriesId: stringId(item.seriesId),
    spuId: stringId(item.spuId),
    ram: item.ram,
    rom: item.rom,
    config: `${item.ram.replace(/GB$/, '')}+${item.rom.replace(/GB$/, '')}`,
    brand: item.brandName,
    series: item.seriesName,
    model: item.modelName,
    name: item.goodsName,
    version: item.edition,
    color: item.colorName,
    status: item.onSale ? 'active' : 'inactive',
  }
}

function quantityValue(value) {
  if (value === '' || value === null || value === undefined) {
    return null
  }
  if (!/^\d+$/.test(value) || Number(value) > 2147483647) {
    throw new TypeError('数量须为可保存的非负整数。')
  }
  return Number(value)
}

function writeCard(model) {
  const special = model.id !== undefined
  return {
    key: model.key,
    cardType: special ? 'SPECIFIED' : 'COMMON',
    followsOrderPlatforms: model.followsOrderPlatforms,
    targets: model.goods.map(({ id, spuId, ram, rom }) => ({
      goodsId: stringId(id),
      spuId: stringId(spuId),
      ram,
      rom,
    })),
    subsidyPlans: model.subsidies.map(
      ({ key, mode, fixedAmount, maxAmount, ratio, priceBase }) => ({
        key,
        subsidyMode: mode,
        fixedAmount: mode === 'FIXED' ? decimalText(fixedAmount) : null,
        maxSubsidyAmount: mode === 'FIXED' ? decimalText(maxAmount) : null,
        ratio: mode === 'RATIO' ? decimalText(ratio) : null,
        priceBase: mode === 'RATIO' ? priceBase || null : null,
      }),
    ),
    platformAssignments: Object.entries(model.platforms).map(([platformCode, entry]) => ({
      platformCode,
      planKey: entry.subsidyKey,
      quantity: special ? quantityValue(entry.quantity) : null,
    })),
  }
}

export function policyDraft(form, recordVersion) {
  const retail = form.channel === 'RETAIL'
  const link = form.links.trim()
  return {
    recordVersion,
    perspective: form.channel,
    selectedPlatforms: [...form.platforms],
    verifyType: retail ? form.verifyType : null,
    sceneTypes: retail ? [...form.scenes] : null,
    estimated: retail ? form.estimated : null,
    startTime: policyApiTime(form.startTime),
    endTime: policyApiTime(form.endTime),
    specialRemark: form.remark || null,
    attachmentInfo: [
      ...(link ? [{ type: 'LINK', url: link }] : []),
      ...(form.attachments ?? []).map(({ id, originalName }) => ({
        type: 'FILE',
        id,
        originalName,
      })),
    ],
    goodsForm: { cards: form.cards.map(writeCard) },
  }
}

export function restorePolicyForm(detail) {
  const source = detail.form
  const goods = new Map(detail.goods.map((item) => [item.goodsId, item]))
  const form = {
    ...createForm(),
    channel: source.perspective,
    platforms: [...source.selectedPlatforms],
    verifyType: source.verifyType,
    scenes: source.perspective === 'RETAIL' ? [...selectedPolicyScenes(source)] : null,
    estimated: source.estimated,
    startTime: policyTime(source.startTime),
    endTime: policyTime(source.endTime),
    remark: source.specialRemark ?? '',
    links: source.attachmentInfo.find((item) => item.type === 'LINK')?.url ?? '',
    attachments: source.attachmentInfo
      .filter((item) => item.type === 'FILE')
      .map(({ id, originalName }) => ({ type: 'FILE', id, originalName })),
  }
  const change = source.goodsForm.timeAdjustment
  if (detail.sourcePolicyCode || change) {
    form.adjustmentReason = change?.reason ?? ''
    form.adjustmentFiles = change?.attachmentInfo ?? []
    if (change) {
      form.endTime = policyTime(change.endTime)
    }
  }
  for (const card of source.goodsForm.cards) {
    if (!card.targets?.length) {
      continue
    }
    const special = card.cardType === 'SPECIFIED'
    const model = {
      key: card.key,
      followsOrderPlatforms: card.followsOrderPlatforms,
      goods: card.targets.map((target) => {
        const item = goods.get(target.goodsId)
        const matches =
          item && item.spuId === target.spuId && item.ram === target.ram && item.rom === target.rom
        if (matches) {
          return applicantGood(item)
        }
        return applicantGood({
          ...target,
          modelName: `商品 ${target.goodsId}`,
          goodsName: `商品 ${target.goodsId}（配置已变更或已删除）`,
        })
      }),
      subsidies: (card.subsidyPlans ?? []).map((plan) => ({
        key: plan.key,
        mode: plan.subsidyMode,
        fixedAmount: plan.fixedAmount ?? '',
        maxAmount: plan.maxSubsidyAmount ?? '',
        ratio: plan.ratio ?? '',
        priceBase: plan.priceBase ?? '',
      })),
      platforms: Object.fromEntries(
        (card.platformAssignments ?? []).map((entry) => [
          entry.platformCode,
          {
            subsidyKey: entry.planKey,
            ...(special
              ? {
                  quantity:
                    entry.quantity === null || entry.quantity === undefined
                      ? ''
                      : String(entry.quantity),
                }
              : {}),
          },
        ]),
      ),
    }
    if (!model.subsidies.length) {
      model.subsidies.push(createSubsidy())
    }
    if (special) {
      model.id = model.goods[0].id
    }
    form.cards.push(model)
  }
  return form
}
