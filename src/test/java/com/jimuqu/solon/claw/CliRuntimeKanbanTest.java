package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

public class CliRuntimeKanbanTest {
    @Test
    void shouldRouteKanbanSlashCommandThroughCliRuntime() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CliRuntime runtime = new CliRuntime(env.commandService, env.conversationOrchestrator);

        GatewayReply created = runtime.send("cli-test", "/kanban create CLI task", null);
        assertThat(created.isError()).isFalse();
        assertThat(created.getContent()).contains("已创建看板任务");

        GatewayReply list = runtime.send("cli-test", "/kanban list", null);
        assertThat(list.getContent()).contains("CLI task");
    }

    @Test
    void shouldSendUnknownSlashCommandToModel() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CliRuntime runtime = new CliRuntime(env.commandService, env.conversationOrchestrator);

        GatewayReply reply = runtime.send("cli-test", "/not-a-command keep this as prose", null);

        assertThat(reply.isError()).isFalse();
        assertThat(reply.isCommandHandled()).isFalse();
        assertThat(reply.getContent()).contains("echo:/not-a-command keep this as prose");
    }

    @Test
    void shouldRouteCompactAliasThroughCliRuntime() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CliRuntime runtime = new CliRuntime(env.commandService, env.conversationOrchestrator);

        GatewayReply initial = runtime.send("cli-test", "hello before compact", null);
        assertThat(initial.getContent()).contains("echo:hello before compact");

        GatewayReply reply = runtime.send("cli-test", "/compact keep user intent", null);

        assertThat(reply.isCommandHandled()).isTrue();
        assertThat(reply.getContent()).contains("上下文压缩");
    }
}
