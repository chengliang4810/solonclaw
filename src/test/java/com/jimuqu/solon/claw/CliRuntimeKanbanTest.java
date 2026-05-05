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
}
