import { parseReviewTarget } from './review-link'
import {
  canOpen,
  homePage,
  workspacePageReplacement,
  USER_MANAGEMENT_SECTIONS,
} from '../constants/navigation'

export function reviewReturnLocation(user) {
  const name = ['admin-policies', 'product-history', 'submissions'].find((page) =>
    canOpen(user, page),
  )
  return { name: name ?? homePage(user) }
}

export function workspaceDestination(user, route) {
  const linked =
    route.name === 'review-round' ||
    (route.name === 'apply' && 'round' in route.query) ||
    (route.name === 'admin-policies' && ('policyId' in route.query || 'round' in route.query))
  const target = linked ? parseReviewTarget(route.fullPath) : null
  if (linked && !target) {
    return { name: 'review-link-unavailable' }
  }
  const replacement = workspacePageReplacement(user, route.name)
  if (replacement && canOpen(user, replacement)) {
    if (route.name === 'apply' && target) {
      return { name: 'review-round', params: { id: target.id }, query: { round: target.round } }
    }
    return { name: replacement }
  }
  if (linked && route.name !== 'review-round' && !canOpen(user, route.name)) {
    return { name: 'review-link-unavailable' }
  }
  if (route.name === 'review-link-unavailable') {
    return null
  }
  if (
    USER_MANAGEMENT_SECTIONS.some((section) => section.name === route.name) &&
    !canOpen(user, route.name)
  ) {
    const section = USER_MANAGEMENT_SECTIONS.find((item) => canOpen(user, item.name))
    if (section) {
      return { name: section.name }
    }
  }
  if (
    route.name === 'review-round' &&
    ['admin-policies', 'product-history', 'submissions'].some((name) => canOpen(user, name))
  ) {
    return null
  }
  const home = homePage(user)
  return canOpen(user, route.name) || (route.name === 'no-access' && home === route.name)
    ? null
    : { name: home }
}
