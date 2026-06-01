# 038-skill-ignore-scan-filter

## 标题
技能扫描支持 ignore 文件

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: low
- parallelSafe: true

## 对标能力
对标实现允许技能包用 ignore 文件排除开发和文档产物，但主技能说明文件永不可忽略。

## 当前缺口
当前技能安全扫描规则较完整，但缺少 ignore 文件语义，可能对社区技能中的 benign docs 或 release notes 产生噪音。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/context/DefaultSkillGuardService.java`

## 验证方式
ignore 文件排除 docs 和旧说明文件；尝试忽略主技能说明文件无效；被忽略文件不计入 findings。
