package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.util.regex.Pattern;

/** 语义阶段说明的显式协议与安全清理工具，供模型运行和历史恢复统一复用。 */
public final class ProgressUpdateSanitizer {
    /** 模型声明多步骤阶段说明时必须使用的稳定前缀。 */
    public static final String DECLARATION_PREFIX = "【阶段说明】";

    /** 单条语义阶段说明最大字符数。 */
    public static final int MAX_CHARS = 240;

    /** 阶段说明中禁止出现的思维链、内部提示词和元指令标记。 */
    private static final Pattern UNSAFE_PATTERN =
            Pattern.compile(
                    "(?i)(</?(?:think|analysis|reasoning|reflection)\\b|chain[ _-]?of[ _-]?thought|system[ _-]?prompt|developer[ _-]?message|internal[ _-]?(?:prompt|instruction|reasoning)|思维链|系统提示词|开发者消息|内部(?:提示词|指令|推理)|推理过程)");

    /** 将换行和连续空白统一压缩为单个空格。 */
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /** 禁止创建无状态工具实例。 */
    private ProgressUpdateSanitizer() {}

    /**
     * 清理模型显式声明的阶段说明；未使用协议前缀的普通工具前文本不会被展示。
     *
     * @param value 模型工具调用轮的可见正文。
     * @return 已移除协议前缀的安全单行文本；未声明或不安全时返回空串。
     */
    public static String sanitizeDeclared(String value) {
        String normalized = singleLine(value);
        if (!normalized.startsWith(DECLARATION_PREFIX)) {
            return "";
        }
        return sanitize(normalized.substring(DECLARATION_PREFIX.length()));
    }

    /**
     * 清理历史中的阶段说明正文，兼容升级前尚未使用显式协议前缀的安全记录。
     *
     * @param value 待清理的历史正文。
     * @return 脱敏、单行且不超过 240 字符的正文；疑似内部推理时返回空串。
     */
    public static String sanitize(String value) {
        String normalized = singleLine(value);
        if (normalized.startsWith(DECLARATION_PREFIX)) {
            normalized = normalized.substring(DECLARATION_PREFIX.length()).trim();
        }
        if (StrUtil.isBlank(normalized) || UNSAFE_PATTERN.matcher(normalized).find()) {
            return "";
        }
        String redacted = singleLine(SecretRedactor.redact(normalized));
        if (StrUtil.isBlank(redacted) || UNSAFE_PATTERN.matcher(redacted).find()) {
            return "";
        }
        if (redacted.length() <= MAX_CHARS) {
            return redacted;
        }
        return redacted.substring(0, MAX_CHARS - 3) + "...";
    }

    /** 将任意文本规整为单行，确保后续长度和安全判断基于最终展示形态。 */
    private static String singleLine(String value) {
        return WHITESPACE_PATTERN
                .matcher(stripFormatCharacters(StrUtil.nullToEmpty(value)))
                .replaceAll(" ")
                .trim();
    }

    /**
     * 移除全部 Unicode 格式控制字符，避免用零宽字符、双向控制符或字节序标记拆分敏感标记。
     *
     * @param value 待清理文本。
     * @return 不包含 Unicode FORMAT 字符的文本。
     */
    private static String stripFormatCharacters(String value) {
        StringBuilder cleaned = null;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int charCount = Character.charCount(codePoint);
            if (Character.getType(codePoint) == Character.FORMAT) {
                if (cleaned == null) {
                    cleaned = new StringBuilder(value.length());
                    cleaned.append(value, 0, offset);
                }
            } else if (cleaned != null) {
                cleaned.appendCodePoint(codePoint);
            }
            offset += charCount;
        }
        return cleaned == null ? value : cleaned.toString();
    }
}
