# split-stream-diagnostics-into-per-attempt-structured-telemetry

## 标题
细化流式中断诊断为按尝试记录

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: true

## 分类
- category: Hermes 后端安全/诊断对齐 backlog 候选

## 现有补充草案
- 已有同主题草案：`../external-backend/019-stream-diagnostics-health-output.md`

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
Hermes 的 `stream_diag` 会按 attempt 采集 headers、chunks、bytes、HTTP status、异常链和 TTFB；当前项目只有聚合式 `stream_health`，缺少同等粒度的流式退避遥测。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java`
- `src/main/java/com/jimuqu/solon/claw/agent`
- `src/main/java/com/jimuqu/solon/claw/core/service/ToolExecutor.java`

## 验证方式
对一次流式请求的失败重试能产出结构化记录，能在日志和 dashboard 中同时看到尝试号、耗时、上游头和错误链。

