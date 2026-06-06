package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 承载本地终端记录文本相关状态和辅助逻辑。 */
public class LocalTerminalTranscript {
    /** 最大ENTRIES的统一常量值。 */
    private static final int MAX_ENTRIES = 120;

    /** 默认限制的统一常量值。 */
    private static final int DEFAULT_LIMIT = 12;

    /** 最大RENDER限制的统一常量值。 */
    private static final int MAX_RENDER_LIMIT = 50;

    /** 最大文本LENGTH的统一常量值。 */
    private static final int MAX_TEXT_LENGTH = 240;

    /** 展示最大LENGTH的统一常量值。 */
    private static final int SHOW_MAX_LENGTH = 6000;

    /** 保存entries集合，维持调用顺序或去重语义。 */
    private final List<Entry> entries = new ArrayList<Entry>();

    /**
     * 执行用户相关逻辑。
     *
     * @param text 待处理文本。
     */
    public void user(String text) {
        add("user", text);
    }

    /**
     * 执行assistant相关逻辑。
     *
     * @param text 待处理文本。
     */
    public void assistant(String text) {
        add("assistant", text);
    }

    /**
     * 判断是否记录文本命令。
     *
     * @param input 输入参数。
     * @return 如果记录文本命令满足条件则返回 true，否则返回 false。
     */
    public boolean isTranscriptCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        return "/transcript".equalsIgnoreCase(value)
                || value.toLowerCase(java.util.Locale.ROOT).startsWith("/transcript ");
    }

    /**
     * 执行render相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回render结果。
     */
    public String render(String command) {
        List<Entry> snapshot = snapshot();
        if (snapshot.isEmpty()) {
            return "当前终端暂无虚拟历史。";
        }
        if (isShowCommand(command)) {
            return renderEntry(snapshot, command);
        }
        int limit = limit(command);
        int start = Math.max(0, snapshot.size() - limit);
        StringBuilder buffer = new StringBuilder();
        buffer.append("终端虚拟历史：showing=")
                .append(snapshot.size() - start)
                .append("/")
                .append(snapshot.size())
                .append("  使用：/transcript <条数>，/transcript show <编号>");
        for (int i = start; i < snapshot.size(); i++) {
            Entry entry = snapshot.get(i);
            buffer.append('\n')
                    .append(i + 1)
                    .append(". ")
                    .append(formatTime(entry.timestampMillis))
                    .append(' ')
                    .append(entry.role)
                    .append(": ")
                    .append(entry.preview);
        }
        return buffer.toString();
    }

    /**
     * 渲染Entry。
     *
     * @param snapshot snapshot 参数。
     * @param command 待执行或解析的命令文本。
     * @return 返回render Entry结果。
     */
    private String renderEntry(List<Entry> snapshot, String command) {
        int index = showIndex(command);
        if (index <= 0) {
            return "使用：/transcript show <编号> 查看终端虚拟历史中的完整条目。";
        }
        if (index > snapshot.size()) {
            return "没有找到编号为 " + index + " 的虚拟历史条目。当前可用范围：1-" + snapshot.size() + "。";
        }
        Entry entry = snapshot.get(index - 1);
        StringBuilder buffer = new StringBuilder("终端虚拟历史详情：\n");
        buffer.append("index=")
                .append(index)
                .append("  time=")
                .append(formatTime(entry.timestampMillis))
                .append("  role=")
                .append(entry.role)
                .append('\n');
        buffer.append(SecretRedactor.redact(entry.fullText, SHOW_MAX_LENGTH));
        return buffer.toString();
    }

    /**
     * 执行lines相关逻辑。
     *
     * @return 返回lines结果。
     */
    public List<String> lines() {
        List<Entry> snapshot = snapshot();
        List<String> lines = new ArrayList<String>(snapshot.size());
        for (Entry entry : snapshot) {
            lines.add(entry.role + ": " + entry.preview);
        }
        return lines;
    }

    /**
     * 执行次数相关逻辑。
     *
     * @return 返回次数结果。
     */
    public int count() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /**
     * 执行add相关逻辑。
     *
     * @param role role 参数。
     * @param text 待处理文本。
     */
    private void add(String role, String text) {
        String value = StrUtil.nullToEmpty(text).trim();
        if (StrUtil.isBlank(value)) {
            return;
        }
        synchronized (entries) {
            entries.add(
                    new Entry(
                            role,
                            trim(SecretRedactor.redact(value, 1200)),
                            value,
                            System.currentTimeMillis()));
            while (entries.size() > MAX_ENTRIES) {
                entries.remove(0);
            }
        }
    }

    /**
     * 执行snapshot相关逻辑。
     *
     * @return 返回snapshot结果。
     */
    private List<Entry> snapshot() {
        synchronized (entries) {
            return new ArrayList<Entry>(entries);
        }
    }

    /**
     * 执行限制相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回限制结果。
     */
    private int limit(String command) {
        String value = StrUtil.nullToEmpty(command).trim();
        if (isShowCommand(value)) {
            return DEFAULT_LIMIT;
        }
        String rest =
                value.length() <= "/transcript".length()
                        ? ""
                        : value.substring("/transcript".length()).trim();
        if (StrUtil.isBlank(rest)) {
            return DEFAULT_LIMIT;
        }
        try {
            int parsed = Integer.parseInt(rest);
            if (parsed <= 0) {
                return DEFAULT_LIMIT;
            }
            return Math.min(parsed, MAX_RENDER_LIMIT);
        } catch (Exception ignored) {
            return DEFAULT_LIMIT;
        }
    }

    /**
     * 判断是否展示命令。
     *
     * @param command 待执行或解析的命令文本。
     * @return 如果展示命令满足条件则返回 true，否则返回 false。
     */
    private boolean isShowCommand(String command) {
        String value = StrUtil.nullToEmpty(command).trim().toLowerCase(Locale.ROOT);
        return value.equals("/transcript show")
                || value.startsWith("/transcript show ")
                || value.equals("/transcript inspect")
                || value.startsWith("/transcript inspect ");
    }

    /**
     * 执行展示索引相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回展示Index结果。
     */
    private int showIndex(String command) {
        String value = StrUtil.nullToEmpty(command).trim();
        String lower = value.toLowerCase(Locale.ROOT);
        String text;
        if (lower.startsWith("/transcript inspect")) {
            text =
                    value.length() <= "/transcript inspect".length()
                            ? ""
                            : value.substring("/transcript inspect".length()).trim();
        } else if (lower.startsWith("/transcript show")) {
            text =
                    value.length() <= "/transcript show".length()
                            ? ""
                            : value.substring("/transcript show".length()).trim();
        } else {
            text = "";
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 执行trim相关逻辑。
     *
     * @param text 待处理文本。
     * @return 返回trim结果。
     */
    private String trim(String text) {
        String value = StrUtil.nullToEmpty(text).replace('\r', ' ').replace('\n', ' ').trim();
        if (value.length() <= MAX_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_TEXT_LENGTH) + "...";
    }

    /**
     * 格式化时间。
     *
     * @param millis millis 参数。
     * @return 返回时间结果。
     */
    private String formatTime(long millis) {
        if (millis <= 0L) {
            return "-";
        }
        return new SimpleDateFormat("HH:mm:ss").format(new Date(millis));
    }

    /** 承载Entry相关状态和辅助逻辑。 */
    private static final class Entry {
        /** 记录Entry中的role。 */
        private final String role;

        /** 记录Entry中的预览。 */
        private final String preview;

        /** 记录Entry中的full文本。 */
        private final String fullText;

        /** 记录Entry中的时间戳Millis。 */
        private final long timestampMillis;

        /**
         * 创建Entry实例，并注入运行所需依赖。
         *
         * @param role role 参数。
         * @param preview 预览参数。
         * @param fullText full文本参数。
         * @param timestampMillis 时间戳Millis参数。
         */
        private Entry(String role, String preview, String fullText, long timestampMillis) {
            this.role = role;
            this.preview = preview;
            this.fullText = fullText;
            this.timestampMillis = timestampMillis;
        }
    }
}
