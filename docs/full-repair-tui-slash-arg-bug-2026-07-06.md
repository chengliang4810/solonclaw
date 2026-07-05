# TUI Slash 数字参数部分解析缺陷报告

日期：2026-07-06

## 对应外部对标能力点

- 本地 CLI / TUI 交互层：slash commands 的参数语义必须严格、可预测。
- 任务监督与回放能力：`/replay`、`/replay-diff` 需要可靠解析历史索引。

## 现象

部分 TUI slash 命令使用 `parseInt` 解析数字参数，会把带尾随字符的输入当作有效数字：

- `/replay 1abc` 被当作 `/replay 1`。
- `/replay-diff 1abc 2` 被当作 `/replay-diff 1 2`。
- `/logs 2abc` 被当作 `/logs 2`。
- `/history 2abc` 被当作 `/history 2`。

这会让用户输入错误参数时进入错误的功能路径，而不是看到 usage 提示。

## 根因

`parseInt` 允许部分解析，遇到非数字尾缀会停止并返回前缀数字。TUI 里已有 `/copy` 和 `/skills browse` 的完整 token 校验模式，但上述命令没有复用同一规则。

## 修复

- 新增 `parsePositiveIntegerArg()`，统一要求参数完整匹配正整数并且在 JavaScript safe integer 范围内。
- `/copy`、`/logs`、`/history`、`/replay`、`/replay-diff`、`/skills browse` 改为复用该入口。
- 新增回归测试覆盖 malformed 参数不再被部分解析。

## 验证

```powershell
npm test -- src/__tests__/createSlashHandler.test.ts -t "malformed /replay|malformed /replay-diff|malformed /history|malformed /logs"
npm test -- src/__tests__/createSlashHandler.test.ts
npm run lint
npm run type-check
```

结果：目标红测在修复前失败；修复后目标测试、完整 `createSlashHandler` 测试、lint 与 type-check 均通过。
