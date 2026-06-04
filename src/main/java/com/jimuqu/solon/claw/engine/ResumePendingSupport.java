package com.jimuqu.solon.claw.engine;

import cn.hutool.core.util.StrUtil;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Agent pending 恢复原因与提示文案。 */
final class ResumePendingSupport {
    private static final Set<String> GATEWAY_INTERRUPTION_REASONS =
            Collections.unmodifiableSet(
                    new LinkedHashSet<String>(
                            Arrays.asList(
                                    "restart_timeout", "shutdown_timeout", "restart_interrupted")));

    private ResumePendingSupport() {}

    static boolean isGatewayInterruptionReason(String reason) {
        if (StrUtil.isBlank(reason)) {
            return false;
        }
        return GATEWAY_INTERRUPTION_REASONS.contains(normalizeReason(reason));
    }

    static String gatewayInterruptionSystemNote(String reason) {
        String normalized = normalizeReason(reason);
        if (!GATEWAY_INTERRUPTION_REASONS.contains(normalized)) {
            return "";
        }
        String reasonText;
        if ("shutdown_timeout".equals(normalized)) {
            reasonText = "网关关闭";
        } else if ("restart_interrupted".equals(normalized)) {
            reasonText = "网关重启中断";
        } else {
            reasonText = "网关重启";
        }
        return "[系统提示：此会话上一轮执行被"
                + reasonText
                + "打断，历史上下文已保留。如果历史中包含尚未处理完的工具结果，请先处理这些结果并总结已完成的工作，再继续给出最终答复。]";
    }

    private static String normalizeReason(String reason) {
        return StrUtil.nullToEmpty(reason).trim().toLowerCase(Locale.ROOT);
    }
}
