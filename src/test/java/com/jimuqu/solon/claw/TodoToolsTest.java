package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.TodoTools;
import java.io.File;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class TodoToolsTest {
    @Test
    void shouldReadAndReplaceCurrentList() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TodoTools tools = new TodoTools(env.appConfig, "MEMORY:room:user");

        ONode empty = ONode.ofJson(tools.todo(null, null));
        assertThat(empty.get("todos").size()).isEqualTo(0);
        assertThat(empty.get("summary").get("total").getInt()).isEqualTo(0);

        ONode written =
                ONode.ofJson(
                        tools.todo(
                                Arrays.asList(
                                        item("1", "Plan implementation", "in_progress"),
                                        item("2", "Verify behavior", "pending")),
                                false));

        assertThat(written.get("todos").size()).isEqualTo(2);
        assertThat(written.get("todos").get(0).get("id").getString()).isEqualTo("1");
        assertThat(written.get("summary").get("in_progress").getInt()).isEqualTo(1);
        assertThat(written.get("summary").get("pending").getInt()).isEqualTo(1);
    }

    @Test
    void shouldMergeByIdAndAppendNewItems() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TodoTools tools = new TodoTools(env.appConfig, "MEMORY:room:user");

        tools.todo(
                Arrays.asList(
                        item("1", "Plan implementation", "in_progress"),
                        item("2", "Verify behavior", "pending")),
                false);
        ONode merged =
                ONode.ofJson(
                        tools.todo(
                                Arrays.asList(
                                        item("1", null, "completed"),
                                        item("3", "Document result", "pending")),
                                true));

        assertThat(merged.get("todos").size()).isEqualTo(3);
        assertThat(merged.get("todos").get(0).get("status").getString()).isEqualTo("completed");
        assertThat(merged.get("todos").get(0).get("content").getString())
                .isEqualTo("Plan implementation");
        assertThat(merged.get("todos").get(2).get("id").getString()).isEqualTo("3");
    }

    @Test
    void shouldNormalizeLikeJimuqu() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TodoTools tools = new TodoTools(env.appConfig, "MEMORY:room:user");

        ONode written =
                ONode.ofJson(
                        tools.todo(
                                Arrays.asList(
                                        item("dup", "first", "completed"),
                                        item("dup", "last", "unknown"),
                                        item("", "", null)),
                                false));

        assertThat(written.get("todos").size()).isEqualTo(2);
        assertThat(written.get("todos").get(0).get("id").getString()).isEqualTo("dup");
        assertThat(written.get("todos").get(0).get("content").getString()).isEqualTo("last");
        assertThat(written.get("todos").get(0).get("status").getString()).isEqualTo("pending");
        assertThat(written.get("todos").get(1).get("id").getString()).isEqualTo("?");
        assertThat(written.get("todos").get(1).get("content").getString())
                .isEqualTo("(no description)");
    }

    @Test
    void shouldRedactSecretsFromTodoToolResultOnly() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TodoTools tools = new TodoTools(env.appConfig, "MEMORY:secret-room:user");

        String result =
                tools.todo(
                        Arrays.asList(
                                item(
                                        "todo-ghp_todoid12345",
                                        "检查 Authorization: Bearer ghp_todocontent12345",
                                        "pending")),
                        false);

        assertThat(result)
                .contains("todo-ghp_***")
                .contains("Authorization: Bearer ***")
                .doesNotContain("ghp_todoid12345")
                .doesNotContain("ghp_todocontent12345");
        String cache = todoCache(env);
        assertThat(cache).contains("ghp_todoid12345").contains("ghp_todocontent12345");
    }

    private static TodoTools.TodoItem item(String id, String content, String status) {
        TodoTools.TodoItem item = new TodoTools.TodoItem();
        item.setId(id);
        item.setContent(content);
        item.setStatus(status);
        return item;
    }

    private static String todoCache(TestEnvironment env) {
        File[] files = new File(env.appConfig.getRuntime().getCacheDir()).listFiles();
        assertThat(files).isNotNull();
        for (File file : files) {
            if (file.getName().startsWith("todo-") && file.getName().endsWith(".json")) {
                return FileUtil.readUtf8String(file);
            }
        }
        return "";
    }
}
