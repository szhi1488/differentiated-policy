// 仅解析站内办理路径。单据 ID 保持字符串，轮次与 Java int 的范围一致。
export function parseReviewTarget(path) {
  if (typeof path !== 'string' || path.includes('#')) {
    return null
  }
  const match = /^(\/reviews\/([1-9]\d{0,18})|\/apply)\?([^?]+)$/.exec(path)
  if (!match) {
    return null
  }
  const params = new URLSearchParams(match[3])
  const revision = match[1] === '/apply'
  const keys = revision ? ['policyId', 'round'] : ['round', 'node']
  if ([...params.keys()].some((key) => !keys.includes(key) || params.getAll(key).length !== 1)) {
    return null
  }
  const id = revision ? params.get('policyId') : match[2]
  const round = params.get('round')
  const node = revision ? 'REVISION' : params.get('node')
  if (!id || !/^[1-9]\d{0,18}$/.test(id) || BigInt(id) > 9223372036854775807n) {
    return null
  }
  if (!round || !/^[1-9]\d{0,9}$/.test(round) || Number(round) > 2147483647) {
    return null
  }
  if (node !== null && !['PRODUCT_REVIEW', 'PURCHASE_REVIEW', 'REVISION'].includes(node)) {
    return null
  }
  return { id, round, node }
}

export function reviewTargetPath({ id, round, node }) {
  return `/reviews/${id}?round=${round}${node ? '&node=' + node : ''}`
}
