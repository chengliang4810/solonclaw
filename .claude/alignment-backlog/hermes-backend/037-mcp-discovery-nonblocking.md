# 037-mcp-discovery-nonblocking

## 标题
Make MCP discovery nonblocking at startup

## 状态
- status: done
- completedCommit: 38da0e58
- testResult: `mvn -Dskip.web.build=true "-Dtest=SkillBundlePathSupportTest,SkillImportServiceTest,ModelMetadataServiceTest,RuntimeRefreshBehaviorTest,DashboardStatusServiceTest,DashboardDiagnosticOutputTest,McpRuntimeServiceTest" test` 通过（141 tests, 0 failures, 0 errors）

## 优先级 / 风险
- priority: medium
- risk: medium
- parallelSafe: true
- overlapHint: `037-mcp-discovery-nonblocking`

## 对标能力
来源领域：backend alignment

## 当前缺口
Hermes explicitly runs MCP discovery in an executor so the event loop stays responsive, and re-loads tools asynchronously on reload. The current project already has bounded executors and keepalive plumbing, but the bootstrap path should be checked for any synchronous discovery that could still block ready/startup.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/gateway/run.py:19472-19483`
- `/Users/chengliang/code-repositories/hermes-agent/gateway/run.py:13963-14115`
- `/Users/chengliang/code-repositories/hermes-agent/agent/lsp/manager.py:1-34`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/mcp/McpRuntimeService.java:163-240`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/mcp/McpKeepaliveService.java:40-101`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java:143-147`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/web/DashboardMcpService.java`

## 验证方式
Prove slow or failing MCP discovery does not delay gateway ready, records last error state, and can recover on later reload.
