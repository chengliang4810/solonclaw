# 009-dashboard-doctor-output

## 标题
对齐 Dashboard doctor 后端输出

## 优先级 / 风险
- priority: high
- risk: low
- parallelSafe: true

## 外部对标参考
- `对标实现路径：cli/doctor.py`
- `对标实现路径：tests/cli/test_doctor.py`
- `对标实现路径：tests/cli/test_doctor_dedicated_provider_skip.py`

## 当前项目目标文件
- `src/main/java/com/jimuqu/solon/claw/web/DashboardGatewayDoctorService.java`
- `src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsController.java`
- `src/test/java/com/jimuqu/solon/claw/DashboardDiagnosticOutputTest.java`

## 当前缺口
外部对标 doctor summary 包含 runtime_home、model checks、last_shutdown 与 provider-specific skip logic；当前 backend doctor 输出更偏 channel status，尚未暴露同等结构化健康字段与语义。

## 实现范围
补齐 dashboard doctor 聚合，让后端输出 外部对标风格 structured health payload，并以现有 diagnostics/service 层增加小型 output-shape 测试。

## 验证方式
`mvn -q -Dtest=DashboardDiagnosticOutputTest test`
