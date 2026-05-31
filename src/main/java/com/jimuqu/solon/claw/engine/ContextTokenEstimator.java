package com.jimuqu.solon.claw.engine;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;

/** Shared rough token estimator for context budget checks. */
final class ContextTokenEstimator {
    private static final String DATA_URI_MARKER = "[inline-media]";

    private ContextTokenEstimator() {}

    static int estimate(String content) {
        return estimate(content, true, false);
    }

    static int estimateForBudget(String content) {
        return estimate(content, false, true);
    }

    private static int estimate(String content, boolean roundAsciiUp, boolean minimumAsciiToken) {
        if (StrUtil.isBlank(content)) {
            return 0;
        }
        String text = maskInlineDataUris(content);
        long asciiCount = 0L;
        long nonAsciiCount = 0L;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) <= 0x7F) {
                asciiCount++;
            } else {
                nonAsciiCount++;
            }
        }

        long asciiTokens =
                roundAsciiUp
                        ? (asciiCount + CompressionConstants.CHARS_PER_TOKEN - 1L)
                                / CompressionConstants.CHARS_PER_TOKEN
                        : asciiCount / CompressionConstants.CHARS_PER_TOKEN;
        long estimated =
                nonAsciiCount
                        + (minimumAsciiToken ? Math.max(1L, asciiTokens) : asciiTokens);
        return estimated > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(1, (int) estimated);
    }

    private static String maskInlineDataUris(String content) {
        StringBuilder buffer = null;
        int scanFrom = 0;
        while (scanFrom < content.length()) {
            int prefix = indexOfDataUriPrefix(content, scanFrom);
            if (prefix < 0) {
                break;
            }
            int comma = content.indexOf(',', prefix);
            if (comma < 0) {
                break;
            }
            String header = content.substring(prefix, comma).toLowerCase();
            if (!header.contains(";base64")) {
                scanFrom = comma + 1;
                continue;
            }
            int end = findBase64End(content, comma + 1);
            if (end <= comma + 1) {
                scanFrom = comma + 1;
                continue;
            }
            if (buffer == null) {
                buffer = new StringBuilder(content.length());
            }
            buffer.append(content, scanFrom, prefix).append(DATA_URI_MARKER);
            scanFrom = end;
        }
        if (buffer == null) {
            return content;
        }
        buffer.append(content, scanFrom, content.length());
        return buffer.toString();
    }

    private static int indexOfDataUriPrefix(String content, int fromIndex) {
        for (int i = fromIndex; i <= content.length() - 11; i++) {
            if (startsWithIgnoreCase(content, i, "data:image/")
                    || startsWithIgnoreCase(content, i, "data:audio/")
                    || startsWithIgnoreCase(content, i, "data:video/")) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWithIgnoreCase(String content, int offset, String prefix) {
        if (offset < 0 || offset + prefix.length() > content.length()) {
            return false;
        }
        return content.regionMatches(true, offset, prefix, 0, prefix.length());
    }

    private static int findBase64End(String content, int fromIndex) {
        int i = fromIndex;
        while (i < content.length()) {
            char ch = content.charAt(i);
            if ((ch >= 'A' && ch <= 'Z')
                    || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '+'
                    || ch == '/'
                    || ch == '='
                    || ch == '\r'
                    || ch == '\n') {
                i++;
                continue;
            }
            break;
        }
        return i;
    }
}
