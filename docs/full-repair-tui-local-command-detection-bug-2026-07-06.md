# TUI 本地命令误判 Bug 报告

## 对应能力

- 本地 CLI / TUI 交互层：普通自然语言输入应进入聊天主链，只有明确的本地 setup / doctor / config 等命令才应走本地命令执行路径。

## 现象

- 用户在 TUI 中输入 `setup a deployment plan` 这类普通自然语言时，会被识别成本地 setup 命令。
- 结果是输入绕过聊天提交，误进入本地 setup 命令路径，用户无法让 Agent 正常处理该请求。

## 根因

- `terminal-ui/src/domain/localCommands.ts` 中的 `setup(?:\s|$)` 过宽。
- 该规则会匹配所有以 `setup ` 开头的文本，而不是只匹配后端实际支持的 setup 命令形态。

## 修复

- 收紧 `setup` 分支，仅识别：
  - `setup`
  - `setup --...`
  - `setup terminal`
  - `setup tools`
  - `setup agent`
  - `setup tts`
  - `setup model...`
  - `setup gateway...`
- 保留既有本地 setup 命令，同时让 `setup a deployment plan` 回到聊天提交路径。

## 验证

- `npm test -- src/__tests__/localCommands.test.ts`
- `npm run type-check`
- `npm run lint`
