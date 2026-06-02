# 030-browser-automation-doctor

## 标题
Teach doctor to diagnose browser automation prerequisites

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: medium
- parallelSafe: true
- overlapHint: `030-browser-automation-doctor`

## 对标能力
来源领域：backend runtime/config/doctor alignment

## 当前缺口
The project already has browser runtime/tooling, but no doctor path that tells operators whether browser automation is actually usable. Hermes doctor checks both the Node/agent-browser layer and the Chromium engine so the failure mode is actionable.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/hermes_cli/doctor.py:1318-1387 (Node.js, agent-browser, and Playwright Chromium checks plus install hints)`
- `/Users/chengliang/code-repositories/hermes-agent/tests/hermes_cli/test_doctor.py:105-190 (doctor overrides for runtime-gated and optional tool availability)`
- `/Users/chengliang/code-repositories/hermes-agent/tests/tools/test_browser_supervisor_healthcheck.py:1-168 (browser supervisor healthcheck semantics)`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/tool/runtime/BrowserRuntimeService.java`

## 验证方式
Simulate browser-provider present, command missing, and config-disabled states, then verify doctor reports availability, missing prerequisites, and next steps.
