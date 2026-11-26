export const USER_ROLE_LABELS = {
  ADMIN: '系统管理员',
  OPERATIONS: '运营',
  PRODUCT_OPS: '产品运营',
  PURCHASE: '采购',
}
export const USER_STATUS_LABELS = { ENABLED: '已启用', DISABLED: '已停用' }
export function roleLabels(codes, roles = []) {
  return (
    (codes ?? [])
      .map(
        (code) =>
          roles.find((role) => role.roleCode === code)?.roleName ?? USER_ROLE_LABELS[code] ?? code,
      )
      .join('、') || '未分配岗位'
  )
}
