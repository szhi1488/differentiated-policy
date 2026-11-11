# 管理员工作台

更新日期：2026-11-11。

管理员登录后进入 `#/admin/catalog/sources`。左侧导航只有「商品」分组及其下的「源商品库」。旧管理员地址由路由守卫统一跳回源商品库。申请人访问管理员地址时回到 `#/apply`。客户端免登、浏览器扫码及网页授权都按服务端会话的 `user.roleType` 分流。[飞书鉴权](feishu-auth-flow.md)

源商品库按参考图设置标题区、品牌页签、筛选区、原文表格和分页区。表格显示两个品牌、七个原始字段和明确标记的静态样例。导入、筛选、品牌切换和分页控件暂时禁用。窄屏可打开侧栏，表格在自身容器内横向滚动。

布局由 `frontend/src/layouts/AdminLayout.vue` 提供，页面为 `frontend/src/views/admin/catalog/CatalogSourcesPage.vue`，样例放在 `frontend/src/mocks/fixtures/source-goods.js`。管理员前端入口见[前端工程检查与验收](frontend-harness.md)。已接入的后端吉客云单页查询见[商品同步方案](catalog-admin-design.md#单页查询联调)。

本轮隔离副本保留原登录入口，`npm.cmd run check` 通过 ESLint、Prettier、16 个文件的 62 项测试及生产构建。模拟管理员会话检查 1440×1000 默认入口、768×1000 旧链接和 390×844 直达，均显示唯一菜单和十条样例。手机导航可开合，表格可在容器内横向滚动，三个尺寸均无页面级横向溢出。模拟申请人访问管理员地址时返回申请页。截图在 `.local-cache/admin-source-qa/`。共享工作区同时有扫码登录改动，本轮未联调该入口或真实飞书授权。
