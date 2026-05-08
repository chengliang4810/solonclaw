package com.jimuqu.solon.claw.cli;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.Widget;

/** Registers local TUI shortcuts on top of JLine. */
public final class TerminalShortcuts {
    private TerminalShortcuts() {}

    public static void install(LineReader reader) {
        if (reader == null) {
            return;
        }
        register(reader, "jimuqu-events", "/events", KeyMap.ctrl('G'));
        register(reader, "jimuqu-sessions", "/sessions", KeyMap.ctrl('S'));
        register(reader, "jimuqu-copy", "/copy", KeyMap.ctrl('Y'));
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

    public static String helpLine() {
        return "快捷键：Ctrl-G 事件，Ctrl-S 会话，Ctrl-Y 复制上一条回复";
    }
}
