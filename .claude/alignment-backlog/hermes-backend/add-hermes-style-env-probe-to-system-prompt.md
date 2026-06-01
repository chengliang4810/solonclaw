# add-hermes-style-env-probe-to-system-prompt

## 标题
补齐 Hermes 风格的环境探针注入

## 状态
- status: selected

## 优先级 / 风险
- priority: high
- risk: low
- parallelSafe: true

## 分类
- category: Hermes 后端安全/诊断对齐 backlog 候选

## 现有补充草案
- 已有同主题草案：`../external-backend/018-env-probe-diagnostics.md`

## Hermes 参考
- Hermes 将环境探测拆成独立的 `tools/env_probe.py`，以缓存的单行信号注入系统提示，并对远程终端后端显式跳过。
- Hermes 有独立的 `agent/stream_diag.py`，按重试尝试收集 chunk/bytes/headers/HTTP status/异常链，并同时写入日志与用户可见状态。
- Hermes 的 `hermes_cli/doctor.py` 会聚合 provider、版本、环境与安全告警，还把 OAuth 兜底、Termux/平台差异和可修复建议编码进诊断输出。
- Hermes 的 `tools/approval.py` 把高风险命令审批做成单一真源，强调会话级状态、冻结的 YOLO 开关、插件生命周期钩子和 gateway/CLI 共用审批路径。

## 当前项目观察
- 当前项目已有较强的后端安全基线：`SecurityPolicyService` 以路径/凭据/设备/主机级规则做静态拦截，`DangerousCommandApprovalService` 负责危险命令判定与审批。
- 当前项目已有 Dashboard 侧的统一诊断面板：`DashboardDiagnosticsService` 聚合 runtime、providers、channels、stream_health、tools、mcp、security、runs 等后端视图。
- 当前项目已有网关 doctor 聚合：`DashboardGatewayDoctorService` 输出平台连通性、模型健康检查与最后一次 shutdown forensic 记录。
- 当前项目已有 `/health` 基础健康检查与安全审计接口（`HealthController`、`DashboardDiagnosticsController.securityAudit`），但缺少 Hermes 那种更细粒度的本地环境探针与流式退避诊断模块。

## 当前缺口
Hermes 有独立、缓存化的环境探针，用于在系统提示里提前暴露 Python/pip/PEP 668/远程后端差异；当前项目主要是通用 health/diagnostics，没有同等级别的本地工具链探针。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/bootstrap/HealthController.java`
- `src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java`
- `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`

## 验证方式
新增一个可缓存、只输出单行的本地环境探针，并在健康/诊断输出中验证远程后端被跳过、探针结果可被前端/系统提示消费。

