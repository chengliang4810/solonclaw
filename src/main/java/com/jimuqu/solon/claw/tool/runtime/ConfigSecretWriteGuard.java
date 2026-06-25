package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SecretValueGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 保护对话式文件编辑，避免脱敏后的配置密钥占位符被写回工作区配置文件。 */
final class ConfigSecretWriteGuard {
    /** YAML 冒号配置行，覆盖 apiKey、token、secret 等敏感配置键。 */
    private static final Pattern YAML_SECRET_FIELD =
            Pattern.compile(
                    "(?im)^([ \\t-]*)([A-Za-z0-9_.-]*(?:api[_-]?key|apikey|token|secret|password|passwd|credential|access[_-]?token|refresh[_-]?token|bearer[_-]?token|client[_-]?secret|private[_-]?key)[A-Za-z0-9_.-]*)([ \\t]*:[ \\t]*)(['\"]?)([^\\r\\n#]*?)(\\4)([ \\t]*(?:#.*)?)$");

    /** JSON 风格配置字段，覆盖工具输入中可能出现的对象形式配置。 */
    private static final Pattern JSON_SECRET_FIELD =
            Pattern.compile(
                    "(?i)(\"([A-Za-z0-9_.-]*(?:api_?key|apikey|token|secret|password|passwd|credential|access_?token|refresh_?token|bearer_?token|client_?secret|private_?key)[A-Za-z0-9_.-]*)\"\\s*:\\s*\")([^\"]*)(\")");

    /** 创建配置密钥写入保护实例。 */
    private ConfigSecretWriteGuard() {}

    /**
     * 对文件写入内容执行密钥占位符降级保护。
     *
     * @param target 目标文件路径。
     * @param oldContent 写入前的文件内容；新增文件可为空。
     * @param newContent 即将写入的文件内容。
     */
    static void assertNoPlaceholderSecretDowngrade(Path target, String oldContent, String newContent) {
        if (!looksLikeConfigFile(target) || StrUtil.equals(oldContent, newContent)) {
            return;
        }
        Map<String, String> oldSecrets = extractSecretFields(oldContent);
        if (!hasAnyUsableSecret(oldSecrets)) {
            return;
        }
        Map<String, String> newSecrets = extractSecretFields(newContent);
        for (Map.Entry<String, String> entry : newSecrets.entrySet()) {
            String newValue = entry.getValue();
            if (SecretValueGuard.isPlaceholderSecret(newValue)) {
                throw new IllegalArgumentException(
                        "拒绝写入配置密钥占位符："
                                + safePath(target)
                                + " 中的 "
                                + safeKey(entry.getKey())
                                + " 原本已配置真实密钥，新内容包含脱敏或示例占位值。请使用 config_set_secret 单独更新密钥。");
            }
        }
    }

    /**
     * 判断已有配置文本里是否存在任意可用密钥；只要存在真实凭据，后续整文件写入就不能把敏感字段降级为展示占位符。
     *
     * @param values 敏感字段快照。
     * @return 存在可用密钥时返回 true。
     */
    private static boolean hasAnyUsableSecret(Map<String, String> values) {
        for (String value : values.values()) {
            if (SecretValueGuard.hasUsableSecret(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断文件是否属于需要保护的配置文本。
     *
     * @param target 目标文件路径。
     * @return 如果文件名或路径符合配置文件特征则返回 true。
     */
    private static boolean looksLikeConfigFile(Path target) {
        if (target == null || target.getFileName() == null) {
            return false;
        }
        String name = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".yml")
                || name.endsWith(".yaml")
                || name.endsWith(".json")
                || name.endsWith(".toml")
                || name.endsWith(".properties")) {
            return true;
        }
        String path = target.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return path.endsWith("/config") || path.endsWith("/config.local");
    }

    /**
     * 从配置文本中提取敏感字段的原始值。
     *
     * @param content 配置文本。
     * @return 以规范化字段名为键的敏感字段快照。
     */
    private static Map<String, String> extractSecretFields(String content) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        String text = StrUtil.nullToEmpty(content);
        Matcher yamlMatcher = YAML_SECRET_FIELD.matcher(text);
        while (yamlMatcher.find()) {
            putSecret(values, yamlMatcher.group(2), yamlMatcher.group(5));
        }
        Matcher jsonMatcher = JSON_SECRET_FIELD.matcher(text);
        while (jsonMatcher.find()) {
            putSecret(values, jsonMatcher.group(2), jsonMatcher.group(3));
        }
        return values;
    }

    /**
     * 追加敏感字段，跳过空键。
     *
     * @param values 字段快照。
     * @param rawKey 原始字段名。
     * @param rawValue 原始字段值。
     */
    private static void putSecret(Map<String, String> values, String rawKey, String rawValue) {
        String key = normalizeKey(rawKey);
        if (StrUtil.isBlank(key)) {
            return;
        }
        values.put(key, StrUtil.nullToEmpty(rawValue).trim());
    }

    /**
     * 规范化敏感字段名，保证 api-key、api_key 和 apiKey 等形式可以比较。
     *
     * @param rawKey 原始字段名。
     * @return 规范化后的字段名。
     */
    private static String normalizeKey(String rawKey) {
        return StrUtil.nullToEmpty(rawKey)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "");
    }

    /**
     * 生成安全展示用路径。
     *
     * @param target 目标文件路径。
     * @return 脱敏后的文件路径。
     */
    private static String safePath(Path target) {
        try {
            if (target != null && Files.exists(target)) {
                return SecretRedactor.redact(target.toRealPath().toString(), 400);
            }
        } catch (Exception pathResolutionFailure) {
            // 路径仅用于错误展示，解析失败时继续回退到原始字符串脱敏，避免泄漏完整真实路径。
        }
        return SecretRedactor.redact(String.valueOf(target), 400);
    }

    /**
     * 生成安全展示用字段名。
     *
     * @param key 配置键。
     * @return 脱敏后的字段名。
     */
    private static String safeKey(String key) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(key), 120);
    }
}
