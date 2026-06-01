# cron-routing-token-all

## 标题
Add Hermes-style cron routing tokens and plugin-aware home fallback

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: medium
- parallelSafe: false

## 分类
- category: hermes-backend-alignment

## Hermes 参考
- Hermes cron jobs can temporarily swap to a per-job profile/HERMES_HOME and restore the process environment after each run, so scheduled jobs can resolve profile-local config, scripts, and credentials without leaking state.
- Hermes separates strict create-time prompt scanning from a looser assembled-prompt scan that includes loaded skill content and script context, which reduces false positives while still blocking injection payloads.
- Hermes supports richer cron delivery resolution, including routing intents like `all` and plugin-defined home-channel env vars, while keeping headless no-agent script execution explicit and isolated.

## 当前项目观察
- The current project already has a cron repository, file-locked scheduler ticks, catch-up windows, source-based parallelization, workdir validation, dangerous-command approvals, and run-history persistence.
- Current cron execution already supports prompt assembly, script pre-runs, wake-gate handling, `no_agent` mode, and inactivity timeouts, so the remaining alignment work is mostly about Hermes-specific routing, isolation, and scanner behavior.
- Delivery validation and prompt scanning are stricter and less flexible than Hermes in a few places, especially around assembled prompts and target resolution.

## 当前缺口
Hermes can expand routing tokens such as `all` to every configured home channel and can fall back to plugin-defined cron delivery env vars. The current backend validates only fixed built-in platforms and explicit targets, so it cannot fan out to all configured destinations or honor plugin-provided home targets.

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/scheduler/CronJobService.java`
- `src/main/java/com/jimuqu/solon/claw/scheduler/DefaultCronScheduler.java`
- `src/main/java/com/jimuqu/solon/claw/core/enums/PlatformType.java`
- `src/main/resources/app.yml`

## 验证方式
Create a job with `deliver=all` and one with a plugin-backed platform target, then verify delivery fans out once per configured home channel, deduplicates repeated targets, and skips disabled channels cleanly.

