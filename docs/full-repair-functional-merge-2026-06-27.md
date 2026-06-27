# 阶段 3.2 功能融合合并记录

日期：2026-06-27

## 对应能力点

- 对应本地 Dashboard / Web 控制台能力：渠道设置、诊断、会话搜索、人格工作区、模型管理、运行时配置。
- 本阶段按阶段 3.1 清单逐项处理高确定性重复，不做低收益的大范围重构。

## 已处理项

1. 移除未挂载网关页面链路
   - 删除 `web/src/views/solonclaw/GatewaysView.vue`。
   - 删除 `web/src/stores/solonclaw/gateways.ts`。
   - 删除 `web/src/api/solonclaw/gateways.ts`。
   - 清理中英文网关页面文案，并把渠道页描述改为当前真实入口。
   - 提交：`61f267a8c`

2. 统一会话搜索入口
   - 删除 `/api/sessions/search` 旧路由。
   - 删除 `DashboardSessionService.searchSessions()` 和只服务旧路由的 snippet helper。
   - 保留前端 `searchSessions()` 函数名，但其实际请求仍为 `/api/search`。
   - 提交：`c958dcb2a`

3. 统一网关诊断入口
   - 删除 `/api/gateway/doctor` 旧路由。
   - 保留 `/api/diagnostics/doctor` 作为 Dashboard 统一诊断入口。
   - 更新 `README_EN.md` 和 HTTP 测试。
   - 提交：`771f7000e`

4. 复用工作区文件 API 包装
   - 从 `web/src/api/solonclaw/files.ts` 导出 `WorkspaceFile`、`fetchWorkspaceFiles()`、`fetchWorkspaceFile()`。
   - `persona.ts` 与 `skills.ts` 复用上述入口，移除本地重复类型和读取包装。
   - 提交：`2a8fe62ba`

5. 复用模型提供方转换
   - `fetchConfigModels()` 复用 `toGroup()` 的 provider 转换结果。
   - 不删除 `/api/model/info`、`/api/models`、`/api/providers`，三者仍分别服务当前模型、运行状态和 provider 配置。
   - 提交：`d45a66b63`

6. 复用渠道凭证映射
   - 用 `CHANNEL_CREDENTIAL_FIELDS` 替代 `saveCredentials()` 中按平台复制的分支。
   - 保持原有配置键、空值删除和布尔值保存语义不变。
   - 提交：`4ab02e904`

## 已复核保留项

- `/api/workspace/diaries` 与 `/api/workspace/files`：业务边界不同，未合并。
- `/api/runs/{runId}/events` 与 `/api/chat/runs/{runId}/events`：查询与流式读取场景不同，未合并。
- `/api/tools/toolsets` 与 `/api/tools/platform-toolsets`：目录与平台绑定策略不同，未合并。

## 验证

- `npm run build`：阶段 3.2 前端改动后多次通过。
- `mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DashboardSessionServiceTest,SessionSearchServiceTest test`：通过，45 tests。
- `mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DashboardControllerHttpTest test`：通过，41 tests。
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`：阶段内多次通过。
- 残留扫描确认运行时代码中不再引用：
  - `GatewaysView`
  - `useGatewayStore`
  - `/api/gateway/doctor`
  - `/api/sessions/search`
  - `DashboardSessionService.searchSessions`

## 剩余风险

- 当前清理范围基于阶段 3.1 清单，不代表全项目不存在其它更深层重复。
- `terminal-ui/package.json` 与 `terminal-ui/package-lock.json` 仍是本工作树既有未提交改动，未纳入阶段 3.2 提交。
