package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.context.BuiltinMemoryProvider;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.MemorySnapshot;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.support.constants.ContextFileConstants;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;

import org.junit.jupiter.api.Test;

import java.util.List;

/** 验证主人群聊连续性与访客群聊隐私隔离。 */
public class GatewayGroupPrivacyTest {
    /** 主人在群聊中应复用私聊会话，但回复仍投递到原群。 */
    @Test
    void shouldReuseOwnerDirectSessionFromGroup() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayAuthorizationService.setPlatformAdmin(
                PlatformType.MEMORY, "owner", "Owner", "owner-dm");
        GatewayReply direct =
                env.gatewayService.handle(env.message("owner-dm", "owner", "private"));
        GatewayMessage group =
                env.message(
                        "team-room",
                        "owner",
                        GatewayBehaviorConstants.CHAT_TYPE_GROUP,
                        "Team",
                        "Owner",
                        "group follow-up");

        GatewayReply reply = env.gatewayService.handle(group);

        assertThat(reply.getSessionId()).isEqualTo(direct.getSessionId());
        assertThat(group.sourceKey()).isEqualTo("MEMORY:owner-dm:owner");
        assertThat(env.memoryChannelAdapter.getLastRequest().getChatId()).isEqualTo("team-room");
    }

    /** 访客群聊允许正常回答，但命令、私有上下文、记忆和工具全部隔离。 */
    @Test
    void shouldRunGroupGuestWithPublicContextAndNoTools() throws Exception {
        RecordingLlmGateway llm = new RecordingLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(llm);
        env.gatewayAuthorizationService.setPlatformAdmin(
                PlatformType.MEMORY, "owner", "Owner", "owner-dm");
        PersonaWorkspaceService workspace = new PersonaWorkspaceService(env.appConfig);
        workspace.write(ContextFileConstants.KEY_AGENTS, "PRIVATE_AGENTS_SECRET");
        workspace.write(ContextFileConstants.KEY_MEMORY, "PRIVATE_MEMORY_SECRET");
        workspace.write(ContextFileConstants.KEY_USER, "PRIVATE_USER_SECRET");
        workspace.write(ContextFileConstants.KEY_TOOLS, "PRIVATE_TOOLS_SECRET");
        workspace.write(ContextFileConstants.KEY_SOUL, "PUBLIC_SOUL_ROLE");
        workspace.write(ContextFileConstants.KEY_IDENTITY, "PUBLIC_IDENTITY_ROLE");
        GatewayMessage guest =
                env.message(
                        "team-room",
                        "guest",
                        GatewayBehaviorConstants.CHAT_TYPE_GROUP,
                        "Team",
                        "Guest",
                        "/status");

        GatewayReply reply = env.gatewayService.handle(guest);

        assertThat(reply.getContent()).isEqualTo("echo:/status");
        assertThat(guest.sourceKey()).isEqualTo("MEMORY:__group_guest__:team-room:guest");
        assertThat(llm.lastToolCount).isZero();
        assertThat(llm.lastRunContextMemoryPrefetch).isNullOrEmpty();
        assertThat(llm.lastSystemPrompt)
                .contains("[Group Guest Privacy]", "PUBLIC_SOUL_ROLE", "PUBLIC_IDENTITY_ROLE")
                .doesNotContain(
                        "PRIVATE_AGENTS_SECRET",
                        "PRIVATE_MEMORY_SECRET",
                        "PRIVATE_USER_SECRET",
                        "PRIVATE_TOOLS_SECRET",
                        "[Agent Runtime]",
                        "[Enabled Skills]");
        assertThat(env.memoryChannelAdapter.getLastRequest().getChatId()).isEqualTo("team-room");
    }

    /** 内建记忆提供方收到访客来源键时不得读取任何主人记忆快照。 */
    @Test
    void shouldNotLoadBuiltinMemoryForGroupGuest() throws Exception {
        BuiltinMemoryProvider provider = new BuiltinMemoryProvider(new FailingLoadMemoryService());

        String prompt =
                provider.systemPromptBlock(
                        GatewayMessage.groupGuestSourceKey(
                                PlatformType.FEISHU, "team-room", "guest"));

        assertThat(prompt).isEmpty();
    }

    /** 记录模型实际收到的工具数量。 */
    private static class RecordingLlmGateway extends FakeLlmGateway {
        /** 最近一次模型调用收到的工具数量。 */
        private int lastToolCount = -1;

        /** 记录工具数量后复用标准测试模型响应。 */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects)
                throws Exception {
            lastToolCount = toolObjects == null ? 0 : toolObjects.size();
            return super.chat(session, systemPrompt, userMessage, toolObjects);
        }
    }

    /** 若访客隔离失效，读取快照会立即让测试失败。 */
    private static class FailingLoadMemoryService implements MemoryService {
        /** 群聊访客不允许触发主人记忆快照读取。 */
        @Override
        public MemorySnapshot loadSnapshot() {
            throw new AssertionError("访客会话读取了主人记忆");
        }

        /** 测试替身不支持普通记忆读取。 */
        @Override
        public String read(String target) {
            throw new UnsupportedOperationException();
        }

        /** 测试替身不支持新增记忆。 */
        @Override
        public String add(String target, String content) {
            throw new UnsupportedOperationException();
        }

        /** 测试替身不支持替换记忆。 */
        @Override
        public String replace(String target, String oldText, String newContent) {
            throw new UnsupportedOperationException();
        }

        /** 测试替身不支持删除记忆。 */
        @Override
        public String remove(String target, String matchText) {
            throw new UnsupportedOperationException();
        }
    }
}
