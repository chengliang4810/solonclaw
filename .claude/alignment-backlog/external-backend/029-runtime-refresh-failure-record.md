# 029-runtime-refresh-failure-record

## 标题
记录 runtime config refresh 最近失败

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true

## 对标能力
对标实现会显式暴露配置解析失败，避免运行时静默回退或只在日志中出现一次。

## 当前缺口
当前 refresh 失败会返回调用方并写日志，但失败原因没有统一进入 status、health 或 doctor 入口。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/gateway/service/GatewayRuntimeRefreshService.java`
- `src/main/java/com/jimuqu/solon/claw/gateway/service/GatewayRuntimeStatusService.java`
- `src/main/java/com/jimuqu/solon/claw/web/DashboardStatusService.java`

## 验证方式
写入非法 YAML 后调用 refresh，断言 state/status 中记录最近失败且错误脱敏。
