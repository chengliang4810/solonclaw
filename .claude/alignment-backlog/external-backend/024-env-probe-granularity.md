# 024-env-probe-granularity

## 标题
细化子进程环境探针分级输出

## 状态
- status: selected

## 优先级 / 风险
- priority: high
- risk: low
- parallelSafe: true

## 对标能力
对标实现的环境探针会区分策略决策、可见性、来源、实际导出名称，并保证敏感值不外泄。

## 当前缺口
当前 probe 已能说明 allow/block/provider-blocked，但缺少 `visibility/source/effectiveName` 等更细粒度诊断字段。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/SubprocessEnvironmentSanitizer.java`
- `src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java`
- `src/test/java/com/jimuqu/solon/claw/tool/runtime/SubprocessEnvironmentSanitizerTest.java`

## 验证方式
`mvn -Dskip.web.build=true -Dtest=SubprocessEnvironmentSanitizerTest test`
