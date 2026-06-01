# 032-approval-timeout-denied-outcome

## 标题
结构化审批超时与拒绝结果

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: true

## 对标能力
对标实现把 timeout 和 denied 都视为未同意，并返回结构化 outcome，防止模型把超时误解为可绕过失败。

## 当前缺口
当前危险命令审批已有审计和阻断能力，但需要确认超时、拒绝和提示文本在所有入口都足够一致。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/DangerousCommandApprovalService.java`
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/ApprovalAuditObserver.java`

## 验证方式
扩展审批测试，断言 timeout/denied 都返回 `approved=false` 且审计 outcome 可区分。
