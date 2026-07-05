# Web 非 Hash 深链回退问题报告

## 问题

直接打开 `/solonclaw/files` 这类非 hash dashboard 深链时，页面会回退到聊天页，而不是进入目标功能页。hash 路由 `#/solonclaw/files` 正常。

## 根因

`web/src/main.ts` 静态导入 router，`createWebHashHistory()` 会在 `normalizeLoginTokenUrl(...)` 归一化直接路径之前创建路由历史，导致直接路径映射成 hash 路由的结果没有被 router 初始化采用。

## 修复

将 router 改为 URL 归一化之后动态导入，确保 `/solonclaw/*` 直接路径先被转换为对应 hash 路由，再创建 hash history。

## 验证

- `node --experimental-strip-types tests\loginUrlTokenCleanupStatic.test.ts`
- `node --experimental-strip-types tests\dashboardDirectRoutes.test.ts`
- `npx vue-tsc -p tsconfig.app.json --noEmit --incremental false`
