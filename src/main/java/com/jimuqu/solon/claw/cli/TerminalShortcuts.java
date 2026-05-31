package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import java.util.Map;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.Widget;

/** Registers local TUI shortcuts on top of JLine. */
public final class TerminalShortcuts {
    private TerminalShortcuts() {}

    public static void install(LineReader reader) {
        install(reader, System.getenv(), System.getProperty("os.name"));
    }

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

    private static boolean hasEnv(Map<String, String> env, String key) {
        return StrUtil.isNotBlank(envValue(env, key));
    }

    private static String envValue(Map<String, String> env, String key) {
        if (env == null || key == null) {
            return "";
        }
        return StrUtil.nullToEmpty(env.get(key)).trim();
    }

    static void register(
            final LineReader reader, String widgetName, final String command, String sequence) {
        if (reader == null || widgetName == null || command == null || sequence == null) {
            return;
        }
        reader.getWidgets()
                .put(
                        widgetName,
                        new Widget() {
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

    static void registerNewline(final LineReader reader, String widgetName, String sequence) {
        if (reader == null || widgetName == null || sequence == null) {
            return;
        }
        reader.getWidgets()
                .put(
                        widgetName,
                        new Widget() {
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

    public static String helpLine() {
        return "快捷键：Ctrl-G 事件，Ctrl-S 会话，Ctrl-T 任务，Ctrl-Y 复制上一条回复";
    }
}
