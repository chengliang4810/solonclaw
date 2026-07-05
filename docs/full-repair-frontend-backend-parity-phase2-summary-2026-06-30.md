# 阶段 2 前后端功能一致性收口记录

日期：2026-06-30

复核时间：2026-07-04

复核时间：2026-07-05

增量复核时间：2026-07-05

## 结论

阶段 2 在当前源码面已完成本轮增量收口。

本轮先提交了阶段 2.1 / 2.3 只读复核清单，确认唯一高置信缺口是平台 Toolsets 后端已有更新接口但 Dashboard 只有只读展示。随后已补齐 Web Dashboard 保存入口，并把 `dev` 推送到 `origin/dev`。

2026-07-04 当前 HEAD 复核未发现新的高置信前后端功能缺口。平台 Toolsets 保存入口仍由后端 `PUT /api/tools/platform-toolsets/{platform}`、前端 `updatePlatformToolsets(...)`、诊断页保存按钮和 `platformToolsetsUiStatic.test.ts` 覆盖；新增 TUI runtime 渠道配置能力也已通过 Web 端 `/api/tui/rpc` 调用 `channel.save`、`channel.qr.start`、`channel.qr.get` 等后端方法。

2026-07-05 当前 HEAD 复核仍未发现新的高置信前后端功能缺口。平台 Toolsets 后端更新接口和 Web 保存入口继续存在；真实 Web E2E 发现的模型 `401 Invalid API Key` 属于外部凭据或上游服务状态问题，聊天失败后会话列表 `message_count=0` 已登记为 BUG-033 待复核，不先归类为前端或后端缺失功能。

2026-07-05 增量复核发现定时任务 inspect 聚合端点已有后端和前端 API，但任务详情抽屉仍拆成任务详情与运行记录两次请求。已改为通过 store 统一调用 `inspectJob`，让 UI 使用 `/api/cron/jobs/{id}/inspect` 的聚合结果。

## 已完成项

### 1. 阶段 2.1 / 2.3 复核清单

- 提交：`ec0c987f2 docs: 记录阶段二一致性复核清单 / Record phase two parity inventory`
- 文档：`docs/full-repair-parity-inventory-phase2-1-2026-06-30.md`
- 结论：媒体管理、Insights、会话 trajectory 与 checkpoints 已有前端入口；平台 Toolsets 更新能力缺少 Dashboard 保存入口。

### 2. 平台 Toolsets 保存入口

- 提交：`dfc6fcb3e feat: 补齐平台工具集保存入口 / Add platform toolsets save entry`
- 后端契约：`PUT /api/tools/platform-toolsets/{platform}`，请求字段为 `enabledToolsets`、`disabledToolsets`、`approvalRequired`。
- 前端 API：`web/src/api/solonclaw/diagnostics.ts` 增加 `updatePlatformToolsets(platform, payload)`。
- 前端 UI：`web/src/views/solonclaw/DiagnosticsView.vue` 的平台 Toolsets 面板支持编辑启用工具集、禁用工具集和审批开关，并在保存后用后端返回值刷新当前行状态。
- 覆盖测试：`web/tests/platformToolsetsUiStatic.test.ts` 增加 `PUT` 路径和保存入口静态哨兵。

### 3. 定时任务 inspect 聚合端点接入

- 提交：本次提交。
- 后端契约：`GET /api/cron/jobs/{jobId}/inspect` 同时返回任务详情和运行记录。
- 前端 API：`web/src/api/solonclaw/jobs.ts` 已有 `inspectJob(jobId, limit)`。
- 前端 UI：`JobCard.vue` 的详情抽屉改为通过 `jobsStore.inspectJob(jobId, 20)` 一次加载详情任务和运行记录。
- 覆盖测试：`web/tests/jobInspectEndpointStatic.test.ts` 锁定 store 转发和 JobCard 聚合端点调用。

## 验证记录

平台 Toolsets 修复提交前已执行：

- `node --experimental-strip-types web/tests/platformToolsetsUiStatic.test.ts`：通过。
- `cd web && npx vue-tsc -b --noEmit --pretty false`：通过。
- `npm --prefix web run build`：通过。
- `cd web && bun /Users/chengliang/.codex/plugins/cache/sisyphuslabs/omo/4.13.0/skills/programming/scripts/typescript/check-no-excuse-rules.ts src/api/solonclaw/diagnostics.ts src/views/solonclaw/DiagnosticsView.vue tests/platformToolsetsUiStatic.test.ts`：通过。
- `git diff --check`：通过。
- `python3 scripts/check-project-naming.py --root-path docs --check-git-commit-subjects --check-git-object-text --check-current-branch-range`：通过。

浏览器烟测：

