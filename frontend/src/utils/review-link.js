// 仅解析站内办理路径。单据 ID 保持字符串，轮次与 Java int 的范围一致。
export function parseReviewTarget(path) {
  if (typeof path !== 'string' || path.includes('#')) {
    return null
  }
  const match = /^(\/reviews\/([1-9]\d{0,18})|\/apply|\/admin\/policies)\?([^?]+)$/.exec(path)
  if (!match) {
    return null
  }
  const params = new URLSearchParams(match[3])
  const roundPath = Boolean(match[2])
  const keys = roundPath ? ['round'] : ['policyId', 'round']
  if ([...params.keys()].some((key) => !keys.includes(key) || params.getAll(key).length !== 1)) {
    return null
  }
  const id = roundPath ? match[2] : params.get('policyId')
  const round = params.get('round')
  if (!id || !/^[1-9]\d{0,18}$/.test(id) || BigInt(id) > 9223372036854775807n) {
    return null
  }
  if (!round || !/^[1-9]\d{0,9}$/.test(round) || Number(round) > 2147483647) {
    return null
  }
  const query = roundPath ? `round=${round}` : `policyId=${id}&round=${round}`
  return { id, round, path: `${match[1]}?${query}` }
}
