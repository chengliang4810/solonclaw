# 028-config-drift-diagnostics

## 标题
Add config drift and unknown-field diagnostics

## 状态
- status: done
- completedCommit: `0b8950b1`

## 优先级 / 风险
- priority: high
- risk: low
- parallelSafe: true
- overlapHint: `028-config-drift-diagnostics`

## 对标能力
来源领域：backend runtime/config/doctor alignment

## 当前缺口
Current config loading/writing validates types, but it does not report unknown keys or drift between raw config and effective runtime resolution. Hermes has regression coverage specifically for config drift so stale or dead keys are visible instead of silently ignored.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/hermes_cli/doctor.py:830 (doctor checks config version and stale keys)`
- `/Users/chengliang/code-repositories/hermes-agent/tests/hermes_cli/test_config_drift.py:1-37 (dead-config regression guard)`
- `/Users/chengliang/code-repositories/hermes-agent/hermes_cli/web_server.py:5129 (unknown keys return 400 in config surface)`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/config/RuntimeConfigResolver.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/web/DashboardConfigService.java`

## 验证方式
Feed configs with unknown and legacy keys, then verify a diagnostic summary lists them without leaking secrets and distinguishes raw vs effective values.
