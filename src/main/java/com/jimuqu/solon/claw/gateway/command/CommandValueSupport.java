package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import org.slf4j.Logger;

/** 提供命令服务复用的轻量值转换工具，避免主命令服务继续堆积无状态辅助逻辑。 */
final class CommandValueSupport {
    /** 安全展示标识时允许保留的最大长度，避免会话或来源字段撑爆命令回复。 */
    private static final int SAFE_IDENTIFIER_LIMIT = 400;

    private CommandValueSupport() {}

    /**
     * 格式化命令回复中的更新时间戳。
     *
     * @param timestamp 毫秒时间戳；小于等于 0 表示从未更新。
     * @return 用户可读的时间文本。
     */
    static String formatTimestamp(long timestamp) {
        if (timestamp <= 0) {
            return "never";
        }
        return DateUtil.formatDateTime(new java.util.Date(timestamp));
    }

    /**
     * 生成安全展示用的标识符，统一走脱敏逻辑避免来源键或会话标识过长输出。
     *
     * @param value 原始标识符。
     * @return 可安全展示的标识符。
     */
    static String safeIdentifier(String value) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), SAFE_IDENTIFIER_LIMIT);
    }

    /**
     * 解析 /goal 命令中的最大轮次选项。
     *
     * @param raw 原始 goal 参数。
     * @param defaultValue 缺省最大轮次。
     * @param log 调用方日志，用于保持解析失败时的原有日志分类。
     * @return 有效的最大轮次，至少为 1。
     */
    static int parseGoalMaxTurns(String raw, int defaultValue, Logger log) {
        String[] tokens = StrUtil.nullToEmpty(raw).split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (("--max-turns".equals(token) || "--max".equals(token)) && i + 1 < tokens.length) {
                try {
                    return Math.max(1, Integer.parseInt(tokens[i + 1]));
                } catch (Exception e) {
                    if (log != null) {
                        log.debug(
                                "Goal max turns option parsing failed; using default value: {}",
                                exceptionSummary(e));
                    }
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    /**
     * 在命令辅助逻辑捕获中断异常时恢复中断标记，避免吞掉上层调度信号。
     *
     * @param error 捕获到的命令处理异常。
     */
    static void restoreInterruptIfNeeded(Exception error) {
        if (error instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 生成命令服务异常摘要；只记录异常类型，避免命令参数或配置值进入日志。
     *
     * @param error 命令处理过程中捕获到的异常。
     * @return 可写入日志的异常摘要。
     */
    static String exceptionSummary(Exception error) {
        return error == null ? "unknown" : error.getClass().getName();
    }

    /**
     * 剥离 /goal 命令中的控制选项，只保留用户希望长期执行的目标文本。
     *
     * @param raw 原始 goal 参数。
     * @return 去掉控制选项后的目标文本。
     */
    static String stripGoalOptions(String raw) {
        String[] tokens = StrUtil.nullToEmpty(raw).trim().split("\\s+");
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (("--max-turns".equals(token) || "--max".equals(token)) && i + 1 < tokens.length) {
                i++;
                continue;
            }
            if (buffer.length() > 0) {
                buffer.append(' ');
            }
            buffer.append(token);
        }
        return buffer.toString().trim();
    }
}
