package com.jimuqu.solon.claw.gateway.command;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** 验证命令回复时间格式化统一复用 CommandValueSupport，避免各命令处理器复制同一格式化逻辑。 */
class CommandTimestampSupportReuseTest {
    /** 命令处理器不应再保留私有时间格式化方法或直接调用 Hutool 时间格式化。 */
    @Test
    void commandHandlersShouldReuseSharedTimestampFormatter() throws Exception {
        String[] paths =
                new String[] {
                    "src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCommandService.java",
                    "src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCronCommandHandler.java",
                    "src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultRuntimeCommandHandler.java",
                    "src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultSkillCommandHandler.java",
                    "src/main/java/com/jimuqu/solon/claw/gateway/command/SlashCommandStatusRenderer.java",
                };
        for (String path : paths) {
            String source =
                    new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            assertFalse(source.contains("private String formatTimestamp("), path);
            assertFalse(source.contains("private static String formatTimestamp("), path);
            assertFalse(source.contains("DateUtil.formatDateTime("), path);
        }
    }
}
