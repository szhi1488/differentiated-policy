import { request } from './request'

export function readSubsidyTimeline(
  { spuId, ram, rom, dateFrom, dateTo, perspective, platformCode },
  signal,
  applicant,
) {
  const params = new URLSearchParams({ spuId, ram, rom, dateFrom, dateTo })
  if (platformCode) {
    params.set('platformCode', platformCode)
  }
  if (applicant && perspective) {
    params.set('perspective', perspective)
  }
  const path = applicant ? '/api/policies/subsidy-timeline' : '/api/admin/policies/subsidy-timeline'
  return request(path + '?' + params, { signal })
}
