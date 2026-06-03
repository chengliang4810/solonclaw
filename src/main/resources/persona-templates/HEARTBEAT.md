# HEARTBEAT.md 模板

`HEARTBEAT.md` 位于 agent 工作区中。当你希望运行时跳过 heartbeat 模型调用时，保持这个文件为空，或只保留 Markdown 注释和标题。

默认运行时模板是：

```markdown
# 保持此文件为空（或仅保留注释）即可跳过心跳 API 调用。

# 当你希望 agent 定期检查某些事情时，再把任务写在下面。
```

只有当你希望 agent 定期检查某些事情时，才在注释下面添加简短任务。保持 heartbeat 指令精简，因为它们会在周期性唤醒时被读取。
