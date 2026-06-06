package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** 校验密钥Value安全边界，阻止不符合约束的运行路径。 */
public final class SecretValueGuard {
    /** PLACEHOLDER密钥VALUES的统一常量值。 */
    private static final Set<String> PLACEHOLDER_SECRET_VALUES =
            new LinkedHashSet<String>(
                    Arrays.asList(
                            "*",
                            "**",
                            "***",
                            "****",
                            "changeme",
                            "your_api_key",
                            "your-api-key",
                            "placeholder",
                            "example",
                            "dummy",
                            "null",
                            "none"));

    /** 创建密钥Value保护实例。 */
    private SecretValueGuard() {}

    /**
     * 判断是否存在Usable密钥。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Usable密钥满足条件则返回 true，否则返回 false。
     */
    public static boolean hasUsableSecret(String value) {
        return hasUsableSecret(value, 4);
    }

    /**
     * 判断是否存在Usable密钥。
     *
     * @param value 待规范化或校验的原始值。
     * @param minLength minLength 参数。
     * @return 如果Usable密钥满足条件则返回 true，否则返回 false。
     */
    public static boolean hasUsableSecret(String value, int minLength) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        String cleaned = value.trim();
        if (cleaned.length() < minLength) {
            return false;
        }
        return !isPlaceholderSecret(value);
    }

    /**
     * 判断是否Placeholder密钥。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Placeholder密钥满足条件则返回 true，否则返回 false。
     */
    public static boolean isPlaceholderSecret(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return PLACEHOLDER_SECRET_VALUES.contains(normalized) || normalized.contains("...");
    }
}
