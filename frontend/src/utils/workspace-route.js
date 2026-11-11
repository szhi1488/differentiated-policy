// 页面分流只使用服务端身份；业务接口仍须独立校验权限。
export function workspaceDestination(user, route) {
  const role = user?.roleType === 'ADMIN' ? 'ADMIN' : 'APPLICANT'
  return route.meta.role === role
    ? null
    : role === 'ADMIN'
      ? { name: 'catalog-sources' }
      : { name: 'apply' }
}
