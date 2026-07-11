package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 提取工具参数中的文件路径和写入意图，安全策略服务只保留策略判定。 */
final class SecurityPathExtractionSupport {
    private SecurityPathExtractionSupport() {}

    /**
     * 创建路径提取辅助对象。
     *
     * @return 返回新的路径提取辅助对象。
     */
    static SecurityPathExtractionSupport create() {
        return new SecurityPathExtractionSupport();
    }

    /**
     * 提取Paths。
     *
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     * @return 返回Paths结果。
     */
    List<String> extractPaths(String toolName, Map<String, Object> args) {
        List<String> paths = new ArrayList<String>();
        if (args == null) {
            return paths;
        }
        collectPaths(args, paths);
        if (ToolNameConstants.PATCH.equals(toolName) || hasPatchIntent(args)) {
            collectPatchTexts(args, paths);
        }
        return paths;
    }

    /**
     * 收集Patch Texts。
     *
     * @param raw 原始输入值。
     * @param paths 文件或目录路径参数。
     */
    @SuppressWarnings("unchecked")
    private void collectPatchTexts(Object raw, List<String> paths) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (looksLikePatchTextKey(key)) {
                    extractPatchPaths(value, paths);
                } else {
                    collectPatchTexts(value, paths);
                }
            }
            return;
        }
        if (raw instanceof Collection) {
            for (Object value : (Collection<Object>) raw) {
                collectPatchTexts(value, paths);
            }
            return;
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                collectPatchTexts(java.lang.reflect.Array.get(raw, i), paths);
            }
        }
    }

    /**
     * 判断是否具有补丁文本键特征。
     *
     * @param key 配置键或映射键。
     * @return 返回looks Like Patch Text键结果。
     */
    private static boolean looksLikePatchTextKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).trim().toLowerCase(Locale.ROOT);
        return "patch".equals(normalized)
                || "diff".equals(normalized)
                || "content".equals(normalized)
                || "input".equals(normalized);
    }

    /**
     * 收集Paths。
     *
     * @param raw 原始输入值。
     * @param paths 文件或目录路径参数。
     */
    @SuppressWarnings("unchecked")
    private void collectPaths(Object raw, List<String> paths) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (looksLikePathKey(key)) {
                    addPathValue(paths, value);
                } else {
                    collectPaths(value, paths);
                }
            }
            return;
        }
        if (raw instanceof Collection) {
            for (Object value : (Collection<Object>) raw) {
                collectPaths(value, paths);
            }
            return;
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                collectPaths(java.lang.reflect.Array.get(raw, i), paths);
            }
        }
    }

    /**
     * 判断是否具有路径键特征。
     *
     * @param key 配置键或映射键。
     * @return 返回looks Like路径键结果。
     */
    private static boolean looksLikePathKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).toLowerCase(Locale.ROOT);
        return "path".equals(normalized)
                || "paths".equals(normalized)
                || "file".equals(normalized)
                || "files".equals(normalized)
                || "filename".equals(normalized)
                || "filenames".equals(normalized)
                || "file_name".equals(normalized)
                || "file_names".equals(normalized)
                || "file_path".equals(normalized)
                || "file_paths".equals(normalized)
                || "filepath".equals(normalized)
                || "filepaths".equals(normalized)
                || "dir".equals(normalized)
                || "dirs".equals(normalized)
                || "cwd".equals(normalized)
                || "workdir".equals(normalized)
                || "working_dir".equals(normalized)
                || "workingdirectory".equals(normalized)
                || "dirname".equals(normalized)
                || "dirnames".equals(normalized)
                || "directory".equals(normalized)
                || "directories".equals(normalized)
                || "output_file".equals(normalized)
                || "outputfile".equals(normalized)
                || "out_file".equals(normalized)
                || "outfile".equals(normalized)
                || "destination".equals(normalized)
                || "dest".equals(normalized)
                || "target_file".equals(normalized)
                || "targetfile".equals(normalized)
                || normalized.endsWith("_path")
                || normalized.endsWith("_paths")
                || normalized.endsWith("path")
                || normalized.endsWith("_file")
                || normalized.endsWith("file");
    }

    /**
     * 提取Patch Paths。
     *
     * @param raw 原始输入值。
     * @param paths 文件或目录路径参数。
     */
    private void extractPatchPaths(Object raw, List<String> paths) {
        if (raw == null) {
            return;
        }
        String text = String.valueOf(raw);
        Matcher matcher =
                Pattern.compile(
                                "^\\*\\*\\*\\s+(?:Update|Add|Delete)\\s+File:\\s*(.+)$",
                                Pattern.MULTILINE)
                        .matcher(text);
        while (matcher.find()) {
            addPathValue(paths, matcher.group(1));
        }
        Matcher moveMatcher =
                Pattern.compile(
                                "^\\*\\*\\*\\s+Move\\s+File:\\s*(.+?)\\s*->\\s*(.+)$",
                                Pattern.MULTILINE)
                        .matcher(text);
        while (moveMatcher.find()) {
            addPathValue(paths, moveMatcher.group(1));
            addPathValue(paths, moveMatcher.group(2));
        }
        Matcher moveSourceMatcher =
                Pattern.compile(
                                "^\\*\\*\\*\\s+Move\\s+File:\\s*(?!.*\\s->\\s)(.+)$",
                                Pattern.MULTILINE)
                        .matcher(text);
        while (moveSourceMatcher.find()) {
            addPathValue(paths, moveSourceMatcher.group(1));
        }
        Matcher moveToMatcher =
                Pattern.compile("^\\*\\*\\*\\s+Move\\s+to:\\s*(.+)$", Pattern.MULTILINE)
                        .matcher(text);
        while (moveToMatcher.find()) {
            addPathValue(paths, moveToMatcher.group(1));
        }
        Matcher gitDiffMatcher =
                Pattern.compile("^diff\\s+--git\\s+a/(\\S+)\\s+b/(\\S+).*$", Pattern.MULTILINE)
                        .matcher(text);
        while (gitDiffMatcher.find()) {
            addPathValue(paths, gitDiffMatcher.group(1));
            addPathValue(paths, gitDiffMatcher.group(2));
        }
        Matcher gitRenameMatcher =
                Pattern.compile("^(?:rename|copy)\\s+(?:from|to)\\s+(.+)$", Pattern.MULTILINE)
                        .matcher(text);
        while (gitRenameMatcher.find()) {
            addPathValue(paths, gitRenameMatcher.group(1));
        }
        Matcher unifiedHeaderMatcher =
                Pattern.compile("^(?:---|\\+\\+\\+)\\s+(?:a/|b/)?([^\\s]+).*$", Pattern.MULTILINE)
                        .matcher(text);
        while (unifiedHeaderMatcher.find()) {
            String value = unifiedHeaderMatcher.group(1);
            if (!"/dev/null".equals(value)) {
                addPathValue(paths, value);
            }
        }
    }

    /**
     * 追加路径值。
     *
     * @param paths 文件或目录路径参数。
     * @param raw 原始输入值。
     */
    private void addPathValue(List<String> paths, Object raw) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Map) {
            collectPaths(raw, paths);
            return;
        }
        if (raw instanceof Collection) {
            for (Object item : (Collection<?>) raw) {
                addPathValue(paths, item);
            }
            return;
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                addPathValue(paths, java.lang.reflect.Array.get(raw, i));
            }
            return;
        }
        String value = StrUtil.nullToEmpty(String.valueOf(raw)).trim();
        if (value.length() > 0) {
            paths.add(value);
        }
    }

    /**
     * 判断是否Write Like工具。
     *
     * @param toolName 工具名称。
     * @return 如果Write Like工具满足条件则返回 true，否则返回 false。
     */
    static boolean isWriteLikeTool(String toolName) {
        String normalized = StrUtil.nullToEmpty(toolName).trim().toLowerCase(Locale.ROOT);
        return "file_write".equals(normalized)
                || "file_delete".equals(normalized)
                || "write_file".equals(normalized)
                || "delete_file".equals(normalized)
                || "file_remove".equals(normalized)
                || "remove_file".equals(normalized)
                || "unlink_file".equals(normalized)
                || "file_append".equals(normalized)
                || "append_file".equals(normalized)
                || "file_move".equals(normalized)
                || "move_file".equals(normalized)
                || "file_rename".equals(normalized)
                || "rename_file".equals(normalized)
                || "file_mkdir".equals(normalized)
                || "mkdir".equals(normalized)
                || "create_file".equals(normalized)
                || "edit_file".equals(normalized)
                || ToolNameConstants.PATCH.equals(toolName);
    }

    /**
     * 判断是否存在Write Intent。
     *
     * @param raw 原始输入值。
     * @return 如果Write Intent满足条件则返回 true，否则返回 false。
     */
    @SuppressWarnings("unchecked")
    static boolean hasWriteIntent(Object raw) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if ((looksLikeActionKey(key) || looksLikeToolNameKey(key))
                        && isWriteIntentValue(value)) {
                    return true;
                }
                if (hasWriteIntent(value)) {
                    return true;
                }
            }
            return false;
        }
        if (raw instanceof Collection) {
            for (Object value : (Collection<Object>) raw) {
                if (hasWriteIntent(value)) {
                    return true;
                }
            }
            return false;
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                if (hasWriteIntent(java.lang.reflect.Array.get(raw, i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断是否存在Patch Intent。
     *
     * @param raw 原始输入值。
     * @return 如果Patch Intent满足条件则返回 true，否则返回 false。
     */
    @SuppressWarnings("unchecked")
    private static boolean hasPatchIntent(Object raw) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if ((looksLikeActionKey(key) || looksLikeToolNameKey(key))
                        && isPatchIntentValue(value)) {
                    return true;
                }
                if (hasPatchIntent(value)) {
                    return true;
                }
            }
            return false;
        }
        if (raw instanceof Collection) {
            for (Object value : (Collection<Object>) raw) {
                if (hasPatchIntent(value)) {
                    return true;
                }
            }
            return false;
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                if (hasPatchIntent(java.lang.reflect.Array.get(raw, i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断是否具有工具名称键特征。
     *
     * @param key 配置键或映射键。
     * @return 返回looks Like工具名称键结果。
     */
    private static boolean looksLikeToolNameKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).trim().toLowerCase(Locale.ROOT);
        return "tool".equals(normalized)
                || "name".equals(normalized)
                || "tool_name".equals(normalized)
                || "toolname".equals(normalized);
    }

    /**
     * 判断是否具有Action键特征。
     *
     * @param key 配置键或映射键。
     * @return 返回looks Like Action键结果。
     */
    private static boolean looksLikeActionKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).trim().toLowerCase(Locale.ROOT);
        return "action".equals(normalized)
                || "operation".equals(normalized)
                || "op".equals(normalized)
                || "mode".equals(normalized)
                || "method".equals(normalized);
    }

    /**
     * 判断是否Patch Intent Value。
     *
     * @param raw 原始输入值。
     * @return 如果Patch Intent Value满足条件则返回 true，否则返回 false。
     */
    private static boolean isPatchIntentValue(Object raw) {
        String value = StrUtil.nullToEmpty(String.valueOf(raw)).trim().toLowerCase(Locale.ROOT);
        return "patch".equals(value);
    }

    /**
     * 判断是否Write Intent Value。
     *
     * @param raw 原始输入值。
     * @return 如果Write Intent Value满足条件则返回 true，否则返回 false。
     */
    private static boolean isWriteIntentValue(Object raw) {
        String value = StrUtil.nullToEmpty(String.valueOf(raw)).trim().toLowerCase(Locale.ROOT);
        return "write".equals(value)
                || "file_write".equals(value)
                || "write_file".equals(value)
                || "append".equals(value)
                || "file_append".equals(value)
                || "append_file".equals(value)
                || "delete".equals(value)
                || "file_delete".equals(value)
                || "delete_file".equals(value)
                || "remove".equals(value)
                || "remove_file".equals(value)
                || "move".equals(value)
                || "file_move".equals(value)
                || "move_file".equals(value)
                || "rename".equals(value)
                || "file_rename".equals(value)
                || "rename_file".equals(value)
                || "create".equals(value)
                || "create_file".equals(value)
                || "file_create".equals(value)
                || "mkdir".equals(value)
                || "file_mkdir".equals(value)
                || "edit".equals(value)
                || "edit_file".equals(value)
                || "patch".equals(value)
                || "install".equals(value)
                || "update".equals(value)
                || "save".equals(value);
    }
}
