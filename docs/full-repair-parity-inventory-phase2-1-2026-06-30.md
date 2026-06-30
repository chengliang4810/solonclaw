# 阶段 2.1 当前前后端一致性复核清单

日期：2026-06-30

## 结论

当前 `work/full-repair-optimization` HEAD 的阶段 2 复核发现 1 个高置信缺口：平台 Toolsets 后端已提供更新接口，但 Web Dashboard 只有只读展示，没有可保存的配置入口。

本轮先提交只读清单，不直接混入功能改动。下一项按阶段 2.1 / 2.3 原子修复平台 Toolsets UI 与前端 API 保存能力。

## 本轮检查范围

- 后端控制器：`src/main/java/com/jimuqu/solon/claw/web/*Controller.java`
- 前端 API：`web/src/api/solonclaw/*`
- 前端路由与导航：`web/src/router/index.ts`、`web/src/components/layout/*`
- Dashboard 页面：`web/src/views/solonclaw/*`

## 高置信缺口

### 1. 平台 Toolsets 更新入口缺少 UI 操作面

事实：

- 后端存在只读与更新接口：
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardPlatformToolsetsController.java:31`：`GET /api/tools/platform-toolsets`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardPlatformToolsetsController.java:43`：`PUT /api/tools/platform-toolsets/{platform}`
- 前端类型已经描述可更新字段：
  - `web/src/api/solonclaw/diagnostics.ts:80`：`enabledToolsets`
  - `web/src/api/solonclaw/diagnostics.ts:83`：`disabledToolsets`
  - `web/src/api/solonclaw/diagnostics.ts:84`：`approvalRequired`
- 前端目前只有读取函数：
  - `web/src/api/solonclaw/diagnostics.ts:282`：`fetchPlatformToolsets()`
- 诊断页目前只展示平台 Toolsets：
  - `web/src/views/solonclaw/DiagnosticsView.vue:610`：加载只读数据。
  - `web/src/views/solonclaw/DiagnosticsView.vue:903` 至 `web/src/views/solonclaw/DiagnosticsView.vue:914`：展示启用、禁用和审批状态，没有编辑、保存或刷新保存后的交互。

影响：

- 用户只能看到平台工具集策略，无法通过 Dashboard 修改。
- 后端已有 `PUT` 能力和自然语言工具入口，Web Dashboard 没有对应配置操作，属于“后端已有但前端未完整使用”和“后端已有但 UI 操作界面缺失”的交叉缺口。

修复建议：

- 在 `web/src/api/solonclaw/diagnostics.ts` 增加 `updatePlatformToolsets(platform, payload)`。
- 在 `DiagnosticsView.vue` 的平台 Toolsets 面板补齐启用工具集、禁用工具集、是否需要审批的编辑与保存交互。
- 增加静态覆盖测试，确认前端调用 `PUT /api/tools/platform-toolsets/{platform}`，并确认页面存在保存入口。

## 已复核但当前不作为缺口的候选

### 1. 媒体管理

结论：已有前端 API 和渠道页操作入口，不列为阶段 2.1 缺口。

证据：

- 后端媒体接口覆盖列表、索引、详情、刷新、下载、引用：
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardMediaController.java:31`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardMediaController.java:43`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardMediaController.java:66`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardMediaController.java:77`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardMediaController.java:100`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardMediaController.java:123`
- 前端 API 已封装同名能力：
  - `web/src/api/solonclaw/media.ts:22`
  - `web/src/api/solonclaw/media.ts:28`
  - `web/src/api/solonclaw/media.ts:32`
  - `web/src/api/solonclaw/media.ts:38`
  - `web/src/api/solonclaw/media.ts:44`
  - `web/src/api/solonclaw/media.ts:50`
- 渠道页已有列表、索引、本地路径输入、详情抽屉、刷新、下载、引用入口：
  - `web/src/views/solonclaw/ChannelsView.vue:26`
  - `web/src/views/solonclaw/ChannelsView.vue:67`
  - `web/src/views/solonclaw/ChannelsView.vue:124`
  - `web/src/views/solonclaw/ChannelsView.vue:156`

### 2. Insights 洞察

结论：已有前端 API 和诊断页展示入口，不列为“完全未使用”缺口。

证据：

- 后端接口：
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardInsightsController.java:28`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardInsightsController.java:38`
- 前端 API：
  - `web/src/api/solonclaw/insights.ts:18`
  - `web/src/api/solonclaw/insights.ts:22`
- 诊断页加载和展示：
  - `web/src/views/solonclaw/DiagnosticsView.vue:39`
  - `web/src/views/solonclaw/DiagnosticsView.vue:597`
  - `web/src/views/solonclaw/DiagnosticsView.vue:916`

### 3. 会话 trajectory 与 checkpoints

结论：已有前端 API 和 Runs 页面操作入口，不列为阶段 2.1 缺口。

证据：

- 后端接口：
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardSessionController.java:80`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardSessionController.java:97`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardSessionController.java:160`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardSessionController.java:183`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardSessionController.java:206`
- 前端 API：
  - `web/src/api/solonclaw/sessions.ts:367`
  - `web/src/api/solonclaw/sessions.ts:376`
  - `web/src/api/solonclaw/sessions.ts:380`
  - `web/src/api/solonclaw/sessions.ts:384`
  - `web/src/api/solonclaw/sessions.ts:388`
- Runs 页面入口：
  - `web/src/views/solonclaw/RunsView.vue:83`
  - `web/src/views/solonclaw/RunsView.vue:113`
  - `web/src/views/solonclaw/RunsView.vue:147`
  - `web/src/views/solonclaw/RunsView.vue:157`
  - `web/src/views/solonclaw/RunsView.vue:167`
  - `web/src/views/solonclaw/RunsView.vue:371`
  - `web/src/views/solonclaw/RunsView.vue:381`

## 下一项原子任务

阶段 2.1 / 2.3：补齐平台 Toolsets Dashboard 保存入口。

验收点：

1. 前端 API 能调用 `PUT /api/tools/platform-toolsets/{platform}`。
2. 诊断页平台 Toolsets 面板能编辑并保存每个平台的启用工具集、禁用工具集、审批开关。
3. 保存成功后刷新当前平台 Toolsets 数据，未保存时保留原展示。
4. 新增测试覆盖 API 写入路径和页面保存入口。
