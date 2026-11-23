import { parseReviewTarget } from './review-link'

// 页面分流只使用服务端身份；业务接口仍须独立校验权限。
export function workspaceDestination(user, route) {
  const role = user?.roleType === 'ADMIN' ? 'ADMIN' : 'APPLICANT'
  if (route.name === 'review-round' || (route.name === 'apply' && 'round' in route.query)) {
    const target = parseReviewTarget(route.fullPath)
    if (!target || (target.node === 'REVISION' && role !== 'APPLICANT')) {
      return { name: 'review-link-unavailable' }
    }
    if (route.name === 'review-round' && target.node === 'REVISION') {
      return { name: 'apply', query: { policyId: target.id, round: target.round }, replace: true }
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
