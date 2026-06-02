# 016-cron-execution-context-isolation

## 标题
隔离 Cron 执行上下文并补齐 headless 执行路径

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: true
- overlapHint: `016-cron-execution-context-isolation`

## 对标能力
来源领域：backend parity analysis for cron / approval / security

## 当前缺口
Hermes makes cron execution explicitly split between a headless/no_agent path and the full agent loop, and it treats profile/workdir jobs as context-mutating work that must not share mutable runtime state with parallel jobs. Current project already has cron execution, no_agent support, and some isolation hooks, but this is still the best place to tighten parity around per-run context bleed and a dedicated headless execution contract.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/cron/scheduler.py:_job_profile_context`
- `/Users/chengliang/code-repositories/hermes-agent/cron/scheduler.py:_run_job_impl:no_agent short-circuit`
- `/Users/chengliang/code-repositories/hermes-agent/cron/scheduler.py:tick() sequential_jobs/parallel_jobs split`
- `/Users/chengliang/code-repositories/hermes-agent/tests/cron/test_codex_execution_paths.py`
- `/Users/chengliang/code-repositories/hermes-agent/tests/cron/test_cron_context_from.py`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/scheduler/DefaultCronScheduler.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/scheduler/CronJobService.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/core/model/AgentRunContext.java`

## 验证方式
Add regression tests proving headless cron jobs bypass agent construction, concurrent cron jobs do not leak run/session context, and per-job profile/workdir mutations remain isolated under parallel ticks.
