# 039-skill-usage-atomic-sidecar

## 标题
技能 usage sidecar 原子写与跨进程保护

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: medium
- parallelSafe: false

## 对标能力
对标实现对技能 view/use/patch 记录使用文件锁、临时文件和 replace 写入，并区分可观测技能与可管理技能。

## 当前缺口
当前 usage sidecar 已存在，但跨进程写入、corrupt 降级和 bundled/hub/agent provenance 边界还不完整。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/context/SkillUsageTracker.java`
- `src/main/java/com/jimuqu/solon/claw/context/LocalSkillService.java`
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/SkillTools.java`

## 验证方式
corrupted sidecar 不阻断 view；并发 bump 不丢计数；只管理 eligible 技能。
