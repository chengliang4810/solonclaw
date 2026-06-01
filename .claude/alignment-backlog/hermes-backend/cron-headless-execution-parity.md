# cron-headless-execution-parity

## 标题
Align headless cron execution and script isolation

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true

## 分类
- category: hermes-backend-alignment

## 现有补充草案
- 已有同主题草案：`../external-backend/017-headless-cron-execution-path.md`

## Hermes 参考
- Hermes cron jobs can temporarily swap to a per-job profile/HERMES_HOME and restore the process environment after each run, so scheduled jobs can resolve profile-local config, scripts, and credentials without leaking state.
- Hermes separates strict create-time prompt scanning from a looser assembled-prompt scan that includes loaded skill content and script context, which reduces false positives while still blocking injection payloads.
- Hermes supports richer cron delivery resolution, including routing intents like `all` and plugin-defined home-channel env vars, while keeping headless no-agent script execution explicit and isolated.

## 当前项目观察
- The current project already has a cron repository, file-locked scheduler ticks, catch-up windows, source-based parallelization, workdir validation, dangerous-command approvals, and run-history persistence.
- Current cron execution already supports prompt assembly, script pre-runs, wake-gate handling, `no_agent` mode, and inactivity timeouts, so the remaining alignment work is mostly about Hermes-specific routing, isolation, and scanner behavior.
- Delivery validation and prompt scanning are stricter and less flexible than Hermes in a few places, especially around assembled prompts and target resolution.

## 当前缺口
Hermes keeps no-agent script execution as a distinct headless path with explicit script-path validation, wake-gate handling, and prompt/delivery isolation. The current backend already has a similar mode, but the execution contract still differs enough that the job/script boundary and runtime-state cleanup should be reviewed for parity.

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/scheduler/CronJobService.java`
- `src/main/java/com/jimuqu/solon/claw/scheduler/DefaultCronScheduler.java`
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/SubprocessEnvironmentSanitizer.java`

## 验证方式
Run a no-agent script job that exits cleanly, one that emits wakeAgent=false, and one that fails or times out; confirm outputs, delivery behavior, and environment cleanup all match the intended headless contract.

