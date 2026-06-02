# 031-doctor-issue-summary

## 标题
Summarize doctor issues and next actions

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true
- overlapHint: `031-doctor-issue-summary`

## 对标能力
来源领域：backend runtime/config/doctor alignment

## 当前缺口
Current doctor output is a collection of sections, but Hermes also produces an explicit issue summary with severity and suggested next actions so operators can quickly see what actually blocks the system. That aggregation is missing from the backend doctor surface here.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/hermes_cli/doctor.py:201-206 (_fail_and_issue appends fix instructions to issues)`
- `/Users/chengliang/code-repositories/hermes-agent/hermes_cli/doctor.py:261-306 (sectioned checks feed a consolidated issues list)`
- `/Users/chengliang/code-repositories/hermes-agent/tests/hermes_cli/test_doctor.py:193-239 (doctor issues and fix guidance are asserted)`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/web/DashboardGatewayDoctorService.java`

## 验证方式
Construct mixed-failure scenarios and assert the response includes ordered issues, highest severity, and deterministic next actions.
