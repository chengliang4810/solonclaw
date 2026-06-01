# 036-restart-notification-marker

## 标题
重启恢复标记与启动后通知

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: false

## 对标能力
对标实现会把重启请求方路由信息落盘，启动后只向目标会话发送一次恢复通知。

## 当前缺口
当前有 home channel、delivery 和 diagnostics，但缺少重启 marker、请求方元数据与一次性通知状态机。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/gateway/service/GatewayRuntimeRefreshService.java`
- `src/main/java/com/jimuqu/solon/claw/gateway/service/GatewayRuntimeStatusService.java`
- `src/main/java/com/jimuqu/solon/claw/gateway/GatewayDeliveryService.java`

## 验证方式
保存 requester platform/chat/thread/message id；启动后仅通知一次；无 marker 不发送。
