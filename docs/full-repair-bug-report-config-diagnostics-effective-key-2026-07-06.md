# 配置诊断内部键泄露修复报告

## 问题

`GET /api/config/diagnostics` 的配置漂移结果会把 `solonclaw.*` 配置映射后的 AppConfig 内部路径输出到 `effective_key`。例如 `solonclaw.dashboard.accessToken` 漂移时，响应中出现 `dashboard.accessToken`。

## 影响

用户在 Dashboard 诊断页会同时看到当前外部配置键和内部 Java 配置路径，容易误以为 `dashboard.accessToken` 也是可写配置键。诊断结果还会把项目内部结构暴露给前端与工具调用方。

## 根因

`RuntimeConfigResolver.diagnostics()` 需要用内部路径和扁平化的 `AppConfig` 做比较，但它复用了同一个内部路径作为响应字段输出。比较逻辑是必要的，输出内部键不是必要的。

## 修复

诊断结果只保留当前配置文件中的外部键 `key`，内部路径仅用于读取生效值和脱敏，不再输出 `effective_key`。

## 验证

新增 `RuntimeConfigResolverTest.shouldKeepExternalConfigKeyInDashboardTokenDriftDiagnostics`，覆盖 `solonclaw.dashboard.accessToken` 漂移时不输出 `effective_key=dashboard.accessToken`。

验证命令：

```powershell
mvn "-Dskip.web.build=true" "-Dtest=RuntimeConfigResolverTest" test
```
