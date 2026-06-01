# 006-skill-security-trust-policy

## 标题
强化技能安全扫描与信任策略

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: false

## Hermes 参考
- `/Users/chengliang/code-repositories/hermes-agent/tools/skills_guard.py`
- `/Users/chengliang/code-repositories/hermes-agent/agent/file_safety.py`

## 当前项目目标文件
- `src/main/java/com/jimuqu/solon/claw/skillhub/service/DefaultSkillGuardService.java`
- `src/main/java/com/jimuqu/solon/claw/skillhub/service/DefaultSkillImportService.java`

## 当前缺口
Hermes skills guard 按 builtin、trusted、community、agent-created 分层，并对注入、外泄、持久化、破坏性模式做更明确的安装决策；当前扫描器已有基础规则，但 trust policy 与结果语义仍可细化。

## 实现范围
将扫描结果与安装策略绑定到来源信任级别，补强 agent-created/community 判定，并补全更贴近 Hermes 的 exfiltration/persistence 规则。

## 验证方式
`mvn -Dskip.web.build=true -Dtest=DefaultSkillGuardServiceTest,SkillImportServiceTest test`
