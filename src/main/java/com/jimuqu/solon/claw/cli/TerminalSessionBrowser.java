package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Local terminal session browser built on top of the normal /resume command. */
public class TerminalSessionBrowser {
    private static final int DEFAULT_LIMIT = 10;

    private final SessionRepository sessionRepository;
    private List<SessionChoice> lastChoices = new ArrayList<SessionChoice>();

    public TerminalSessionBrowser(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public boolean isBrowserCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim().toLowerCase(Locale.ROOT);
        return "/sessions".equals(value)
                || value.startsWith("/sessions ")
                || "/session pick".equals(value)
                || value.startsWith("/session pick ");
    }

    public String resolveCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("/session pick ")) {
            return "";
        }
        String indexText = value.substring("/session pick ".length()).trim();
        int index;
        try {
            index = Integer.parseInt(indexText);
        } catch (NumberFormatException e) {
            return "";
        }
        if (index < 1 || index > lastChoices.size()) {
            return "";
        }
        return "/resume " + lastChoices.get(index - 1).sessionId;
    }

    public String render(String input) {
        List<SessionChoice> choices = choices(query(input));
        this.lastChoices = choices;
        if (choices.isEmpty()) {
            return "没有找到可浏览的会话。";
        }
        StringBuilder buffer = new StringBuilder("最近会话：\n");
        for (int i = 0; i < choices.size(); i++) {
            SessionChoice choice = choices.get(i);
            buffer.append(i + 1)
                    .append(". ")
                    .append(shortId(choice.sessionId))
                    .append("  ")
                    .append(StrUtil.blankToDefault(choice.title, "(未命名会话)"))
                    .append('\n')
                    .append("   branch=")
                    .append(StrUtil.blankToDefault(choice.branchName, "-"))
                    .append("  source=")
                    .append(StrUtil.blankToDefault(choice.sourceKey, "-"))
                    .append("  updated=")
                    .append(formatTime(choice.updatedAt))
                    .append("  tokens=")
                    .append(choice.totalTokens)
                    .append('\n');
        }
        buffer.append("使用：/session pick <编号> 恢复，或直接 /resume <session-id|title>");
        return buffer.toString();
    }

    private List<SessionChoice> choices(String query) {
        List<SessionChoice> result = new ArrayList<SessionChoice>();
        if (sessionRepository == null) {
            return result;
        }
        List<SessionRecord> records;
        try {
            records =
                    StrUtil.isBlank(query)
                            ? sessionRepository.listRecent(DEFAULT_LIMIT)
                            : sessionRepository.search(query, DEFAULT_LIMIT);
        } catch (Exception e) {
            return result;
        }
        if (records == null) {
            return result;
        }
        for (SessionRecord record : records) {
            if (record == null || StrUtil.isBlank(record.getSessionId())) {
                continue;
            }
            result.add(new SessionChoice(record));
        }
        return result;
    }

    private String query(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        if (value.length() <= "/sessions".length()) {
            return "";
        }
        return value.substring("/sessions".length()).trim();
    }

    private String shortId(String sessionId) {
        String value = StrUtil.nullToEmpty(sessionId).trim();
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private String formatTime(long millis) {
        if (millis <= 0L) {
            return "-";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(millis));
    }

    private static class SessionChoice {
        private final String sessionId;
        private final String sourceKey;
        private final String branchName;
        private final String title;
        private final long updatedAt;
        private final long totalTokens;

        private SessionChoice(SessionRecord record) {
            this.sessionId = StrUtil.nullToEmpty(record.getSessionId()).trim();
            this.sourceKey = StrUtil.nullToEmpty(record.getSourceKey()).trim();
            this.branchName = StrUtil.nullToEmpty(record.getBranchName()).trim();
            this.title = StrUtil.nullToEmpty(record.getTitle()).trim();
            this.updatedAt = record.getUpdatedAt();
            this.totalTokens = record.getCumulativeTotalTokens();
        }
    }
}
