# 全面修复阶段 1.1 原子级功能缺陷报告

生成时间：2026-06-29

## 对应外部对标能力点

- 国内渠道接入：飞书、钉钉渠道的群聊响应策略、会话白名单与 Dashboard 配置一致性。
- Dashboard-first setup：通过 Dashboard 扫码和表单保存渠道凭据后，运行时必须读取并使用同一份配置。
- 渠道网关安全边界：群聊是否必须提及、哪些会话可自由响应，属于入站消息准入策略，不能出现“界面显示已保存但运行时不生效”。

## 审计范围

本报告只记录当前 `work/full-repair-optimization` HEAD 可以从源码直接证明的功能 bug。已在历史报告中修复的 BUG-001 至 BUG-007 不重复列入；未能从当前源码闭环证明的问题只保留给后续审计，不写入本报告。

## BUG-008：飞书/钉钉群聊响应策略在 Dashboard 保存后不生效

状态：待修复

影响范围：

- Dashboard 渠道设置页中的飞书、钉钉“需要提及”和“可自由响应会话”配置。
- 飞书、钉钉群聊入站消息准入逻辑。
- 用户通过 UI 调整渠道响应范围后的实际运行效果。

当前事实：

- `web/src/components/solonclaw/settings/PlatformSettings.vue` 在飞书和钉钉设置中保存 `require_mention` 与 `free_response_chats`。
- `web/src/api/solonclaw/config.ts` 的 `updateConfigSection('feishu' | 'dingtalk', values)` 会把这些字段直接合并到 `channels.feishu` 或 `channels.dingtalk`。
- 后端 `AppConfig.ChannelConfig` 建模的是 `dmPolicy`、`groupPolicy`、`allowedUsers`、`groupAllowedUsers`、`allowedChats` 等字段，没有 `require_mention` 或 `free_response_chats`。
- `AppConfigLoader.applyChannelConfig` 只读取 `solonclaw.channels.<channel>.dmPolicy`、`groupPolicy`、`groupAllowedUsers`、`allowedChats` 等当前命名字段，不会读取 `require_mention` 或 `free_response_chats`。
- 钉钉运行时 `DingTalkChannelAdapter.allowInbound` 固定要求群聊消息 `inAtList=true`，并读取 `groupPolicy`、`groupAllowedUsers`、`allowedChats` 控制准入，不读取 Dashboard 保存的 `require_mention` 或 `free_response_chats`。

可复现现象：

1. 打开 Dashboard 的飞书或钉钉渠道设置。
2. 修改“需要提及”开关或“可自由响应会话”输入框。
3. UI 调用保存接口并提示保存成功。
4. 重启或刷新运行时配置后，群聊入站逻辑仍按 `groupPolicy`、`allowedChats` 和适配器固定逻辑执行，刚才保存的 `require_mention` / `free_response_chats` 不参与判断。

源码证据：

- `web/src/components/solonclaw/settings/PlatformSettings.vue:249`：飞书保存 `require_mention`。
- `web/src/components/solonclaw/settings/PlatformSettings.vue:253`：飞书保存 `free_response_chats`。
- `web/src/components/solonclaw/settings/PlatformSettings.vue:294`：钉钉保存 `require_mention`。
- `web/src/components/solonclaw/settings/PlatformSettings.vue:298`：钉钉保存 `free_response_chats`。
- `src/main/java/com/jimuqu/solon/claw/config/AppConfig.java:1623`：后端渠道策略从 `dmPolicy` 开始建模，没有上述 UI 字段。
- `src/main/java/com/jimuqu/solon/claw/config/AppConfigLoader.java:1715`：加载 `dmPolicy`。
- `src/main/java/com/jimuqu/solon/claw/config/AppConfigLoader.java:1723`：加载 `groupPolicy`。
- `src/main/java/com/jimuqu/solon/claw/config/AppConfigLoader.java:1738`：加载 `allowedChats`。
- `src/main/java/com/jimuqu/solon/claw/gateway/platform/dingtalk/DingTalkChannelAdapter.java:1286`：钉钉群聊固定检查是否提及机器人。
- `src/main/java/com/jimuqu/solon/claw/gateway/platform/dingtalk/DingTalkChannelAdapter.java:1289`：钉钉群聊准入读取 `groupPolicy`。

建议修复阶段：阶段 2.1、阶段 2.3 和阶段 5.2。

最小修复方向：

- 不新增历史兼容字段读取；按当前项目命名把 UI 字段改为后端真实字段，例如 `groupPolicy` 和 `allowedChats`。
- 如果“需要提及”要成为可配置能力，应先在当前 `solonclaw` 配置模型中新增明确字段，并同步后端加载、适配器判断和 Dashboard schema。
- 对飞书和钉钉分别增加保存后读取配置、入站准入行为的回归测试，防止 UI 字段再次脱离运行时字段。

## 不写入本轮 bug 的候选

- 钉钉 QR setup 的 `robotCode` 写入候选：当前源码确实存在 `clientId` 写入 `robotCode` 的路径，但还需要结合钉钉扫码注册协议返回字段确认是否属于真实运行缺陷，本轮不列入正式 bug。
- TUI RPC 与 QR 平台范围候选：当前未形成比已有报告更强的源码闭环证据，本轮不列入阶段 1.1 正式 bug。
- 旧 BUG-001 至 BUG-007：已有报告和修复提交，本轮只追加当前 HEAD 新发现。

## 后续处理顺序建议

1. 阶段 2.1 / 2.3 修 BUG-008，把 Dashboard 保存字段与后端真实策略字段统一，避免继续生成无效配置。
2. 阶段 2.3 继续复核钉钉 QR setup 的 `robotCode` 来源，确认协议字段后再决定是否修复。
3. 修复后必须分别覆盖“UI 保存后的配置文件内容”和“适配器入站判断读取的字段”，不能只验证接口返回成功。
