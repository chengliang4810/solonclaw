# 钉钉渠道

SolonClaw 通过钉钉 Stream Mode 接收入站消息，不需要公网回调地址。普通回复优先使用入站消息携带的 session webhook；主动消息、附件、AI 卡片和处理状态表情使用钉钉 OpenAPI。

## 创建应用

1. 登录钉钉开发者后台，创建企业内部应用。
2. 添加机器人能力，将消息接收模式设置为 Stream Mode。
3. 记录应用的 Client ID 和 Client Secret。
4. 将机器人添加到需要使用的单聊或群聊。

## 配置

可以在 Dashboard 的渠道设置中扫码配置，也可以写入运行配置：

```yaml
# 是否启用钉钉渠道。
solonclaw.channels.dingtalk.enabled: true
# 钉钉应用 Client ID。
solonclaw.channels.dingtalk.clientId: your-client-id
# 钉钉应用 Client Secret。
solonclaw.channels.dingtalk.clientSecret: your-client-secret
# 可选机器人编码；留空时使用 Client ID。
solonclaw.channels.dingtalk.robotCode:
# 允许使用机器人的 staffId 或 unionId；空列表表示不限制，* 表示全部允许。
solonclaw.channels.dingtalk.allowedUsers:
  - staff-admin
# 是否要求群消息必须 @ 机器人；默认 false。
solonclaw.channels.dingtalk.requireMention: false
# 无需 @ 即可响应的群会话 ID。
solonclaw.channels.dingtalk.freeResponseChats:
  - cid-example
# 可替代 @ 的正则唤醒词；非法表达式会被忽略。
solonclaw.channels.dingtalk.mentionPatterns:
  - ^小马
# 仅允许机器人响应的群会话 ID；空列表表示不限制。
solonclaw.channels.dingtalk.allowedChats:
```

`allowedUsers` 是私聊、群聊、控制命令和卡片动作共用的硬门禁。配置后，发送者的 staffId 或 unionId 至少一个必须命中。

## AI 卡片和进度

```yaml
# 工具进度展示模式：off、new 或 all。
solonclaw.channels.dingtalk.toolProgress: all
# 长任务进度卡模板 ID。
solonclaw.channels.dingtalk.progressCardTemplateId: your-progress-template-id
# 危险命令审批卡模板 ID；留空时回退为文本审批。
solonclaw.channels.dingtalk.approvalCardTemplateId: your-approval-template-id
# 是否允许 AI Card 增量更新。
solonclaw.channels.dingtalk.aiCardStreaming.enabled: true
```

进度卡模板使用 `title`、`status`、`summary`、`detail`、`updatedAt` 变量。审批卡模板使用 `title`、`command`、`description`、`allowAlways`、`approveOnce`、`approveSession`、`approveAlways`、`deny` 变量；按钮交互数据依次绑定四个动作变量。

## 支持能力

- 单聊和群聊文本、Markdown、图片、语音、视频、文件与富文本入站。
- 本地附件原生上传和会话文件发送。
- AI 卡片创建、增量更新与卡片动作回调。
- `Thinking` 和 `Done` 处理状态表情。
- session webhook 官方域名校验、到期保护和 20,000 字符限制。
- 重复消息抑制、用户允许名单、群会话白名单、免提及会话和正则唤醒词。

## 排查

- 无法连接：检查渠道是否启用，以及 Client ID、Client Secret 是否有效。
- 群内不响应：检查 `allowedUsers`、`allowedChats`、`requireMention` 和机器人是否已加入该群。
- AI 卡片未显示：检查模板 ID、模板变量和应用的卡片权限；未配置模板时仍可使用普通文本。
- 主动消息失败：先在目标会话中向机器人发送消息，使系统记录 userId 和会话上下文。
