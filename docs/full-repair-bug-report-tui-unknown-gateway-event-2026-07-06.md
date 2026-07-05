# TUI 未知网关事件静默丢弃问题修复报告

## 问题现象

TUI 收到未注册的网关事件类型时，没有状态提示、没有 activity 记录，也没有引导用户查看 `/logs`。当后端新增事件、事件名拼写错误或前后端协议短暂漂移时，用户只能看到相关功能没有反应。

## 影响范围

- 影响 TUI WebSocket 网关事件分发。
- 影响所有未来新增但前端尚未识别的事件类型。
- 不影响已有明确处理的事件类型。

## 根因

`terminal-ui/src/app/createGatewayEventHandler.ts` 是 TUI 收到网关事件后的统一分发入口。该入口使用 `switch (ev.type)` 处理已知事件，但没有 `default` 分支。未知事件会自然走到函数结尾并被静默丢弃。

## 修复方案

在事件分发入口增加 `default` 分支，将未知事件写入 activity，并提示用户通过 `/logs` 查看详情。修复保留在共享入口，避免给每个事件来源重复加兜底逻辑。

## 回归测试

在 `terminal-ui/src/__tests__/createGatewayEventHandler.test.ts` 中新增未知事件场景，覆盖：

- 收到 `future.event` 时不会静默丢弃。
- activity 中展示 `unhandled gateway event: future.event · /logs to inspect`。
- 提示等级为 `warn`。

验证命令：

```powershell
npm test -- createGatewayEventHandler -t "surfaces unknown gateway events"
```

结果：测试先失败确认缺陷存在，修复后通过。
