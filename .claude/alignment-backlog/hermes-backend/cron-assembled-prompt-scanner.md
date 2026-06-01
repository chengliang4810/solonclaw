# cron-assembled-prompt-scanner

## 标题
Split strict and assembled cron prompt scanners

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: false

## 分类
- category: hermes-backend-alignment

## 现有补充草案
- 已有同主题草案：`../external-backend/008-cron-runtime-assembled-prompt-safety.md`

## Hermes 参考
- Hermes cron jobs can temporarily swap to a per-job profile/HERMES_HOME and restore the process environment after each run, so scheduled jobs can resolve profile-local config, scripts, and credentials without leaking state.
- Hermes separates strict create-time prompt scanning from a looser assembled-prompt scan that includes loaded skill content and script context, which reduces false positives while still blocking injection payloads.
- Hermes supports richer cron delivery resolution, including routing intents like `all` and plugin-defined home-channel env vars, while keeping headless no-agent script execution explicit and isolated.

## 当前项目观察
- The current project already has a cron repository, file-locked scheduler ticks, catch-up windows, source-based parallelization, workdir validation, dangerous-command approvals, and run-history persistence.
- Current cron execution already supports prompt assembly, script pre-runs, wake-gate handling, `no_agent` mode, and inactivity timeouts, so the remaining alignment work is mostly about Hermes-specific routing, isolation, and scanner behavior.
- Delivery validation and prompt scanning are stricter and less flexible than Hermes in a few places, especially around assembled prompts and target resolution.

## 当前缺口
Hermes scans the raw user prompt strictly, then applies a separate assembled-prompt scan after skills and script output are injected. The current backend already scans prompts, but it does not clearly preserve Hermes' two-tier behavior for benign skill content versus hostile assembled content.

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/scheduler/CronJobService.java`
- `src/main/java/com/jimuqu/solon/claw/scheduler/DefaultCronScheduler.java`

## 验证方式
Run a cron job with benign security/runbook prose in a skill and confirm it is allowed, then run a job with an obvious instruction-injection payload and confirm assembled-prompt blocking still fires with a clear reason.

