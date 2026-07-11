package com.jimuqu.solon.claw.engine;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;

/** 承载上下文tokenEstimator相关状态和辅助逻辑。 */
final class ContextTokenEstimator {
    /** 数据URIMARKER的统一常量值。 */
    private static final String DATA_URI_MARKER = "[inline-media]";

    /** 图片MARKER的统一常量值。 */
    private static final String IMAGE_MARKER = "[image]";

    /** 创建上下文token Estimator实例。 */
    private ContextTokenEstimator() {}

    /**
     * 执行estimate相关逻辑。
     *
     * @param content 待处理内容。
     * @return 返回estimate结果。
     */
    static int estimate(String content) {
        return estimate(content, true, false);
    }

    /**
     * 估算For Budget。
     *
     * @param content 待处理内容。
     * @return 返回For Budget结果。
     */
    static int estimateForBudget(String content) {
        return estimate(content, false, true);
    }

    /**
     * 执行estimate相关逻辑。
     *
     * @param content 待处理内容。
     * @param roundAsciiUp roundAsciiUp 参数。
     * @param minimumAsciiToken minimumAsciitoken参数。
     * @return 返回estimate结果。
     */
    private static int estimate(String content, boolean roundAsciiUp, boolean minimumAsciiToken) {
        if (StrUtil.isBlank(content)) {
            return 0;
        }
        ImageTokenEstimate imageEstimate = maskImagePlaceholders(maskInlineDataUris(content));
        String text = imageEstimate.text;
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
                        + (minimumAsciiToken ? Math.max(1L, asciiTokens) : asciiTokens)
                        + imageEstimate.imageCount
                                * CompressionConstants.IMAGE_ATTACHMENT_ESTIMATED_TOKENS;
        return estimated > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(1, (int) estimated);
    }

    /**
     * 脱敏Inline Data Uris。
     *
     * @param content 待处理内容。
     * @return 返回Inline Data Uris结果。
     */
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

    /**
     * 脱敏图片Placeholders。
     *
     * @param content 待处理内容。
     * @return 返回图片Placeholders结果。
     */
    private static ImageTokenEstimate maskImagePlaceholders(String content) {
        if (StrUtil.isBlank(content)) {
            return new ImageTokenEstimate(content, 0);
        }
        StringBuilder buffer = new StringBuilder(content.length());
        int imageCount = 0;
        int scanFrom = 0;
        while (scanFrom < content.length()) {
            int marker = indexOfImageMarker(content, scanFrom);
            if (marker < 0) {
                buffer.append(content, scanFrom, content.length());
                break;
            }
            buffer.append(content, scanFrom, marker).append(IMAGE_MARKER);
            imageCount++;
            int lineEnd = content.indexOf('\n', marker);
            scanFrom = lineEnd < 0 ? content.length() : lineEnd;
        }
        if (imageCount == 0) {
            return new ImageTokenEstimate(content, 0);
        }
        return new ImageTokenEstimate(buffer.toString(), imageCount);
    }

    /**
     * 执行索引Of图片Marker相关逻辑。
     *
     * @param content 待处理内容。
     * @param fromIndex from索引参数。
     * @return 返回index Of图片Marker结果。
     */
    private static int indexOfImageMarker(String content, int fromIndex) {
        for (int i = fromIndex; i < content.length(); i++) {
            if (startsWithIgnoreCase(content, i, "MEDIA:")) {
                return i;
            }
            if (startsWithIgnoreCase(content, i, "ATTACHMENT:")) {
                return i;
            }
            if (startsWithIgnoreCase(content, i, "image_url")) {
                return i;
            }
        }
        return -1;
    }

    /** 承载图片tokenEstimate相关状态和辅助逻辑。 */
    private static final class ImageTokenEstimate {
        /** 记录图片tokenEstimate中的文本。 */
        private final String text;

        /** 记录图片tokenEstimate中的图片次数。 */
        private final int imageCount;

        /**
         * 创建图片token Estimate实例，并注入运行所需依赖。
         *
         * @param text 待处理文本。
         * @param imageCount 图片Count参数。
         */
        private ImageTokenEstimate(String text, int imageCount) {
            this.text = text;
            this.imageCount = imageCount;
        }
    }

    /**
     * 执行索引Of数据URIPrefix相关逻辑。
     *
     * @param content 待处理内容。
     * @param fromIndex from索引参数。
     * @return 返回index Of Data URI Prefix结果。
     */
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

    /**
     * 判断是否以忽略Case开头。
     *
     * @param content 待处理内容。
     * @param offset 分页偏移量。
     * @param prefix prefix 参数。
     * @return 返回starts With忽略Case结果。
     */
    private static boolean startsWithIgnoreCase(String content, int offset, String prefix) {
        if (offset < 0 || offset + prefix.length() > content.length()) {
            return false;
        }
        return content.regionMatches(true, offset, prefix, 0, prefix.length());
    }

    /**
     * 查找Base64 End。
     *
     * @param content 待处理内容。
     * @param fromIndex from索引参数。
     * @return 返回Base64 End结果。
     */
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
