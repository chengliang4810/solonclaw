package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses explicit MEDIA: delivery directives from plain response text. */
public final class MediaDirectiveSupport {
    private static final Pattern MEDIA_PATTERN =
            Pattern.compile(
                    "MEDIA:\\s*(?<path>\"[^\"\\n]+\"|'[^'\\n]+'|\\S+)",
                    Pattern.CASE_INSENSITIVE);

    private MediaDirectiveSupport() {}

    public static List<MediaDirective> parse(String content) {
        String value = StrUtil.nullToEmpty(content);
        List<MediaDirective> refs = new ArrayList<MediaDirective>();
        Matcher matcher = MEDIA_PATTERN.matcher(value);
        while (matcher.find()) {
            if (insideMarkdownCode(value, matcher.start())) {
                continue;
            }
            String path = cleanupMediaPath(matcher.group("path"));
            if (StrUtil.isNotBlank(path)) {
                refs.add(new MediaDirective(matcher.group(), path));
            }
        }
        return refs;
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
