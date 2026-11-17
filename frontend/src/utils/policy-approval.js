import { subsidySignature } from './policy-coverage'

/** 只比较覆盖的业务结果；查询时点和假设通过时间自然推进，不算结果变化。 */
export function coverageSignature(coverage) {
  return JSON.stringify(
    coverage.items
      .map((item) =>
        JSON.stringify([
          item.goodsId,
          item.platformCode,
          item.outcome,
          item.winner?.id ?? null,
          item.winner?.auditLogId ?? null,
          item.winner?.submissionVersion ?? null,
          item.winner?.effectiveFrom ?? null,
          subsidySignature(item.existingSubsidy),
        ]),
      )
      .sort(),
  )
}

export function sameReviewVersion(before, after) {
  return (
    before?.id === after?.id &&
    before?.recordVersion === after?.recordVersion &&
    before?.submissionVersion === after?.submissionVersion
  )
}
