# 全面修复阶段 1.1 原子级功能缺陷报告

生成时间：2026-06-29

## 对应外部对标能力点

- 国内渠道接入：飞书、钉钉渠道的群聊响应策略、会话白名单与 Dashboard 配置一致性。
- Dashboard-first setup：通过 Dashboard 扫码和表单保存渠道凭据后，运行时必须读取并使用同一份配置。
- 渠道网关安全边界：群聊是否必须提及、哪些会话可自由响应，属于入站消息准入策略，不能出现“界面显示已保存但运行时不生效”。

## 审计范围

本报告只记录当前 `work/full-repair-optimization` HEAD 可以从源码直接证明的功能 bug。已在历史报告中修复的 BUG-001 至 BUG-007 不重复列入；未能从当前源码闭环证明的问题只保留给后续审计，不写入本报告。

## BUG-008：飞书/钉钉群聊响应策略在 Dashboard 保存后不生效

状态：已修复并复核（2026-07-01）

影响范围：

- Dashboard 渠道设置页中的飞书、钉钉“需要提及”和“可自由响应会话”配置。
- 飞书、钉钉群聊入站消息准入逻辑。
- 用户通过 UI 调整渠道响应范围后的实际运行效果。

2026-07-01 复核事实：

- Dashboard 飞书、钉钉设置页现在保存当前命名字段 `requireMention` 与 `freeResponseChats`。
- 后端 `AppConfig.ChannelConfig` 已建模 `requireMention` 与 `freeResponseChats`，默认仍保持群聊需提及、自由响应会话为空。
- `AppConfigLoader.applyChannelConfig` 已读取 `solonclaw.channels.<channel>.requireMention` 与 `solonclaw.channels.<channel>.freeResponseChats`。
- `DashboardConfigService` 与 `DashboardRuntimeConfigService` 已暴露飞书、钉钉的 `requireMention` / `freeResponseChats` schema 与运行时配置键。
- 飞书与钉钉入站判断已使用 `config.isRequireMention()` 和 `config.getFreeResponseChats()`，不再固定忽略 Dashboard 保存值。

原可复现现象：

1. 打开 Dashboard 的飞书或钉钉渠道设置。
2. 修改“需要提及”开关或“可自由响应会话”输入框。
3. UI 调用保存接口并提示保存成功。
4. 重启或刷新运行时配置后，群聊入站逻辑仍按 `groupPolicy`、`allowedChats` 和适配器固定逻辑执行，刚才保存的 `require_mention` / `free_response_chats` 不参与判断。

当前源码证据：

- `web/src/components/solonclaw/settings/PlatformSettings.vue:221`：飞书保存 `requireMention`。
- `web/src/components/solonclaw/settings/PlatformSettings.vue:222`：飞书保存 `freeResponseChats`。
- `web/src/components/solonclaw/settings/PlatformSettings.vue:229`：钉钉保存 `requireMention`。
- `web/src/components/solonclaw/settings/PlatformSettings.vue:230`：钉钉保存 `freeResponseChats`。
- `src/main/java/com/jimuqu/solon/claw/config/AppConfig.java:1638`：后端渠道配置建模 `requireMention`。
- `src/main/java/com/jimuqu/solon/claw/config/AppConfig.java:1641`：后端渠道配置建模 `freeResponseChats`。
- `src/main/java/com/jimuqu/solon/claw/config/AppConfigLoader.java:1751`：加载 `requireMention`。
- `src/main/java/com/jimuqu/solon/claw/config/AppConfigLoader.java:1758`：加载 `freeResponseChats`。
- `src/main/java/com/jimuqu/solon/claw/gateway/platform/feishu/FeishuChannelAdapter.java:942`：飞书群聊入站读取 `requireMention` 与 `freeResponseChats`。
- `src/main/java/com/jimuqu/solon/claw/gateway/platform/dingtalk/DingTalkChannelAdapter.java:1299`：钉钉群聊入站读取 `requireMention` 与 `freeResponseChats`。
- `src/test/java/com/jimuqu/solon/claw/ChannelConfigPolicyLoadTest.java:28`：覆盖飞书、钉钉策略字段加载。
- `src/test/java/com/jimuqu/solon/claw/DashboardControllerHttpTest.java:497`：覆盖 Dashboard schema 暴露飞书、钉钉策略字段。
- `src/test/java/com/jimuqu/solon/claw/FeishuWebsocketInboundTest.java:120`：覆盖飞书自由响应会话免提及入站。
- `src/test/java/com/jimuqu/solon/claw/FeishuWebsocketInboundTest.java:145`：覆盖飞书关闭强制提及后的群聊入站。
- `src/test/java/com/jimuqu/solon/claw/DingTalkInboundDispatchTest.java:106`：覆盖钉钉自由响应会话免提及入站。
- `src/test/java/com/jimuqu/solon/claw/DingTalkInboundDispatchTest.java:122`：覆盖钉钉关闭强制提及后的群聊入站。

复核命令：

- `mvn '-Dskip.web.build=true' '-Dtest=ChannelConfigPolicyLoadTest,DomesticChannelEnhancementTest,FeishuWebsocketInboundTest,DingTalkInboundDispatchTest' test`

结果：51 个 focused 测试通过，当前报告不再作为待修复 bug 跟踪。

## 不写入本轮 bug 的候选

- 钉钉 QR setup 的 `robotCode` 写入候选：当前源码确实存在 `clientId` 写入 `robotCode` 的路径，但还需要结合钉钉扫码注册协议返回字段确认是否属于真实运行缺陷，本轮不列入正式 bug。
- TUI RPC 与 QR 平台范围候选：当前未形成比已有报告更强的源码闭环证据，本轮不列入阶段 1.1 正式 bug。
- 旧 BUG-001 至 BUG-007：已有报告和修复提交，本轮只追加当前 HEAD 新发现。

## 后续处理顺序建议

1. BUG-008 已复核闭环；后续只在新增渠道策略字段时继续保持 UI 保存、运行时配置、AppConfig 加载和适配器判断四处同步。
2. 阶段 2.3 继续复核钉钉 QR setup 的 `robotCode` 来源，确认协议字段后再决定是否修复。
3. 新增修复仍必须分别覆盖“UI 保存后的配置文件内容”和“适配器入站判断读取的字段”，不能只验证接口返回成功。
