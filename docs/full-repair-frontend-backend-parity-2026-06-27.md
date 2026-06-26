# 阶段 2 前后端功能一致性修复记录

日期：2026-06-27

## 处理范围

- 阶段 2.1：后端已有但前端未使用的功能。
- 阶段 2.2：前端已有但后端没有的功能。
- 阶段 2.3：后端功能缺少 UI 入口的情况，已合并到 2.1 逐项处理。

## 已补齐的后端功能入口

1. 运行时审批事件
   - 后端接口：`/api/approval/events`、`/api/approval/stats`
   - 前端入口：诊断页新增运行时审批事件卡片。
   - 提交：`5b580abcf`

2. 运行时模型状态
   - 后端接口：`/api/models`、`/api/models/health`
   - 前端入口：模型页新增运行时模型状态面板。
   - 提交：`159d8a794`

3. 运行洞察统计
   - 后端接口：`/api/insights/overview`、`/api/insights/skills`
   - 前端入口：用量统计页新增运行洞察和技能使用明细。
   - 提交：`02bc0a945`

4. 技能改进记录
   - 后端接口：`/api/curator/improvements`、`/api/curator/apply`、`/api/curator/ignore`
   - 前端入口：技能页侧栏新增技能改进记录，支持标记已应用或忽略。
   - 说明：后端 `apply/ignore` 当前语义是记录建议状态，不直接修改技能文件；前端文案按“标记”表达。
   - 提交：`8dafb8740`

5. 平台工具集策略
   - 后端接口：`/api/tools/toolsets`、`/api/tools/platform-toolsets`、`/api/tools/platform-toolsets/{platform}`
   - 前端入口：渠道页新增平台工具集策略面板。
   - 提交：`c10a76358`

6. 通用会话搜索
   - 后端接口：`/api/search`
   - 前端入口：全局会话搜索弹窗改用通用搜索接口，保留原有弹窗交互。
   - 提交：`c0d8fe09e`

## 已补齐的后端接口缺口

1. 工作区文件下载
   - 前端引用：`/api/solonclaw/download`
   - 后端补齐：新增受控下载接口，只允许下载 `PersonaWorkspaceService` 暴露的固定工作区文件 key 或文件名。
   - 提交：`38d9fcdfd`

## 已确认无需新增 UI 的接口

- `/api/gateway/doctor`：与 `/api/diagnostics/doctor` 使用同一 doctor 服务，诊断页已通过后者接入。
- `/api/gateway/message`：消息网关注入入口，供外部渠道/签名调用，不属于 Dashboard UI 操作入口。
- `/api/tui/handshake`、`/api/tui/rpc`：TUI 专用接口，不属于 Web Dashboard 阶段 2 UI 缺口。
- `/api/chat/uploads`：前端已通过 `fetch(getBaseUrlValue() + "/api/chat/uploads")` 直接使用，脚本初筛误判。

## 验证

- 多次执行 `npm run build`，通过。
- 多次执行 `git diff --check`，通过。
- 执行 `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`，通过。
- 针对后端下载接口执行 `mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DashboardControllerHttpTest test`，通过。

## 剩余风险

- 阶段 2 的接口差异扫描基于源码中的静态路径和人工复核，动态拼接路径已补充抽查；后续阶段如果新增 API，需要重新跑同类扫描。
- 当前工作树仍存在未纳入本阶段提交的 `terminal-ui/package.json` 与 `terminal-ui/package-lock.json` 本地改动，未被本阶段提交带入。
