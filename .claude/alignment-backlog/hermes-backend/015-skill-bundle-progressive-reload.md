# 015-skill-bundle-progressive-reload

## 标题
对齐技能 bundle 渐进重载策略

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true

## Hermes 参考
- `/Users/chengliang/code-repositories/hermes-agent/agent/skill_bundles.py`
- `/Users/chengliang/code-repositories/hermes-agent/agent/skill_commands.py`

## 当前项目目标文件
- `src/main/java/com/jimuqu/solon/claw/skillhub/support/SkillBundleLoader.java`
- `src/main/java/com/jimuqu/solon/claw/skillhub/service/DefaultSkillHubService.java`

## 当前缺口
Hermes skill bundles 依赖磁盘 mtime 刷新和按命令名延迟展开；当前 bundle loader 已有缓存，但失效、重载和重复 slug 处理还可更接近 Hermes 渐进加载模型。

## 实现范围
收紧 bundle 缓存失效与重复项处理，让 bundle 扫描/重载更稳定，同时保持纯后端实现。

## 验证方式
`mvn -Dskip.web.build=true -Dtest=SkillHubFallbackTest,SkillsHubCommandTest test`
