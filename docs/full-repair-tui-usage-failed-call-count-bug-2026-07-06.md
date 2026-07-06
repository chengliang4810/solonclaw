# TUI 失败模型调用 Usage 计数修复

## 问题

TUI 中真实发起模型请求后，如果 provider 返回 401 等错误，随后执行 `/usage` 会显示 `no API calls yet`。

## 根因

`session.usage` 的调用次数来自 `AgentRunRepository.countUsageRunsBySession()`。SQLite 实现只统计 `input_tokens`、`output_tokens` 或 `total_tokens` 大于 0 的 run。鉴权失败这类请求已经产生模型尝试，但 provider 不返回 usage token，因此 run 中 token 全为 0，被误判为没有 API 调用。

## 修复

保持原 token 统计不变，只调整调用次数判定：

- 有 token 用量的 run 继续计数。
- 无 token 但已经结束、`attempts > 0` 且记录了 provider/model 的 run 也计为一次调用。
- 排队、未发起模型、未结束的 run 不计入。

## 验证

```bash
mvn "-Dskip.web.build=true" "-Dtest=TerminalUiRpcServiceTest#sessionUsageCountsFailedModelAttemptsWithoutTokens" test
mvn "-Dskip.web.build=true" "-Dtest=TerminalUiRpcServiceTest" test
```
