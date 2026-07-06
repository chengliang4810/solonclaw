# Web 直达路由规范化缺陷报告

日期：2026-07-06

## 对应能力点

- Dashboard-first 操作入口：常用页面应支持浏览器地址栏直达。
- Web UI 真实用户路径：诊断页、状态页等短路径应落到对应 Dashboard 页面。

## 现象

用户登录后直接访问 `/diagnostics` 或 `/status` 时，页面没有进入诊断页，而是落到默认聊天页。`dashboardDirectRoutes.ts` 已声明这些短路径应映射到 `#/solonclaw/diagnostics`。

## 根因

`normalizeLoginTokenUrl()` 在处理无 hash 的直达路径时，只追加目标 hash，但保留原始 pathname，生成类似 `/diagnostics#/solonclaw/diagnostics` 的 URL。Hash router 在非根路径下启动后会进入兜底逻辑，实际体验变成聊天页。

## 修复

当存在直达 hash 映射时，将 pathname 规范为 `/`，生成 `/#/solonclaw/diagnostics` 这类根路径 hash URL，避免非根 pathname 干扰路由启动。

## 验证

- 红测：`node --experimental-strip-types tests\loginUrlTokenNormalization.test.ts` 修复前因 `/chat#/...` 旧行为失败。
- 修复后同一测试通过，并需运行 Dashboard 相关静态路由测试与 Web 构建。
