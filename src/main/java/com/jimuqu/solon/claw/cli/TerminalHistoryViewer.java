package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;

/** Local terminal history preview for the currently bound session. */
public class TerminalHistoryViewer {
    private static final int DEFAULT_LIMIT = 12;
    private static final int MAX_LIMIT = 50;
    private static final int SHOW_MAX_LENGTH = 6000;

    private final SessionRepository sessionRepository;
    private final CliRuntime cliRuntime;

    public TerminalHistoryViewer(SessionRepository sessionRepository, CliRuntime cliRuntime) {
        this.sessionRepository = sessionRepository;
        this.cliRuntime = cliRuntime;
    }

    public boolean isHistoryCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim().toLowerCase(Locale.ROOT);
        return "/history".equals(value) || value.startsWith("/history ");
    }

    public String render(String sessionId, String input) {
        if (sessionRepository == null || cliRuntime == null) {
            return "当前终端没有可用的会话历史。";
        }
        SessionRecord session;
        try {
            session = sessionRepository.getBoundSession(cliRuntime.sourceKey(sessionId));
        } catch (Exception e) {
            return "读取当前会话失败：" + e.getMessage();
        }
        if (session == null || StrUtil.isBlank(session.getSessionId())) {
            return "当前终端还没有绑定会话。";
        }
        if (isShowCommand(input)) {
            return renderEntry(session, input);
        }
        List<HistoryEntry> entries;
        try {
            entries = entries(session, limit(input));
        } catch (Exception e) {
            return "解析当前会话历史失败：" + e.getMessage();
        }
        if (entries.isEmpty()) {
            return "当前会话没有可预览的历史消息。";
        }
        StringBuilder buffer = new StringBuilder("当前会话历史：\n");
        buffer.append("session=")
                .append(session.getSessionId())
                .append("  title=")
                .append(StrUtil.blankToDefault(session.getTitle(), "(未命名会话)"))
                .append('\n');
        for (HistoryEntry entry : entries) {
            buffer.append(entry.index)
                    .append(". ")
                    .append(entry.role)
                    .append(": ")
                    .append(entry.preview)
                    .append('\n');
        }
        buffer.append("使用：/history <条数> 查看更多，/history show <编号> 查看完整条目，/sessions 浏览其他会话。");
        return buffer.toString();
    }

    private String renderEntry(SessionRecord session, String input) {
        int target = showIndex(input);
        if (target <= 0) {
            return "使用：/history show <编号> 查看当前会话中的完整历史条目。";
        }
        List<HistoryEntry> entries;
        try {
            entries = allEntries(session);
        } catch (Exception e) {
            return "解析当前会话历史失败：" + e.getMessage();
        }
        if (entries.isEmpty()) {
            return "当前会话没有可查看的历史条目。";
        }
        if (target > entries.size()) {
            return "没有找到编号为 " + target + " 的历史条目。当前可用范围：1-" + entries.size() + "。";
        }
        HistoryEntry entry = entries.get(target - 1);
        StringBuilder buffer = new StringBuilder("历史条目详情：\n");
        buffer.append("session=")
                .append(session.getSessionId())
                .append("  title=")
                .append(StrUtil.blankToDefault(session.getTitle(), "(未命名会话)"))
                .append('\n');
        buffer.append("index=")
                .append(entry.index)
                .append("  role=")
                .append(entry.role)
                .append('\n');
        buffer.append(SecretRedactor.redact(entry.fullContent, SHOW_MAX_LENGTH));
        return buffer.toString();
    }

    private List<HistoryEntry> entries(SessionRecord session, int limit) throws Exception {
        List<HistoryEntry> all = allEntries(session);
        if (all.size() <= limit) {
            return all;
        }
        return new ArrayList<HistoryEntry>(all.subList(all.size() - limit, all.size()));
    }

    private List<HistoryEntry> allEntries(SessionRecord session) throws Exception {
        List<ChatMessage> messages = MessageSupport.loadMessages(session.getNdjson());
        List<HistoryEntry> all = new ArrayList<HistoryEntry>();
        for (ChatMessage message : messages) {
            if (message == null || message.getRole() == ChatRole.SYSTEM) {
                continue;
            }
            String content = StrUtil.nullToEmpty(message.getContent()).trim();
            if (StrUtil.isBlank(content)) {
                continue;
            }
            all.add(
                    new HistoryEntry(
                            all.size() + 1,
                            roleLabel(message.getRole()),
                            trim(SecretRedactor.redact(content, 1200)),
                            content));
        }
        return all;
    }

    private int limit(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        if (isShowCommand(value)) {
            return DEFAULT_LIMIT;
        }
        if (value.length() <= "/history".length()) {
            return DEFAULT_LIMIT;
        }
        String text = value.substring("/history".length()).trim();
        try {
            int parsed = Integer.parseInt(text);
            return Math.max(1, Math.min(MAX_LIMIT, parsed));
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    private boolean isShowCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim().toLowerCase(Locale.ROOT);
        return value.equals("/history show")
                || value.startsWith("/history show ")
                || value.equals("/history inspect")
                || value.startsWith("/history inspect ");
    }

    private int showIndex(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        String lower = value.toLowerCase(Locale.ROOT);
        String text;
        if (lower.startsWith("/history inspect")) {
            text =
                    value.length() <= "/history inspect".length()
                            ? ""
                            : value.substring("/history inspect".length()).trim();
        } else if (lower.startsWith("/history show")) {
            text =
                    value.length() <= "/history show".length()
                            ? ""
                            : value.substring("/history show".length()).trim();
        } else {
            text = "";
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String roleLabel(ChatRole role) {
        if (role == ChatRole.USER) {
            return "用户";
        }
        if (role == ChatRole.ASSISTANT) {
            return "助手";
        }
        if (role == ChatRole.TOOL) {
            return "工具";
        }
        return String.valueOf(role);
    }

    private String trim(String content) {
        String normalized =
                StrUtil.nullToEmpty(content).replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= 220) {
            return normalized;
        }
        return normalized.substring(0, 220) + "...";
    }

    private static class HistoryEntry {
        private final int index;
        private final String role;
        private final String preview;
        private final String fullContent;

        private HistoryEntry(int index, String role, String preview, String fullContent) {
            this.index = index;
            this.role = role;
            this.preview = preview;
            this.fullContent = fullContent;
        }
    }
}
