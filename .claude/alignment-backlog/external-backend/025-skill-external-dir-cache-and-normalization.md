# 025-skill-external-dir-cache-and-normalization

## 标题
外部技能目录缓存与归一去重

## 状态
- status: selected

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true

## 对标能力
对标实现会对 external skills dirs 做路径展开、归一去重，并基于目录变化做轻量缓存。

## 当前缺口
当前外部技能目录每次扫描都会重新遍历，status 也未显式暴露 configured/local/duplicate/included 诊断信息。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/context/SkillDirectoryResolver.java`
- `src/main/java/com/jimuqu/solon/claw/context/ExternalSkillDirectoryService.java`
- `src/test/java/com/jimuqu/solon/claw/ExternalSkillDirectoryServiceTest.java`

## 验证方式
`mvn -Dskip.web.build=true -Dtest=ExternalSkillDirectoryServiceTest test`
