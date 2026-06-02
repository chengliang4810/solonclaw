# 039-skill-usage-atomic-sidecar

## 标题
Skill usage sidecar atomic writes and cross-process locking

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: false
- overlapHint: `039-skill-usage-atomic-sidecar`

## 对标能力
来源领域：memory-skills-backend-alignment

## 当前缺口
Hermes treats usage telemetry as a best-effort but durable sidecar with process locking and atomic replacement; the current tracker reads/writes JSON directly, so concurrent bumps or partial writes can corrupt the record and skew curator decisions.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/tools/skill_usage.py:67-100 (_usage_file_lock with flock/msvcrt)`
- `/Users/chengliang/code-repositories/hermes-agent/tools/skill_usage.py:153-157 (tempfile + os.replace atomic save)`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/context/SkillUsageTracker.java:18-160`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/context/LocalSkillService.java:88-220`

## 验证方式
Concurrent bumps from multiple processes preserve counts; a truncated/corrupt sidecar degrades to empty state instead of breaking view/manage calls; writes land via temp+replace rather than in-place overwrite.
