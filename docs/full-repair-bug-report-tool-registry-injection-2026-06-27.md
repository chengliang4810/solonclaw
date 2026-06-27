# Tool Registry 注入链路缺陷报告

生成时间：2026-06-27

本文档对应阶段 1.1：以单个功能点为最小单位保存已证实的功能 bug 报告。

## BUG-003：工具注册表启动期依赖 Dashboard 诊断服务导致容器注入失败

状态：已修复，提交 `c310b1b53`

影响范围：

- Dashboard HTTP 启动。
- Agent 工具注册表创建。
- 依赖 `ConversationOrchestrator` 的定时任务调度器和命令服务启动链路。

当前事实：

- `ToolConfiguration.toolRegistry(...)` 原本在创建 `ToolRegistry` 时直接要求注入 `DashboardDiagnosticsService`。
- `DashboardConfiguration.dashboardDiagnosticsService(...)` 同时要求注入 `ToolRegistry`、`ConversationOrchestrator` 和 `CommandService`。
- `ConversationOrchestrator` 与 `CommandService` 又都依赖 `ToolRegistry`，形成启动期闭环。
- `DashboardInsightsService` 原本依赖具体类 `SqliteSessionRepository`，而容器注册的是 `SessionRepository` 接口，也会放大 Dashboard 服务注入失败。

可复现现象：

```bash
mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=DashboardControllerHttpTest#shouldAvoidInjectingDashboardTokenAndProtectSensitiveApis test
```

修复前测试启动 Solon 容器时报错：

```text
Solon start failed: Method param injection failed: 'conversationOrchestrator'
    at com.jimuqu.solon.claw.bootstrap.SchedulerConfiguration.defaultCronScheduler
```

虽然报错表面指向 `conversationOrchestrator`，但根因是 `ToolRegistry` 创建链路被 Dashboard 诊断服务反向依赖阻塞。

源码证据：

- `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
  - `toolRegistry(...)` 负责创建 `DefaultToolRegistry`。
- `src/main/java/com/jimuqu/solon/claw/bootstrap/DashboardConfiguration.java`
  - `dashboardDiagnosticsService(...)` 依赖 `ToolRegistry` 与 `ConversationOrchestrator`。
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
  - 诊断管理工具与审批队列工具需要读取 `DashboardDiagnosticsService`。
- `src/main/java/com/jimuqu/solon/claw/web/DashboardInsightsService.java`
  - 洞察服务只需要会话仓储接口能力，不应依赖 SQLite 具体实现。

修复方向：

- `DashboardInsightsService` 改为依赖 `SessionRepository` 接口。
- `ToolRegistry` 不再在启动期强制注入 `DashboardDiagnosticsService`。
- `DiagnosticsManageTools` 与 `ApprovalQueueManageTools` 改为通过 `Supplier<DashboardDiagnosticsService>` 调用时解析诊断服务。
- 非 Solon 容器的测试夹具显式传入诊断服务供应器，生产路径由 `Solon.context().getBean(...)` 延迟获取。

验证结果：

```bash
mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false \
  -Dmaven.compiler.useIncrementalCompilation=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=ToolRegistryExposureTest test
```

结果：`Tests run: 52, Failures: 0, Errors: 0, Skipped: 0`

```bash
mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=DashboardControllerHttpTest#shouldAvoidInjectingDashboardTokenAndProtectSensitiveApis test
```

结果：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`

阶段归属：

- 阶段 1.1：已保存原子级 bug 报告。
- 阶段 2.3：保留自然语言工具对 Dashboard 诊断和审批队列的操作入口。
- 阶段 7.1：修复已独立提交并合并到 `dev`。
