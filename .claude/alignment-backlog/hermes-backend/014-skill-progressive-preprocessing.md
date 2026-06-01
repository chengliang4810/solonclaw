# 014-skill-progressive-preprocessing

## 标题
补齐技能渐进加载前置预处理

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true

## Hermes 参考
- `/Users/chengliang/code-repositories/hermes-agent/agent/skill_preprocessing.py`
- `/Users/chengliang/code-repositories/hermes-agent/agent/skill_commands.py`

## 当前项目目标文件
- `src/main/java/com/jimuqu/solon/claw/context/LocalSkillService.java`
- `src/main/java/com/jimuqu/solon/claw/context/ExternalSkillDirectoryService.java`
- `src/main/java/com/jimuqu/solon/claw/context/SkillDirectoryResolver.java`

## 当前缺口
Hermes 在 skill 装载前会做模板变量替换、可选 inline shell 预处理，并严格按受信任根目录解析技能；当前技能读取更偏 raw markdown，渐进加载与预处理不够前置。

## 实现范围
在后端技能装配链增加可控预处理阶段，并继续收紧本地/外部技能根目录解析与去重规则，不扩展到前端或市场迁移。

## 验证方式
`mvn -Dskip.web.build=true -Dtest=ExternalSkillDirectoryServiceTest,MemoryAndSkillsTest test`
