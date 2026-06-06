package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 封装媒体Directive辅助逻辑，降低主流程中的重复实现。 */
public final class MediaDirectiveSupport {
    /** 媒体PREFIX的统一常量值。 */
    private static final Pattern MEDIA_PREFIX =
            Pattern.compile("MEDIA:\\s*", Pattern.CASE_INSENSITIVE);

    /** 媒体扩展名BOUNDARY的统一常量值。 */
    private static final Pattern MEDIA_EXTENSION_BOUNDARY =
            Pattern.compile(
                    "\\.(?:png|jpe?g|gif|webp|bmp|heic|heif|mp4|mov|avi|mkv|webm|3gp|m4v|silk|ogg|opus|mp3|wav|m4a|aac|flac|amr|pdf|docx?|xlsx?|pptx?|txt|csv|md|markdown|json|zip|rar|7z|apk|ipa)(?=$|[\\s`\"',;:)\\]}])",
                    Pattern.CASE_INSENSITIVE);

    /** 创建媒体Directive辅助实例。 */
    private MediaDirectiveSupport() {}

    /**
     * 执行解析相关逻辑。
     *
     * @param content 待处理内容。
     * @return 返回parse结果。
     */
    public static List<MediaDirective> parse(String content) {
        String value = StrUtil.nullToEmpty(content);
        List<MediaDirective> refs = new ArrayList<MediaDirective>();
        Matcher matcher = MEDIA_PREFIX.matcher(value);
        while (matcher.find()) {
            if (insideMarkdownCode(value, matcher.start())) {
                continue;
            }
            ParsedMediaPath parsed = parsePath(value, matcher.end());
            if (parsed != null && StrUtil.isNotBlank(parsed.path)) {
                refs.add(
                        new MediaDirective(
                                value.substring(matcher.start(), parsed.tokenEnd), parsed.path));
            }
        }
        return refs;
    }

    /**
     * 解析路径。
     *
     * @param text 待处理文本。
     * @param start start 参数。
     * @return 返回解析后的路径。
     */
    private static ParsedMediaPath parsePath(String text, int start) {
        if (start >= text.length()) {
            return null;
        }
        char first = text.charAt(start);
        if (first == '`') {
            return null;
        }
        if (first == '"' || first == '\'') {
            int end = start + 1;
            while (end < text.length()) {
                char c = text.charAt(end);
                if (c == '\n' || c == '\r') {
                    return null;
                }
                if (c == first) {
                    return new ParsedMediaPath(
                            end + 1, cleanupMediaPath(text.substring(start, end + 1)));
                }
                end++;
            }
            return null;
        }
        int lineEnd = start;
        while (lineEnd < text.length()) {
            char c = text.charAt(lineEnd);
            if (c == '\n' || c == '\r') {
                break;
            }
            lineEnd++;
        }
        String line = text.substring(start, lineEnd);
        int length = unquotedPathLength(line);
        if (length <= 0) {
            return null;
        }
        return new ParsedMediaPath(start + length, cleanupMediaPath(line.substring(0, length)));
    }

    /**
     * 执行unquoted路径Length相关逻辑。
     *
     * @param line 行参数。
     * @return 返回unquoted路径Length结果。
     */
    private static int unquotedPathLength(String line) {
        String value = StrUtil.nullToEmpty(line);
        if (value.trim().length() == 0) {
            return 0;
        }
        Matcher matcher = MEDIA_EXTENSION_BOUNDARY.matcher(value);
        if (matcher.find()) {
            int end = matcher.end();
            while (end < value.length() && isTrailingPathPunctuation(value.charAt(end))) {
                end++;
            }
            return end;
        }
        int end = 0;
        while (end < value.length() && !Character.isWhitespace(value.charAt(end))) {
            end++;
        }
        return end;
    }

    /**
     * 判断是否Trailing路径Punctuation。
     *
     * @param c c 参数。
     * @return 如果Trailing路径Punctuation满足条件则返回 true，否则返回 false。
     */
    private static boolean isTrailingPathPunctuation(char c) {
        return c == '"' || c == '\'' || c == ',' || c == ';' || c == ':' || c == ')' || c == '}'
                || c == ']';
    }

    /**
     * 执行insideMarkdownCode相关逻辑。
     *
     * @param text 待处理文本。
     * @param index 索引参数。
     * @return 返回inside Markdown Code结果。
     */
    private static boolean insideMarkdownCode(String text, int index) {
        boolean inside = false;
        int i = 0;
        while (i < index && i < text.length()) {
            char c = text.charAt(i);
            if (c != '`') {
                i++;
                continue;
            }
            int end = i + 1;
            while (end < index && end < text.length() && text.charAt(end) == '`') {
                end++;
            }
            inside = !inside;
            i = end;
        }
        return inside;
    }

    /**
     * 执行cleanup媒体路径相关逻辑。
     *
     * @param raw 原始输入值。
     * @return 返回cleanup媒体路径。
     */
    private static String cleanupMediaPath(String raw) {
        String path = StrUtil.nullToEmpty(raw).trim();
        if (path.startsWith("`")) {
            return "";
        }
        if (path.length() >= 2) {
            char first = path.charAt(0);
            char last = path.charAt(path.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                path = path.substring(1, path.length() - 1).trim();
            }
        }
        while (path.startsWith("\"") || path.startsWith("'")) {
            path = path.substring(1).trim();
        }
        while (path.endsWith("\"")
                || path.endsWith("'")
                || path.endsWith(",")
                || path.endsWith(".")
                || path.endsWith(";")
                || path.endsWith(":")
                || path.endsWith(")")
                || path.endsWith("}")
                || path.endsWith("]")) {
            path = path.substring(0, path.length() - 1).trim();
        }
        if (path.startsWith("~/")) {
            return new File(System.getProperty("user.home"), path.substring(2)).getAbsolutePath();
        }
        return path;
    }

    /** 承载Parsed媒体路径相关状态和辅助逻辑。 */
    private static final class ParsedMediaPath {
        /** 记录Parsed媒体路径中的tokenEnd。 */
        private final int tokenEnd;

        /** 记录Parsed媒体路径中的路径。 */
        private final String path;

        /**
         * 创建Parsed媒体路径实例，并注入运行所需依赖。
         *
         * @param tokenEnd tokenEnd 参数。
         * @param path 文件或目录路径。
         */
        private ParsedMediaPath(int tokenEnd, String path) {
            this.tokenEnd = tokenEnd;
            this.path = StrUtil.nullToEmpty(path);
        }
    }

    /** 承载媒体Directive相关状态和辅助逻辑。 */
    public static final class MediaDirective {
        /** 记录媒体Directive中的token。 */
        private final String token;

        /** 记录媒体Directive中的路径。 */
        private final String path;

        /**
         * 创建媒体Directive实例，并注入运行所需依赖。
         *
         * @param token token 参数。
         * @param path 文件或目录路径。
         */
        private MediaDirective(String token, String path) {
            this.token = StrUtil.nullToEmpty(token);
            this.path = StrUtil.nullToEmpty(path);
        }

        /**
         * 读取token。
         *
         * @return 返回读取到的token。
         */
        public String getToken() {
            return token;
        }

        /**
         * 读取路径。
         *
         * @return 返回读取到的路径。
         */
        public String getPath() {
            return path;
        }
    }
}
