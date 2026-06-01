# 035-gateway-shutdown-drain

## 标题
停机先 drain 再 interrupt 并清理工具进程

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: high
- parallelSafe: false

## 对标能力
对标实现停机时先等待运行中 agent 收尾，超时后才中断，并清理工具子进程和运行时资源。

## 当前缺口
当前已有 run control，但服务停止边界仍需要覆盖 drain、interrupt、pending approval 和工具进程清理顺序。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/gateway/service/*`
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/ProcessRegistry.java`
- `src/main/java/com/jimuqu/solon/claw/engine/AgentRunControlService.java`

## 验证方式
正常 drain 不 interrupt；超时 interrupt；工具子进程清理发生在 adapter disconnect 前。
