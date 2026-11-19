import { catalogConfigLabel } from './catalog-dictionary'
import { subsidySignature } from './policy-coverage'

/** 只装配正式选品和接口返回的范围，不查询当前目录，也不扩展未选中的颜色。 */
export function coverageProducts(detail) {
  const goods = new Map(detail.snapshot.goods.map((item) => [item.goodsId, item]))
  const scopes = new Map()
  for (const card of detail.snapshot.form.goodsForm.cards) {
    for (const target of card.targets) {
      for (const assignment of card.platformAssignments) {
        scopes.set(JSON.stringify([target.goodsId, assignment.platformCode]), {
          cardType: card.cardType,
          target,
          proposedSubsidy: card.subsidyPlans.find(({ key }) => key === assignment.planKey) ?? null,
        })
      }
    }
  }
  return detail.coverage.items.map((row) => {
    const product = goods.get(row.goodsId)
    const selection = scopes.get(JSON.stringify([row.goodsId, row.platformCode]))
    const target = selection?.target
    const knownConfiguration = Boolean(
      product?.modelName &&
      target?.spuId &&
      target.ram &&
      target.rom &&
      (!product.spuId || product.spuId === target.spuId) &&
      (!product.ram || product.ram === target.ram) &&
      (!product.rom || product.rom === target.rom),
    )
    const configurationName = knownConfiguration
      ? `${[product.modelName, product.edition].filter(Boolean).join(' · ')} · ${catalogConfigLabel(`${target.ram.replace(/GB$/, '')}+${target.rom.replace(/GB$/, '')}`)}`
      : null
    return {
      ...row,
      ...selection,
      key: JSON.stringify([row.goodsId, row.platformCode]),
      name: product?.goodsName ?? `商品 ${row.goodsId}`,
      color: product?.colorName ?? null,
      configurationName,
      configurationKey: knownConfiguration
        ? [target.spuId, product.modelName, product.edition ?? '', target.ram, target.rom]
        : null,
    }
  })
}

/** 普通选品先按型号配置及平台汇总，成员的实际结果和补贴始终逐项保留。 */
export function groupCoverageProducts(products) {
  const groups = new Map()
  for (const product of products) {
    const mergeable = product.cardType === 'COMMON' && product.configurationKey
    const key = mergeable
      ? JSON.stringify([...product.configurationKey, product.platformCode])
      : product.key
    if (!groups.has(key)) {
      groups.set(key, {
        ...product,
        key,
        name:
          product.cardType === 'COMMON' && product.configurationName
            ? product.configurationName
            : product.name,
        members: [],
      })
    }
    groups.get(key).members.push(product)
  }
  return [...groups.values()].map((group) => {
    const outcomeCounts = {}
    for (const member of group.members) {
      outcomeCounts[member.outcome] = (outcomeCounts[member.outcome] ?? 0) + 1
    }
    const proposed = subsidySignature(group.proposedSubsidy)
    const subsidyVaries = group.members.some(
      (member) => subsidySignature(member.proposedSubsidy) !== proposed,
    )
    const comparisonSignature = (member) =>
      JSON.stringify([
        member.outcome,
        member.winner?.id ?? null,
        member.winner?.auditLogId ?? null,
        member.winner?.submissionVersion ?? null,
        subsidySignature(member.existingSubsidy),
      ])
    const comparisonUniform =
      !subsidyVaries &&
      (group.members.length === 1 ||
        !group.winner ||
        Boolean(group.winner.auditLogId && subsidySignature(group.existingSubsidy) !== null)) &&
      group.members.every((member) => comparisonSignature(member) === comparisonSignature(group))
    return {
      ...group,
      outcome: Object.keys(outcomeCounts).length > 1 ? 'PARTIAL' : group.outcome,
      outcomeCounts,
      subsidyVaries,
      proposedSubsidy: subsidyVaries ? null : group.proposedSubsidy,
      comparisonUniform,
      winner: comparisonUniform ? group.winner : null,
      colorCount: new Set(group.members.map(({ color }) => color).filter(Boolean)).size,
    }
  })
}
