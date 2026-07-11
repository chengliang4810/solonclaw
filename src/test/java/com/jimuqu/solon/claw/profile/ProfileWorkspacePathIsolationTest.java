package com.jimuqu.solon.claw.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.engine.DefaultConversationOrchestrator;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.AttachmentPathResolver;
import com.jimuqu.solon.claw.support.MediaDirectiveSupport;
import com.jimuqu.solon.claw.tool.runtime.MessagingTools;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证相对工作区、附件和媒体路径不会从命名 Profile 回退到全局进程目录。 */
class ProfileWorkspacePathIsolationTest {
    /** 每个测试独占的文件系统根目录。 */
    @TempDir Path tempDir;

    /** Agent 运行服务缺失时，fallback 工作区仍应优先使用当前 Profile home。 */
    @Test
    void conversationFallbackUsesCurrentProfileHomeAndKeepsUnscopedDefault() throws Exception {
        Path profileHome = Files.createDirectories(tempDir.resolve("profiles/work"));
        Path processHome = Files.createDirectories(tempDir.resolve("process"));
        DefaultConversationOrchestrator orchestrator = emptyOrchestrator();

        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        "work", profileHome, Collections.<String, String>emptyMap(), null)) {
            assertThat(resolveAgentScope(orchestrator).getWorkspaceDir())
                    .isEqualTo(profileHome.toAbsolutePath().normalize().toString());
        }

        String previousUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", processHome.toString());
            assertThat(resolveAgentScope(orchestrator).getWorkspaceDir())
                    .isEqualTo(processHome.toString());
        } finally {
            restoreProperty("user.dir", previousUserDir);
        }
    }

    /** 相同相对附件名在两个 Profile 中必须读取各自文件，不能命中全局 cwd。 */
    @Test
    void messagingAttachmentsResolveWithinEachProfileAndNeverFallbackWhileScoped()
            throws Exception {
        Path profileA = profileHome("a");
        Path profileB = profileHome("b");
        Path processHome = Files.createDirectories(tempDir.resolve("process"));
        Files.writeString(profileA.resolve("shared.png"), "profile-a");
        Files.writeString(profileB.resolve("shared.png"), "profile-b");
        Files.writeString(processHome.resolve("shared.png"), "global");
        Files.writeString(processHome.resolve("global-only.png"), "global-only");
        Files.createDirectories(profileA.resolve("nested"));
        try {
            Files.createSymbolicLink(
                    profileA.resolve("nested/escape.png"), processHome.resolve("global-only.png"));
        } catch (UnsupportedOperationException ignored) {
            // 文件系统不支持符号链接时，仍由 `..` 路径覆盖越界拒绝。
        } catch (java.io.IOException ignored) {
            // Windows 未启用符号链接权限时跳过链接分支。
        }

        CapturingDeliveryService deliveryA = new CapturingDeliveryService();
        CapturingDeliveryService deliveryB = new CapturingDeliveryService();
        AppConfig configA = profileConfig(profileA);
        AppConfig configB = profileConfig(profileB);
        MessagingTools toolsA = messagingTools(deliveryA, configA);
        MessagingTools toolsB = messagingTools(deliveryB, configB);
        String previousUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", processHome.toString());
            try (ProfileRuntimeScope.Scope ignored =
                    ProfileRuntimeScope.open(
                            "a", profileA, Collections.<String, String>emptyMap(), null)) {
                toolsA.sendMessage(
                        null, null, "send-a", Collections.singletonList("shared.png"), null);
                assertThat(deliveredAttachmentText(deliveryA)).isEqualTo("profile-a");
                assertThatThrownBy(
                                () ->
                                        toolsA.sendMessage(
                                                null,
                                                null,
                                                "must-not-leak",
                                                Collections.singletonList("global-only.png"),
                                                null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("does not exist");
                assertThatThrownBy(
                                () ->
                                        toolsA.sendMessage(
                                                null,
                                                null,
                                                "must-not-traverse",
                                                Collections.singletonList(
                                                        "../../process/global-only.png"),
                                                null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("escapes the current profile workspace");
                if (Files.isSymbolicLink(profileA.resolve("nested/escape.png"))) {
                    assertThatThrownBy(
                                    () ->
                                            toolsA.sendMessage(
                                                    null,
                                                    null,
                                                    "must-not-follow-link",
                                                    Collections.singletonList("nested/escape.png"),
                                                    null))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("escapes the current profile workspace");
                }
            }
            try (ProfileRuntimeScope.Scope ignored =
                    ProfileRuntimeScope.open(
                            "b", profileB, Collections.<String, String>emptyMap(), null)) {
                toolsB.sendMessage(
                        null, null, "send-b", Collections.singletonList("shared.png"), null);
                assertThat(deliveredAttachmentText(deliveryB)).isEqualTo("profile-b");
            }
        } finally {
            restoreProperty("user.dir", previousUserDir);
        }
    }

    /** 粘贴的相对附件路径按 Profile 隔离，同时 `~` 仍指向真实 OS 用户目录。 */
    @Test
    void pastedAttachmentPathsUseProfileHomeButKeepOsUserHomeForTilde() throws Exception {
        Path profileA = profileHome("a");
        Path profileB = profileHome("b");
        Path osHome = Files.createDirectories(tempDir.resolve("os-home"));
        Files.writeString(profileA.resolve("notes.txt"), "notes-a");
        Files.writeString(profileB.resolve("notes.txt"), "notes-b");
        Files.writeString(osHome.resolve("avatar.png"), "os-avatar");
        Files.writeString(osHome.resolve("only-os.txt"), "only-os");

        String previousUserHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", osHome.toString());
            assertThat(resolvePastedAttachment(profileA, "a", "\"notes.txt\""))
                    .isEqualTo("notes-a");
            assertThat(resolvePastedAttachment(profileB, "b", "\"notes.txt\""))
                    .isEqualTo("notes-b");
            assertThat(resolvePastedAttachment(profileA, "a", "\"~/avatar.png\""))
                    .isEqualTo("os-avatar");
            assertThat(resolvePastedAttachments(profileA, "a", "\"only-os.txt\"")).isEmpty();
        } finally {
            restoreProperty("user.home", previousUserHome);
        }
    }

    /** MEDIA 相对路径应绑定当前 Profile；未进入 scope 与 `~` 的既有语义保持不变。 */
    @Test
    void mediaDirectiveUsesProfileHomeWithoutRedefiningTilde() throws Exception {
        Path profileA = profileHome("a");
        Path profileB = profileHome("b");
        Path osHome = Files.createDirectories(tempDir.resolve("os-home"));
        String previousUserHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", osHome.toString());
            assertThat(scopedMediaPath("a", profileA, "MEDIA:report.pdf"))
                    .isEqualTo(profileA.resolve("report.pdf").toString());
            assertThat(scopedMediaPath("b", profileB, "MEDIA:report.pdf"))
                    .isEqualTo(profileB.resolve("report.pdf").toString());
            assertThat(scopedMediaPath("a", profileA, "MEDIA:~/voice.mp3"))
                    .isEqualTo(osHome.resolve("voice.mp3").toFile().getAbsolutePath());
            assertThat(MediaDirectiveSupport.parse("MEDIA:report.pdf").get(0).getPath())
                    .isEqualTo("report.pdf");
        } finally {
            restoreProperty("user.home", previousUserHome);
        }
    }

    /** 创建仅用于 fallback 范围解析的空编排器。 */
    private DefaultConversationOrchestrator emptyOrchestrator() {
        return new DefaultConversationOrchestrator(
                null, null, null, null, null, null, null, null, null, null, null);
    }

    /** 通过私有入口读取本轮 Agent fallback 范围，避免启动完整会话链。 */
    private AgentRuntimeScope resolveAgentScope(DefaultConversationOrchestrator orchestrator)
            throws Exception {
        Method method =
                DefaultConversationOrchestrator.class.getDeclaredMethod(
                        "resolveAgentScope", SessionRecord.class);
        method.setAccessible(true);
        return (AgentRuntimeScope) method.invoke(orchestrator, new SessionRecord());
    }

    /** 创建具备独立 home/cache 的 Profile 配置。 */
    private AppConfig profileConfig(Path home) throws Exception {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.toString());
        config.getRuntime().setCacheDir(Files.createDirectories(home.resolve("cache")).toString());
        config.getWorkspace().setDir(home.toString());
        return config;
    }

    /** 创建消息工具并绑定与配置一致的附件缓存。 */
    private MessagingTools messagingTools(CapturingDeliveryService delivery, AppConfig config) {
        return new MessagingTools(
                delivery, "MEMORY:room:user", new AttachmentCacheService(config), config);
    }

    /** 读取最近投递附件缓存中的文本，用于确认实际来源 Profile。 */
    private String deliveredAttachmentText(CapturingDeliveryService delivery) throws Exception {
        assertThat(delivery.lastRequest).isNotNull();
        assertThat(delivery.lastRequest.getAttachments()).hasSize(1);
        return Files.readString(
                Path.of(delivery.lastRequest.getAttachments().get(0).getLocalPath()));
    }

    /** 在指定 Profile scope 中解析粘贴附件并读取缓存内容。 */
    private String resolvePastedAttachment(Path profileHome, String profile, String input)
            throws Exception {
        AppConfig config = profileConfig(profileHome);
        AttachmentPathResolver resolver =
                new AttachmentPathResolver(config, new AttachmentCacheService(config));
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        profile, profileHome, Collections.<String, String>emptyMap(), null)) {
            AttachmentPathResolver.ResolvedInput resolved = resolver.resolve(input);
            assertThat(resolved.getAttachments()).hasSize(1);
            return Files.readString(Path.of(resolved.getAttachments().get(0).getLocalPath()));
        }
    }

    /** 在指定 Profile scope 中解析相对附件，用于确认不会回退 OS 用户目录。 */
    private List<com.jimuqu.solon.claw.core.model.MessageAttachment> resolvePastedAttachments(
            Path profileHome, String profile, String input) throws Exception {
        AppConfig config = profileConfig(profileHome);
        AttachmentPathResolver resolver =
                new AttachmentPathResolver(config, new AttachmentCacheService(config));
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        profile, profileHome, Collections.<String, String>emptyMap(), null)) {
            return resolver.resolve(input).getAttachments();
        }
    }

    /** 在指定 Profile scope 中读取首个 MEDIA 路径。 */
    private String scopedMediaPath(String profile, Path home, String content) {
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        profile, home, Collections.<String, String>emptyMap(), null)) {
            return MediaDirectiveSupport.parse(content).get(0).getPath();
        }
    }

    /** 创建 Profile 工作区与缓存目录。 */
    private Path profileHome(String name) throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("profiles").resolve(name));
        Files.createDirectories(home.resolve("cache"));
        return home.toAbsolutePath().normalize();
    }

    /** 恢复测试临时覆盖的 JVM 属性。 */
    private void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    /** 记录最后一次投递请求，避免测试依赖完整渠道网关。 */
    private static final class CapturingDeliveryService implements DeliveryService {
        /** 最近一次投递请求。 */
        private DeliveryRequest lastRequest;

        /** 保存投递请求。 */
        @Override
        public void deliver(DeliveryRequest request) {
            this.lastRequest = request;
        }

        /** 测试不公开渠道状态。 */
        @Override
        public List<ChannelStatus> statuses() {
            return Collections.emptyList();
        }
    }
}
