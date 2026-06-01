# 037-mcp-discovery-nonblocking

## 标题
启动期 MCP discovery 不阻塞网关事件循环

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: medium
- parallelSafe: true

## 对标能力
对标实现把慢 MCP discovery 放到独立执行器，失败只记录状态，不阻塞平台心跳和消息处理。

## 当前缺口
当前 MCP runtime 有 timeout 和 bounded executor，但需要确认启动/bootstrap 阶段不会同步阻塞网关 ready。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/mcp/McpRuntimeService.java`
- `src/main/java/com/jimuqu/solon/claw/mcp/McpKeepaliveService.java`
- `src/main/java/com/jimuqu/solon/claw/web/DashboardMcpService.java`

## 验证方式
慢连接不阻塞 gateway ready；失败记录 last_error；后续 reload 可恢复。
