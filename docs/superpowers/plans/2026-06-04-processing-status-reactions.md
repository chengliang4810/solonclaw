# Processing Status Reactions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add domestic-channel processing status emoji reactions so Feishu and DingTalk show that an inbound user message is being handled before the final reply is sent.

**Architecture:** The gateway owns the processing lifecycle and calls adapter hooks after authorization succeeds and before command/conversation execution starts. Channel adapters implement best-effort platform-specific reactions against the inbound message ID already carried in `GatewayMessage.threadId`; hook failures are logged and must not block the main reply path.

**Tech Stack:** Java, Solon, Hutool HTTP, Snack4 JSON, Feishu OAPI SDK/REST API, DingTalk OpenAPI SDK, JUnit 5, AssertJ, Maven.

---

## 已确认范围

- 外部对标能力点：国内消息渠道的“处理状态表情回应”，用于在用户消息原文上标记“处理中 / 完成 / 失败”。
- 飞书：处理开始添加 `Typing` message reaction；成功或取消时移除 `Typing`；失败时移除 `Typing` 后添加 `CrossMark`。
- 钉钉：处理开始添加 `🤔Thinking` emotion；最终回复成功后撤回 `🤔Thinking` 并添加 `🥳Done`；失败时撤回 `🤔Thinking` 后可添加失败 emotion。
- 该能力只作用于已授权且会进入命令或对话主链的消息；配对提示、未授权丢弃、重复消息不触发处理状态。
- 该能力默认 best-effort：平台 API 异常只记日志，不影响 LLM、命令处理、回复投递。
- 不新增海外渠道，不改变消息投递格式，不新增 CLI wizard。

## 待确认范围

- 钉钉 emotion API 已确认需要 `com.aliyun:dingtalk:2.2.53`；旧版 `2.2.34` 未包含 `RobotReplyEmotion` / `RobotRecallEmotion` 请求模型。
- Dashboard 是否需要独立开关。当前 web i18n 已有“表情回应”文案，本轮后端先支持配置读取或默认启用，前端开关可后续接入。

## File Structure

- Modify `src/main/java/com/jimuqu/solon/claw/core/service/ChannelAdapter.java`
  - Add default processing lifecycle hooks with Chinese Javadoc.
- Modify `src/main/java/com/jimuqu/solon/claw/core/model/GatewayMessage.java`
  - Keep `threadId` as source message ID and add only documentation if needed.
- Modify `src/main/java/com/jimuqu/solon/claw/gateway/service/DefaultGatewayService.java`
  - Resolve the adapter for the inbound platform and call lifecycle hooks around command/conversation execution.
- Modify `src/main/java/com/jimuqu/solon/claw/gateway/platform/base/AbstractConfigurableChannelAdapter.java`
  - Provide shared safe no-op behavior only if common logging is useful.
- Modify `src/main/java/com/jimuqu/solon/claw/gateway/platform/feishu/FeishuChannelAdapter.java`
  - Implement Feishu add/remove reaction through OAPI message reaction endpoints.
  - Track `messageId -> reactionId` in a bounded map.
- Modify `src/main/java/com/jimuqu/solon/claw/gateway/platform/dingtalk/DingTalkChannelAdapter.java`
  - Implement DingTalk emotion reply/recall after SDK API confirmation.
  - Track per-chat/message completion idempotency.
- Test `src/test/java/com/jimuqu/solon/claw/GatewayProcessingReactionLifecycleTest.java`
  - Verify gateway hook ordering, success/failure/cancel outcomes, authorization gating, duplicate suppression, and hook failure isolation.
- Test `src/test/java/com/jimuqu/solon/claw/FeishuProcessingReactionTest.java`
  - Verify Feishu request URLs/payloads, reaction-id cache behavior, failure transition, disabled/blank message no-op.
- Test `src/test/java/com/jimuqu/solon/claw/DingTalkProcessingEmotionTest.java`
  - Verify DingTalk Thinking/Done lifecycle once API boundary is finalized.

