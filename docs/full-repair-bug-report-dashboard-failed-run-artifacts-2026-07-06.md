# Dashboard 失败运行会话详情与轨迹为空

## 范围

- Dashboard 会话列表、会话详情、recap 与 trajectory。
- 模型调用失败且会话正文 `ndjson` 尚未落入用户/助手消息的运行记录。

## 现象

失败会话列表已经能用 `agent_runs.input_preview` 和 `error` 补出 `message_count=2` 与预览，但进入会话详情、recap 或 trajectory 时仍只读取空 `ndjson`，页面和导出内容看不到同一轮用户输入与失败原因。

## 根因

`DashboardSessionService#toSessionInfo()` 已接入 `AgentRunRepository` 作为空正文回退，但 `getSessionMessages()`、`recap()`、`trajectory()` 和 `saveTrajectory()` 仍直接把原始 `SessionRecord` 交给消息解析或 artifact 服务，导致列表与详情使用了两套可见消息来源。

## 修复

- 在 `DashboardSessionService` 增加共享 `visibleMessages()`，空正文时把最近运行的输入预览和最终回复/错误转换为只读消息。
- `getSessionMessages()`、会话列表统计、recap、trajectory、save trajectory 统一复用该可见消息来源。
- Artifact 路径使用只读会话副本，不把回退消息写回持久化 `ndjson`。

## 验证

```bash
mvn "-Dskip.web.build=true" "-Dtest=DashboardControllerHttpTest#shouldReflectFailedRunInDashboardSessionSummary" test
```
