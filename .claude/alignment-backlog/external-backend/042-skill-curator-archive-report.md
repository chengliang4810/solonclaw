# 042-skill-curator-archive-report

## 标题
Curator 自动归档报告链

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: high
- parallelSafe: false

## 对标能力
对标实现会把过期或合并后的技能移动到可恢复 archive，并输出 structured consolidation/pruned report。

## 当前缺口
当前 curator 主要标记 agent-created 技能并写报告，尚未实际归档、恢复或重写计划任务中的技能引用。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/context/SkillCuratorService.java`
- `src/main/java/com/jimuqu/solon/claw/context/SkillUsageTracker.java`
- `src/main/java/com/jimuqu/solon/claw/scheduler/*`

## 验证方式
pinned 不归档；hub 永不归档；agent-created 超期移动到 archive 可恢复；报告区分 consolidated 与 pruned。
