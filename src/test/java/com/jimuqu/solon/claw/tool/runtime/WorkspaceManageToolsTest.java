package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.support.constants.ContextFileConstants;
import com.jimuqu.solon.claw.web.DashboardWorkspaceService;
import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;

/** 验证工作区管理工具对稳定环境笔记和持续关注任务的原子维护行为。 */
class WorkspaceManageToolsTest {
    /** 每个测试独立使用的临时目录。 */
    @TempDir Path tempDir;

    /** 提供真实文件读写行为的临时人格工作区。 */
    private PersonaWorkspaceService workspace;

    /** 被测工作区管理工具。 */
    private WorkspaceManageTools tool;

    /** 为每个用例创建隔离工作区和工具实例。 */
    @BeforeEach
    void setUp() {
        AppConfig config = new AppConfig();
        File runtimeDir = new File(tempDir.toFile(), "runtime");
        config.getRuntime().setHome(runtimeDir.getAbsolutePath());
        config.getRuntime().setContextDir(new File(runtimeDir, "context").getAbsolutePath());
        config.getWorkspace().setDir(new File(tempDir.toFile(), "workspace").getAbsolutePath());
        workspace = new PersonaWorkspaceService(config);
        tool = new WorkspaceManageTools(new DashboardWorkspaceService(workspace));
    }

    /** 同一标签再次写入时更新原条目，并保留其他小节和无关条目。 */
    @Test
    void shouldUpdateMatchingNoteWithoutDuplicatingOrDroppingOtherContent() {
        workspace.write(
                ContextFileConstants.KEY_TOOLS,
                "# TOOLS.md\n\n说明文字\n\n## SSH\n\n- 主机：旧地址\n- 用户：deploy\n\n## TTS\n\n- 语音：晓晓\n");

        ONode result = invoke("upsert_note", ContextFileConstants.KEY_TOOLS, "SSH", "主机：新地址");

        assertSuccess(result);
        assertThat(workspace.read(ContextFileConstants.KEY_TOOLS))
                .contains("说明文字", "- 主机：新地址", "- 用户：deploy", "## TTS", "- 语音：晓晓")
                .doesNotContain("- 主机：旧地址");
        assertThat(countOccurrences(workspace.read(ContextFileConstants.KEY_TOOLS), "主机："))
                .isEqualTo(1);
    }

    /** 删除动作只移除目标标签条目，不改动同小节和其他小节内容。 */
    @Test
    void shouldRemoveOnlyMatchingNote() {
        workspace.write(
                ContextFileConstants.KEY_HEARTBEAT,
                "# HEARTBEAT.md\n\n## 任务\n\n- 仓库：每天检查更新\n- 服务：每周检查状态\n\n## 说明\n\n保留这里\n");

        ONode result = invoke("remove_note", ContextFileConstants.KEY_HEARTBEAT, "任务", "仓库：无需继续检查");

        assertSuccess(result);
        assertThat(workspace.read(ContextFileConstants.KEY_HEARTBEAT))
                .doesNotContain("仓库：每天检查更新")
                .contains("- 服务：每周检查状态", "## 说明", "保留这里");
    }

    /** Heartbeat 未指定小节时写入默认“任务”小节。 */
    @Test
    void shouldUseTaskSectionForHeartbeatByDefault() {
        workspace.write(ContextFileConstants.KEY_HEARTBEAT, "# HEARTBEAT.md\n");

        ONode result =
                invoke("upsert_note", ContextFileConstants.KEY_HEARTBEAT, null, "依赖：每周检查安全更新");

        assertSuccess(result);
        assertThat(workspace.read(ContextFileConstants.KEY_HEARTBEAT))
                .contains("## 任务", "- 依赖：每周检查安全更新");
    }

    /** 原子笔记动作只允许维护 TOOLS.md 和 HEARTBEAT.md。 */
    @Test
    void shouldRejectUnsupportedWorkspaceFile() {
        ONode result = invoke("upsert_note", ContextFileConstants.KEY_AGENTS, "规则", "语言：中文");

        assertError(result);
        assertThat(result.get("error").getString())
                .contains("only supports TOOLS.md and HEARTBEAT.md");
    }

    /** 条目或小节标题包含凭证时拒绝写入，且不把敏感值回显到结果。 */
    @Test
    void shouldRejectCredentialsInNoteOrSection() {
        String secret = "sk-testcredential1234567890";

        ONode noteResult =
                invoke("upsert_note", ContextFileConstants.KEY_TOOLS, "服务", "API_KEY=" + secret);
        ONode sectionResult =
                invoke("upsert_note", ContextFileConstants.KEY_TOOLS, "token=" + secret, "服务：本地");

        assertError(noteResult);
        assertError(sectionResult);
        assertThat(noteResult.toJson()).doesNotContain(secret);
        assertThat(sectionResult.toJson()).doesNotContain(secret);
        assertThat(workspace.read(ContextFileConstants.KEY_TOOLS)).doesNotContain(secret);
    }

    /** 调用被测工具并解析统一结果信封。 */
    private ONode invoke(String action, String key, String section, String content) {
        return ONode.ofJson(tool.workspaceManage(action, key, section, content));
    }

    /** 断言工具调用成功。 */
    private void assertSuccess(ONode result) {
        assertThat(result.get("status").getString()).as(result.toJson()).isNotEqualTo("error");
    }

    /** 断言工具调用失败。 */
    private void assertError(ONode result) {
        assertThat(result.get("status").getString()).as(result.toJson()).isEqualTo("error");
    }

    /** 统计指定文本在正文中的非重叠出现次数。 */
    private int countOccurrences(String text, String value) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(value, index)) >= 0) {
            count++;
            index += value.length();
        }
        return count;
    }
}