## Task 1: Gateway lifecycle hook contract

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/core/service/ChannelAdapter.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/gateway/service/DefaultGatewayService.java`
- Test: `src/test/java/com/jimuqu/solon/claw/GatewayProcessingReactionLifecycleTest.java`

- [x] **Step 1: Write failing success lifecycle test**

```java
@Test
void shouldMarkProcessingStartThenSuccessAroundAuthorizedConversation() throws Exception {
    TrackingChannelAdapter adapter = new TrackingChannelAdapter();
    DefaultGatewayService service = serviceWith(adapter, new ReplyingConversation());
    GatewayMessage message = authorizedMessage("chat-1", "user-1", "msg-1", "hello");

    GatewayReply reply = service.handle(message);

    assertThat(reply.getContent()).isEqualTo("ok");
    assertThat(adapter.events()).containsExactly("start:msg-1", "complete:msg-1:SUCCESS");
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=GatewayProcessingReactionLifecycleTest#shouldMarkProcessingStartThenSuccessAroundAuthorizedConversation test`

Expected: FAIL because `ChannelAdapter` has no lifecycle hook and `DefaultGatewayService` does not call it.

- [x] **Step 3: Add hook API**

Add default methods:

```java
/** 标记渠道已开始处理入站消息，用于添加“处理中”表情回应。 */
default void onProcessingStart(GatewayMessage message) throws Exception {
    // 默认渠道不支持处理状态表情回应。
}

/** 标记渠道已结束处理入站消息，用于清理或切换处理状态表情回应。 */
default void onProcessingComplete(GatewayMessage message, ProcessingOutcome outcome) throws Exception {
    // 默认渠道不支持处理状态表情回应。
}
```

Create enum:

```java
package com.jimuqu.solon.claw.core.enums;

/** 渠道消息处理结果，用于驱动处理状态表情回应。 */
public enum ProcessingOutcome {
    /** 回复已正常生成并投递。 */
    SUCCESS,
    /** 主链处理失败并生成错误回复。 */
    FAILURE,
    /** Agent 运行被用户取消。 */
    CANCELLED
}
```

- [x] **Step 4: Wire gateway calls**

In `DefaultGatewayService.handle`, after authorization succeeds and before command/conversation execution, call `safeProcessingStart(message)`. After the reply path completes, call `safeProcessingComplete(message, outcome)`. Do not call hooks for `preAuthorize`, unauthorized, duplicate, or null messages.

- [x] **Step 5: Run success lifecycle test**

Run: `mvn -Dtest=GatewayProcessingReactionLifecycleTest#shouldMarkProcessingStartThenSuccessAroundAuthorizedConversation test`

Expected: PASS.

## Task 2: Failure, cancellation, and no-op gates

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/gateway/service/DefaultGatewayService.java`
- Test: `src/test/java/com/jimuqu/solon/claw/GatewayProcessingReactionLifecycleTest.java`

- [x] **Step 1: Write failing failure/cancel tests**

Add tests proving:
- unauthorized messages do not call start/complete
- duplicate messages do not call start/complete
- conversation exception records `FAILURE`
- `AgentRunCancelledException` records `CANCELLED`
- hook exceptions do not block normal replies

- [x] **Step 2: Run tests to verify failures**

Run: `mvn -Dtest=GatewayProcessingReactionLifecycleTest test`

Expected: FAIL until gateway exception paths call the correct outcome.

- [x] **Step 3: Implement exception-path outcomes**

Track `processingStarted` and `completed` booleans so every started message gets exactly one completion callback, including exceptions. Use `safeProcessingComplete` from `finally` where practical, with explicit outcome selection.

- [x] **Step 4: Run tests**

Run: `mvn -Dtest=GatewayProcessingReactionLifecycleTest test`

Expected: PASS.

## Task 3: Feishu processing reactions

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/gateway/platform/feishu/FeishuChannelAdapter.java`
- Test: `src/test/java/com/jimuqu/solon/claw/FeishuProcessingReactionTest.java`

- [x] **Step 1: Write failing Feishu tests**

Test these behaviors with an injectable HTTP executor or overridable request method:
- `onProcessingStart` posts `{"reaction_type":{"emoji_type":"Typing"}}` to the message reaction create endpoint.
- successful create caches returned `reaction_id`.
- success completion deletes the cached reaction and adds nothing else.
- failure completion deletes `Typing` and creates `CrossMark`.
- blank `threadId` is no-op.
- delete failure does not add `CrossMark`.

- [x] **Step 2: Run Feishu test to verify failure**

Run: `mvn -Dtest=FeishuProcessingReactionTest test`

Expected: FAIL because Feishu adapter lacks processing lifecycle overrides.

- [x] **Step 3: Implement Feishu REST reaction methods**

Use Hutool HTTP with `Authorization: Bearer <tenantAccessToken>`:
- `POST https://open.feishu.cn/open-apis/im/v1/messages/{message_id}/reactions`
- `DELETE https://open.feishu.cn/open-apis/im/v1/messages/{message_id}/reactions/{reaction_id}`

Parse response with Snack4 and keep a bounded `LinkedHashMap<String, String>` cache.

- [x] **Step 4: Run Feishu test**

Run: `mvn -Dtest=FeishuProcessingReactionTest test`

Expected: PASS.

## Task 4: DingTalk processing emotions

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/gateway/platform/dingtalk/DingTalkChannelAdapter.java`
- Test: `src/test/java/com/jimuqu/solon/claw/DingTalkProcessingEmotionTest.java`

- [x] **Step 1: Confirm API boundary**

Inspect `com.aliyun:dingtalk:2.2.34` classes with:

```bash
jar tf ~/.m2/repository/com/aliyun/dingtalk/2.2.34/dingtalk-2.2.34.jar | rg 'Emotion|Robot.*Emotion'
```

If SDK classes exist, use them. If not, create a small Hutool REST wrapper method with a protected `sendEmotionRequest` override point for tests.

- [x] **Step 2: Write failing DingTalk tests**

Test:
- start sends `🤔Thinking` for `threadId` plus `chatId`
- success recalls `🤔Thinking` then sends `🥳Done`
- failure recalls `🤔Thinking` and records failure status when supported
- completion is idempotent per inbound message
- blank `threadId` or `chatId` is no-op

- [x] **Step 3: Run DingTalk tests to verify failure**

Run: `mvn -Dtest=DingTalkProcessingEmotionTest test`

Expected: FAIL before implementation.

- [x] **Step 4: Implement DingTalk emotion lifecycle**

Use `GatewayMessage.threadId` as open message id and `GatewayMessage.chatId` as open conversation id. Refresh access token first. Keep all failures best-effort and log redacted errors.

- [x] **Step 5: Run DingTalk tests**

Run: `mvn -Dtest=DingTalkProcessingEmotionTest test`

Expected: PASS.

## Task 5: Verification and naming guard

**Files:**
- No new production files unless earlier tasks require helper classes.

- [x] **Step 1: Run targeted Java tests**

Run:

```bash
mvn -Dtest=GatewayProcessingReactionLifecycleTest,FeishuProcessingReactionTest,DingTalkProcessingEmotionTest test
```

Expected: PASS.

- [x] **Step 2: Run broader gateway regression tests**

Run:

```bash
mvn -Dtest=GatewayCommandFlowTest,GatewayErrorHandlingTest,FeishuWebsocketInboundTest,DingTalkAiCardRoutingTest test
```

Expected: PASS.

- [x] **Step 3: Run formatting and naming checks**

Run:

```bash
mvn -DskipTests spotless:check
python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range
git diff --check
```

Expected: all pass and no old project naming appears in current branch range.

## Self-Review

- Spec coverage: The plan covers lifecycle hook, gateway outcome routing, Feishu reaction API, DingTalk emotion API, best-effort behavior, and verification.
- Placeholder scan: No `TBD` or unspecified implementation steps remain; DingTalk API uncertainty is handled by an explicit inspection step and fallback boundary.
- Type consistency: `GatewayMessage.threadId`, `ProcessingOutcome`, `ChannelAdapter` hook names, and test names are consistent across tasks.
