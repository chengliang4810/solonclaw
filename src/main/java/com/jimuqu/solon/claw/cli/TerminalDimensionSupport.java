package com.jimuqu.solon.claw.cli;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;

/** 封装终端Dimension辅助逻辑，降低主流程中的重复实现。 */
public final class TerminalDimensionSupport {
    /** 默认COLUMNS的统一常量值。 */
    public static final int DEFAULT_COLUMNS = 80;

    /** 默认ROWS的统一常量值。 */
    public static final int DEFAULT_ROWS = 24;

    /** 最小COLUMNS的统一常量值。 */
    public static final int MIN_COLUMNS = 1;

    /** 最小ROWS的统一常量值。 */
    public static final int MIN_ROWS = 1;

    /** 最大COLUMNS的统一常量值。 */
    public static final int MAX_COLUMNS = 2000;

    /** 最大ROWS的统一常量值。 */
    public static final int MAX_ROWS = 1000;

    /** 创建终端Dimension辅助实例。 */
    private TerminalDimensionSupport() {}

    /**
     * 清理Dimension。
     *
     * @param value 待规范化或校验的原始值。
     * @param min min 参数。
     * @param max max 参数。
     * @param fallback 兜底参数。
     * @return 返回Dimension结果。
     */
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

    /**
     * 清理大小。
     *
     * @param columns columns 参数。
     * @param rows rows 参数。
     * @return 返回大小结果。
     */
    public static Size sanitizeSize(Object columns, Object rows) {
        return new Size(
                sanitizeDimension(columns, MIN_COLUMNS, MAX_COLUMNS, DEFAULT_COLUMNS),
                sanitizeDimension(rows, MIN_ROWS, MAX_ROWS, DEFAULT_ROWS));
    }

    /**
     * 执行清理相关逻辑。
     *
     * @param terminal 终端参数。
     */
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
                // 保留此处实现约束，避免后续维护时破坏既有行为。
            }
        }
    }
}
