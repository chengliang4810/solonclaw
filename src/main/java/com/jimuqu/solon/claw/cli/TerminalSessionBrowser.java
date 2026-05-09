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
    private static final int PREVIEW_LIMIT = 160;

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
                || value.startsWith("/session pick ")
                || "/session show".equals(value)
                || value.startsWith("/session show ")
                || "/session inspect".equals(value)
                || value.startsWith("/session inspect ");
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
        String value = StrUtil.nullToEmpty(input).trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("/session show")
                || lower.startsWith("/session show ")
                || lower.equals("/session inspect")
                || lower.startsWith("/session inspect ")) {
            return renderDetail(value);
        }
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
        buffer.append("使用：/session show <编号> 查看详情，/session pick <编号> 恢复，或直接 /resume <session-id|title>");
        return buffer.toString();
    }

    private String renderDetail(String input) {
        SessionRecord record = resolveDetailRecord(detailReference(input));
        if (record == null || StrUtil.isBlank(record.getSessionId())) {
            return "没有找到匹配的会话。先运行 /sessions 浏览，或使用 /session show <session-id|title>。";
        }
        StringBuilder buffer = new StringBuilder("会话详情：\n");
        appendLine(buffer, "id", record.getSessionId());
        appendLine(buffer, "title", StrUtil.blankToDefault(record.getTitle(), "(未命名会话)"));
        appendLine(buffer, "branch", StrUtil.blankToDefault(record.getBranchName(), "-"));
        appendLine(buffer, "source", StrUtil.blankToDefault(record.getSourceKey(), "-"));
        appendLine(buffer, "parent", StrUtil.blankToDefault(record.getParentSessionId(), "-"));
        appendLine(buffer, "agent", StrUtil.blankToDefault(record.getActiveAgentName(), "default"));
        appendLine(buffer, "model", modelLine(record));
        appendLine(buffer, "created", formatTime(record.getCreatedAt()));
        appendLine(buffer, "updated", formatTime(record.getUpdatedAt()));
        appendLine(buffer, "messages", String.valueOf(countMessages(record.getNdjson())));
        buffer.append("tokens: last=")
                .append(record.getLastTotalTokens())
                .append(" cumulative=")
                .append(record.getCumulativeTotalTokens())
                .append(" input=")
                .append(record.getCumulativeInputTokens())
                .append(" output=")
                .append(record.getCumulativeOutputTokens())
                .append(" reasoning=")
                .append(record.getCumulativeReasoningTokens())
                .append('\n');
        String summary = preview(record.getCompressedSummary());
        if (StrUtil.isNotBlank(summary)) {
            appendLine(buffer, "summary", summary);
        }
        buffer.append("建议：/resume ")
                .append(record.getSessionId())
                .append("，/recap，/trajectory，/goal status，/compact，/branch <名称>，/undo，/retry");
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

    private String detailReference(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/session inspect")) {
            return value.length() <= "/session inspect".length()
                    ? ""
                    : value.substring("/session inspect".length()).trim();
        }
        if (lower.startsWith("/session show")) {
            return value.length() <= "/session show".length()
                    ? ""
                    : value.substring("/session show".length()).trim();
        }
        return "";
    }

    private SessionRecord resolveDetailRecord(String reference) {
        if (sessionRepository == null) {
            return null;
        }
        String value = StrUtil.nullToEmpty(reference).trim();
        if (StrUtil.isBlank(value)) {
            return null;
        }
        SessionRecord fromIndex = resolveFromIndex(value);
        if (fromIndex != null) {
            return fromIndex;
        }
        try {
            SessionRecord byId = sessionRepository.findById(value);
            if (byId != null) {
                return byId;
            }
        } catch (Exception ignored) {
            // Fall through to resume-style lookup.
        }
        try {
            List<SessionRecord> candidates = sessionRepository.findResumeCandidates(value, 1);
            if (candidates != null && !candidates.isEmpty()) {
                return candidates.get(0);
            }
        } catch (Exception ignored) {
            // A terminal browser should stay read-only and best-effort.
        }
        return null;
    }

    private SessionRecord resolveFromIndex(String reference) {
        int index;
        try {
            index = Integer.parseInt(reference);
        } catch (NumberFormatException e) {
            return null;
        }
        if (index < 1 || index > lastChoices.size()) {
            return null;
        }
        String sessionId = lastChoices.get(index - 1).sessionId;
        try {
            return sessionRepository.findById(sessionId);
        } catch (Exception e) {
            return null;
        }
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

    private void appendLine(StringBuilder buffer, String label, String value) {
        buffer.append(label).append(": ").append(StrUtil.blankToDefault(value, "-")).append('\n');
    }

    private String modelLine(SessionRecord record) {
        String provider = StrUtil.nullToEmpty(record.getLastResolvedProvider()).trim();
        String model = StrUtil.nullToEmpty(record.getLastResolvedModel()).trim();
        String override = StrUtil.nullToEmpty(record.getModelOverride()).trim();
        StringBuilder buffer = new StringBuilder();
        buffer.append("provider=").append(StrUtil.blankToDefault(provider, "-"));
        buffer.append(" model=").append(StrUtil.blankToDefault(model, "-"));
        if (StrUtil.isNotBlank(override)) {
            buffer.append(" override=").append(override);
        }
        return buffer.toString();
    }

    private int countMessages(String ndjson) {
        String value = StrUtil.nullToEmpty(ndjson);
        if (StrUtil.isBlank(value)) {
            return 0;
        }
        String[] lines = value.split("\\r?\\n");
        int count = 0;
        for (String line : lines) {
            if (StrUtil.isNotBlank(line)) {
                count++;
            }
        }
        return count;
    }

    private String preview(String text) {
        String value = StrUtil.nullToEmpty(text).replace('\r', ' ').replace('\n', ' ').trim();
        if (value.length() <= PREVIEW_LIMIT) {
            return value;
        }
        return value.substring(0, PREVIEW_LIMIT) + "...";
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
