# TUI 裸 setup 命令无会话时无法打开设置面板问题修复报告

## 问题现象

当后端启动后进入 `setup required` 状态、尚未创建会话时，用户在 TUI 输入裸命令 `setup` 会收到 `session not ready yet`，无法打开首轮配置面板。相同状态下输入 `/setup` 可以正常打开设置面板。

## 影响范围

- 影响 TUI 首次启动或配置缺失后的设置引导。
- 影响裸命令 `setup`、`setup model`、`setup gateway` 的自然输入路径。
- 不影响 `/setup` slash 命令路径。

## 根因

`terminal-ui/src/app/useSubmission.ts` 的提交分发逻辑先把裸 `setup` 识别为本地 CLI 命令，然后在进入 `slash.exec` 前要求存在 `sid`。但 TUI 原生设置命令本来不依赖会话，已有 `/setup` slash handler 可以直接打开本地面板。

因此，无会话状态下裸 `setup` 被错误拦在会话检查处，没有复用已经存在的原生 `/setup` 处理器。

## 修复方案

在本地 CLI 命令分支中增加原生 setup 命令白名单：

- `setup`
- `setup model`
- `setup gateway`

这些命令会被转换为对应 slash 命令并交给现有 slash handler 处理。其它本地 CLI 命令继续保持原来的 `slash.exec` 后端路径，避免把 `setup --quick`、`setup terminal` 等未实现为原生面板的命令伪装成本地成功。

## 回归测试

新增 `terminal-ui/src/__tests__/useSubmissionSetupCommand.test.ts`，覆盖无会话状态下裸 `setup` 必须路由到 `/setup`，且不得输出 `session not ready yet` 或调用后端 `slash.exec`。

验证命令：

```powershell
npm test -- src/__tests__/useSubmissionSetupCommand.test.ts src/__tests__/localCommands.test.ts src/__tests__/createSlashHandler.test.ts src/__tests__/setupPanel.test.ts
```

结果：4 个测试文件通过，114 个测试通过。
