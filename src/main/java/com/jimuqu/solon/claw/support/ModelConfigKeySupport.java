package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 统一识别只能通过 Provider、模型和后台任务路由专用入口修改的配置键。 */
public final class ModelConfigKeySupport {
    /** 通用配置入口拒绝模型配置时使用的统一提示。 */
    public static final String DEDICATED_ENTRY_MESSAGE = "模型与 Provider 配置请使用模型设置或 Provider 管理专用入口。";

    /** 后台任务模型路由的成对配置键。 */
    private static final Set<String> TASK_MODEL_KEYS =
            new HashSet<String>(
                    Arrays.asList(
                            "scheduler.defaultProvider",
                            "scheduler.defaultModel",
                            "compression.summaryProvider",
                            "compression.summaryModel",
                            "learning.modelProvider",
                            "learning.model",
                            "skills.curator.aiProvider",
                            "skills.curator.aiModel",
                            "proactive.modelProvider",
                            "proactive.model",
                            "approvals.modelProvider",
                            "approvals.model"));

    /** 禁止创建无状态工具实例。 */
    private ModelConfigKeySupport() {}

    /**
     * 判断配置键是否必须走模型专用入口。
     *
     * @param key 原始或带 solonclaw 前缀的配置键。
     * @return 模型、Provider、备用链或后台任务模型路由键返回 true。
     */
    public static boolean isDedicatedKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).trim();
        if (normalized.startsWith("solonclaw.")) {
            normalized = normalized.substring("solonclaw.".length());
        }
        return "model".equals(normalized)
                || normalized.startsWith("model.")
                || "providers".equals(normalized)
                || normalized.startsWith("providers.")
                || "fallbackProviders".equals(normalized)
                || normalized.startsWith("fallbackProviders.")
                || normalized.startsWith("fallbackProviders[")
                || TASK_MODEL_KEYS.contains(normalized);
    }

    /**
     * 拒绝模型配置键通过通用配置入口写入。
     *
     * @param key 待检查配置键。
     */
    public static void requireGeneralConfigKey(String key) {
        if (isDedicatedKey(key)) {
            throw new IllegalArgumentException(DEDICATED_ENTRY_MESSAGE);
        }
    }
}
