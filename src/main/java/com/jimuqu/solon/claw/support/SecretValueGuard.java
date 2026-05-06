package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Shared guard for example or placeholder secrets. */
public final class SecretValueGuard {
    private static final Set<String> PLACEHOLDER_SECRET_VALUES =
            new LinkedHashSet<String>(
                    Arrays.asList(
                            "*",
                            "**",
                            "***",
                            "changeme",
                            "your_api_key",
                            "your-api-key",
                            "placeholder",
                            "example",
                            "dummy",
                            "null",
                            "none"));

    private SecretValueGuard() {}

    public static boolean hasUsableSecret(String value) {
        return hasUsableSecret(value, 4);
    }

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

    public static boolean isPlaceholderSecret(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return PLACEHOLDER_SECRET_VALUES.contains(normalized);
    }
}
