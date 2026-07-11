package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 验证模型流等待具备有限超时，避免 TUI 永久保持运行态。 */
class SolonAiStreamTimeoutStaticTest {
    /** 检查共享模型流等待点使用统一超时。 */
    @Test
    void shouldBoundOwnedModelStreamWait() throws Exception {
        String source =
                Files.readString(
                        Path.of("src/main/java/com/jimuqu/solon/claw/llm/SolonAiLlmGateway.java"),
                        StandardCharsets.UTF_8);

        assertThat(source).contains("MODEL_STREAM_TIMEOUT = Duration.ofMinutes(5)");
        assertThat(source).contains(".blockLast(MODEL_STREAM_TIMEOUT)");
    }
}
