# 政策审核设置与本轮审核人

## 管理员工作台入口

管理员在「系统管理 → 政策审核设置」为每个品牌选择一名终审采购。OPPO 手机的产品运营按标准系列预设，例如 Find、Reno 选 Ivan，K、A 选 Layson。一加手机也按标准系列预设，Ace、数字、Turbo 可分别选择 Barry 或其他产品运营。单独更换某一系列的初审人仅影响使用该系列的后续提交。这些名字用于说明公司分工，正式配置以管理员选定的本地用户 ID 为准。

## 搜索候选人员

`GET /api/admin/policy-review-settings/candidates` 支持 `reviewGroup=PRODUCT|PURCHASE`、`name`、`department`、`page`、`pageSize`。初审候选须有启用的产品运营岗位（`PRODUCT_OPS`），终审候选须有启用的采购岗位（`PURCHASE`）。两者都要求同企业、账号启用且拥有对应的审核决定权限。管理员如兼任审核岗位，可出现在所兼岗位的名单里。岗位筛选在统计总数和分页之前执行。服务端在保存分工、正式提交与实际签署时核验资格。

## 读取与保存

`GET /api/admin/policy-review-settings` 返回配置 `version`、标准目录下的 `brands` 和 `policyChatConfigured`。各品牌包含 `brandId`、`brandName`、`productMode`、`purchaseReviewer`、`brandProductReviewer` 和 `series`。系列显示 `seriesId`、`seriesName` 和 `productReviewer`。

`PUT /api/admin/policy-review-settings` 使用 `review.manage` 权限，一次保存页面的分工。未列入配置的品牌或系列保持未指派。下例中的编号仅用于说明请求格式。

```json
{
  "expectedVersion": 0,
  "brands": [
    {"brandId":"1","productMode":"BY_SERIES","purchaseUserId":"10","brandProductUserId":null,
     "series":[{"seriesId":"101","productUserId":"20"},{"seriesId":"102","productUserId":"21"}]},
    {"brandId":"2","productMode":"BY_SERIES","purchaseUserId":"11","brandProductUserId":null,
     "series":[{"seriesId":"201","productUserId":"22"},{"seriesId":"202","productUserId":"23"}]}
  ]
}
```

一名采购最多负责一个品牌。两个品牌选择同一个采购时，整次保存返回 400 `POLICY_REVIEW_SETTINGS_INVALID` 并提示冲突品牌。两位采购可在同一次保存中互换品牌。商品中心和机型补贴查询按当前已保存的品牌分工取数。例如，OPPO 的采购查 OPPO 全系列的补贴，一加的采购查一加全系列的补贴。管理员可以查全部品牌。

配置版本过期返回 409 `POLICY_REVIEW_SETTINGS_CONFLICT`。不合格的负责人、跨品牌系列、重复品牌或系列会使整次保存失败。业务群由 `FEISHU_POLICY_CHAT_ID` 设置。写请求沿用系统的可信来源校验。

## 首提及重提核定

运营每张新申请仅可申报同一品牌的一个系列。正式提交时根据现行叠加规则决定是否需要初审，读取管理员事先保存的分工，在同一事务冻结本轮节点负责人、配置版本和依据。直达采购的轮次只需要品牌采购。OPPO 和一加进入初审的轮次同时需要该系列的产品运营和该品牌采购。未配置的系列在需要初审时须由管理员补配。任一必需岗位缺人、账号无资格、自审或双签同人都会阻止提交，保留可编辑申请。

预设变更只影响下一次首提或退回重提。已经提交的轮次继续由冻结的负责人签署。签署时仍核验该账号的有效状态和岗位权限。

产品运营从 `GET /api/policy-reviews/my-product-decisions` 查询本人实际签署的初审记录。采购工作台仅在本人获派的轮次进入终审后开放该轮数据。直接终审的轮次从提交起可查，经过初审的轮次从初审通过起可查。正式批准时，在事务中按获批轮次的平台确定全体有效成员，加原申请人去重，群公告重试沿用冻结的点名名单。

## 后台投递记录

初审卡、采购提醒、退回提醒、个人待办及终审群公告由后台按业务事件登记并投递。`policy_review_delivery` 保存业务轮次、接收指派、投递状态、尝试次数、脱敏错误和飞书回执。重试复用原投递标识。发送结果未知且已超出安全重试窗口时，保留 `UNKNOWN` 及原因。群公告继续沿用获批时冻结的点名名单。

执行期提醒、平台交接及商品导入异常暂停提醒也保存在同一张表。独立事件按企业和事件标识去重。[数据库字段与查阅方式](../sql/database-design.md#群消息投递编号与飞书回执)
