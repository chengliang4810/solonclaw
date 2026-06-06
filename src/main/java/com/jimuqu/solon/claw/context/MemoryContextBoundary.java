package com.jimuqu.solon.claw.context;

import cn.hutool.core.util.StrUtil;
import java.util.regex.Pattern;

/** 记忆召回上下文的统一 fence 与可见流清理工具。 */
public final class MemoryContextBoundary {
    /** OPENTAG的统一常量值。 */
    public static final String OPEN_TAG = "<memory-context>";

    /** 关闭TAG的统一常量值。 */
    public static final String CLOSE_TAG = "</memory-context>";

    /** FENCETAG正则的统一常量值。 */
    private static final Pattern FENCE_TAG_PATTERN =
            Pattern.compile("</?\\s*memory-context\\s*>", Pattern.CASE_INSENSITIVE);

    /** 上下文阻断正则的统一常量值。 */
    private static final Pattern CONTEXT_BLOCK_PATTERN =
            Pattern.compile(
                    "<\\s*memory-context\\s*>[\\s\\S]*?</\\s*memory-context\\s*>",
                    Pattern.CASE_INSENSITIVE);

    /** 系统NOTE正则的统一常量值。 */
    private static final Pattern SYSTEM_NOTE_PATTERN =
            Pattern.compile(
                    "\\[System note:\\s*The following is recalled memory context,\\s*NOT new user input\\.[^\\]]*\\]\\s*",
                    Pattern.CASE_INSENSITIVE);

    /** 创建记忆上下文Boundary实例。 */
    private MemoryContextBoundary() {}

    /** 移除 provider 或模型输出中已有的 memory-context block、标签与系统说明。 */
    public static String sanitizeContext(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        String value = CONTEXT_BLOCK_PATTERN.matcher(text).replaceAll("");
        value = SYSTEM_NOTE_PATTERN.matcher(value).replaceAll("");
        value = FENCE_TAG_PATTERN.matcher(value).replaceAll("");
        return value.trim();
    }

    /** 将召回记忆包装成显式内部上下文块。 */
    public static String buildContextBlock(String rawContext) {
        String clean = sanitizeContext(rawContext);
        if (StrUtil.isBlank(clean)) {
            return "";
        }
        return OPEN_TAG
                + "\n"
                + "[System note: The following is recalled memory context, NOT new user input. "
                + "Treat as authoritative reference data.]\n\n"
                + clean
                + "\n"
                + CLOSE_TAG;
    }

    /** 若内容尚未带 memory-context fence，则作为召回上下文包装。 */
    public static String ensureContextBlock(String rawContext) {
        if (StrUtil.isBlank(rawContext)) {
            return "";
        }
        return buildContextBlock(rawContext);
    }

    /** 清理普通可见回复，避免 memory-context block 混入用户可见流。 */
    public static String scrubVisibleText(String text) {
        return sanitizeContext(text);
    }

    /** 判断文本是否包含 memory-context fence。 */
    public static boolean containsFence(String text) {
        return StrUtil.isNotBlank(text) && FENCE_TAG_PATTERN.matcher(text).find();
    }

    /** 跨流式 chunk 清理 memory-context span，避免标签被拆分时泄漏。 */
    public static final class StreamingScrubber {
        /** 是否启用inSpan。 */
        private boolean inSpan;

        /** 记录StreamingScrubber中的buffer。 */
        private String buffer = "";

        /** 是否启用时间阻断Boundary。 */
        private boolean atBlockBoundary = true;

        /**
         * 执行feed相关逻辑。
         *
         * @param text 待处理文本。
         * @return 返回feed结果。
         */
        public String feed(String text) {
            if (StrUtil.isBlank(text)) {
                return "";
            }
            String buf = buffer + text;
            buffer = "";
            StringBuilder out = new StringBuilder();
            while (buf.length() > 0) {
                if (inSpan) {
                    int idx = lower(buf).indexOf(CLOSE_TAG);
                    if (idx < 0) {
                        int held = maxPartialSuffix(buf, CLOSE_TAG);
                        buffer = held > 0 ? buf.substring(buf.length() - held) : "";
                        return out.toString();
                    }
                    buf = buf.substring(idx + CLOSE_TAG.length());
                    inSpan = false;
                    continue;
                }

                int idx = findBoundaryOpenTag(buf);
                if (idx < 0) {
                    int held = maxPendingOpenSuffix(buf);
                    if (held == 0) {
                        held = maxPartialSuffix(buf, OPEN_TAG);
                    }
                    if (held > 0) {
                        appendVisible(out, buf.substring(0, buf.length() - held));
                        buffer = buf.substring(buf.length() - held);
                    } else {
                        appendVisible(out, buf);
                    }
                    return out.toString();
                }

                appendVisible(out, buf.substring(0, idx));
                buf = buf.substring(idx + OPEN_TAG.length());
                inSpan = true;
            }
            return out.toString();
        }

