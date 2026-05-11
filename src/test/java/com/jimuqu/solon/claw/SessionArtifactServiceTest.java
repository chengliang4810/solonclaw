package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.SessionArtifactService;
import com.jimuqu.solon.claw.support.SessionArtifactStorageService;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.web.DashboardSessionService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;

public class SessionArtifactServiceTest {
    @Test
    void shouldRenderRecapAndTrajectoryFromSessionMessages() throws Exception {
        SessionRecord session = new SessionRecord();
        session.setSessionId("session-artifact");
        session.setTitle("artifact demo");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofUser("请检查项目"),
                                ChatMessage.ofAssistant("已经完成检查"),
                                ChatMessage.ofUser("继续"),
                                ChatMessage.ofAssistant("最终答复"))));

        SessionArtifactService service = new SessionArtifactService();
        Map<String, Object> recap = service.recap(session, 1);
        Map<String, Object> trajectory = service.trajectory(session, null, true);

        assertThat(recap.get("text").toString()).contains("继续").contains("最终答复");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conversations =
                (List<Map<String, Object>>) trajectory.get("conversations");
        assertThat(conversations).extracting(row -> row.get("from"))
                .containsExactly("system", "human", "gpt", "human", "gpt");
        assertThat(conversations.get(1).get("value")).isEqualTo("请检查项目");
        assertThat(conversations.get(2).get("value").toString()).contains("<think>");
    }

    @Test
    void shouldExposeRecapAndTrajectoryThroughSlashAndDashboardService() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.send("artifact-room", "artifact-user", "hello");
        env.send("artifact-room", "artifact-user", "/pairing claim-admin");
        env.send("artifact-room", "artifact-user", "start");

        SessionRecord session =
                env.sessionRepository.getBoundSession("MEMORY:artifact-room:artifact-user");
        session.setTitle("dashboard artifact");
        env.sessionRepository.save(session);

        GatewayReply recap = env.gatewayService.handle(env.message("artifact-room", "artifact-user", "/recap"));
        GatewayReply trajectory =
                env.gatewayService.handle(env.message("artifact-room", "artifact-user", "/trajectory"));
        DashboardSessionService dashboard = new DashboardSessionService(env.sessionRepository);

        assertThat(recap.getContent()).contains("用户: start").contains("助手: echo:start");
        assertThat(trajectory.getContent()).contains("\"conversations\"").contains("\"from\":\"human\"");
        assertThat(dashboard.recap(session.getSessionId(), 10).get("text").toString())
                .contains("echo:start");
        assertThat(dashboard.trajectory(session.getSessionId(), null, true).get("conversations"))
                .asList()
                .isNotEmpty();
    }

    @Test
    void shouldSaveTrajectoryAsJimuquJsonlArtifact() throws Exception {
        File artifactsDir = Files.createTempDirectory("trajectory-artifacts").toFile();
        SessionRecord session = new SessionRecord();
        session.setSessionId("session-jsonl");
        session.setTitle("jsonl demo");
        session.setLastResolvedModel("gpt-test");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("写一个测试"),
                                ChatMessage.ofAssistant("测试已完成"))));

        SessionArtifactService service =
                new SessionArtifactService(new SessionArtifactStorageService(artifactsDir));
        Map<String, Object> saved = service.saveTrajectory(session, null, true);

        assertThat(String.valueOf(saved.get("path"))).isEqualTo("runtime://artifacts/trajectory_samples.jsonl");
        assertThat(saved).doesNotContainKey("host_path");
        File target = new File(artifactsDir, "trajectory_samples.jsonl");
        assertThat(target.getName()).isEqualTo("trajectory_samples.jsonl");
        assertThat(target).exists();
        String content = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        assertThat(content)
                .contains("\"conversations\"")
                .contains("\"model\":\"gpt-test\"")
                .contains("\"completed\":true");
    }

    @Test
    void shouldSaveTrajectoryThroughSlashAndDashboardService() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.send("artifact-save-room", "artifact-save-user", "hello");
        env.send("artifact-save-room", "artifact-save-user", "/pairing claim-admin");
        env.send("artifact-save-room", "artifact-save-user", "start");
        SessionRecord session =
                env.sessionRepository.getBoundSession("MEMORY:artifact-save-room:artifact-save-user");
        DashboardSessionService dashboard =
                new DashboardSessionService(
                        env.sessionRepository,
                        null,
                        new SessionArtifactService(env.appConfig));

        GatewayReply slash =
                env.gatewayService.handle(
                        env.message(
                                "artifact-save-room",
                                "artifact-save-user",
                                "/trajectory save 原始问题"));
        Map<String, Object> dashboardSaved =
                dashboard.saveTrajectory(session.getSessionId(), "原始问题", false);

        assertThat(slash.getContent()).contains("已保存 trajectory").contains("trajectory_samples.jsonl");
        assertThat(slash.getContent()).doesNotContain(env.appConfig.getRuntime().getHome());
        assertThat(String.valueOf(dashboardSaved.get("path")))
                .isEqualTo("runtime://artifacts/failed_trajectories.jsonl");
        assertThat(dashboardSaved).doesNotContainKey("host_path");
        assertThat(String.valueOf(dashboardSaved.get("path")))
                .doesNotContain(env.appConfig.getRuntime().getHome());
    }
}
