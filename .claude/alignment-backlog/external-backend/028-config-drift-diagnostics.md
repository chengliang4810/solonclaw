# 028-config-drift-diagnostics

## 标题
增加配置漂移与未知字段诊断

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: low
- parallelSafe: true

## 对标能力
对标实现会诊断未知键、过时键、原始配置与运行态解析结果之间的漂移。

## 当前缺口
当前项目已有 runtime config 写入、schema 校验与 dashboard config API，但缺少后端层面的配置漂移报告与未知字段摘要。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/config/RuntimeConfigResolver.java`
- `src/main/java/com/jimuqu/solon/claw/web/DashboardConfigService.java`

## 验证方式
构造未知键、覆盖来源与运行态差异，断言诊断摘要稳定且敏感值脱敏。