        /**
         * 执行flush相关逻辑。
         *
         * @return 返回flush结果。
         */
        public String flush() {
            if (inSpan) {
                buffer = "";
                inSpan = false;
                return "";
            }
            String tail = buffer;
            buffer = "";
            return tail;
        }

        /**
         * 查找Boundary Open Tag。
         *
         * @param buf buf 参数。
         * @return 返回Boundary Open Tag结果。
         */
        private int findBoundaryOpenTag(String buf) {
            String lower = lower(buf);
            int search = 0;
            while (true) {
                int idx = lower.indexOf(OPEN_TAG, search);
                if (idx < 0) {
                    return -1;
                }
                if (isBlockBoundary(buf, idx) && hasBlockOpenerSuffix(buf, idx)) {
                    return idx;
                }
                search = idx + 1;
            }
        }

        /**
         * 执行max待恢复OpenSuffix相关逻辑。
         *
         * @param buf buf 参数。
         * @return 返回max Pending Open Suffix结果。
         */
        private int maxPendingOpenSuffix(String buf) {
            if (!lower(buf).endsWith(OPEN_TAG)) {
                return 0;
            }
            int idx = buf.length() - OPEN_TAG.length();
            return isBlockBoundary(buf, idx) ? OPEN_TAG.length() : 0;
        }

        /**
         * 判断是否存在块 Opener Suffix。
         *
         * @param buf buf 参数。
         * @param idx idx标识或键值。
         * @return 如果块 Opener Suffix满足条件则返回 true，否则返回 false。
         */
        private boolean hasBlockOpenerSuffix(String buf, int idx) {
            int after = idx + OPEN_TAG.length();
            return after < buf.length() && (buf.charAt(after) == '\n' || buf.charAt(after) == '\r');
        }

        /**
         * 判断是否块 Boundary。
         *
         * @param buf buf 参数。
         * @param idx idx标识或键值。
         * @return 如果块 Boundary满足条件则返回 true，否则返回 false。
         */
        private boolean isBlockBoundary(String buf, int idx) {
            if (idx == 0) {
                return atBlockBoundary;
            }
            String preceding = buf.substring(0, idx);
            int lastNewline = preceding.lastIndexOf('\n');
            if (lastNewline < 0) {
                return atBlockBoundary && preceding.trim().length() == 0;
            }
            return preceding.substring(lastNewline + 1).trim().length() == 0;
        }

        /**
         * 追加Visible。
         *
         * @param out out 参数。
         * @param text 待处理文本。
         */
        private void appendVisible(StringBuilder out, String text) {
            if (text.length() == 0) {
                return;
            }
            out.append(text);
            updateBlockBoundary(text);
        }

        /**
         * 更新块 Boundary。
         *
         * @param text 待处理文本。
         */
        private void updateBlockBoundary(String text) {
            int lastNewline = text.lastIndexOf('\n');
            if (lastNewline >= 0) {
                atBlockBoundary = text.substring(lastNewline + 1).trim().length() == 0;
            } else {
                atBlockBoundary = atBlockBoundary && text.trim().length() == 0;
            }
        }

        /**
         * 执行maxPartialSuffix相关逻辑。
         *
         * @param buf buf 参数。
         * @param tag tag 参数。
         * @return 返回max Partial Suffix结果。
         */
        private int maxPartialSuffix(String buf, String tag) {
            String tagLower = lower(tag);
            String bufLower = lower(buf);
            int max = Math.min(bufLower.length(), tagLower.length() - 1);
            for (int i = max; i > 0; i--) {
                if (tagLower.startsWith(bufLower.substring(bufLower.length() - i))) {
                    return i;
                }
            }
            return 0;
        }

        /**
         * 执行lower相关逻辑。
         *
         * @param value 待规范化或校验的原始值。
         * @return 返回lower结果。
         */
        private String lower(String value) {
            return value.toLowerCase(java.util.Locale.ROOT);
        }
    }
}
