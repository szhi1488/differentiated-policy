import { ADMIN_PAGES } from '../constants/admin'

// 页面分流只使用服务端身份；业务接口仍须独立校验权限。
export function workspaceDestination(user, path) {
  if (user?.roleType !== 'ADMIN') {
    return path === '/apply' ? null : '/apply'
  }
  return ADMIN_PAGES.some((page) => page.path === path) ? null : '/admin/policies'
}
