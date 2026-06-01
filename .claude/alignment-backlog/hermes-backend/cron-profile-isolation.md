# cron-profile-isolation

## 标题
Add per-job cron profile isolation

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: false

## 分类
- category: hermes-backend-alignment

## 现有补充草案
- 已有同主题草案：`../external-backend/016-cron-execution-context-isolation.md`

## Hermes 参考
- Hermes cron jobs can temporarily swap to a per-job profile/HERMES_HOME and restore the process environment after each run, so scheduled jobs can resolve profile-local config, scripts, and credentials without leaking state.
- Hermes separates strict create-time prompt scanning from a looser assembled-prompt scan that includes loaded skill content and script context, which reduces false positives while still blocking injection payloads.
- Hermes supports richer cron delivery resolution, including routing intents like `all` and plugin-defined home-channel env vars, while keeping headless no-agent script execution explicit and isolated.

## 当前项目观察
- The current project already has a cron repository, file-locked scheduler ticks, catch-up windows, source-based parallelization, workdir validation, dangerous-command approvals, and run-history persistence.
- Current cron execution already supports prompt assembly, script pre-runs, wake-gate handling, `no_agent` mode, and inactivity timeouts, so the remaining alignment work is mostly about Hermes-specific routing, isolation, and scanner behavior.
- Delivery validation and prompt scanning are stricter and less flexible than Hermes in a few places, especially around assembled prompts and target resolution.

## 当前缺口
Hermes can run each cron job under an isolated profile by temporarily swapping HERMES_HOME and restoring the environment afterward. The current backend has source/workdir scoping, but no per-job profile field or profile-scoped config/credential resolution for scheduled runs.

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/core/model/CronJobRecord.java`
- `src/main/java/com/jimuqu/solon/claw/scheduler/CronJobService.java`
- `src/main/java/com/jimuqu/solon/claw/scheduler/DefaultCronScheduler.java`
- `src/main/resources/app.yml`

## 验证方式
Create two jobs pinned to different profiles and verify each job resolves profile-local config, skills, and scripts; then confirm environment and runtime home are restored after each tick.

