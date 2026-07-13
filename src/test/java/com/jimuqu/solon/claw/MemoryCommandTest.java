package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.command.CommandRegistry;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.MemoryApprovalRequest;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证 /memory 对共享记忆审批服务的命令入口。 */
public class MemoryCommandTest {
    /** 验证审批开关、待审批展示和批准拒绝都复用同一记忆服务。 */
    @Test
    void shouldManageMemoryApprovalWithoutExposingPayload() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        claimAdmin(env, "memory-room", "memory-user");

        GatewayReply initial = env.send("memory-room", "memory-user", "/memory");
        assertThat(CommandRegistry.resolve("memory")).isNotNull();
        assertThat(initial.getContent()).contains("记忆写入审批：关闭").contains("待审批变更：0 条");
        assertThat(initial.getRuntimeMetadata())
                .containsEntry("command_status", "handled")
                .containsEntry("command", "memory")
                .containsEntry("action", "status");

        GatewayReply enabled = env.send("memory-room", "memory-user", "/memory approval on");
        assertThat(enabled.getContent()).contains("已开启");
        env.memoryService.add("memory", "偏好 token=ghp_memorycommand123456");
        List<MemoryApprovalRequest> pending = env.memoryService.listPendingApprovals();

        GatewayReply listed = env.send("memory-room", "memory-user", "/memory pending");
        assertThat(listed.getContent())
                .contains("id=" + pending.get(0).getId())
                .contains("origin=background_review")
                .contains("summary=add memory: 偏好 token=***")
                .doesNotContain("ghp_memorycommand123456")
                .doesNotContain("payload");

        GatewayReply applied =
                env.send(
                        "memory-room", "memory-user", "/memory apply " + pending.get(0).getId());
        assertThat(applied.getContent()).contains("已批准并应用 1 条");
        assertThat(env.memoryService.read("memory")).contains("ghp_memorycommand123456");

        env.memoryService.add("user", "应被丢弃的记忆");
        String rejectedId = env.memoryService.listPendingApprovals().get(0).getId();
        GatewayReply rejected =
                env.send("memory-room", "memory-user", "/memory deny " + rejectedId);
        assertThat(rejected.getContent()).contains("已拒绝并丢弃 1 条");
        assertThat(env.memoryService.read("user")).doesNotContain("应被丢弃的记忆");
    }

    /** 验证非法子命令返回明确用法，且不增加历史兼容别名。 */
    @Test
    void shouldRejectInvalidMemoryApprovalArguments() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        claimAdmin(env, "memory-usage", "memory-user");

        GatewayReply missingId = env.send("memory-usage", "memory-user", "/memory approve");
        GatewayReply legacyAlias = env.send("memory-usage", "memory-user", "/memory mode on");
        GatewayReply invalidToggle =
                env.send("memory-usage", "memory-user", "/memory approval enabled");
        GatewayReply unknownId =
                env.send("memory-usage", "memory-user", "/memory approve deadbeef");

        assertThat(missingId.isError()).isTrue();
        assertThat(legacyAlias.isError()).isTrue();
        assertThat(invalidToggle.isError()).isTrue();
        assertThat(unknownId.isError()).isTrue();
        assertThat(unknownId.getRuntimeMetadata())
                .containsEntry("command_status", "handled")
                .containsEntry("command", "memory")
                .containsEntry("action", "approve");
        assertThat(missingId.getContent())
                .contains("用法：/memory")
                .contains("approval [on|off]");
        assertThat(legacyAlias.getContent()).doesNotContain("mode [on|off]");
    }

    /** 验证同一秒暂存的审批项仍保持服务层插入顺序，不按随机标识重排。 */
    @Test
    void shouldPreservePendingInsertionOrder() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        claimAdmin(env, "memory-order", "memory-user");
        env.memoryService.setApprovalEnabled(true);
        env.memoryService.add("memory", "第一条同秒记忆");
        env.memoryService.add("memory", "第二条同秒记忆");

        GatewayReply listed = env.send("memory-order", "memory-user", "/memory pending");

        assertThat(listed.getContent().indexOf("第一条同秒记忆"))
                .isLessThan(listed.getContent().indexOf("第二条同秒记忆"));
        assertThat(listed.getRuntimeMetadata()).containsEntry("action", "pending");
    }

    /** 将测试来源设为管理员，使命令断言不受渠道配对门禁影响。 */
    private void claimAdmin(TestEnvironment env, String chatId, String userId) throws Exception {
        env.send(chatId, userId, "hello");
        env.send(chatId, userId, "/pairing claim-admin");
    }
}
