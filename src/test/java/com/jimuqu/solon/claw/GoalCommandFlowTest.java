package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

public class GoalCommandFlowTest {
    @Test
    void shouldKickoffGoalThroughCliRuntimeEventPath() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CliRuntime runtime = new CliRuntime(env.commandService, env.conversationOrchestrator);

        GatewayReply reply = runtime.send("goal-session", "/goal 完成 CLI 验证 --max 1", null);

        assertThat(reply.getContent()).contains("echo:完成 CLI 验证");
        assertThat(reply.getRuntimeMetadata().get("goal_verdict")).isEqualTo("continue");
        assertThat(
                        env.sessionRepository
                                .getBoundSession("MEMORY:cli:goal-session")
                                .getGoalStateJson())
                .contains("完成 CLI 验证")
                .contains("\"turns_used\":1");
    }

    @Test
    void shouldResumeGoalThroughCliRuntimeEventPath() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CliRuntime runtime = new CliRuntime(env.commandService, env.conversationOrchestrator);

        runtime.send("goal-resume-session", "/goal 完成恢复验证 --max 2", null);
        runtime.send("goal-resume-session", "/goal pause", null);

        GatewayReply resumed = runtime.send("goal-resume-session", "/goal resume", null);

        assertThat(resumed.getContent()).contains("echo:[Continuing toward your standing goal]");
        assertThat(resumed.getRuntimeMetadata().get("goal_verdict")).isEqualTo("continue");
        assertThat(
                        env.sessionRepository
                                .getBoundSession("MEMORY:cli:goal-resume-session")
                                .getGoalStateJson())
                .contains("完成恢复验证")
                .contains("\"turns_used\":1");
    }
}
