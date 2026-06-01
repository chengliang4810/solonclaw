# 003-tool-result-persistence-artifacts

## 标题
对齐工具结果持久化与 artifacts

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: true

## 外部对标参考
- `对标实现路径：tools/tool_result_storage.py`
- `对标实现路径：agent/tool_executor.py`

## 当前项目目标文件
- `src/main/java/com/jimuqu/solon/claw/storage/session/SqliteAgentSession.java`
- `src/main/java/com/jimuqu/solon/claw/storage/repository/SqliteSessionRepository.java`
- `src/main/java/com/jimuqu/solon/claw/support/SessionArtifactService.java`

## 当前缺口
外部对标会把大型工具输出外置存储，并在 live transcript 中保留 preview/path；当前项目主要依赖 SQLite session snapshot 与 artifact 服务，仍需确认超大工具结果、可回放工具内容、recap/trajectory 输出是否需要同级 spill-to-file 或归一化。

## 实现范围
对比并补齐 live-session 工具结果持久化：超大输出 spillover、preview 格式、session replay fidelity、recap/trajectory artifact 保存前归一化。

## 验证方式
`mvn -q -Dtest=SqliteAgentSessionTest test`
