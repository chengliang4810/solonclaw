# TUI 后端不可用时额外显示 config.get 内部错误

## 范围

- Node TUI 启动流程。
- 后端不可用或连接失败时的终端错误提示。
- delegation nudge 的 `display.tui_agents_nudge` 配置预取。

## 现象

当后端地址不可连接时，TUI 已经显示了可操作的连接失败提示，但随后还会追加：

```text
error: gateway not connected: config.get
```

这条信息来自内部配置读取，用户无法用它修复启动问题，会干扰真实启动诊断。

## 根因

`createGatewayEventHandler()` 在 handler 创建时立即调用 `ensureAgentsNudgeConfig()`，进而通过普通 `rpc('config.get', { key: 'full' })` 读取配置。普通 RPC 失败会进入 transcript 的 `sys("error: ...")` 路径。后端尚未 ready 时，这个 eager 配置读取会把内部 `config.get` 失败暴露给用户。

## 修复

- 移除 handler 创建时的 eager 配置读取。
- 在 `gateway.ready` 后再预取 delegation nudge 配置，保持真实会话前预热，但不在后端不可用时发起内部 RPC。
- 保留首次 `subagent.*` 事件上的懒加载兜底。

## 验证

```bash
npm --prefix terminal-ui test -- src/__tests__/createGatewayEventHandler.test.ts --testNamePattern "full config|nudge"
npm --prefix terminal-ui run type-check
```
