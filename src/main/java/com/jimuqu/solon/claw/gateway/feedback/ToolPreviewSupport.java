package com.jimuqu.solon.claw.gateway.feedback;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;

import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;

import org.noear.snack4.ONode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 工具参数预览辅助。 */
public final class ToolPreviewSupport {
    /** 创建工具Preview辅助实例。 */
    private ToolPreviewSupport() {}

    /**
     * 构建可发送到消息渠道的工具调用参数预览。
     *
     * @param toolName 工具名称。
     * @param args 工具调用参数。
     * @param maxLen 渠道消息中允许展示的最大长度。
     * @param verbose 是否展示完整 JSON 参数。
     * @return 已脱敏、已截断的预览文本。
     */
    public static String buildPreview(
            String toolName, Map<String, Object> args, int maxLen, boolean verbose) {
        if (MapUtil.isEmpty(args)) {
            return "";
        }

        Map<String, Object> safeArgs = sanitizeArgs(toolName, args);
        String preview;
        if (verbose) {
            preview = ONode.serialize(safeArgs);
        } else {
            preview = pickPrimaryValue(toolName, safeArgs);
        }

        preview = SecretRedactor.redact(normalize(preview), Math.max(maxLen * 2, 256));
        if (preview.length() <= maxLen) {
            return preview;
        }
        if (verbose) {
            return buildJsonSafePreview(safeArgs, maxLen);
        }
        return preview.substring(0, Math.max(0, maxLen - 3)) + "...";
    }

    /**
     * 在 verbose 模式下生成尽量合法且短小的 JSON 预览。
     *
     * @param args 已脱敏的工具参数。
     * @param maxLen 最大保留字符数。
     * @return 长度受控的 JSON 文本。
     */
    private static String buildJsonSafePreview(Map<String, Object> args, int maxLen) {
        try {
            Object copy = ONode.deserialize(ONode.serialize(args), Object.class);
            shrinkJsonStrings(copy, Math.max(24, maxLen / 3));
            String serialized = normalize(ONode.serialize(copy));
            if (serialized.length() <= maxLen) {
                return serialized;
            }
        } catch (Exception ignored) {
            // 保留此处实现约束，避免后续维护时破坏既有行为。
        }
        Map<String, Object> fallback = new LinkedHashMap<String, Object>();
        fallback.put("truncated", Boolean.TRUE);
        fallback.put(
                "preview", normalize(ONode.serialize(args)).substring(0, Math.max(0, maxLen - 32)));
        return normalize(ONode.serialize(fallback));
    }

