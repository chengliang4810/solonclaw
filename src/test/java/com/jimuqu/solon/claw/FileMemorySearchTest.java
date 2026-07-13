package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.context.FileMemoryService;
import com.jimuqu.solon.claw.core.model.MemorySearchResult;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 专题记忆和 SQLite FTS5 统一检索测试。 */
class FileMemorySearchTest {
    @Test
    void writesTopicAndSearchesAllMemoryFiles() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileMemoryService service = new FileMemoryService(env.appConfig);
        service.setApprovalEnabled(false);

        service.add("topic:deployment", "项目约定：bluegreen-marker 使用蓝绿发布");
        List<MemorySearchResult> results = service.search("bluegreen-marker", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getPath()).isEqualTo("memory/deployment.md");
        assertThat(results.get(0).getSnippet()).contains("bluegreen-marker");
        assertThat(service.get(results.get(0).getPath())).contains("使用蓝绿发布");
    }
}
