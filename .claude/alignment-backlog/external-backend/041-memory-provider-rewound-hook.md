# 041-memory-provider-rewound-hook

## 标题
MemoryProvider session switch 补 rewound 语义

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: medium
- parallelSafe: false

## 对标能力
对标实现会在同一 session transcript 被回退时向 memory provider 发送 rewound 语义，避免外部缓存继续引用错误轮次。

## 当前缺口
当前 `MemoryProvider` 主要覆盖 prompt block、prefetch、syncTurn，缺少会话不变但 transcript 回退的生命周期通知。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/context/MemoryProvider.java`
- `src/main/java/com/jimuqu/solon/claw/context/MemoryManager.java`
- `src/main/java/com/jimuqu/solon/claw/context/DefaultMemoryManager.java`

## 验证方式
普通 resume/branch 不污染 rewound metadata；undo 时 provider 收到 `rewound=true`；老 provider 兼容运行。
