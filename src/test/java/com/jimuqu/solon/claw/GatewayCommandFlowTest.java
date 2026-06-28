package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class GatewayCommandFlowTest {
    @Test
    void shouldHandleBasicCommandsAndConversationFlow() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        GatewayReply claimPrompt = env.send("room-1", "user-1", "hello");
        assertThat(claimPrompt.getContent()).contains("/pairing claim-admin");
        GatewayReply claimReply = env.send("room-1", "user-1", "/pairing claim-admin");
        assertThat(claimReply.getContent()).contains("唯一管理员");

        GatewayReply firstReply =
                env.gatewayService.handle(env.message("room-1", "user-1", "hello"));
        assertThat(firstReply.getContent()).contains("echo:hello");
        String firstSessionId = firstReply.getSessionId();

        GatewayReply statusReply =
                env.gatewayService.handle(env.message("room-1", "user-1", "/status"));
        assertThat(statusReply.getContent()).contains(firstSessionId);

        GatewayReply retryReply =
                env.gatewayService.handle(env.message("room-1", "user-1", "/retry"));
        assertThat(retryReply.getContent()).contains("echo:hello");

        GatewayReply branchReply =
                env.gatewayService.handle(env.message("room-1", "user-1", "/branch review"));
        assertThat(branchReply.getContent()).contains("review");

        GatewayReply forkReply =
                env.gatewayService.handle(env.message("room-1", "user-1", "/fork review-fork"));
        assertThat(forkReply.getContent()).contains("review-fork");

        GatewayReply undoReply =
                env.gatewayService.handle(env.message("room-1", "user-1", "/undo"));
        assertThat(undoReply.getContent()).contains("已从会话中移除上一轮对话");

        GatewayReply newReply = env.gatewayService.handle(env.message("room-1", "user-1", "/new"));
        assertThat(newReply.getSessionId()).isNotEqualTo(firstSessionId);

        SessionRecord rebound = env.sessionRepository.getBoundSession("MEMORY:room-1:user-1");
        assertThat(rebound.getSessionId()).isEqualTo(newReply.getSessionId());
    }

    @Test
    void undoEmptySessionReportsNoPreviousTurn() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        GatewayReply claimPrompt = env.send("room-empty-undo", "user-empty-undo", "hello");
        assertThat(claimPrompt.getContent()).contains("/pairing claim-admin");
        GatewayReply claimReply = env.send("room-empty-undo", "user-empty-undo", "/pairing claim-admin");
        assertThat(claimReply.getContent()).contains("唯一管理员");
        GatewayReply newReply = env.send("room-empty-undo", "user-empty-undo", "/new");

        GatewayReply undoReply = env.send("room-empty-undo", "user-empty-undo", "/undo");

        assertThat(undoReply.isError()).isTrue();
        assertThat(undoReply.getSessionId()).isEqualTo(newReply.getSessionId());
        assertThat(undoReply.getContent()).contains("没有可撤销的上一轮对话");
    }

    @Test
    void shouldCreateNamedSessionFromNewCommandArgument() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.send("room-new-title", "user-new-title", "hello");
        env.send("room-new-title", "user-new-title", "/pairing claim-admin");

        GatewayReply newReply = env.send("room-new-title", "user-new-title", "/new  客户项目\r\n复盘  ");
        SessionRecord rebound =
                env.sessionRepository.getBoundSession("MEMORY:room-new-title:user-new-title");

        assertThat(newReply.getSessionId()).isEqualTo(rebound.getSessionId());
        assertThat(newReply.getContent()).contains("客户项目 复盘");
        assertThat(rebound.getTitle()).isEqualTo("客户项目 复盘");
    }

    @Test
    void shouldRenderHelpWithChineseDescriptionsPerLine() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        GatewayReply claimPrompt = env.send("room-help", "user-help", "hello");
        assertThat(claimPrompt.getContent()).contains("/pairing claim-admin");
        GatewayReply claimReply = env.send("room-help", "user-help", "/pairing claim-admin");
        assertThat(claimReply.getContent()).contains("唯一管理员");

        GatewayReply helpReply = env.send("room-help", "user-help", "/help");
        assertThat(helpReply.getContent()).contains("/new - 创建并切换到新会话");
        assertThat(helpReply.getContent()).contains("/help - 显示帮助信息");
        assertThat(helpReply.getContent()).contains("/insights - 查看使用洞察与运行摘要");
        assertThat(helpReply.getContent()).contains("/plugins - 查看插件加载状态");
        assertThat(helpReply.getContent()).contains("/reload-skills - 重新扫描本地技能目录");
        assertThat(Arrays.asList(helpReply.getContent().split("\\R")))
                .isNotEmpty()
                .allMatch(line -> line.startsWith("/") && line.contains(" - "));
    }

    @Test
    void shouldRenderRegistryCommandsInHelpAndResolveAliases() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.send("room-registry", "user-registry", "hello");
        env.send("room-registry", "user-registry", "/pairing claim-admin");

        GatewayReply helpReply = env.send("room-registry", "user-registry", "/help");
        assertThat(helpReply.getContent())
                .contains("/statusbar - 管理 TUI 状态栏显示")
                .doesNotContain("/footer -")
                .doesNotContain("/handoff -")
                .doesNotContain("/subgoal -");

        GatewayReply aliasReply = env.send("room-registry", "user-registry", "/status-bar");
        assertThat(aliasReply.isError()).isTrue();
        assertThat(aliasReply.getRuntimeMetadata())
                .containsEntry("command_status", "registered_unimplemented")
                .containsEntry("command", "statusbar");

        GatewayReply shortAliasReply = env.send("room-registry", "user-registry", "/sb");
        assertThat(shortAliasReply.isError()).isTrue();
        assertThat(shortAliasReply.getRuntimeMetadata())
                .containsEntry("command_status", "registered_unimplemented")
                .containsEntry("command", "statusbar");

        GatewayReply backgroundAliasReply =
                env.send("room-registry", "user-registry", "/bg nightly task");
        assertThat(backgroundAliasReply.isError()).isTrue();
        assertThat(backgroundAliasReply.getRuntimeMetadata())
                .containsEntry("command_status", "registered_unimplemented")
                .containsEntry("command", "background");

        GatewayReply backgroundBtwReply =
                env.send("room-registry", "user-registry", "/btw nightly task");
        assertThat(backgroundBtwReply.isError()).isTrue();
        assertThat(backgroundBtwReply.getRuntimeMetadata())
                .containsEntry("command_status", "registered_unimplemented")
                .containsEntry("command", "background");

        GatewayReply agentsAliasReply = env.send("room-registry", "user-registry", "/agents");
        assertThat(agentsAliasReply.isError()).isTrue();
        assertThat(agentsAliasReply.getRuntimeMetadata())
                .containsEntry("command_status", "registered_unimplemented")
                .containsEntry("command", "tasks");
    }

    @Test
    void shouldListAndSearchSessionsFromSlashCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.send("room-sessions", "user-sessions", "hello");
        env.send("room-sessions", "user-sessions", "/pairing claim-admin");

        GatewayReply firstNew = env.send("room-sessions", "user-sessions", "/new 客户周报");
        GatewayReply secondNew = env.send("room-sessions", "user-sessions", "/new 研发计划");

        GatewayReply listReply = env.send("room-sessions", "user-sessions", "/sessions");

        assertThat(listReply.getContent())
                .contains("最近会话")
                .contains("客户周报")
                .contains("研发计划")
                .contains("1.")
                .contains("/resume");
        assertThat(listReply.getRuntimeMetadata())
                .containsEntry("command_status", "handled")
                .containsEntry("command", "sessions");

        GatewayReply searchReply = env.send("room-sessions", "user-sessions", "/sessions 周报");

        assertThat(searchReply.getContent())
                .contains("最近会话")
                .contains("客户周报")
                .contains(firstNew.getSessionId())
                .doesNotContain("研发计划")
                .doesNotContain(secondNew.getSessionId());
    }

    @Test
    void shouldReportSlashCommandAccessIdentity() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.send("room-whoami", "user-whoami", "hello");
        env.send("room-whoami", "user-whoami", "/pairing claim-admin");

        GatewayReply reply = env.send("room-whoami", "user-whoami", "/whoami");

        assertThat(reply.getContent())
                .contains("platform=MEMORY")
                .contains("user=user-whoami")
                .contains("chat=room-whoami")
                .contains("role=admin")
                .contains("authorized=true");
        assertThat(reply.getRuntimeMetadata())
                .containsEntry("command_status", "handled")
                .containsEntry("command", "whoami")
                .containsEntry("role", "admin");
    }

    @Test
    void shouldBrowseRegisteredCommandsFromSlashCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.send("room-commands", "user-commands", "hello");
        env.send("room-commands", "user-commands", "/pairing claim-admin");

        GatewayReply reply = env.send("room-commands", "user-commands", "/commands");

        assertThat(reply.getContent())
                .contains("命令目录")
                .contains("/new")
                .contains("/sessions")
                .contains("/whoami")
                .contains("/model")
                .contains("page=1");
        assertThat(reply.getRuntimeMetadata())
                .containsEntry("command_status", "handled")
                .containsEntry("command", "commands")
                .containsEntry("page", Integer.valueOf(1));
    }

    @Test
    void shouldReloadLocalSkillsFromTopLevelSlashCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.send("room-reload-skills", "user-reload-skills", "hello");
        env.send("room-reload-skills", "user-reload-skills", "/pairing claim-admin");
        File skillDir = new File(env.appConfig.getRuntime().getSkillsDir(), "ops/reload-demo");
        Files.createDirectories(skillDir.toPath());
        Files.write(
                new File(skillDir, "SKILL.md").toPath(),
                Arrays.asList(
                        "---",
                        "name: reload-demo",
                        "description: Reload demo skill",
                        "---",
                        "Use this skill to verify slash command reload."),
                StandardCharsets.UTF_8);

        GatewayReply reply = env.send("room-reload-skills", "user-reload-skills", "/reload-skills");

        assertThat(reply.getContent())
                .contains("已重新加载本地技能")
                .contains("ops/reload-demo")
                .contains("共 1 个");
        assertThat(reply.getRuntimeMetadata())
                .containsEntry("command_status", "handled")
                .containsEntry("command", "reload-skills")
                .containsEntry("skill_count", Integer.valueOf(1));
    }

    @Test
    void shouldRenderUsageInsightsFromSlashCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.send("room-insights", "user-insights", "hello");
        env.send("room-insights", "user-insights", "/pairing claim-admin");
        File skillDir = new File(env.appConfig.getRuntime().getSkillsDir(), "ops/insight-demo");
        Files.createDirectories(skillDir.toPath());
        Files.write(
                new File(skillDir, "SKILL.md").toPath(),
                Arrays.asList(
                        "---",
                        "name: insight-demo",
                        "description: Insight demo skill",
                        "---",
                        "Use this skill to verify slash command insights."),
                StandardCharsets.UTF_8);
        env.localSkillService.listSkillNames();

        GatewayReply reply = env.send("room-insights", "user-insights", "/insights");

        assertThat(reply.getContent())
                .contains("使用洞察")
                .contains("sessions.total=")
                .contains("skills.available=1")
                .contains("runtime.memory=");
        assertThat(reply.getRuntimeMetadata())
                .containsEntry("command_status", "handled")
                .containsEntry("command", "insights")
                .containsKey("session_total")
                .containsEntry("skill_available", Integer.valueOf(1));
    }

    @Test
    void shouldClearSessionScopedSecurityStateWhenResuming() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-resume-a", "user-resume", "hello"));
        env.gatewayService.handle(env.message("room-resume-b", "user-resume", "hello"));
        env.gatewayAuthorizationService.claimAdmin(
                env.message("room-resume-a", "user-resume", "/pairing claim-admin"));

        SessionRecord sessionA =
                env.sessionRepository.bindNewSession("MEMORY:room-resume-a:user-resume");
        SessionRecord sessionB =
                env.sessionRepository.bindNewSession("MEMORY:room-resume-b:user-resume");
        SqliteAgentSession agentSessionA = new SqliteAgentSession(sessionA, env.sessionRepository);
        SqliteAgentSession agentSessionB = new SqliteAgentSession(sessionB, env.sessionRepository);
        env.dangerousCommandApprovalService.enableSessionAutoApproval(agentSessionA);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSessionA,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
        env.dangerousCommandApprovalService.approve(
                agentSessionA, DangerousCommandApprovalService.ApprovalScope.SESSION, "tester");
        env.dangerousCommandApprovalService.enableSessionAutoApproval(agentSessionB);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSessionB,
                "execute_shell",
                "git_reset_hard",
                "git reset --hard",
                "git reset --hard origin/main");

        GatewayReply resumeReply =
                env.send("room-resume-a", "user-resume", "/resume " + sessionA.getSessionId());
        SessionRecord resumed =
                env.sessionRepository.getBoundSession("MEMORY:room-resume-a:user-resume");
        SqliteAgentSession resumedSession = new SqliteAgentSession(resumed, env.sessionRepository);
        SqliteAgentSession untouchedSession =
                new SqliteAgentSession(
                        env.sessionRepository.findById(sessionB.getSessionId()),
                        env.sessionRepository);

        assertThat(resumeReply.getSessionId()).isEqualTo(sessionA.getSessionId());
        assertThat(env.dangerousCommandApprovalService.isSessionAutoApprovalEnabled(resumedSession))
                .isFalse();
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(resumedSession)).isNull();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                resumedSession, "recursive_delete"))
                .isFalse();
        assertThat(env.dangerousCommandApprovalService.isSessionAutoApprovalEnabled(untouchedSession))
                .isTrue();
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(untouchedSession))
                .isNotNull();
    }
}
