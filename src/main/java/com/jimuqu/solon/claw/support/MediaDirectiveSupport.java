package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses explicit MEDIA: delivery directives from plain response text. */
public final class MediaDirectiveSupport {
    private static final Pattern MEDIA_PREFIX =
            Pattern.compile("MEDIA:\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEDIA_EXTENSION_BOUNDARY =
            Pattern.compile(
                    "\\.(?:png|jpe?g|gif|webp|bmp|heic|heif|mp4|mov|avi|mkv|webm|3gp|m4v|silk|ogg|opus|mp3|wav|m4a|aac|flac|amr|pdf|docx?|xlsx?|pptx?|txt|csv|md|markdown|json|zip|rar|7z|apk|ipa)(?=$|[\\s`\"',;:)\\]}])",
                    Pattern.CASE_INSENSITIVE);

    private MediaDirectiveSupport() {}

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
                refs.add(new MediaDirective(value.substring(matcher.start(), parsed.tokenEnd), parsed.path));
            }
        }
        return refs;
    }

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

    private static boolean isTrailingPathPunctuation(char c) {
        return c == '"' || c == '\'' || c == ',' || c == ';' || c == ':' || c == ')' || c == '}' || c == ']';
    }

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

    private static final class ParsedMediaPath {
        private final int tokenEnd;
        private final String path;

        private ParsedMediaPath(int tokenEnd, String path) {
            this.tokenEnd = tokenEnd;
            this.path = StrUtil.nullToEmpty(path);
        }
    }

    public static final class MediaDirective {
        private final String token;
        private final String path;

        private MediaDirective(String token, String path) {
            this.token = StrUtil.nullToEmpty(token);
            this.path = StrUtil.nullToEmpty(path);
        }

        public String getToken() {
            return token;
        }

        public String getPath() {
            return path;
        }
    }
}
