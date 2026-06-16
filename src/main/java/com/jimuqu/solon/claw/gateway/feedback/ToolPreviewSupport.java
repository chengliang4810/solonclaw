package com.jimuqu.solon.claw.gateway.feedback;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;

/** 工具参数预览辅助。 */
public final class ToolPreviewSupport {
    /** 创建工具Preview辅助实例。 */
    private ToolPreviewSupport() {}

    /**
     * 构建Preview。
     *
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     * @param maxLen 最大保留字符数。
     * @param verbose verbose 参数。
     * @return 返回创建好的Preview。
     */
    public static String buildPreview(
            String toolName, Map<String, Object> args, int maxLen, boolean verbose) {
        if (args == null || args.isEmpty()) {
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
     * 构建JSON Safe Preview。
     *
     * @param args 工具或命令参数。
     * @param maxLen 最大保留字符数。
     * @return 返回创建好的JSON Safe Preview。
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
     * 执行shrinkJSON字符串s相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxStringLength max字符串Length参数。
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
     * 执行pickPrimary值相关逻辑。
     *
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     * @return 返回pick Primary Value结果。
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
     * 执行preferredKeys相关逻辑。
     *
     * @param toolName 工具名称。
     * @return 返回preferred Keys结果。
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
                || "codesearch".equals(toolName)) {
            return new String[] {"query", "q", "keyword"};
        }
        if ("webfetch".equals(toolName)) {
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
     * 清理参数。
     *
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     * @return 返回参数结果。
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
     * 清理Value。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回Value结果。
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
     * 判断是否Sensitive Arg键。
     *
     * @param key 配置键或映射键。
     * @return 如果Sensitive Arg键满足条件则返回 true，否则返回 false。
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
     * 执行规范化相关逻辑。
     *
     * @param text 待处理文本。
     * @return 返回规范化结果。
     */
    private static String normalize(String text) {
        return StrUtil.nullToEmpty(text).replace('\r', ' ').replace('\n', ' ').trim();
    }
}
