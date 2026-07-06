# TUI `/terminal-setup` 命令未出现在帮助面板

## 范围

- 本地 TUI slash command 发现路径。
- `/help` / `/commands` 帮助面板。

## 现象

`/terminal-setup` 可以执行，也会触发 IDE 终端按键配置确认流程，但用户打开 `/help` 或 `/commands` 时看不到该命令。新用户需要靠猜测或补全才知道它存在。

## 根因

`terminal-ui/src/app/slash/commands/core.ts` 中已经实现了 `terminal-setup` 命令，但同文件的本地 TUI 帮助 rows 漏写该命令。帮助面板不会自动从本地命令表生成这部分 rows，因此出现可执行但不可发现的命令。

## 修复

- 在本地 TUI 帮助 rows 中补充 `/terminal-setup [auto|vscode|cursor|windsurf]`。
- 扩展 `createSlashHandler.test.ts`，锁定 `/commands` 面板必须展示该命令。

## 验证

```bash
npm --prefix terminal-ui test -- src/__tests__/createSlashHandler.test.ts --testNamePattern "local help panel|terminal-setup"
```
