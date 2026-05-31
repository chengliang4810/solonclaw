package com.jimuqu.solon.claw.cli;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;

/** Sanitizes terminal dimensions before they reach CLI/TUI layout code. */
public final class TerminalDimensionSupport {
    public static final int DEFAULT_COLUMNS = 80;
    public static final int DEFAULT_ROWS = 24;
    public static final int MIN_COLUMNS = 1;
    public static final int MIN_ROWS = 1;
    public static final int MAX_COLUMNS = 2000;
    public static final int MAX_ROWS = 1000;

    private TerminalDimensionSupport() {}

    public static int sanitizeDimension(Object value, int min, int max, int fallback) {
        if (!(value instanceof Number)) {
            return fallback;
        }
        double raw = ((Number) value).doubleValue();
        if (Double.isNaN(raw) || Double.isInfinite(raw) || raw <= 0D) {
            return fallback;
        }
        int rounded = (int) Math.floor(raw);
        if (rounded < min) {
            return fallback;
        }
        if (rounded > max) {
            return max;
        }
        return rounded;
    }

    public static Size sanitizeSize(Object columns, Object rows) {
        return new Size(
                sanitizeDimension(columns, MIN_COLUMNS, MAX_COLUMNS, DEFAULT_COLUMNS),
                sanitizeDimension(rows, MIN_ROWS, MAX_ROWS, DEFAULT_ROWS));
    }

    public static void sanitize(Terminal terminal) {
        if (terminal == null) {
            return;
        }
        Size current = terminal.getSize();
        Size sanitized =
                sanitizeSize(
                        current == null ? null : Integer.valueOf(current.getColumns()),
                        current == null ? null : Integer.valueOf(current.getRows()));
        if (current == null
                || current.getColumns() != sanitized.getColumns()
                || current.getRows() != sanitized.getRows()) {
            try {
                terminal.setSize(sanitized);
            } catch (RuntimeException ignored) {
                // Some terminal implementations expose dimensions as read-only.
            }
        }
    }
}
