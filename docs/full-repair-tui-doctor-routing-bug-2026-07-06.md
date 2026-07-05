# TUI Doctor 路由 Bug 报告

## 对应能力

- Dashboard-first setup / doctor：TUI setup 面板中的诊断入口应能稳定调用后端 doctor 命令。

## 现象

- TUI setup 面板提供“诊断”操作，并通过 `/doctor` 触发。
- 当后端命令目录已经加载，且目录中没有 `/doctor` 别名时，TUI 会在本地返回 `unknown command: /doctor — try /help`。
- 用户无法从 setup 面板进入诊断结果。

## 根因

- `terminal-ui/src/app/createSlashHandler.ts` 先执行后端 setup 命令旁路，再执行本地 slash registry，最后根据 catalog 拒绝未知命令。
- `/doctor` 是后端 `TerminalSetupCommands` 支持的本地诊断命令，但未加入 `shouldUseBackendSetupCommand()` 的旁路清单。

## 修复

- 将 `doctor` 加入后端 `slash.exec` 旁路。
- 保留 catalog 的未知命令保护，避免泛化放行其它未注册 slash 命令。

## 验证

- `npm test -- src/__tests__/createSlashHandler.test.ts -t "keeps /doctor"`
- `npm test -- src/__tests__/createSlashHandler.test.ts`
