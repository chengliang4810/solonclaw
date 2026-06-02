# 040-skill-hub-path-guard

## 标题
Skill hub install/uninstall path guard

## 状态
- status: done
- completedCommit: 6c8f299f
- testResult: `mvn -Dskip.web.build=true "-Dtest=SkillBundlePathSupportTest,SkillImportServiceTest,ModelMetadataServiceTest,RuntimeRefreshBehaviorTest,DashboardStatusServiceTest,DashboardDiagnosticOutputTest,McpRuntimeServiceTest" test` 通过（141 tests, 0 failures, 0 errors）

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: true
- overlapHint: `040-skill-hub-path-guard`

## 对标能力
来源领域：memory-skills-backend-alignment

## 当前缺口
Hermes pairs path normalization with explicit safety guards around hub-managed state and rollback staging; the current hub path helper only normalizes strings, so install/uninstall flows still need stronger 'stay under skills root' and failure-safe state handling to prevent escape or drift.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/agent/file_safety.py:226-241 (blocks .hub cache reads to prevent injection carriers)`
- `/Users/chengliang/code-repositories/hermes-agent/agent/curator_backup.py:529-618 (safe rollback staging and unsafe path rejection)`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/skillhub/support/SkillBundlePathSupport.java:8-73`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/skillhub/support/SkillHubStateStore.java:17-162`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/skillhub/service/DefaultSkillGuardService.java:20-260`

## 验证方式
Reject empty, '.', './x', '../x', absolute, or symlink-escaped hub targets; uninstall failures do not advance lock/manifest state; all accepted paths resolve under the intended skills root.
