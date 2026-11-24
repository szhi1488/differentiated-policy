import { parseReviewTarget } from './review-link'

export function reviewReturnLocation(user) {
  return { name: user?.roleType === 'ADMIN' ? 'admin-policies' : 'submissions' }
}

// 页面分流只使用服务端身份；业务接口仍须独立校验权限。
export function workspaceDestination(user, route) {
  const role = user?.roleType === 'ADMIN' ? 'ADMIN' : 'APPLICANT'
  const linkedAdminPolicy =
    route.name === 'admin-policies' &&
    role === 'ADMIN' &&
    ('policyId' in route.query || 'round' in route.query)
  if (
    route.name === 'review-round' ||
    (route.name === 'apply' && 'round' in route.query) ||
    linkedAdminPolicy
  ) {
    if (!parseReviewTarget(route.fullPath) || (route.name === 'apply' && role !== 'APPLICANT')) {
      return { name: 'review-link-unavailable' }
    }
  }
  if (route.meta.reviewWorkspace) {
    return null
  }
  return route.meta.role === role
    ? null
    : role === 'ADMIN'
      ? { name: 'catalog-sources' }
      : { name: 'apply' }
}
