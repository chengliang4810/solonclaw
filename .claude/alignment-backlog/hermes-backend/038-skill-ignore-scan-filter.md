# 038-skill-ignore-scan-filter

## 标题
Skill scan ignore-file filtering

## 状态
- status: selected

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true
- overlapHint: `038-skill-ignore-scan-filter`

## 对标能力
来源领域：memory-skills-backend-alignment

## 当前缺口
Hermes lets skill packs suppress benign docs/release notes through ignore semantics while keeping the canonical skill manifest visible; the current scanner still treats the tree as raw content, so community skills can produce avoidable noise during validation and discovery.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/agent/skill_utils.py:27-63 (EXCLUDED_SKILL_DIRS and path pruning)`
- `/Users/chengliang/code-repositories/hermes-agent/agent/skill_utils.py:423-436 (index scan walks pruned skill files only)`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/skillhub/service/DefaultSkillGuardService.java:20-260`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/context/LocalSkillService.java:128-220`

## 验证方式
Ignored docs and ancillary files are skipped from findings; the main skill manifest cannot be ignored; scan results stay stable across equivalent tree layouts.
