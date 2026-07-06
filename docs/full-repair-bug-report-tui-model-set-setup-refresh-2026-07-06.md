# TUI model set 成功后仍停留在 setup required 问题修复报告

## 问题现象

TUI 在缺少模型配置的 `setup required` 状态下执行 `/model set ...`，后端会把 provider 和 model 写入 `workspace/config.yml`，但 TUI 不会立即刷新 setup 状态，也不会创建或恢复会话。用户必须重启 TUI 才能进入可对话状态。

## 影响范围

- 影响本地 TUI 的首次模型配置流程。
- 影响 `/model set ...` 和 `/model configure ...` 成功后的状态恢复。
- 不影响已有会话中的普通 `/model <name>` 切换。

## 根因

`terminal-ui/src/app/createSlashHandler.ts` 已经把 `/model set` 路由到后端 `slash.exec`，并会渲染后端输出。但该路径只展示命令结果，没有像外部 setup handoff 一样重新调用 `setup.status`。因此即使后端已经写入模型配置，前端仍保持 `sid=null` 和 `setup required`。

## 修复方案

在 backend setup 命令回调中增加最小恢复逻辑：

- 只处理 `/model set` 和 `/model configure`。
- 只在后端输出包含“模型配置已写入”时触发。
- 只在当前仍无会话且状态为 `setup required` 时触发。
- 调用 `setup.status` 复查 provider 状态；provider 已配置后调用现有 `session.newSession()`。

## 回归测试

在 `terminal-ui/src/__tests__/createSlashHandler.test.ts` 中新增用例，覆盖：

- `/model set ...` 成功输出会正常显示。
- 成功后会调用 `setup.status`。
- provider 已配置后会立即调用 `newSession()`。

验证命令：

```powershell
npm test -- src/__tests__/createSlashHandler.test.ts -t "starts a session after /model set"
```

结果：测试先失败确认缺陷存在，修复后通过。
