package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.cli.TuiShell;
import com.jimuqu.solon.claw.config.AppConfig;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

public class TuiShellHeaderTest {
    @Test
    void shouldRenderStatusLineFromRuntimeModelConfig() throws Exception {
        AppConfig config = new AppConfig();
        config.getModel().setProviderKey("default");
        config.getModel().setDefaultModel("gpt-test");
        config.getLlm().setReasoningEffort("high");
        TuiShell shell =
                new TuiShell(
                        null,
                        new CliMode(CliMode.Kind.TUI, null, "work"),
                        null,
                        config);

        String line = statusLine(shell, "work");

        assertThat(line)
                .contains("session=work")
                .contains("provider=default")
                .contains("model=gpt-test")
                .contains("reasoning=high");
    }

    @Test
    void shouldRenderFallbackStatusLineWhenConfigIsMissing() throws Exception {
        TuiShell shell = new TuiShell(null, new CliMode(CliMode.Kind.TUI, null, null));

        String line = statusLine(shell, "tui");

        assertThat(line)
                .contains("session=tui")
                .contains("provider=default")
                .contains("model=-")
                .contains("reasoning=-");
    }

    private String statusLine(TuiShell shell, String sessionId) throws Exception {
        Method method = TuiShell.class.getDeclaredMethod("statusLine", String.class);
        method.setAccessible(true);
        return (String) method.invoke(shell, sessionId);
    }
}
