package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageInterceptor;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultTransformService;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.react.ReActTrace;

public class ToolResultTransformServiceTest {
    @TempDir File tempDir;

    @Test
    void shouldKeepToolResultUnchangedWhenNoTransformerRegistered() {
        ToolResultTransformService service = new ToolResultTransformService();
        ReActTrace trace = newTrace("original");

        service.buildInterceptor().onObservation(trace, "dummy_tool", "original", 5L);

        assertThat(trace.getLastObservation()).isEqualTo("original");
    }

    @Test
    void shouldIgnoreNullTransformerResult() {
        ToolResultTransformService service = new ToolResultTransformService();
        service.addTransformer(context -> null);
        ReActTrace trace = newTrace("original");

        service.buildInterceptor().onObservation(trace, "dummy_tool", "original", 5L);

        assertThat(trace.getLastObservation()).isEqualTo("original");
    }

    @Test
    void shouldUseFirstValidTransformerResult() {
        ToolResultTransformService service = new ToolResultTransformService();
        service.addTransformer(context -> null);
        service.addTransformer(context -> "first");
        service.addTransformer(context -> "second");
        ReActTrace trace = newTrace("original");

        service.buildInterceptor().onObservation(trace, "dummy_tool", "original", 5L);

        assertThat(trace.getLastObservation()).isEqualTo("first");
    }

    @Test
    void shouldFallbackToOriginalWhenTransformerThrows() {
        ToolResultTransformService service = new ToolResultTransformService();
        service.addTransformer(
                context -> {
                    throw new RuntimeException("boom");
                });
        ReActTrace trace = newTrace("original");

        service.buildInterceptor().onObservation(trace, "dummy_tool", "original", 5L);

        assertThat(trace.getLastObservation()).isEqualTo("original");
    }

    @Test
    void shouldExposeToolContextToTransformer() {
        ToolResultTransformService service = new ToolResultTransformService();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("a", Integer.valueOf(1));
        args.put("b", "x");
        final ToolResultTransformService.ToolResultContext[] captured =
                new ToolResultTransformService.ToolResultContext[1];
        service.addTransformer(
                context -> {
                    captured[0] = context;
                    return "rewritten";
                });
        ReActTrace trace = newTrace("original");
        trace.incrementToolCallCount();

        service.buildInterceptor().onAction(trace, "my_tool", args);
        service.buildInterceptor().onObservation(trace, "my_tool", "original", 12L);

        assertThat(trace.getLastObservation()).isEqualTo("rewritten");
        assertThat(captured[0].getToolName()).isEqualTo("my_tool");
        assertThat(captured[0].getArgs()).isEqualTo(args);
        assertThat(captured[0].getResult()).isEqualTo("original");
        assertThat(captured[0].getToolCallId()).isEqualTo("my_tool-1");
        assertThat(captured[0].getDurationMs()).isEqualTo(12L);
    }

    @Test
    void shouldTransformBeforeLargeResultStorageWhenInterceptorsAreOrderedThatWay() {
        ToolResultTransformService transformService = new ToolResultTransformService();
        transformService.addTransformer(context -> repeat("x", 400));
        ToolResultStorageService storageService =
                new ToolResultStorageService(tempDir.getAbsolutePath(), 20, 200000, 300);
        ReActTrace trace = newTrace("small");

        transformService.buildInterceptor().onObservation(trace, "webfetch", "small", 5L);
        new ToolResultStorageInterceptor(storageService, "run-transform")
                .onObservation(trace, "webfetch", trace.getLastObservation(), 5L);

        ToolResultStorageService.StoredResult described =
                ToolResultStorageService.describeObservation(trace.getLastObservation());
        assertThat(described.isTruncated()).isTrue();
        assertThat(described.getResultRef()).isNotBlank();
    }

    private ReActTrace newTrace(String observation) {
        ReActTrace trace = new ReActTrace();
        trace.setLastObservation(observation);
        return trace;
    }

    private String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
