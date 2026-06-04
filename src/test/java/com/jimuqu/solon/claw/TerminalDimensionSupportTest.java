package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.jimuqu.solon.claw.cli.TerminalDimensionSupport;
import java.lang.reflect.Proxy;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;

public class TerminalDimensionSupportTest {
    @Test
    void shouldPassThroughNormalTerminalSize() {
        Size size =
                TerminalDimensionSupport.sanitizeSize(Integer.valueOf(120), Integer.valueOf(40));

        assertThat(size.getColumns()).isEqualTo(120);
        assertThat(size.getRows()).isEqualTo(40);
    }

    @Test
    void shouldClampAbsurdTerminalSizeToSafeBounds() {
        Size size =
                TerminalDimensionSupport.sanitizeSize(
                        Integer.valueOf(131072), Integer.valueOf(99999));

        assertThat(size.getColumns()).isEqualTo(TerminalDimensionSupport.MAX_COLUMNS);
        assertThat(size.getRows()).isEqualTo(TerminalDimensionSupport.MAX_ROWS);
    }

    @Test
    void shouldFallbackWhenTerminalSizeIsMissingOrInvalid() {
        Size size = TerminalDimensionSupport.sanitizeSize(null, Double.valueOf(Double.NaN));

        assertThat(size.getColumns()).isEqualTo(TerminalDimensionSupport.DEFAULT_COLUMNS);
        assertThat(size.getRows()).isEqualTo(TerminalDimensionSupport.DEFAULT_ROWS);
    }

    @Test
    void shouldFloorFractionalTerminalSize() {
        Size size =
                TerminalDimensionSupport.sanitizeSize(
                        Double.valueOf(100.9D), Double.valueOf(30.8D));

        assertThat(size.getColumns()).isEqualTo(100);
        assertThat(size.getRows()).isEqualTo(30);
    }

    @Test
    void shouldNotFailStartupWhenTerminalRejectsSizeUpdate() {
        Terminal terminal =
                (Terminal)
                        Proxy.newProxyInstance(
                                Terminal.class.getClassLoader(),
                                new Class<?>[] {Terminal.class},
                                (proxy, method, args) -> {
                                    if ("getSize".equals(method.getName())) {
                                        return new Size(131072, 24);
                                    }
                                    if ("setSize".equals(method.getName())) {
                                        throw new UnsupportedOperationException("read only size");
                                    }
                                    if ("getWidth".equals(method.getName())) {
                                        return Integer.valueOf(131072);
                                    }
                                    if ("getHeight".equals(method.getName())) {
                                        return Integer.valueOf(24);
                                    }
                                    if ("flush".equals(method.getName())
                                            || "close".equals(method.getName())) {
                                        return null;
                                    }
                                    if (method.getReturnType().equals(Boolean.TYPE)) {
                                        return Boolean.FALSE;
                                    }
                                    if (method.getReturnType().equals(Integer.TYPE)) {
                                        return Integer.valueOf(0);
                                    }
                                    return null;
                                });

        assertThatCode(() -> TerminalDimensionSupport.sanitize(terminal))
                .doesNotThrowAnyException();
    }
}