- 生产预览：`npm --prefix web run preview -- --host 127.0.0.1 --port 4178`。
- 使用本地 mock 后端数据打开 `/#/solonclaw/diagnostics`。
- 1280px 与 375px 宽度均无横向溢出。
- 平台 Toolsets 有两行测试数据，保存按钮可见；保存后当前平台审批状态从“需要审批”更新为“无需审批”。
- 截图保存在未提交目录：`output/visual-qa/platform-toolsets-1280.png`、`output/visual-qa/platform-toolsets-375.png`。

只读代码审查代理结论：

- 结论：PASS。
- 阻塞问题：无。
- 审查确认 API 契约、Vue 绑定、本地状态更新、`antdv-next` 组件用法和静态测试均无提交阻塞项。

2026-07-04 只读复核补充：

- 后端能力缺口审计代理结论：`NO_GAPS`。
- 已复核 `PUT /api/config/raw` 不作为 Web Dashboard 缺口：当前设置页只读展示原始配置，避免绕过安全配置边界。
- 已复核 `/api/gateway/message`、`/api/tui/handshake` 不作为 Web Dashboard 缺口：分别属于渠道注入和 TUI 内部握手面。
- 本地主线程复核命令覆盖 `updatePlatformToolsets`、`savePlatformToolsets`、`/api/tools/platform-toolsets`、`fetchTuiRuntimeOverview`、`channel.save`、`channel.qr.start`、`channel.qr.get` 和 `/api/tui/rpc`。

2026-07-05 只读复核补充：

- 本地主线程复核 `DashboardPlatformToolsetsController`、`web/src/api/solonclaw/diagnostics.ts`、`DiagnosticsView.vue` 和 `platformToolsetsUiStatic.test.ts`，确认平台 Toolsets 已有保存入口。
- Web E2E 复测登录、模型页、设置、系统诊断、日志、用量、运行记录、渠道、技能和对话页均可打开，Chrome console 无 error/warn。
- TUI E2E 复测在线 `/status`、`/stop`、`/quit` 和离线 `/status`、`/quit` 均通过，45 秒离线空闲未见重连刷屏或快速增长。
- 阶段 2.1 只读复核代理结论：当前 HEAD 未发现新增的“后端已有但前端未使用”高置信缺口；Runs、Sessions、Cron、MCP、Agent、Gateway、平台 Toolsets、Config raw 等可疑项均已复核为已接入或明确非 Dashboard 操作入口。
- 阶段 2.2 只读复核代理结论：当前 HEAD 未发现新增的“前端已有但后端缺失”高置信缺口；二维码 setup、MCP 子路径、cron job helper、session/checkpoint helper 和 TUI RPC method 等动态路径均已人工复核。

2026-07-05 inspect 增量修复提交前执行：

- `node --experimental-strip-types web/tests/jobInspectEndpointStatic.test.ts`：通过。
- `node --experimental-strip-types web/tests/jobMutationPayloadStatic.test.ts`：通过。
- `node --experimental-strip-types web/tests/jobFormOptions.test.ts`：通过。
- `node --experimental-strip-types web/tests/jobsDisplay.test.ts`：通过。
- `npm --prefix web run build`：通过。
- `git diff --check`：通过，仅有 Windows 换行提示。

## 推送状态

- `work/full-repair-optimization`：当前 HEAD 为 `dfc6fcb3e`。
- `/Users/chengliang/code-projects/jimuqu-agent` 的 `dev` 已快进到 `dfc6fcb3e`。
- `origin/dev` 已推送到 `dfc6fcb3e`。
- 直连 GitHub 443 超时，最终使用一次性代理环境变量推送成功；未修改全局 Git 配置。
- 2026-07-04 复核时，当前 worktree `HEAD...origin/dev` 为 `0 0`。
- 2026-07-05 复核时，当前 worktree `HEAD...origin/dev` 为 `0 0`。
- 2026-07-05 inspect 增量修复开始前，当前 worktree `HEAD...origin/dev` 为 `0 0`。

## 剩余风险

- 本轮浏览器烟测使用 mock 后端覆盖 UI 交互，不代表真实部署环境的登录、权限和配置文件写入链路已经端到端复测。
- `DiagnosticsView.vue` 仍是大文件，但当前约 2034 行，未触发本项目阶段 1 的 4000 行拆分阈值；后续如继续增加诊断页功能，应优先拆分面板组件。

## 下一阶段入口

阶段 3 进入功能去重与融合加强。优先从已有审计中标出的重复候选继续：

1. 功能重复检测清单。
2. Provider 登录弹窗、工作区/人格文件视图、二维码 setup、国内渠道适配、危险命令测试辅助方法等高重叠区域。
3. 每个融合或复用改造继续按单功能点提交。
