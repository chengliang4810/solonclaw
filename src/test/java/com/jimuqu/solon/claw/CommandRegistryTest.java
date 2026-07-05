package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.command.CommandDescriptor;
import com.jimuqu.solon.claw.command.CommandRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证命令注册表在初始化和维护时不会静默覆盖命令别名。 */
public class CommandRegistryTest {
    /** 重复别名必须快速失败，避免新命令覆盖已有 slash command 解析结果。 */
    @Test
    void shouldRejectDuplicateCommandAliases() throws Exception {
        Map<String, CommandDescriptor> commands = commands();
        Map<String, String> aliases = aliases();
        Map<String, CommandDescriptor> commandSnapshot =
                new LinkedHashMap<String, CommandDescriptor>(commands);
        Map<String, String> aliasSnapshot = new LinkedHashMap<String, String>(aliases);
        try {
            Method register =
                    CommandRegistry.class.getDeclaredMethod("register", CommandDescriptor.class);
            register.setAccessible(true);
            CommandDescriptor descriptor =
                    CommandDescriptor.builder("alias-conflict-probe")
                            .alias("help")
                            .category("test")
                            .description("测试重复别名")
                            .scopes(CommandRegistry.SCOPE_CLI)
                            .build();

            assertThatThrownBy(
                            () -> {
                                try {
                                    register.invoke(null, descriptor);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            })
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("help");
        } finally {
            commands.clear();
            commands.putAll(commandSnapshot);
            aliases.clear();
            aliases.putAll(aliasSnapshot);
        }
    }

    /** 读取命令注册表，便于测试恢复静态状态。 */
    @SuppressWarnings("unchecked")
    private Map<String, CommandDescriptor> commands() throws Exception {
        Field field = CommandRegistry.class.getDeclaredField("COMMANDS");
        field.setAccessible(true);
        return (Map<String, CommandDescriptor>) field.get(null);
    }

    /** 读取别名注册表，便于测试恢复静态状态。 */
    @SuppressWarnings("unchecked")
    private Map<String, String> aliases() throws Exception {
        Field field = CommandRegistry.class.getDeclaredField("ALIASES");
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }
}