    /**
     * 递归收缩 JSON 对象中的长字符串，保留字段结构用于排查。
     *
     * @param value 反序列化后的 Map/List/普通值。
     * @param maxStringLength 单个字符串字段允许保留的最大长度。
     */
    @SuppressWarnings("unchecked")
    private static void shrinkJsonStrings(Object value, int maxStringLength) {
        if (value instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) value;
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                Object item = entry.getValue();
                if (item instanceof String && ((String) item).length() > maxStringLength) {
                    entry.setValue(
                            ((String) item).substring(0, maxStringLength) + "...[truncated]");
                } else {
                    shrinkJsonStrings(item, maxStringLength);
                }
            }
        } else if (value instanceof List) {
            for (Object item : (List<?>) value) {
                shrinkJsonStrings(item, maxStringLength);
            }
        }
    }

    /**
     * 从工具参数中选择最有诊断价值的字段作为简短预览。
     *
     * @param toolName 工具名称。
     * @param args 已脱敏的工具参数。
     * @return 命中的 key=value 文本，未命中时返回整体 JSON。
     */
    private static String pickPrimaryValue(String toolName, Map<String, Object> args) {
        String[] candidates = preferredKeys(toolName);
        for (String key : candidates) {
            if (!args.containsKey(key)) {
                continue;
            }
            Object value = args.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof Iterable) {
                String text = normalize(ONode.serialize(value));
                if (StrUtil.isNotBlank(text)) {
                    return key + "=" + text;
                }
                continue;
            }
            String text = normalize(String.valueOf(value));
            if (StrUtil.isNotBlank(text)) {
                return key + "=" + text;
            }
        }
        return normalize(ONode.serialize(args));
    }

    /**
     * 根据工具类型返回优先展示的参数键。
     *
     * @param toolName 工具名称。
     * @return 按优先级排序的参数键列表。
     */
    private static String[] preferredKeys(String toolName) {
        if ("file_read".equals(toolName)
                || "file_write".equals(toolName)
                || "read_file".equals(toolName)
                || "write_file".equals(toolName)
                || "search_files".equals(toolName)
                || "file_delete".equals(toolName)
                || "patch".equals(toolName)) {
            return new String[] {"fileName", "path", "filePath"};
        }
        if ("file_list".equals(toolName)) {
            return new String[] {"dirName", "path"};
        }
        if ("execute_shell".equals(toolName)
                || "terminal".equals(toolName)
                || "execute_code".equals(toolName)
                || "execute_python".equals(toolName)
                || "execute_js".equals(toolName)) {
            return new String[] {"command", "code"};
        }
        if (ToolNameConstants.CONFIG_SET_SECRET.equals(toolName)
                || "config_update_secret".equals(toolName)) {
            return new String[] {"key"};
        }
        if (ToolNameConstants.CONFIG_GET.equals(toolName)
                || ToolNameConstants.CONFIG_SET.equals(toolName)
                || ToolNameConstants.CONFIG_REFRESH.equals(toolName)
                || "config_read".equals(toolName)
                || "config_write".equals(toolName)) {
            return new String[] {"key", "reconnectChannels"};
        }
        if ("delegate_task".equals(toolName)) {
            return new String[] {"prompt", "goal", "context"};
        }
        if ("send_message".equals(toolName)) {
            return new String[] {"text", "chatId", "platform"};
        }
        if ("session_search".equals(toolName)
                || "websearch".equals(toolName)
                || "web_search".equals(toolName)
                || "codesearch".equals(toolName)) {
            return new String[] {"query", "q", "keyword"};
        }
        if ("webfetch".equals(toolName) || "web_extract".equals(toolName)) {
            return new String[] {"url", "urls"};
        }
        if ("cronjob".equals(toolName)) {
            return new String[] {"action", "name"};
        }
        if ("skill_view".equals(toolName) || "skill_manage".equals(toolName)) {
            return new String[] {"name", "skillName"};
        }
        return new String[] {"path", "command", "code", "query", "text", "name"};
    }

    /**
     * 复制并脱敏工具参数，避免 token、密钥或密码进入渠道消息。
     *
     * @param toolName 工具名称。
     * @param args 原始工具参数。
     * @return 保持原有层级结构的安全参数副本。
     */
    private static Map<String, Object> sanitizeArgs(String toolName, Map<String, Object> args) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        boolean secretTool =
                ToolNameConstants.CONFIG_SET_SECRET.equals(toolName)
                        || "config_update_secret".equals(toolName);
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (secretTool && "value".equalsIgnoreCase(StrUtil.nullToEmpty(key))) {
                safe.put(key, "***");
            } else {
                safe.put(key, sanitizeValue(key, value));
            }
        }
        return safe;
    }

    /**
     * 递归脱敏单个参数值。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 字符串、Map 和 Iterable 的安全副本；其他对象保持原值。
     */
    @SuppressWarnings("unchecked")
    private static Object sanitizeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (isSensitiveArgKey(key)) {
            return "***";
        }
        if (value instanceof String) {
            return SecretRedactor.redact((String) value);
        }
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String nestedKey = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                result.put(nestedKey, sanitizeValue(nestedKey, entry.getValue()));
            }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) {
                result.add(sanitizeValue("", item));
            }
            return result;
        }
        return value;
    }

    /**
     * 判断参数名是否表示敏感信息。
     *
     * @param key 配置键或映射键。
     * @return 命中常见 token、secret、password 等关键词时返回 true。
     */
    private static boolean isSensitiveArgKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).toLowerCase(Locale.ROOT);
        return normalized.contains("apikey")
                || normalized.contains("api_key")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("authorization")
                || normalized.contains("privatekey")
                || normalized.contains("private_key");
    }

    /**
     * 将多行预览压成单行，适配国内消息渠道的进度提示。
     *
     * @param text 待处理文本。
     * @return 去掉首尾空白后的单行文本。
     */
    private static String normalize(String text) {
        return StrUtil.nullToEmpty(text).replace('\r', ' ').replace('\n', ' ').trim();
    }
}
