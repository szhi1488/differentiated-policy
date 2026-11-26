const permissions = {
  OPERATIONS: ['policy.apply', 'policy.read.platform_submitted', 'subsidy.read.platform'],
  PURCHASE: [
    'catalog.import',
    'catalog.clean',
    'policy.purchase.decide_assigned',
    'subsidy.read.company',
  ],
  PRODUCT_OPS: ['policy.product.decide_assigned', 'policy.product.read_own_decisions'],
}
permissions.ADMIN = [
  ...new Set([
    ...Object.values(permissions).flat(),
    'catalog.manage',
    'policy.read.company_submitted',
    'users.manage',
    'roles.manage',
    'platforms.manage',
    'review.manage',
  ]),
]
export function roleSession(...roleCodes) {
  return { roleCodes, permissions: [...new Set(roleCodes.flatMap((code) => permissions[code]))] }
}
