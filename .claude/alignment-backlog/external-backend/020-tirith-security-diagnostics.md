# 020-tirith-security-diagnostics

## 标题
对齐 Tirith 安全诊断与外部对标 audit surface

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true

## 外部对标参考
- `对标实现路径：tools/tirith_security.py`
- `对标实现路径：tools/path_security.py`
- `对标实现路径：cli/security_audit.py`
- `对标实现路径：tests/cli/test_security_audit.py`

## 当前项目目标文件
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/TirithSecurityService.java`
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/SecurityPolicyService.java`
- `src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java`
- `src/test/java/com/jimuqu/solon/claw/tool/runtime/TirithSecurityServiceTest.java`

## 当前缺口
外部对标将安全扫描包装成 dedicated audit surface，并规范失败处理与 diagnostics；当前后端已有 Tirith scanning，但 operational summary 与 failure-mode reporting 可以更显式。

## 实现范围
细化 Tirith-backed security diagnostics，报告 scanner state、fail-open/closed behavior 与 redacted summary fields，并保持可单测验证。

## 验证方式
`mvn -Dskip.web.build=true -Dtest=TirithSecurityServiceTest,SecurityPolicyServiceTest test`
