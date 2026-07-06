# Dashboard 直达路径登录后目标页修复

## 问题

访问 `/solonclaw/diagnostics`、`/solonclaw/settings`、`/solonclaw/runs` 这类无 hash 的 Dashboard 直达路径时，启动阶段会先规范化为 hash 路由，但登录校验成功后仍可能回到聊天页。

## 根因

路由守卫跳转登录页时只使用 `{ name: "login" }`，目标页依赖 `route.redirectedFrom` 恢复。直达路径经过启动归一化和登录页自动校验后，`redirectedFrom` 不够稳定，`LoginView` 会落到默认 `/solonclaw/chat`。

## 修复

路由守卫将原目标 `to.fullPath` 显式传入 `redirect` query。登录成功后优先读取该 query，并且只接受 `/solonclaw/` 开头的站内 Dashboard 路径，避免外部跳转。

## 验证

```bash
node --experimental-strip-types tests\loginStoredTokenValidationStatic.test.ts
node --experimental-strip-types tests\loginUrlTokenNormalization.test.ts
node --experimental-strip-types tests\dashboardDirectRoutes.test.ts
node --experimental-strip-types tests\loginUrlTokenCleanupStatic.test.ts
```
