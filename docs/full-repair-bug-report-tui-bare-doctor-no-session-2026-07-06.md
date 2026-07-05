# TUI 裸 doctor 命令无会话时无法执行问题修复报告

## 问题现象

TUI 启动早期尚未创建会话时，用户输入裸命令 `doctor` 会收到 `session not ready yet`。但 `/doctor` slash 命令本来支持在无会话状态下通过后端诊断入口执行。

## 影响范围

- 影响首次启动、配置缺失或会话尚未就绪时的诊断入口。
- 影响裸命令 `doctor`。
- 不影响显式 `/doctor` slash 命令。

## 根因

`terminal-ui/src/app/useSubmission.ts` 先把裸 `doctor` 识别为本地 CLI 命令，然后在进入后端 `slash.exec` 前要求存在 `sid`。而 `terminal-ui/src/app/createSlashHandler.ts` 已经把 `/doctor` 作为无会话可执行的诊断命令处理，并允许 `session_id: null`。

裸命令提交层没有复用现有 slash handler，导致用户在最需要诊断的无会话状态下反而无法运行诊断。

## 修复方案

将 `doctor` 纳入提交层的无会话 slash 转发白名单。裸 `doctor` 会转换为 `/doctor` 交给现有 slash handler 处理，避免重复实现后端 RPC 调用。

## 回归测试

在 `terminal-ui/src/__tests__/useSubmissionSetupCommand.test.ts` 中新增无会话裸 `doctor` 场景，覆盖：

- `dispatchSubmission('doctor')` 会调用 `slashRef.current('/doctor')`。
- 不输出 `session not ready yet`。
- 不在提交层直接调用 `gw.request`。

验证命令：

```powershell
npm test -- src/__tests__/useSubmissionSetupCommand.test.ts src/__tests__/createSlashHandler.test.ts src/__tests__/localCommands.test.ts
```

结果：3 个测试文件通过，108 个测试通过。
