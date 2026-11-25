import { request } from './request'

export const SUBSIDY_UPDATE_KEY = 'policy-subsidy-updated'

// 只通知同源标签页重新查询，各页面仍按自己的登录权限读取政策。
export function notifySubsidyUpdate() {
  try {
    window.localStorage.setItem(SUBSIDY_UPDATE_KEY, '1')
    window.localStorage.removeItem(SUBSIDY_UPDATE_KEY)
  } catch {
    // 禁用本地存储的客户端仍会在返回页面或再次查询时读取最新结果。
  }
}

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
