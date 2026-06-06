package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import java.util.Map;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.Widget;

/** 承载终端Shortcuts相关状态和辅助逻辑。 */
public final class TerminalShortcuts {
    /** 创建终端Shortcuts实例。 */
    private TerminalShortcuts() {}

    /**
     * 执行install相关逻辑。
     *
     * @param reader reader 参数。
     */
    public static void install(LineReader reader) {
        install(reader, System.getenv(), System.getProperty("os.name"));
    }

    /**
     * 执行install相关逻辑。
     *
     * @param reader reader 参数。
     * @param env 环境变量参数。
     * @param osName os名称参数。
     */
    public static void install(LineReader reader, Map<String, String> env, String osName) {
        if (reader == null) {
            return;
        }
        register(reader, "jimuqu-events", "/events", KeyMap.ctrl('G'));
        register(reader, "jimuqu-sessions", "/sessions", KeyMap.ctrl('S'));
        register(reader, "jimuqu-tasks", "/tasks", KeyMap.ctrl('T'));
        register(reader, "jimuqu-copy", "/copy", KeyMap.ctrl('Y'));
        if (shouldPreserveCtrlJNewline(env, osName)) {
            registerNewline(reader, "jimuqu-newline", KeyMap.ctrl('J'));
        }
    }

    /**
     * 判断是否需要Preserve Ctrl J Newline。
     *
     * @param env 环境变量参数。
     * @param osName os名称参数。
     * @return 如果Preserve Ctrl J Newline满足条件则返回 true，否则返回 false。
     */
    public static boolean shouldPreserveCtrlJNewline(Map<String, String> env, String osName) {
        String normalizedOs = StrUtil.nullToEmpty(osName).toLowerCase();
        if (normalizedOs.contains("windows")) {
            return true;
        }
        if (hasEnv(env, "SSH_CONNECTION") || hasEnv(env, "SSH_CLIENT") || hasEnv(env, "SSH_TTY")) {
            return true;
        }
        if (hasEnv(env, "WT_SESSION")) {
            return true;
        }
        if (hasEnv(env, "GHOSTTY_RESOURCES_DIR") || hasEnv(env, "GHOSTTY_BIN_DIR")) {
            return true;
        }
        if ("xterm-ghostty".equalsIgnoreCase(envValue(env, "TERM"))) {
            return true;
        }
        if ("ghostty".equalsIgnoreCase(envValue(env, "TERM_PROGRAM"))) {
            return true;
        }
        return envValue(env, "WSL_DISTRO_NAME").toLowerCase().contains("microsoft");
    }

    /**
     * 判断是否存在Env。
     *
     * @param env 环境变量参数。
     * @param key 配置键或映射键。
     * @return 如果Env满足条件则返回 true，否则返回 false。
     */
    private static boolean hasEnv(Map<String, String> env, String key) {
        return StrUtil.isNotBlank(envValue(env, key));
    }

    /**
     * 执行环境变量值相关逻辑。
     *
     * @param env 环境变量参数。
     * @param key 配置键或映射键。
     * @return 返回env Value结果。
     */
    private static String envValue(Map<String, String> env, String key) {
        if (env == null || key == null) {
            return "";
        }
        return StrUtil.nullToEmpty(env.get(key)).trim();
    }

    /**
     * 执行register相关逻辑。
     *
     * @param reader reader 参数。
     * @param widgetName widget名称标识或键值。
     * @param command 待执行或解析的命令文本。
     * @param sequence sequence 参数。
     */
    static void register(
            final LineReader reader, String widgetName, final String command, String sequence) {
        if (reader == null || widgetName == null || command == null || sequence == null) {
            return;
        }
        reader.getWidgets()
                .put(
                        widgetName,
                        new Widget() {
                            /**
                             * 执行apply相关逻辑。
                             *
                             * @return 返回apply结果。
                             */
                            @Override
                            public boolean apply() {
                                reader.getBuffer().clear();
                                reader.getBuffer().write(command);
                                reader.callWidget(LineReader.ACCEPT_LINE);
                                return true;
                            }
                        });
        KeyMap<Binding> keyMap = reader.getKeyMaps().get(LineReader.MAIN);
        if (keyMap != null) {
            keyMap.bind(new Reference(widgetName), sequence);
        }
    }

    /**
     * 注册Newline。
     *
     * @param reader reader 参数。
     * @param widgetName widget名称标识或键值。
     * @param sequence sequence 参数。
     */
    static void registerNewline(final LineReader reader, String widgetName, String sequence) {
        if (reader == null || widgetName == null || sequence == null) {
            return;
        }
        reader.getWidgets()
                .put(
                        widgetName,
                        new Widget() {
                            /**
                             * 执行apply相关逻辑。
                             *
                             * @return 返回apply结果。
                             */
                            @Override
                            public boolean apply() {
                                reader.getBuffer().write("\n");
                                reader.callWidget(LineReader.REDISPLAY);
                                return true;
                            }
                        });
        KeyMap<Binding> keyMap = reader.getKeyMaps().get(LineReader.MAIN);
        if (keyMap != null) {
            keyMap.bind(new Reference(widgetName), sequence);
        }
    }

    /**
     * 执行help行相关逻辑。
     *
     * @return 返回help Line结果。
     */
    public static String helpLine() {
        return "快捷键：Ctrl-G 事件，Ctrl-S 会话，Ctrl-T 任务，Ctrl-Y 复制上一条回复";
    }
}
