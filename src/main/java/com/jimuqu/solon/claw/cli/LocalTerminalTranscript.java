package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** In-memory transcript for the current local terminal session. */
public class LocalTerminalTranscript {
    private static final int MAX_ENTRIES = 120;
    private static final int DEFAULT_LIMIT = 12;
    private static final int MAX_RENDER_LIMIT = 50;
    private static final int MAX_TEXT_LENGTH = 240;

    private final List<Entry> entries = new ArrayList<Entry>();

    public void user(String text) {
        add("user", text);
    }

    public void assistant(String text) {
        add("assistant", text);
    }

    public boolean isTranscriptCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        return "/transcript".equalsIgnoreCase(value)
                || value.toLowerCase(java.util.Locale.ROOT).startsWith("/transcript ");
    }

    public String render(String command) {
        List<Entry> snapshot = snapshot();
        if (snapshot.isEmpty()) {
            return "当前终端暂无虚拟历史。";
        }
        int limit = limit(command);
        int start = Math.max(0, snapshot.size() - limit);
        StringBuilder buffer = new StringBuilder();
        buffer.append("终端虚拟历史：showing=")
                .append(snapshot.size() - start)
                .append("/")
                .append(snapshot.size())
                .append("  使用：/transcript <条数>");
        for (int i = start; i < snapshot.size(); i++) {
            Entry entry = snapshot.get(i);
            buffer.append('\n')
                    .append(i + 1)
                    .append(". ")
                    .append(formatTime(entry.timestampMillis))
                    .append(' ')
                    .append(entry.role)
                    .append(": ")
                    .append(entry.text);
        }
        return buffer.toString();
    }

    public List<String> lines() {
        List<Entry> snapshot = snapshot();
        List<String> lines = new ArrayList<String>(snapshot.size());
        for (Entry entry : snapshot) {
            lines.add(entry.role + ": " + entry.text);
        }
        return lines;
    }

    private void add(String role, String text) {
        String value = trim(text);
        if (StrUtil.isBlank(value)) {
            return;
        }
        synchronized (entries) {
            entries.add(new Entry(role, value, System.currentTimeMillis()));
            while (entries.size() > MAX_ENTRIES) {
                entries.remove(0);
            }
        }
    }

    private List<Entry> snapshot() {
        synchronized (entries) {
            return new ArrayList<Entry>(entries);
        }
    }

    private int limit(String command) {
        String value = StrUtil.nullToEmpty(command).trim();
        String rest = value.length() <= "/transcript".length() ? "" : value.substring("/transcript".length()).trim();
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

    private String trim(String text) {
        String value = StrUtil.nullToEmpty(text).replace('\r', ' ').replace('\n', ' ').trim();
        if (value.length() <= MAX_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_TEXT_LENGTH) + "...";
    }

    private String formatTime(long millis) {
        if (millis <= 0L) {
            return "-";
        }
        return new SimpleDateFormat("HH:mm:ss").format(new Date(millis));
    }

    private static final class Entry {
        private final String role;
        private final String text;
        private final long timestampMillis;

        private Entry(String role, String text, long timestampMillis) {
            this.role = role;
            this.text = text;
            this.timestampMillis = timestampMillis;
        }
    }
}
