package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.TerminalShortcuts;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.junit.jupiter.api.Test;

public class TerminalShortcutsTest {
    @Test
    void shouldRegisterTuiShortcutWidgetsAndBindings() {
        LineReader reader = LineReaderBuilder.builder().build();

        TerminalShortcuts.install(reader);

        assertThat(reader.getWidgets()).containsKeys("jimuqu-events", "jimuqu-sessions", "jimuqu-copy");
        KeyMap<Binding> keyMap = reader.getKeyMaps().get(LineReader.MAIN);
        assertReference(keyMap.getBound(KeyMap.ctrl('G')), "jimuqu-events");
        assertReference(keyMap.getBound(KeyMap.ctrl('S')), "jimuqu-sessions");
        assertReference(keyMap.getBound(KeyMap.ctrl('Y')), "jimuqu-copy");
    }

    @Test
    void shouldDescribeShortcutHelpLine() {
        assertThat(TerminalShortcuts.helpLine())
                .contains("Ctrl-G")
                .contains("Ctrl-S")
                .contains("Ctrl-Y");
    }

    private void assertReference(Binding binding, String name) {
        assertThat(binding).isInstanceOf(Reference.class);
        assertThat(((Reference) binding).name()).isEqualTo(name);
    }
}
