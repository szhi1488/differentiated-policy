package com.company.policy.mapper;

/** SQL 中的账号别名 u 必须是当前企业内启用的本地账号。 */
final class ReviewPermissions {
    private ReviewPermissions() {}

    private static final String START = RoleAccessMapper.PERMISSION_START;
    private static final String END = RoleAccessMapper.PERMISSION_END;
    private static final String ACTIVE_ROLE =
            """
            EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id AND r.tenant_key=ur.tenant_key
                    WHERE ur.user_id=u.id AND ur.tenant_key=u.tenant_key AND r.is_enabled=1 AND r.role_code=
            """;
    static final String PURCHASE_PERMISSION = START + "'policy.purchase.decide_assigned'" + END;
    // 办理审核须兼有该节点的启用岗位和决定权限。单据查阅仍按原数据权限核验。
    static final String PURCHASE = ACTIVE_ROLE + "'PURCHASE') AND " + PURCHASE_PERMISSION;
    static final String PRODUCT =
            ACTIVE_ROLE + "'PRODUCT_OPS') AND " + START + "'policy.product.decide_assigned'" + END;
    static final String FOR_GROUP =
            "<choose><when test=\"group == 'PRODUCT'\">"
                    + PRODUCT
                    + "</when><when test=\"group == 'PURCHASE'\">"
                    + PURCHASE
                    + "</when><otherwise>FALSE</otherwise></choose>";
    static final String HISTORY = START + "'policy.product.read_own_decisions'" + END;
    static final String COMPANY = START + "'policy.read.company_submitted'" + END;
    static final String APPLY = START + "'policy.apply'" + END;
}
