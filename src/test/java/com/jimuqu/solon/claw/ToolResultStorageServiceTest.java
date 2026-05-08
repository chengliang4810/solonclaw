package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageInterceptor;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.react.ReActTrace;

public class ToolResultStorageServiceTest {
    @TempDir File tempDir;

    @Test
    void shouldKeepSmallToolResultInline() {
        ToolResultStorageService service =
                new ToolResultStorageService(tempDir.getAbsolutePath(), 1024, 300);

        ToolResultStorageService.StoredResult result =
                service.observe("read_file", "small output", "run-1", "call-1");

        assertThat(result.getObservation()).isEqualTo("small output");
        assertThat(result.getResultRef()).isNull();
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void shouldPersistLargeToolResultAndReturnJimuquBlock() throws Exception {
        ToolResultStorageService service =
                new ToolResultStorageService(tempDir.getAbsolutePath(), 256, 200000, 300);
        String large = repeat("line\n", 200);

        ToolResultStorageService.StoredResult result =
                service.observe("execute_shell", large, "run-1", "call-1");

        assertThat(result.getObservation()).startsWith("<persisted-output>");
        assertThat(result.getObservation()).contains("Full output saved to:");
        assertThat(result.getObservation()).contains("Use the file_read/read_file tool with offset and limit");
        assertThat(result.getObservation()).contains("Tool: execute_shell");
        String ref = result.getResultRef();
        assertThat(ref).isNotBlank();
        assertThat(new File(ref).getCanonicalPath())
                .startsWith(new File(tempDir, "tool-results").getCanonicalPath());
        assertThat(new String(Files.readAllBytes(new File(ref).toPath()), StandardCharsets.UTF_8))
                .isEqualTo(large);

        ToolResultStorageService.StoredResult described =
                ToolResultStorageService.describeObservation(result.getObservation());
        assertThat(described.getResultRef()).isEqualTo(ref);
        assertThat(described.getSizeBytes()).isEqualTo(large.getBytes(StandardCharsets.UTF_8).length);
        assertThat(described.isTruncated()).isTrue();
    }

    @Test
    void shouldRedactPersistedPreviewButKeepFullOutputOnDisk() throws Exception {
        ToolResultStorageService service =
                new ToolResultStorageService(tempDir.getAbsolutePath(), 40, 200000, 300);
        String large =
                "first line\nOPENAI_API_KEY=sk-proj-secretvalue1234567890\n"
                        + repeat("tail\n", 80);

        ToolResultStorageService.StoredResult result =
                service.observe("execute_shell", large, "run-secret", "call-secret");

        assertThat(result.getPreview()).contains("OPENAI_API_KEY=***");
        assertThat(result.getPreview()).doesNotContain("sk-proj-secretvalue1234567890");
        assertThat(result.getObservation()).contains("OPENAI_API_KEY=***");
        assertThat(result.getObservation()).doesNotContain("sk-proj-secretvalue1234567890");
        assertThat(new String(Files.readAllBytes(new File(result.getResultRef()).toPath()), StandardCharsets.UTF_8))
                .contains("sk-proj-secretvalue1234567890");
    }

    @Test
    void shouldDescribeLegacyJsonEnvelope() {
        String json =
                "{\"status\":\"success\",\"success\":true,\"preview\":\"old preview\",\"result_ref\":\"/tmp/result.txt\",\"size\":42,\"truncated\":true}";

        ToolResultStorageService.StoredResult described =
                ToolResultStorageService.describeObservation(json);

        assertThat(described.getPreview()).isEqualTo("old preview");
        assertThat(described.getResultRef()).isEqualTo("/tmp/result.txt");
        assertThat(described.getSizeBytes()).isEqualTo(42L);
        assertThat(described.isTruncated()).isTrue();
    }

    @Test
    void shouldSanitizePathSegmentsWhenPersistingResult() throws Exception {
        ToolResultStorageService service =
                new ToolResultStorageService(tempDir.getAbsolutePath(), 10, 200000, 300);

        ToolResultStorageService.StoredResult result =
                service.observe("shell", repeat("x", 400), "..\\evil/../run", "..\\call");
        String ref = result.getResultRef();

        assertThat(ref).isNotBlank();
        assertThat(new File(ref).getCanonicalPath())
                .startsWith(new File(tempDir, "tool-results").getCanonicalPath());
    }

    @Test
    void shouldRewriteTraceObservationThroughInterceptor() {
        ToolResultStorageService service =
                new ToolResultStorageService(tempDir.getAbsolutePath(), 20, 200000, 300);
        ToolResultStorageInterceptor interceptor =
                new ToolResultStorageInterceptor(service, "run-interceptor");
        ReActTrace trace = new ReActTrace();
        trace.setLastObservation(repeat("z", 400));

        interceptor.onObservation(trace, "webfetch", trace.getLastObservation(), 5L);

        ToolResultStorageService.StoredResult described =
                ToolResultStorageService.describeObservation(trace.getLastObservation());
        assertThat(described.isTruncated()).isTrue();
        assertThat(described.getResultRef()).isNotBlank();
    }

    @Test
    void shouldKeepReadFileOutputInlineToAvoidPersistReadLoop() {
        ToolResultStorageService service =
                new ToolResultStorageService(tempDir.getAbsolutePath(), 20, 200000, 300);
        String large = repeat("read\n", 200);

        ToolResultStorageService.StoredResult result =
                service.observe("file_read", large, "run-1", "read-call");

        assertThat(result.getObservation()).isEqualTo(large);
        assertThat(result.getResultRef()).isNull();
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void shouldPersistLaterMediumResultWhenTurnBudgetIsExceeded() {
        ToolResultStorageService service =
                new ToolResultStorageService(tempDir.getAbsolutePath(), 1000, 600, 300);
        String medium = repeat("m", 400);

        ToolResultStorageService.StoredResult first =
                service.observe("webfetch", medium, "run-budget", "call-1");
        ToolResultStorageService.StoredResult second =
                service.observe("webfetch", medium, "run-budget", "call-2");

        assertThat(first.getObservation()).isEqualTo(medium);
        assertThat(second.isTruncated()).isTrue();
        assertThat(second.getResultRef()).isNotBlank();
    }

    @Test
    void shouldPreferWorkspaceStorageSoFileToolCanReadResultRef() throws Exception {
        File workspace = new File(tempDir, "workspace");
        ToolResultStorageService service =
                new ToolResultStorageService(
                        new File(tempDir, "runtime-cache").getAbsolutePath(),
                        workspace.getAbsolutePath(),
                        20,
                        200000,
                        300);

        ToolResultStorageService.StoredResult result =
                service.observe("execute_shell", repeat("w", 400), "run-workspace", "call-1");
        String ref = result.getResultRef();

        assertThat(new File(ref).getCanonicalPath())
                .startsWith(new File(workspace, ".jimuqu/tool-results").getCanonicalPath());
    }

    private String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
