package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.TerminalShortcuts;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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

        assertThat(reader.getWidgets())
                .containsKeys("jimuqu-events", "jimuqu-sessions", "jimuqu-tasks", "jimuqu-copy");
        KeyMap<Binding> keyMap = reader.getKeyMaps().get(LineReader.MAIN);
        assertReference(keyMap.getBound(KeyMap.ctrl('G')), "jimuqu-events");
        assertReference(keyMap.getBound(KeyMap.ctrl('S')), "jimuqu-sessions");
        assertReference(keyMap.getBound(KeyMap.ctrl('T')), "jimuqu-tasks");
        assertReference(keyMap.getBound(KeyMap.ctrl('Y')), "jimuqu-copy");
    }

    @Test
    void shouldBindCtrlJToNewlineOnlyWhenTerminalNeedsPreservation() {
        LineReader ghosttyReader = LineReaderBuilder.builder().build();
        Map<String, String> ghosttyEnv = new LinkedHashMap<String, String>();
        ghosttyEnv.put("TERM_PROGRAM", "ghostty");

        TerminalShortcuts.install(ghosttyReader, ghosttyEnv, "Mac OS X");

        assertReference(
                ghosttyReader.getKeyMaps().get(LineReader.MAIN).getBound(KeyMap.ctrl('J')),
                "jimuqu-newline");
        assertThat(ghosttyReader.getWidgets()).containsKey("jimuqu-newline");

        LineReader plainReader = LineReaderBuilder.builder().build();
        TerminalShortcuts.install(plainReader, Collections.<String, String>emptyMap(), "Mac OS X");

        Binding plainBinding =
                plainReader.getKeyMaps().get(LineReader.MAIN).getBound(KeyMap.ctrl('J'));
        if (plainBinding instanceof Reference) {
            assertThat(((Reference) plainBinding).name()).isNotEqualTo("jimuqu-newline");
        }
    }

    @Test
    void shouldDetectCtrlJNewlinePreservationEnvironments() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("SSH_CONNECTION", "1.2.3.4 5 6.7.8.9 22");
        assertThat(TerminalShortcuts.shouldPreserveCtrlJNewline(env, "Linux")).isTrue();

        env.clear();
        env.put("WT_SESSION", "window-session");
        assertThat(TerminalShortcuts.shouldPreserveCtrlJNewline(env, "Linux")).isTrue();

        env.clear();
        env.put("TERM", "xterm-ghostty");
        assertThat(TerminalShortcuts.shouldPreserveCtrlJNewline(env, "Mac OS X")).isTrue();

        assertThat(
                        TerminalShortcuts.shouldPreserveCtrlJNewline(
                                Collections.<String, String>emptyMap(), "Mac OS X"))
                .isFalse();
    }

    @Test
    void shouldDescribeShortcutHelpLine() {
        assertThat(TerminalShortcuts.helpLine())
                .contains("Ctrl-G")
                .contains("Ctrl-S")
                .contains("Ctrl-T")
                .contains("Ctrl-Y");
    }

    private void assertReference(Binding binding, String name) {
        assertThat(binding).isInstanceOf(Reference.class);
        assertThat(((Reference) binding).name()).isEqualTo(name);
    }
}
