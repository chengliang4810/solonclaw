# harden-approval-session-and-policy-boundaries

## 标题
收紧审批会话与危险命令边界

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: high
- parallelSafe: false

## 分类
- category: Hermes 后端安全/诊断对齐 backlog 候选

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
Hermes 的 approval 层强调冻结 YOLO 开关、上下文感知 session key 和统一插件钩子；当前项目的危险命令审批已有规则集，但在会话冻结、审批生命周期与扩展钩子上还可进一步对齐。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/DangerousCommandApprovalService.java`
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/SecurityPolicyService.java`
- `src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java`

## 验证方式
审批决策在同一会话内保持稳定，危险命令不会因运行期环境变量或上下文漂移被绕过，并且能在诊断里看到可追踪的审批链路。

