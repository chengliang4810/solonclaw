package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.cli.acp.AcpStdioServer;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;

public class AcpStdioServerTest {
    private static final byte[] ONE_PX_PNG =
            hex(
                    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4"
                            + "890000000a49444154789c6300010000000500010d0a2db40000000049454e44ae426082");

    @Test
    void shouldHandleInitializeNewSessionAndPrompt() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String init =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}");
        assertThat(init)
                .contains("\"id\":1")
                .contains("\"protocol_version\":1")
                .contains("\"agent_capabilities\"")
                .contains("\"prompt_capabilities\":{\"image\":true}")
                .contains("\"session_capabilities\":{\"fork\":{},\"list\":{},\"resume\":{}}")
                .contains("\"slash_commands\":true")
                .contains("context")
                .contains("\"name\":\"compact\"")
                .contains("\"name\":\"steer\"")
                .contains("\"name\":\"queue\"")
                .contains("\"name\":\"kanban\"")
                .contains("\"name\":\"version\"")
                .contains("\"name\":\"acp\"")
                .contains("查看 ACP 本地适配器能力快照")
                .contains("给运行中任务的修正或引导")
                .contains("下一轮要执行的提示")
                .contains("status、queue、steer、interrupt 或 reject")
                .contains("list、create、schema、drawer、pipeline、retry、history、guide 或 dispatch")
                .doesNotContain("\"name\":\"compress\"")
                .contains("reload-mcp");

        String created =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/new\",\"params\":{\"cwd\":\"D:/projects/jimuqu-agent\"}}");
        assertThat(created).contains("\"id\":2").contains("\"session_id\"");
        String sessionId = extractSessionId(created);

        String prompted =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session/prompt\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"hello acp\"}]}}");
        assertThat(prompted)
                .contains("\"id\":3")
                .contains("\"stop_reason\":\"end_turn\"")
                .contains("echo:hello acp");
    }

    @Test
    void shouldSendUnknownAcpSlashCommandToModel() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));
        String sessionId = extractSessionId(newAcpSession(server, 62));

        String prompted =
                prompt(
                        server,
                        63,
                        sessionId,
                        "[{\"type\":\"text\",\"text\":\"/not-a-real-command keep this as prose\"}]");

        assertThat(prompted)
                .contains("\"id\":63")
                .contains("\"stop_reason\":\"end_turn\"")
                .contains("echo:/not-a-real-command keep this as prose");
    }

    @Test
    void shouldRedactAcpProtocolErrorMessages() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String response =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":64,\"method\":\"bad-token=sk-test-acperror12345\",\"params\":{}}");

        assertThat(response)
                .contains("\"id\":64")
                .contains("\"error\"")
                .contains("bad-token=***")
                .doesNotContain("sk-test-acperror12345");
    }

    @Test
    void shouldInlineAcpResourceLinkTextFile(@TempDir Path tempDir) throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));
        Path attached = tempDir.resolve("notes.md");
        Files.write(attached, "# Notes\n\nAttached file body".getBytes(StandardCharsets.UTF_8));
        String sessionId = extractSessionId(newAcpSession(server, 50));

        String prompted =
                prompt(
                        server,
                        51,
                        sessionId,
                        "[{\"type\":\"text\",\"text\":\"Please read this file\"},"
                                + "{\"type\":\"resource_link\",\"name\":\"notes.md\",\"title\":\"Project notes\","
                                + "\"uri\":\""
                                + jsonEscape(attached.toUri().toString())
                                + "\",\"mimeType\":\"text/markdown\"}]");

        assertThat(prompted)
                .contains("\"id\":51")
                .contains("Please read this file")
                .contains("[Attached file: Project notes (notes.md)]")
                .contains("# Notes")
                .contains("Attached file body");
    }

    @Test
    void shouldRedactAcpResourceDisplayMetadata(@TempDir Path tempDir) throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));
        Path attached = tempDir.resolve("token=ghp_acpfiletoken12345.txt");
        Files.write(attached, "visible body".getBytes(StandardCharsets.UTF_8));
        String sessionId = extractSessionId(newAcpSession(server, 65));

        String prompted =
                prompt(
                        server,
                        66,
                        sessionId,
                        "[{\"type\":\"resource_link\","
                                + "\"name\":\"notes-token=ghp_acpnametoken12345.txt\","
                                + "\"title\":\"Title api_key=sk-test-acptitle12345\","
                                + "\"uri\":\""
                                + jsonEscape(attached.toUri().toString())
                                + "\",\"mimeType\":\"text/plain\"}]");

        assertThat(prompted)
                .contains("\"id\":66")
                .contains("Title api_key=***")
                .contains("token=***")
                .contains("visible body")
                .doesNotContain("sk-test-acptitle12345")
                .doesNotContain("ghp_acpnametoken12345")
                .doesNotContain("ghp_acpfiletoken12345");
    }

    @Test
    void shouldPreserveAcpResourceLinkImageFileAsAttachmentNote(@TempDir Path tempDir)
            throws Exception {
        AcpAttachmentGateway gateway = new AcpAttachmentGateway();
        TestEnvironment env = TestEnvironment.withLlm(gateway);
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));
        Path attached = tempDir.resolve("shot.png");
        Files.write(attached, ONE_PX_PNG);
        String sessionId = extractSessionId(newAcpSession(server, 52));

        String prompted =
                prompt(
                        server,
                        53,
                        sessionId,
                        "[{\"type\":\"text\",\"text\":\"Look at this screenshot\"},"
                                + "{\"type\":\"resource_link\",\"name\":\"shot.png\","
                                + "\"uri\":\""
                                + jsonEscape(attached.toUri().toString())
                                + "\",\"mimeType\":\"image/png\"}]");

        assertThat(prompted)
                .contains("\"id\":53")
                .contains("Look at this screenshot")
                .contains("[Attached image: shot.png]")
                .contains("MIME: image/png")
                .contains("Bytes: " + ONE_PX_PNG.length);
        assertThat(gateway.lastAttachments).hasSize(1);
        assertThat(gateway.lastAttachments.get(0).getKind()).isEqualTo("image");
        assertThat(gateway.lastAttachments.get(0).getOriginalName()).isEqualTo("shot.png");
        assertThat(gateway.lastAttachments.get(0).getMimeType()).isEqualTo("image/png");
        assertThat(gateway.lastAttachments.get(0).getData())
                .isEqualTo(Base64.getEncoder().encodeToString(ONE_PX_PNG));
    }

    @Test
    void shouldRedactAcpImageResourceDisplayMetadata() throws Exception {
        AcpAttachmentGateway gateway = new AcpAttachmentGateway();
        TestEnvironment env = TestEnvironment.withLlm(gateway);
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));
        String sessionId = extractSessionId(newAcpSession(server, 67));
        String b64 = Base64.getEncoder().encodeToString(ONE_PX_PNG);

        String prompted =
                prompt(
                        server,
                        68,
                        sessionId,
                        "[{\"type\":\"image\","
                                + "\"name\":\"shot-token=ghp_acpimagename12345.png\","
                                + "\"title\":\"Image api_key=sk-test-acpimage12345\","
                                + "\"uri\":\"file:///tmp/shot.png?token=ghp_acpimageuri12345\","
                                + "\"mimeType\":\"image/png\","
                                + "\"data\":\""
                                + b64
                                + "\"}]");

        assertThat(prompted)
                .contains("\"id\":68")
                .contains("Image api_key=***")
                .contains("token=***")
                .doesNotContain("sk-test-acpimage12345")
                .doesNotContain("ghp_acpimagename12345")
                .doesNotContain("ghp_acpimageuri12345");
        assertThat(gateway.lastAttachments).hasSize(1);
        assertThat(gateway.lastAttachments.get(0).getOriginalName())
                .contains("Image api_key=***")
                .doesNotContain("sk-test-acpimage12345");
    }

    @Test
    void shouldInferAcpResourceLinkImageMimeFromSuffix(@TempDir Path tempDir) throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));
        Path attached = tempDir.resolve("pic.jpg");
        Files.write(attached, ONE_PX_PNG);
        String sessionId = extractSessionId(newAcpSession(server, 54));

        String prompted =
                prompt(
                        server,
                        55,
                        sessionId,
                        "[{\"type\":\"resource_link\",\"name\":\"pic.jpg\",\"uri\":\""
                                + jsonEscape(attached.toUri().toString())
                                + "\"}]");

        assertThat(prompted)
                .contains("\"id\":55")
                .contains("[Attached image: pic.jpg]")
                .contains("MIME: image/jpeg");
    }

    @Test
    void shouldInlineAcpEmbeddedTextResource() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));
        String sessionId = extractSessionId(newAcpSession(server, 56));

        String prompted =
                prompt(
                        server,
                        57,
                        sessionId,
                        "[{\"type\":\"resource\",\"resource\":{\"uri\":\"file:///workspace/todo.txt\","
                                + "\"mimeType\":\"text/plain\",\"text\":\"first\\nsecond\"}}]");

        assertThat(prompted)
                .contains("\"id\":57")
                .contains("[Attached file: todo.txt]")
                .contains("first")
                .contains("second");
    }

    @Test
    void shouldPreserveAcpEmbeddedBlobImageAsAttachmentNote() throws Exception {
        AcpAttachmentGateway gateway = new AcpAttachmentGateway();
        TestEnvironment env = TestEnvironment.withLlm(gateway);
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));
        String sessionId = extractSessionId(newAcpSession(server, 58));
        String b64 = Base64.getEncoder().encodeToString(ONE_PX_PNG);

        String prompted =
                prompt(
                        server,
                        59,
                        sessionId,
                        "[{\"type\":\"resource\",\"resource\":{\"uri\":\"file:///tmp/embed.png\","
                                + "\"mimeType\":\"image/png\",\"blob\":\""
                                + b64
                                + "\"}}]");

        assertThat(prompted)
                .contains("\"id\":59")
                .contains("[Attached image: embed.png]")
                .contains("MIME: image/png")
                .contains("Bytes: " + ONE_PX_PNG.length);
        assertThat(gateway.lastAttachments).hasSize(1);
        assertThat(gateway.lastAttachments.get(0).getOriginalName()).isEqualTo("embed.png");
        assertThat(gateway.lastAttachments.get(0).getMimeType()).isEqualTo("image/png");
        assertThat(gateway.lastAttachments.get(0).getData()).isEqualTo(b64);
    }

    @Test
    void shouldPreserveAcpDirectImageBlockAsAttachmentNote() throws Exception {
        AcpAttachmentGateway gateway = new AcpAttachmentGateway();
        TestEnvironment env = TestEnvironment.withLlm(gateway);
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));
        String sessionId = extractSessionId(newAcpSession(server, 60));
        String b64 = Base64.getEncoder().encodeToString(ONE_PX_PNG);

        String prompted =
                prompt(
                        server,
                        61,
                        sessionId,
                        "[{\"type\":\"text\",\"text\":\"Describe it\"},"
                                + "{\"type\":\"image\",\"name\":\"direct.png\",\"mimeType\":\"image/png\","
                                + "\"data\":\""
                                + b64
                                + "\"}]");

        assertThat(prompted)
                .contains("\"id\":61")
                .contains("Describe it")
                .contains("[Attached image: direct.png]")
                .contains("MIME: image/png")
                .contains("Bytes: " + ONE_PX_PNG.length);
        assertThat(gateway.lastAttachments).hasSize(1);
        assertThat(gateway.lastAttachments.get(0).getOriginalName()).isEqualTo("direct.png");
        assertThat(gateway.lastAttachments.get(0).getMimeType()).isEqualTo("image/png");
        assertThat(gateway.lastAttachments.get(0).getData()).isEqualTo(b64);
    }

    @Test
    void shouldHandleJimuquLifecycleCompatibilityMethods() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String auth =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"auth-1\",\"method\":\"authenticate\",\"params\":{\"method_id\":\"default\"}}");
        assertThat(auth)
                .contains("\"id\":\"auth-1\"")
                .contains("\"authenticated\":false")
                .contains("local runtime credentials");

        String resumed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resume_session\",\"params\":{\"sessionId\":\"missing\",\"cwd\":\"D:/projects/jimuqu-agent\",\"mcp_servers\":[\"editor\"]}}");
        assertThat(resumed)
                .contains("\"id\":4")
                .contains("\"session_id\"")
                .contains("\"mcp_servers\":[\"editor\"]");
        String sessionId = extractSessionId(resumed);

        String listed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"list_sessions\",\"params\":{\"cwd\":\"D:/projects/jimuqu-agent\"}}");
        assertThat(listed).contains("\"id\":5").contains("\"sessions\"").contains(sessionId);

        String forked =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"fork_session\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"cwd\":\"D:/projects/fork\"}}");
        assertThat(forked)
                .contains("\"id\":6")
                .contains("\"cwd\":\"path://fork\"")
                .contains("\"source_key\":\"MEMORY:cli:");

        String cancelled =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"session/cancel\",\"params\":{\"sessionId\":\""
                                + sessionId
                                + "\"}}");
        assertThat(cancelled)
                .contains("\"id\":7")
                .contains("\"ok\":true")
                .contains("\"active_run\":false");
    }

    @Test
    void shouldAdvertiseAndValidateAcpRuntimeAuthMethod() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getProviders().get("default").setApiKey("sk-test-acp-auth");
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        env.appConfig);

        String init =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}");
        assertThat(init)
                .contains("\"id\":20")
                .contains("\"auth_methods\":[{\"id\":\"default\"")
                .contains("default runtime credentials");

        String authenticated =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"authenticate\",\"params\":{\"method_id\":\"Default\"}}");
        assertThat(authenticated)
                .contains("\"id\":21")
                .contains("\"ok\":true")
                .contains("\"authenticated\":true")
                .contains("\"method_id\":\"default\"");

        String rejected =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"authenticate\",\"params\":{\"method_id\":\"other\"}}");
        assertThat(rejected)
                .contains("\"id\":22")
                .contains("\"ok\":false")
                .contains("\"authenticated\":false");
    }

    @Test
    void shouldRedactSecretsFromAcpAuthMethodMetadata() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String providerKey = "token=ghp_acpauthprovider12345";
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Auth Provider");
        provider.setBaseUrl("https://api.openai.com");
        provider.setApiKey("sk-test-acpauthsecret12345");
        provider.setDefaultModel("gpt-auth");
        provider.setDialect("openai");
        env.appConfig.getProviders().put(providerKey, provider);
        env.appConfig.getModel().setProviderKey(providerKey);
        env.appConfig.getLlm().setProvider(providerKey);
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        env.appConfig);

        String init =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":75,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}");
        assertThat(init)
                .contains("\"id\":75")
                .contains("token=***")
                .doesNotContain("ghp_acpauthprovider12345");

        String authenticated =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":76,\"method\":\"authenticate\",\"params\":{\"method_id\":\""
                                + providerKey
                                + "\"}}");
        assertThat(authenticated)
                .contains("\"id\":76")
                .contains("\"ok\":true")
                .contains("\"authenticated\":true")
                .contains("token=***")
                .doesNotContain("ghp_acpauthprovider12345");
    }

    @Test
    void shouldAcceptJimuquEditorSessionModelModeAndConfigMethods() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AppConfig.ProviderConfig fallbackProvider = new AppConfig.ProviderConfig();
        fallbackProvider.setName("BackupProvider");
        fallbackProvider.setBaseUrl("https://api.openai.com");
        fallbackProvider.setApiKey("");
        fallbackProvider.setDefaultModel("gpt-backup");
        fallbackProvider.setDialect("openai");
        env.appConfig.getProviders().put("backup", fallbackProvider);
        AppConfig.FallbackProviderConfig fallback = new AppConfig.FallbackProviderConfig();
        fallback.setProvider("backup");
        env.appConfig.getFallbackProviders().add(fallback);
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        env.dangerousCommandApprovalService,
                        env.appConfig);

        String sessionId = extractSessionId(newAcpSession(server, 30));

        String model =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"set_session_model\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"model_id\":\"default:gpt-test-acp\"}}");
        assertThat(model)
                .contains("\"id\":31")
                .contains("\"ok\":true")
                .contains("\"model_id\":\"default:gpt-test-acp\"")
                .contains("\"modelId\":\"default:gpt-test-acp\"");

        String mode =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":32,\"method\":\"set_session_mode\",\"params\":{\"sessionId\":\""
                                + sessionId
                                + "\",\"mode_id\":\"plan\"}}");
        assertThat(mode)
                .contains("\"id\":32")
                .contains("\"ok\":true")
                .contains("\"mode_id\":\"plan\"")
                .contains("\"modeId\":\"plan\"");

        String config =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":33,\"method\":\"set_config_option\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"config_id\":\"reasoning_effort\",\"value\":\"high\"}}");
        assertThat(config)
                .contains("\"id\":33")
                .contains("\"ok\":true")
                .contains("\"config_id\":\"reasoning_effort\"")
                .contains("\"reasoning_effort\":\"high\"");

        String loaded =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":34,\"method\":\"session/load\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\"}}");
        assertThat(loaded)
                .contains("\"model_id\":\"default:gpt-test-acp\"")
                .contains("\"current_model_id\":\"default:gpt-test-acp\"")
                .contains("\"available_models\"")
                .contains("\"model_id\":\"backup:gpt-backup\"")
                .contains("\"mode_id\":\"plan\"")
                .contains("\"reasoning_effort\":\"high\"");
    }

    @Test
    void shouldRedactSecretsFromAcpModelMetadata() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AppConfig.ProviderConfig defaultProvider = env.appConfig.getProviders().get("default");
        defaultProvider.setName("Default api_key=sk-test-acpmodelprovider12345");
        defaultProvider.setDefaultModel("gpt-token=ghp_acpmodeldefault12345");
        AppConfig.ProviderConfig fallbackProvider = new AppConfig.ProviderConfig();
        fallbackProvider.setName("Backup token=ghp_acpmodelbackupname12345");
        fallbackProvider.setBaseUrl("https://api.openai.com");
        fallbackProvider.setApiKey("");
        fallbackProvider.setDefaultModel("backup-token=ghp_acpmodelbackup12345");
        fallbackProvider.setDialect("openai");
        env.appConfig.getProviders().put("backup", fallbackProvider);
        AppConfig.FallbackProviderConfig fallback = new AppConfig.FallbackProviderConfig();
        fallback.setProvider("backup");
        env.appConfig.getFallbackProviders().add(fallback);
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        env.dangerousCommandApprovalService,
                        env.appConfig);

        String created = newAcpSession(server, 72);
        assertThat(created)
                .contains("\"id\":72")
                .contains("token=***")
                .contains("api_key=***")
                .doesNotContain("ghp_acpmodeldefault12345")
                .doesNotContain("sk-test-acpmodelprovider12345")
                .doesNotContain("ghp_acpmodelbackupname12345")
                .doesNotContain("ghp_acpmodelbackup12345");

        String sessionId = extractSessionId(created);
        String model =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":73,\"method\":\"set_session_model\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"model_id\":\"default:session-token=ghp_acpmodelsession12345\"}}");
        assertThat(model)
                .contains("\"id\":73")
                .contains("session-token=***")
                .doesNotContain("ghp_acpmodelsession12345");

        String loaded =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":74,\"method\":\"session/load\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\"}}");
        assertThat(loaded)
                .contains("\"id\":74")
                .contains("session-token=***")
                .contains("token=***")
                .contains("api_key=***")
                .doesNotContain("ghp_acpmodelsession12345")
                .doesNotContain("ghp_acpmodeldefault12345")
                .doesNotContain("sk-test-acpmodelprovider12345")
                .doesNotContain("ghp_acpmodelbackupname12345")
                .doesNotContain("ghp_acpmodelbackup12345");
    }

    @Test
    void shouldApplyAcpSessionModelToFollowingPrompt() throws Exception {
        TestEnvironment env = TestEnvironment.withLlm(new ModelEchoGateway());
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String sessionId = extractSessionId(newAcpSession(server, 35));
        server.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":36,\"method\":\"set_session_model\",\"params\":{\"session_id\":\""
                        + sessionId
                        + "\",\"model_id\":\"default:acp-session-model\"}}");

        String prompted =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":37,\"method\":\"session/prompt\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"hello model\"}]}}");

        assertThat(prompted)
                .contains("\"id\":37")
                .contains("model=acp-session-model");
        SessionRecord record = env.sessionRepository.findById(sessionId);
        assertThat(record.getModelOverride()).isEqualTo("default:acp-session-model");
        assertThat(record.getLastResolvedModel()).isEqualTo("acp-session-model");
    }

    @Test
    void shouldReturnJimuquStylePromptUpdatesAndUsage() throws Exception {
        TestEnvironment env = TestEnvironment.withLlm(new ToolEventGateway());
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String sessionId = extractSessionId(newAcpSession(server, 38));
        String prompted =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":39,\"method\":\"session/prompt\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"hello updates\"}]}}");

        assertThat(prompted)
                .contains("\"id\":39")
                .contains("\"session_updates\"")
                .contains("\"available_commands_update\"")
                .contains("\"name\":\"compact\"")
                .contains("\"name\":\"steer\"")
                .contains("\"name\":\"queue\"")
                .contains("\"name\":\"kanban\"")
                .contains("\"name\":\"version\"")
                .contains("\"name\":\"acp\"")
                .contains("status、queue、steer、interrupt 或 reject")
                .contains("list、create、schema、drawer、pipeline、retry、history、guide 或 dispatch")
                .contains("\"tool_call_start\"")
                .contains("\"tool_call_update\"")
                .contains("\"agent_thought_chunk\"")
                .contains("\"usage_update\"")
                .contains("\"input_tokens\":13")
                .contains("\"total_tokens\":34")
                .contains("\"context_estimate_tokens\"")
                .contains("\"context_window_tokens\"")
                .contains("\"input\":{\"kind\":\"unstructured\"")
                .contains("echo:hello updates")
                .doesNotContain("[tool.start]");
    }

    @Test
    void shouldRedactSecretsFromAcpPromptToolUpdates() throws Exception {
        TestEnvironment env = TestEnvironment.withLlm(new SecretToolEventGateway());
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String sessionId = extractSessionId(newAcpSession(server, 40));
        String prompted =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":41,\"method\":\"session/prompt\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"hello secret updates\"}]}}");

        assertThat(prompted)
                .contains("\"id\":41")
                .contains("\"tool_call_start\"")
                .contains("\"tool_call_update\"")
                .contains("api_key=***")
                .contains("\"token\":\"***\"")
                .contains("bearer ***")
                .doesNotContain("sk-1234567890abcdef")
                .doesNotContain("sk-abcdef1234567890");
    }


    @Test
    void shouldRegisterAcpMcpServersThroughDashboardMcpService() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DashboardMcpService mcpService = new DashboardMcpService(env.appConfig, env.sqliteDatabase);
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        mcpService);

        String created =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"session/new\",\"params\":{\"cwd\":\"D:/projects/jimuqu-agent\",\"mcp_servers\":[{\"name\":\"editor-fs\",\"command\":\"node\",\"args\":[\"server.js\"],\"tools\":[{\"name\":\"read_file\"}]}]}}");

        assertThat(created)
                .contains("\"id\":10")
                .contains("\"mcp_tool_count\":1")
                .contains("editor-fs");
        Object servers = mcpService.list().get("servers");
        assertThat(String.valueOf(servers))
                .contains("editor-fs")
                .contains("node")
                .contains("read_file");
    }

    @Test
    void shouldRedactAcpSessionMetadataViews() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DashboardMcpService mcpService = new DashboardMcpService(env.appConfig, env.sqliteDatabase);
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        mcpService);

        String created =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":69,\"method\":\"session/new\","
                                + "\"params\":{\"cwd\":\"D:/workspace/token=ghp_acpcwd12345\","
                                + "\"mcp_servers\":[{\"name\":\"docs\","
                                + "\"command\":\"node server.js --api_key=sk-test-acpmcp12345\","
                                + "\"args\":[\"--token=ghp_acpmcparg12345\"],"
                                + "\"auth\":{\"header\":\"Authorization: Bearer ghp_acpmcpauth12345\"},"
                                + "\"tools\":[{\"name\":\"read_file\"}]}]}}");

        assertThat(created)
                .contains("\"id\":69")
                .contains("\"cwd\":\"path://token=***\"")
                .contains("api_key=***")
                .contains("Bearer ***")
                .doesNotContain("ghp_acpcwd12345")
                .doesNotContain("sk-test-acpmcp12345")
                .doesNotContain("ghp_acpmcparg12345")
                .doesNotContain("ghp_acpmcpauth12345");

        String sessionId = extractSessionId(created);
        String config =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":70,\"method\":\"session/set_config_option\","
                                + "\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"config_id\":\"runtime.token\","
                                + "\"value\":{\"access_token\":\"ghp_acpconfig12345\","
                                + "\"callback\":\"https://example.test/callback?api%255Fkey=acp-config-encoded-secret\","
                                + "\"nested\":{\"api_key\":\"sk-test-acpconfig12345\"}}}}");

        assertThat(config)
                .contains("\"id\":70")
                .contains("\"access_token\":\"***\"")
                .contains("api%255Fkey=***")
                .contains("\"api_key\":\"***\"")
                .doesNotContain("ghp_acpconfig12345")
                .doesNotContain("sk-test-acpconfig12345")
                .doesNotContain("acp-config-encoded-secret");

        String listed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":71,\"method\":\"session/list\","
                                + "\"params\":{\"cwd\":\"D:/workspace/token=ghp_acpcwd12345\"}}");
        assertThat(listed)
                .contains("\"id\":71")
                .contains(sessionId)
                .contains("\"cwd\":\"path://token=***\"")
                .doesNotContain("ghp_acpcwd12345")
                .doesNotContain("sk-test-acpmcp12345")
                .doesNotContain("ghp_acpmcparg12345")
                .doesNotContain("ghp_acpmcpauth12345")
                .doesNotContain("ghp_acpconfig12345")
                .doesNotContain("sk-test-acpconfig12345")
                .doesNotContain("acp-config-encoded-secret");
    }

    @Test
    void shouldIsolateDangerousApprovalBetweenAcpSessions() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String sessionAId = extractSessionId(newAcpSession(server, 20));
        String sessionBId = extractSessionId(newAcpSession(server, 21));
        SessionRecord sessionA = env.sessionRepository.findById(sessionAId);
        SessionRecord sessionB = env.sessionRepository.findById(sessionBId);
        SqliteAgentSession agentSessionA =
                new SqliteAgentSession(sessionA, env.sessionRepository);
        SqliteAgentSession agentSessionB =
                new SqliteAgentSession(sessionB, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSessionA,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");

        String bApproval =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"session/prompt\",\"params\":{\"session_id\":\""
                                + sessionBId
                                + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"/approve session\"}]}}");

        assertThat(bApproval).contains("\"id\":22").contains("待审批的危险命令");
        assertThat(bApproval).doesNotContain("echo:resume");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(agentSessionA))
                .isNotNull();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                agentSessionB, "recursive_delete"))
                .isFalse();

        String aApproval =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":23,\"method\":\"session/prompt\",\"params\":{\"session_id\":\""
                                + sessionAId
                                + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"/approve session\"}]}}");
        SessionRecord updatedA = env.sessionRepository.findById(sessionAId);
        SessionRecord updatedB = env.sessionRepository.findById(sessionBId);
        SqliteAgentSession updatedAgentSessionA =
                new SqliteAgentSession(updatedA, env.sessionRepository);
        SqliteAgentSession updatedAgentSessionB =
                new SqliteAgentSession(updatedB, env.sessionRepository);

        assertThat(aApproval).contains("\"id\":23").contains("echo:resume");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(updatedAgentSessionA))
                .isNull();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSessionA, "recursive_delete"))
                .isTrue();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSessionB, "recursive_delete"))
                .isFalse();
    }

    @Test
    void shouldExposeAndResolveDangerousApprovalThroughAcpPermissionsBridge()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        env.dangerousCommandApprovalService);

        String sessionId = extractSessionId(newAcpSession(server, 24));
        SessionRecord session = env.sessionRepository.findById(sessionId);
        SqliteAgentSession agentSession =
                new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete with token=sk-proj-abcdefghijklmnopqrstuvwxyz",
                "OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuvwxyz rm -rf runtime/cache");

        String listed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"permissions/list_open\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\"}}");

        assertThat(listed)
                .contains("\"id\":25")
                .contains("\"count\":1")
                .contains("\"allow_once\"")
                .contains("\"allow_session\"")
                .contains("\"allow_always\"")
                .contains("\"created_at\"")
                .contains("\"createdAt\"")
                .contains("\"expires_in_seconds\"")
                .contains("\"expiresInSeconds\"")
                .contains("\"expired\":false")
                .contains("\"scope_options\":[\"once\",\"session\",\"always\"]")
                .contains("\"scopeOptions\":[\"once\",\"session\",\"always\"]")
                .contains("\"permanent_allowed\":true")
                .contains("\"permanentAllowed\":true")
                .contains("OPENAI_API_KEY=***")
                .contains("token=***")
                .contains("\"approval_key\":\"execute_shell:recursive_delete:***\"")
                .contains("\"approvalKey\":\"execute_shell:recursive_delete:***\"")
                .doesNotContain("execute_shell:recursive_delete:97c852eaef0753db")
                .doesNotContain("sk-proj-abcdefghijklmnopqrstuvwxyz");
        String approvalId = extractJsonString(listed, "approval_id");

        String responded =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":26,\"method\":\"permissions/respond\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"id\":\""
                                + approvalId
                                + "\",\"outcome\":\"allow_session\"}}");

        SessionRecord updated = env.sessionRepository.findById(sessionId);
        SqliteAgentSession updatedAgentSession =
                new SqliteAgentSession(updated, env.sessionRepository);

        assertThat(responded)
                .contains("\"id\":26")
                .contains("\"ok\":true")
                .contains("\"outcome\":\"session\"")
                .contains("echo:resume");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(updatedAgentSession))
                .isNull();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession, "recursive_delete"))
                .isTrue();
    }

    @Test
    void shouldRedactEncodedSecretsFromAcpPermissionsBridge() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        env.dangerousCommandApprovalService);

        String sessionId = extractSessionId(newAcpSession(server, 125));
        SessionRecord session = env.sessionRepository.findById(sessionId);
        SqliteAgentSession agentSession =
                new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "url_policy?api%255Fkey=acp-encoded-secret",
                "encoded ACP https://example.test/callback?api%255Fkey=acp-encoded-secret",
                "curl https://example.test/callback?api%255Fkey=acp-encoded-secret");

        String listed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":126,\"method\":\"permissions/list_open\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\"}}");

        assertThat(listed)
                .contains("\"id\":126")
                .contains("\"count\":1")
                .contains("\"command\":\"curl https://example.test/callback?api%255Fkey=***\"")
                .contains("\"description\":\"encoded ACP https://example.test/callback?api%255Fkey=***\"")
                .contains("\"pattern_key\":\"url_policy?api%255Fkey=***\"")
                .contains("\"patternKey\":\"url_policy?api%255Fkey=***\"")
                .contains("\"approval_key\":\"execute_shell:***\"")
                .contains("\"approvalKey\":\"execute_shell:***\"")
                .doesNotContain("acp-encoded-secret");
    }

    @Test
    void shouldRedactEncodedSecretsFromAcpPermissionResponse() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        env.dangerousCommandApprovalService);

        String sessionId = extractSessionId(newAcpSession(server, 127));
        String responded =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":128,\"method\":\"permissions/respond\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"id\":\"url_policy?api%255Fkey=acp-response-secret\",\"outcome\":\"deny\"}}");

        assertThat(responded)
                .contains("\"id\":128")
                .contains("\"ok\":false")
                .contains("\"id\":\"__invalid_selector__\"")
                .doesNotContain("url_policy")
                .doesNotContain("acp-response-secret");
    }

    @Test
    void shouldRejectUnsafeAcpPermissionSelectorsBeforeSlashApproval() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        env.dangerousCommandApprovalService);

        String sessionId = extractSessionId(newAcpSession(server, 129));
        SessionRecord session = env.sessionRepository.findById(sessionId);
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");

        String responded =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":130,\"method\":\"permissions/respond\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"id\":\"approval-123;always\",\"outcome\":\"allow_always\"}}");

        SessionRecord updated = env.sessionRepository.findById(sessionId);
        SqliteAgentSession updatedAgentSession =
                new SqliteAgentSession(updated, env.sessionRepository);
        assertThat(responded)
                .contains("\"id\":130")
                .contains("\"ok\":false")
                .contains("\"id\":\"__invalid_selector__\"")
                .contains("当前没有待审批的危险命令")
                .doesNotContain("approval-123;always");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(updatedAgentSession))
                .isNotNull();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession, "recursive_delete"))
                .isFalse();
        assertThat(env.dangerousCommandApprovalService.isAlwaysApproved("recursive_delete"))
                .isFalse();
    }

    @Test
    void shouldUseSafeAcpPermissionSelectorForUnsafeStoredApprovalId() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        env.dangerousCommandApprovalService);

        String sessionId = extractSessionId(newAcpSession(server, 131));
        SessionRecord session = env.sessionRepository.findById(sessionId);
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "token_ghp_acpunsafeidpattern123",
                "unsafe id token=ghp_acpunsafeiddesc123",
                "rm -rf runtime/cache --token ghp_acpunsafeidcommand123");

        List<Map<String, Object>> queue =
                (List<Map<String, Object>>)
                        agentSession.getContext().get("_dangerous_command_pending_queue_");
        queue.get(0).put("approvalId", "approval-unsafe token=ghp_acpapprovalid123");
        agentSession.getContext().put("_dangerous_command_pending_", queue.get(0));
        agentSession.updateSnapshot();

        String listed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":132,\"method\":\"permissions/list_open\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\"}}");

        assertThat(listed)
                .contains("\"id\":132")
                .contains("\"count\":1")
                .contains("\"approval_id\":\"key_")
                .contains("\"approvalId\":\"key_")
                .contains("\"id\":\"key_")
                .contains("token_ghp_***")
                .contains("token=***")
                .doesNotContain("approval-unsafe")
                .doesNotContain("ghp_acpapprovalid123")
                .doesNotContain("ghp_acpunsafeidpattern123")
                .doesNotContain("ghp_acpunsafeiddesc123")
                .doesNotContain("ghp_acpunsafeidcommand123");
        String approvalId = extractJsonString(listed, "approval_id");

        String denied =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":133,\"method\":\"permissions/respond\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"approval_id\":\""
                                + approvalId
                                + "\",\"outcome\":\"deny\"}}");

        SessionRecord updated = env.sessionRepository.findById(sessionId);
        SqliteAgentSession updatedAgentSession =
                new SqliteAgentSession(updated, env.sessionRepository);

        assertThat(denied)
                .contains("\"id\":133")
                .contains("\"ok\":true")
                .contains("\"id\":\"" + approvalId + "\"")
                .contains("\"outcome\":\"deny\"")
                .doesNotContain("approval-unsafe")
                .doesNotContain("ghp_acpapprovalid123");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(updatedAgentSession))
                .isNull();
    }

    @Test
    void shouldDenyDangerousApprovalThroughAcpPermissionsBridge() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        env.dangerousCommandApprovalService);

        String sessionId = extractSessionId(newAcpSession(server, 27));
        SessionRecord session = env.sessionRepository.findById(sessionId);
        SqliteAgentSession agentSession =
                new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell\u202E",
                "token_ghp_acppattern123\u202E",
                "recursive token=ghp_acpdesc123\u202E",
                "rm -rf runtime/cache --token ghp_acpcommand123\u202E");

        String listed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":28,\"method\":\"permissions_list_open\",\"params\":{\"sessionId\":\""
                                + sessionId.substring(0, 8)
                                + "\u202E"
                                + sessionId.substring(8)
                                + "\"}}");
        assertThat(listed)
                .contains("\"tool_name\":\"execute_shell\"")
                .contains("token_ghp_***")
                .contains("token=***")
                .doesNotContain("\\u202E")
                .doesNotContain("acppattern123")
                .doesNotContain("ghp_acpdesc123")
                .doesNotContain("ghp_acpcommand123");
        String approvalId = extractJsonString(listed, "approval_id");
        String disguisedApprovalId =
                approvalId.substring(0, 8) + "\u202E" + approvalId.substring(8);

        String denied =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":29,\"method\":\"permissions_respond\",\"params\":{\"sessionId\":\""
                                + sessionId.substring(0, 8)
                                + "\u202E"
                                + sessionId.substring(8)
                                + "\",\"permission_id\":\""
                                + disguisedApprovalId
                                + "\",\"outcome\":\"reject_once\"}}");

        SessionRecord updated = env.sessionRepository.findById(sessionId);
        SqliteAgentSession updatedAgentSession =
                new SqliteAgentSession(updated, env.sessionRepository);

        assertThat(denied)
                .contains("\"id\":29")
                .contains("\"ok\":true")
                .contains("\"outcome\":\"deny\"");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(updatedAgentSession))
                .isNull();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession, "recursive_delete"))
                .isFalse();
    }

    @Test
    void shouldAcceptAcpPermissionOptionIdOutcomeShape() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        env.dangerousCommandApprovalService);

        String sessionId = extractSessionId(newAcpSession(server, 42));
        SessionRecord session = env.sessionRepository.findById(sessionId);
        SqliteAgentSession agentSession =
                new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");

        String listed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":43,\"method\":\"permissions/list_open\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\"}}");
        String approvalId = extractJsonString(listed, "approval_id");

        String allowed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":44,\"method\":\"permissions/respond\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"id\":\""
                                + approvalId
                                + "\",\"outcome\":\"selected\",\"option_id\":\"allow_always\"}}");

        SessionRecord updated = env.sessionRepository.findById(sessionId);
        SqliteAgentSession updatedAgentSession =
                new SqliteAgentSession(updated, env.sessionRepository);

        assertThat(allowed)
                .contains("\"id\":44")
                .contains("\"ok\":true")
                .contains("\"outcome\":\"always\"")
                .contains("echo:resume");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(updatedAgentSession))
                .isNull();
        assertThat(env.dangerousCommandApprovalService.isAlwaysApproved("recursive_delete"))
                .isTrue();
    }

    @Test
    void shouldFallbackSelectedUnknownAcpPermissionOptionToAllowOnce() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        env.dangerousCommandApprovalService);

        String sessionId = extractSessionId(newAcpSession(server, 45));
        SessionRecord session = env.sessionRepository.findById(sessionId);
        SqliteAgentSession agentSession =
                new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");

        String listed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":46,\"method\":\"permissions/list_open\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\"}}");
        String approvalId = extractJsonString(listed, "approval_id");

        String allowed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":47,\"method\":\"permissions/respond\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"id\":\""
                                + approvalId
                                + "\",\"outcome\":\"selected\",\"option_id\":\"custom_future_allow\"}}");

        SessionRecord updated = env.sessionRepository.findById(sessionId);
        SqliteAgentSession updatedAgentSession =
                new SqliteAgentSession(updated, env.sessionRepository);

        assertThat(allowed)
                .contains("\"id\":47")
                .contains("\"ok\":true")
                .contains("\"outcome\":\"once\"")
                .contains("echo:resume");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(updatedAgentSession))
                .isNull();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession, "recursive_delete"))
                .isFalse();
        assertThat(env.dangerousCommandApprovalService.isAlwaysApproved("recursive_delete"))
                .isFalse();
    }

    @Test
    void shouldKeepRequestIdOnDispatchError() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String response =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"bad-1\",\"method\":\"unknown\",\"params\":{}}");
        assertThat(response)
                .contains("\"id\":\"bad-1\"")
                .contains("\"error\"")
                .contains("Unsupported ACP method");
    }

    @Test
    void shouldReadAndWriteContentLengthFrames() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String request =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        byte[] payload = request.getBytes(StandardCharsets.UTF_8);
        String framed = "Content-Length: " + payload.length + "\r\n\r\n" + request;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        new ByteArrayInputStream(framed.getBytes(StandardCharsets.UTF_8)),
                        output);

        assertThat(server.run()).isZero();
        String response = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertThat(response).startsWith("Content-Length: ");
        assertThat(response).contains("\"id\":1").contains("\"agent\"");
    }

    @Test
    void shouldLoadAndListPersistedRepositorySessions() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord record = env.sessionRepository.bindNewSession("MEMORY:cli:persisted-acp");
        record.setTitle("Persisted ACP Session");
        record.setNdjson(
                MessageSupport.toNdjson(
                        java.util.Arrays.asList(
                                ChatMessage.ofUser("persisted user"),
                                ChatMessage.ofAssistant("persisted assistant"))));
        record.setUpdatedAt(System.currentTimeMillis());
        env.sessionRepository.save(record);

        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String loaded =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"session/load\",\"params\":{\"session_id\":\""
                                + record.getSessionId()
                                + "\",\"cwd\":\"D:/projects/jimuqu-agent\"}}");
        assertThat(loaded)
                .contains("\"id\":8")
                .contains(record.getSessionId())
                .contains("Persisted ACP Session")
                .contains("\"session_updates\"")
                .contains("\"available_commands_update\"")
                .contains("\"user_message_chunk\"")
                .contains("\"agent_message_chunk\"")
                .contains("persisted user")
                .contains("persisted assistant");

        String listed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"session/list\",\"params\":{}}");
        assertThat(listed)
                .contains("\"id\":9")
                .contains(record.getSessionId())
                .doesNotContain("\"session_updates\"");
    }

    @Test
    void shouldRedactSecretsFromPersistedAcpMessageHistoryReplay() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord record = env.sessionRepository.bindNewSession("MEMORY:cli:persisted-acp-secret-history");
        record.setTitle("Persisted ACP Secret History");
        record.setNdjson(
                MessageSupport.toNdjson(
                        java.util.Arrays.asList(
                                ChatMessage.ofUser(
                                        "user api_key=sk-test-acpuserhistory12345 https://example.test/callback?api%255Fkey=acp-history-encoded-secret"),
                                ChatMessage.ofAssistant(
                                        "assistant bearer ghp_acpassistanthistory12345 https://example.test/callback?api%255Fkey=acp-history-encoded-secret"))));
        env.sessionRepository.save(record);

        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String loaded =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":75,\"method\":\"session/load\",\"params\":{\"session_id\":\""
                                + record.getSessionId()
                                + "\",\"cwd\":\"D:/projects/jimuqu-agent\"}}");

        assertThat(loaded)
                .contains("\"id\":75")
                .contains("\"user_message_chunk\"")
                .contains("\"agent_message_chunk\"")
                .contains("api_key=***")
                .contains("api%255Fkey=***")
                .contains("bearer ***")
                .doesNotContain("sk-test-acpuserhistory12345")
                .doesNotContain("ghp_acpassistanthistory12345")
                .doesNotContain("acp-history-encoded-secret");
    }

    @Test
    void shouldPageAcpSessionListWithCursorAndLimit() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String firstId = extractSessionId(newAcpSession(server, 70));
        String secondId = extractSessionId(newAcpSession(server, 71));
        String thirdId = extractSessionId(newAcpSession(server, 72));

        String firstPage =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":73,\"method\":\"session/list\",\"params\":{\"limit\":2}}");
        ONode firstPageResult = ONode.ofJson(firstPage).get("result");
        String firstPageSessionId = firstPageResult.get("sessions").get(0).get("session_id").getString();
        String nextCursor = firstPageResult.get("next_cursor").getString();
        assertThat(firstPage)
                .contains("\"id\":73")
                .contains("\"next_cursor\":\"" + nextCursor + "\"")
                .contains("\"nextCursor\":\"" + nextCursor + "\"");
        assertThat(firstPageResult.get("sessions").size()).isEqualTo(2);
        assertThat(nextCursor).isNotBlank();
        assertThat(Arrays.asList(firstId, secondId, thirdId)).contains(firstPageSessionId, nextCursor);

        String secondPage =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":74,\"method\":\"session/list\",\"params\":{\"limit\":2,\"cursor\":\""
                                + nextCursor
                                + "\"}}");
        ONode secondPageResult = ONode.ofJson(secondPage).get("result");
        String secondPageSessionId = secondPageResult.get("sessions").get(0).get("session_id").getString();
        assertThat(secondPage)
                .contains("\"id\":74")
                .doesNotContain("\"next_cursor\":\"")
                .doesNotContain("\"nextCursor\":\"");
        assertThat(secondPageResult.get("sessions").size()).isEqualTo(1);
        assertThat(secondPageSessionId)
                .isIn(firstId, secondId, thirdId)
                .isNotEqualTo(firstPageSessionId)
                .isNotEqualTo(nextCursor);
    }

    @Test
    void shouldReplayPersistedAcpToolCallHistory() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord record = env.sessionRepository.bindNewSession("MEMORY:cli:persisted-acp-tools");
        record.setTitle("Persisted ACP Tool Session");
        record.setNdjson(
                MessageSupport.toNdjson(
                        java.util.Arrays.asList(
                                ChatMessage.ofUser("need a file"),
                                assistantWithToolCall("call_1", "read_file", "path", "README.md"),
                                ChatMessage.ofTool("file content", "read_file", "call_1"),
                                ChatMessage.ofAssistant("done"))));
        env.sessionRepository.save(record);

        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String loaded =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"session/load\",\"params\":{\"session_id\":\""
                                + record.getSessionId()
                                + "\",\"cwd\":\"D:/projects/jimuqu-agent\"}}");

        assertThat(loaded)
                .contains("\"id\":10")
                .contains("\"session_updates\"")
                .contains("\"tool_call_start\"")
                .contains("\"tool_call_update\"")
                .contains("\"tool_call_id\":\"call_1\"")
                .contains("\"toolName\":\"read_file\"")
                .contains("\"title\":\"read_file: path://README.md\"")
                .contains("\"path\":\"README.md\"")
                .contains("file content")
                .contains("\"agent_message_chunk\"")
                .contains("done");
    }

    @Test
    void shouldRedactSecretsFromPersistedAcpToolCallHistory() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord record = env.sessionRepository.bindNewSession("MEMORY:cli:persisted-acp-secret-tools");
        record.setTitle("Persisted ACP Secret Tool Session");
        record.setNdjson(
                MessageSupport.toNdjson(
                        java.util.Arrays.asList(
                                ChatMessage.ofUser("need a secret command"),
                                assistantWithToolCall(
                                        "call_1",
                                        "terminal",
                                        "command",
                                        "curl https://example.test?api_key=sk-1234567890abcdef https://example.test/callback?api%255Fkey=acp-tool-encoded-secret"),
                                ChatMessage.ofTool(
                                        "bearer sk-abcdef1234567890 https://example.test/callback?api%255Fkey=acp-tool-encoded-secret",
                                        "terminal",
                                        "call_1"),
                                ChatMessage.ofAssistant("done"))));
        env.sessionRepository.save(record);

        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String loaded =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"session/load\",\"params\":{\"session_id\":\""
                                + record.getSessionId()
                                + "\",\"cwd\":\"D:/projects/jimuqu-agent\"}}");

        assertThat(loaded)
                .contains("\"id\":11")
                .contains("\"tool_call_start\"")
                .contains("\"tool_call_update\"")
                .contains("api_key=***")
                .contains("api%255Fkey=***")
                .contains("bearer ***")
                .doesNotContain("sk-1234567890abcdef")
                .doesNotContain("sk-abcdef1234567890")
                .doesNotContain("acp-tool-encoded-secret");
    }

    @Test
    void shouldUsePathReferenceForPersistedAcpToolTitles() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord record = env.sessionRepository.bindNewSession("MEMORY:cli:persisted-acp-tool-path");
        record.setNdjson(
                MessageSupport.toNdjson(
                        java.util.Arrays.asList(
                                ChatMessage.ofUser("need a file"),
                                assistantWithToolCall(
                                        "call_path",
                                        "read_file",
                                        "path",
                                        "D:/workspace/token=ghp_acptoolpath12345/report.md"),
                                ChatMessage.ofTool("file content", "read_file", "call_path"))));
        env.sessionRepository.save(record);

        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String loaded =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"session/load\",\"params\":{\"session_id\":\""
                                + record.getSessionId()
                                + "\",\"cwd\":\"D:/projects/jimuqu-agent\"}}");

        assertThat(loaded)
                .contains("\"title\":\"read_file: path://report.md\"")
                .contains("\"path\":\"[REDACTED_PATH]\"")
                .doesNotContain("ghp_acptoolpath12345");
    }


    private String newAcpSession(AcpStdioServer server, int id) {
        return server.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":"
                        + id
                        + ",\"method\":\"session/new\",\"params\":{\"cwd\":\"D:/projects/jimuqu-agent\"}}");
    }

    private String prompt(AcpStdioServer server, int id, String sessionId, String promptJson) {
        return server.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":"
                        + id
                        + ",\"method\":\"session/prompt\",\"params\":{\"session_id\":\""
                        + sessionId
                        + "\",\"prompt\":"
                        + promptJson
                        + "}}");
    }

    private String extractSessionId(String json) {
        return extractJsonString(json, "session_id");
    }

    private String extractJsonString(String json, String key) {
        String marker = "\"session_id\":\"";
        if (!"session_id".equals(key)) {
            marker = "\"" + key + "\":\"";
        }
        int start = json.indexOf(marker);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        assertThat(end).isGreaterThan(valueStart);
        return json.substring(valueStart, end);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static byte[] hex(String value) {
        int len = value.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] =
                    (byte)
                            ((Character.digit(value.charAt(i), 16) << 4)
                                    + Character.digit(value.charAt(i + 1), 16));
        }
        return data;
    }

    private static AssistantMessage assistantWithToolCall(
            String id, String name, String argKey, String argValue) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put(argKey, argValue);
        return new AssistantMessage(
                "",
                false,
                null,
                null,
                Collections.singletonList(new ToolCall("0", id, name, null, args)),
                null);
    }

    private static class ModelEchoGateway extends FakeLlmGateway {
        @Override
        public LlmResult executeOnce(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                java.util.List<Object> toolObjects,
                ConversationFeedbackSink feedbackSink,
                ConversationEventSink eventSink,
                boolean resume,
                AppConfig.LlmConfig resolved,
                AgentRunContext runContext)
                throws Exception {
            String response = "model=" + resolved.getModel();
            InMemoryChatSession chatSession = new InMemoryChatSession(session.getSessionId());
            if (session.getNdjson() != null && session.getNdjson().length() > 0) {
                chatSession.loadNdjson(session.getNdjson());
            }
            chatSession.addMessage(ChatMessage.ofUser(userMessage));
            chatSession.addMessage(ChatMessage.ofAssistant(response));

            LlmResult result = new LlmResult();
            result.setAssistantMessage(new AssistantMessage(response));
            result.setNdjson(chatSession.toNdjson());
            result.setRawResponse("fake");
            result.setProvider(resolved.getProvider());
            result.setModel(resolved.getModel());
            result.setInputTokens(1L);
            result.setOutputTokens(1L);
            result.setTotalTokens(2L);
            return result;
        }
    }

    private static class ToolEventGateway extends FakeLlmGateway {
        @Override
        public LlmResult executeOnce(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                java.util.List<Object> toolObjects,
                ConversationFeedbackSink feedbackSink,
                ConversationEventSink eventSink,
                boolean resume,
                AppConfig.LlmConfig resolved,
                AgentRunContext runContext)
                throws Exception {
            java.util.Map<String, Object> args = new java.util.LinkedHashMap<String, Object>();
            args.put("command", "echo updates");
            eventSink.onReasoningDelta("thinking");
            eventSink.onToolStarted("terminal", args);
            eventSink.onToolCompleted("terminal", "{\"ok\":true}", 12L);

            LlmResult result = super.executeOnce(
                    session,
                    systemPrompt,
                    userMessage,
                    toolObjects,
                    feedbackSink,
                    eventSink,
                    resume,
                    resolved,
                    runContext);
            session.setLastInputTokens(33L);
            session.setLastOutputTokens(44L);
            session.setLastTotalTokens(77L);
            session.setLastReasoningTokens(5L);
            session.setLastCacheReadTokens(6L);
            session.setLastCacheWriteTokens(7L);
            return result;
        }
    }

    private static class SecretToolEventGateway extends FakeLlmGateway {
        @Override
        public LlmResult executeOnce(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                java.util.List<Object> toolObjects,
                ConversationFeedbackSink feedbackSink,
                ConversationEventSink eventSink,
                boolean resume,
                AppConfig.LlmConfig resolved,
                AgentRunContext runContext)
                throws Exception {
            java.util.Map<String, Object> args = new java.util.LinkedHashMap<String, Object>();
            args.put("command", "curl https://example.test?api_key=sk-1234567890abcdef");
            java.util.Map<String, Object> nested = new java.util.LinkedHashMap<String, Object>();
            nested.put("token", "sk-abcdef1234567890");
            args.put("metadata", nested);
            eventSink.onToolStarted("terminal", args);
            eventSink.onToolCompleted("terminal", "bearer sk-1234567890abcdef", 12L);
            return super.executeOnce(
                    session,
                    systemPrompt,
                    userMessage,
                    toolObjects,
                    feedbackSink,
                    eventSink,
                    resume,
                    resolved,
                    runContext);
        }
    }

    private static class AcpAttachmentGateway extends FakeLlmGateway {
        private java.util.List<MessageAttachment> lastAttachments =
                java.util.Collections.emptyList();

        @Override
        public LlmResult executeOnce(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                java.util.List<Object> toolObjects,
                ConversationFeedbackSink feedbackSink,
                ConversationEventSink eventSink,
                boolean resume,
                AppConfig.LlmConfig resolved,
                AgentRunContext runContext)
                throws Exception {
            lastAttachments =
                    runContext == null
                            ? java.util.Collections.<MessageAttachment>emptyList()
                            : runContext.getUserAttachments();
            return super.executeOnce(
                    session,
                    systemPrompt,
                    userMessage,
                    toolObjects,
                    feedbackSink,
                    eventSink,
                    resume,
                    resolved,
                    runContext);
        }
    }
}
