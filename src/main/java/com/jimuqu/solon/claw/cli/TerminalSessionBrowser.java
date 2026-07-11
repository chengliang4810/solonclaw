package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 承载终端会话浏览器相关状态和辅助逻辑。 */
public class TerminalSessionBrowser {
    /** 记录终端会话浏览的仓储降级与单条删除失败，便于诊断但不打断交互。 */
    private static final Logger log = LoggerFactory.getLogger(TerminalSessionBrowser.class);

    /** 默认限制的统一常量值。 */
    private static final int DEFAULT_LIMIT = 10;

    /** 预览限制的统一常量值。 */
    private static final int PREVIEW_LIMIT = 160;

    /** 会话列表展示时间格式，使用系统时区保持原有本地时间语义。 */
    private static final DateTimeFormatter SESSION_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 保存lastChoices集合，维持调用顺序或去重语义。 */
    private List<SessionChoice> lastChoices = new ArrayList<SessionChoice>();

    /**
     * 创建终端会话浏览器实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     */
    public TerminalSessionBrowser(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * 判断是否浏览器命令。
     *
     * @param input 输入参数。
     * @return 如果浏览器命令满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 解析命令。
     *
     * @param input 输入参数。
     * @return 返回解析后的命令。
     */
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

    /**
     * 执行render相关逻辑。
     *
     * @param input 输入参数。
     * @return 返回render结果。
     */
    public String render(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (isSessionsManagementCommand(lower)) {
            return renderSessionsManagement(value);
        }
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
        buffer.append(
                "使用：/session show <编号> 查看详情，/session pick <编号> 恢复，或直接 /resume <session-id|title>");
        return buffer.toString();
    }

    /**
     * 判断是否为会话管理子命令，避免将 stats/delete/export 等命令误当作搜索词。
     *
     * @param lower 已小写的终端输入。
     * @return 属于会话管理命令返回 true。
     */
    private boolean isSessionsManagementCommand(String lower) {
        return lower.equals("/sessions stats")
                || lower.startsWith("/sessions stats ")
                || lower.equals("/sessions export")
                || lower.startsWith("/sessions export ")
                || lower.equals("/sessions delete")
                || lower.startsWith("/sessions delete ")
                || lower.equals("/sessions prune")
                || lower.startsWith("/sessions prune ")
                || lower.equals("/sessions rename")
                || lower.startsWith("/sessions rename ");
    }

    /**
     * 渲染 sessions 管理命令，覆盖统计、导出、删除、裁剪和重命名。
     *
     * @param input 用户输入的完整命令。
     * @return 管理命令输出。
     */
    private String renderSessionsManagement(String input) {
        List<String> tokens = shellTokens(input);
        if (tokens.size() < 2) {
            return sessionsManagementUsage();
        }
        String action = tokens.get(1).toLowerCase(Locale.ROOT);
        if ("stats".equals(action)) {
            return renderStats();
        }
        if ("export".equals(action)) {
            return renderExport(tokens);
        }
        if ("delete".equals(action)) {
            return renderDelete(tokens);
        }
        if ("prune".equals(action)) {
            return renderPrune(tokens);
        }
        if ("rename".equals(action)) {
            return renderRename(tokens);
        }
        return sessionsManagementUsage();
    }

    /** 渲染会话管理命令用法。 */
    private String sessionsManagementUsage() {
        return "用法：/sessions stats | /sessions export <path|-> [--session-id <id>] | "
                + "/sessions delete <id> --yes | /sessions prune --older-than <days> --yes | "
                + "/sessions rename <id> <title>";
    }

    /** 渲染会话统计信息。 */
    private String renderStats() {
        List<SessionRecord> records = allSessions();
        long tokens = 0L;
        int messages = 0;
        Map<String, Integer> sources = new LinkedHashMap<String, Integer>();
        for (SessionRecord record : records) {
            tokens += record.getCumulativeTotalTokens();
            messages += countMessages(record.getNdjson());
            String source = StrUtil.blankToDefault(record.getSourceKey(), "unknown");
            Integer count = sources.get(source);
            sources.put(source, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
        }
        StringBuilder buffer = new StringBuilder("会话统计\n");
        buffer.append("total=").append(records.size()).append('\n');
        buffer.append("messages=").append(messages).append('\n');
        buffer.append("tokens=").append(tokens);
        for (Map.Entry<String, Integer> entry : sources.entrySet()) {
            buffer.append('\n')
                    .append("source.")
                    .append(entry.getKey())
                    .append('=')
                    .append(entry.getValue().intValue());
        }
        return buffer.toString();
    }

    /**
     * 渲染会话导出命令，`-` 表示直接输出 JSONL。
     *
     * @param tokens 命令 token。
     * @return 导出结果或 JSONL 内容。
     */
    private String renderExport(List<String> tokens) {
        if (tokens.size() < 3) {
            return "用法：/sessions export <path|-> [--session-id <id>]";
        }
        String output = tokens.get(2);
        String sessionId = optionValue(tokens, "--session-id", "");
        List<SessionRecord> records = new ArrayList<SessionRecord>();
        if (StrUtil.isNotBlank(sessionId)) {
            SessionRecord record = resolveDetailRecord(sessionId);
            if (record == null) {
                return "没有找到匹配的会话：" + sessionId;
            }
            records.add(record);
        } else {
            records.addAll(allSessions());
        }
        String jsonl = exportJsonl(records);
        if ("-".equals(output)) {
            return jsonl;
        }
        try {
            Files.write(Paths.get(output), jsonl.getBytes(StandardCharsets.UTF_8));
            return "会话已导出\npath=" + output + "\ncount=" + records.size();
        } catch (Exception e) {
            return "会话导出失败：" + e.getMessage();
        }
    }

    /**
     * 渲染单会话删除命令；删除类操作必须显式确认。
     *
     * @param tokens 命令 token。
     * @return 删除结果。
     */
    private String renderDelete(List<String> tokens) {
        if (tokens.size() < 3) {
            return "用法：/sessions delete <session-id> --yes";
        }
        if (!hasYes(tokens)) {
            return "需要确认：删除会话会移除该会话记录。请追加 --yes 或 -y。";
        }
        String reference = tokens.get(2);
        SessionRecord record = resolveDetailRecord(reference);
        if (record == null) {
            return "没有找到匹配的会话：" + reference;
        }
        try {
            sessionRepository.delete(record.getSessionId());
            return "会话已删除\nsession_id=" + record.getSessionId();
        } catch (Exception e) {
            return "会话删除失败：" + e.getMessage();
        }
    }

    /**
     * 渲染旧会话裁剪命令；批量删除必须显式确认。
     *
     * @param tokens 命令 token。
     * @return 裁剪结果。
     */
    private String renderPrune(List<String> tokens) {
        int days = intOption(tokens, "--older-than", 90);
        String source = optionValue(tokens, "--source", "");
        if (!hasYes(tokens)) {
            return "需要确认：将删除更新时间早于 " + days + " 天的会话。请追加 --yes 或 -y。";
        }
        long cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
        int removed = 0;
        for (SessionRecord record : allSessions()) {
            if (record == null || StrUtil.isBlank(record.getSessionId())) {
                continue;
            }
            if (StrUtil.isNotBlank(source) && !source.equals(record.getSourceKey())) {
                continue;
            }
            if (record.getUpdatedAt() <= 0L || record.getUpdatedAt() >= cutoff) {
                continue;
            }
            try {
                sessionRepository.delete(record.getSessionId());
                removed++;
            } catch (Exception e) {
                log.debug(
                        "终端会话裁剪删除单条记录失败，继续处理其他候选: sessionId={}, error={}",
                        record.getSessionId(),
                        e.toString());
            }
        }
        return "会话已裁剪\nolder_than_days=" + days + "\nremoved=" + removed;
    }

    /**
     * 渲染会话重命名命令。
     *
     * @param tokens 命令 token。
     * @return 重命名结果。
     */
    private String renderRename(List<String> tokens) {
        if (tokens.size() < 4) {
            return "用法：/sessions rename <session-id> <title>";
        }
        String reference = tokens.get(2);
        SessionRecord record = resolveDetailRecord(reference);
        if (record == null) {
            return "没有找到匹配的会话：" + reference;
        }
        String title = StrUtil.join(" ", tokens.subList(3, tokens.size())).trim();
        if (StrUtil.isBlank(title)) {
            return "用法：/sessions rename <session-id> <title>";
        }
        record.setTitle(title);
        record.setUpdatedAt(System.currentTimeMillis());
        try {
            sessionRepository.save(record);
            return "会话已重命名\nsession_id=" + record.getSessionId() + "\ntitle=" + title;
        } catch (Exception e) {
            return "会话重命名失败：" + e.getMessage();
        }
    }

    /**
     * 渲染详情。
     *
     * @param input 输入参数。
     * @return 返回render Detail结果。
     */
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

    /**
     * 执行choices相关逻辑。
     *
     * @param query 查询参数。
     * @return 返回choices结果。
     */
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

    /** 读取所有可管理会话，优先使用仓储分页能力避免固定 10 条浏览限制。 */
    private List<SessionRecord> allSessions() {
        List<SessionRecord> records = new ArrayList<SessionRecord>();
        if (sessionRepository == null) {
            return records;
        }
        try {
            int count = Math.max(sessionRepository.countAll(), DEFAULT_LIMIT);
            List<SessionRecord> recent =
                    sessionRepository.listRecent(Math.max(count, DEFAULT_LIMIT), 0);
            if (recent != null) {
                records.addAll(recent);
            }
        } catch (Exception e) {
            try {
                List<SessionRecord> recent = sessionRepository.listRecent(DEFAULT_LIMIT);
                if (recent != null) {
                    records.addAll(recent);
                }
            } catch (Exception fallbackError) {
                log.debug("终端会话列表降级读取仍失败，管理命令退化为空结果: {}", fallbackError.toString());
            }
        }
        return records;
    }

    /**
     * 将会话记录导出为 JSONL 文本。
     *
     * @param records 会话记录。
     * @return JSONL 文本。
     */
    private String exportJsonl(List<SessionRecord> records) {
        StringBuilder buffer = new StringBuilder();
        for (SessionRecord record : records) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("session_id", record.getSessionId());
            item.put("title", record.getTitle());
            item.put("source", record.getSourceKey());
            item.put("branch", record.getBranchName());
            item.put("parent_session_id", record.getParentSessionId());
            item.put("model_provider", record.getLastResolvedProvider());
            item.put("model", record.getLastResolvedModel());
            item.put("created_at", Long.valueOf(record.getCreatedAt()));
            item.put("updated_at", Long.valueOf(record.getUpdatedAt()));
            item.put("messages", Integer.valueOf(countMessages(record.getNdjson())));
            item.put("total_tokens", Long.valueOf(record.getCumulativeTotalTokens()));
            item.put("ndjson", record.getNdjson());
            buffer.append(ONode.serialize(item)).append('\n');
        }
        return buffer.toString();
    }

    /**
     * 从 token 列表读取字符串参数。
     *
     * @param tokens 命令 token。
     * @param name 参数名。
     * @param defaultValue 默认值。
     * @return 参数值。
     */
    private String optionValue(List<String> tokens, String name, String defaultValue) {
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (name.equals(token) && i + 1 < tokens.size()) {
                return tokens.get(i + 1);
            }
            if (token != null && token.startsWith(name + "=")) {
                return token.substring((name + "=").length());
            }
        }
        return defaultValue;
    }

    /**
     * 从 token 列表读取整数参数。
     *
     * @param tokens 命令 token。
     * @param name 参数名。
     * @param defaultValue 默认值。
     * @return 参数值。
     */
    private int intOption(List<String> tokens, String name, int defaultValue) {
        String value = optionValue(tokens, name, "");
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Math.max(Integer.parseInt(value), 0);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 判断用户是否显式确认危险会话操作。
     *
     * @param tokens 命令 token。
     * @return 包含 --yes 或 -y 返回 true。
     */
    private boolean hasYes(List<String> tokens) {
        return tokens.contains("--yes") || tokens.contains("-y");
    }

    /**
     * 解析终端命令 token，支持简单引号包裹的标题或路径。
     *
     * @param input 原始输入。
     * @return token 列表。
     */
    private List<String> shellTokens(String input) {
        List<String> tokens = new ArrayList<String>();
        String value = StrUtil.nullToEmpty(input).trim();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaping = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaping) {
                current.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                escaping = true;
                continue;
            }
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (escaping) {
            current.append('\\');
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * 执行查询相关逻辑。
     *
     * @param input 输入参数。
     * @return 返回query结果。
     */
    private String query(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        if (value.length() <= "/sessions".length()) {
            return "";
        }
        return value.substring("/sessions".length()).trim();
    }

    /**
     * 执行详情引用相关逻辑。
     *
     * @param input 输入参数。
     * @return 返回detail Reference结果。
     */
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

    /**
     * 解析Detail记录。
     *
     * @param reference 引用参数。
     * @return 返回解析后的Detail记录。
     */
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
        } catch (Exception e) {
            log.debug("按会话ID解析终端会话失败，继续尝试候选匹配: reference={}, error={}", value, e.toString());
        }
        try {
            List<SessionRecord> candidates = sessionRepository.findResumeCandidates(value, 1);
            if (candidates != null && !candidates.isEmpty()) {
                return candidates.get(0);
            }
        } catch (Exception e) {
            log.debug("按会话候选解析终端会话失败，返回空结果: reference={}, error={}", value, e.toString());
        }
        return null;
    }

    /**
     * 解析From Index。
     *
     * @param reference 引用参数。
     * @return 返回解析后的From Index。
     */
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

    /**
     * 执行短标识相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @return 返回short标识。
     */
    private String shortId(String sessionId) {
        String value = StrUtil.nullToEmpty(sessionId).trim();
        return value.length() <= 12 ? value : value.substring(0, 12);
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
        return SESSION_TIME_FORMATTER.format(Instant.ofEpochMilli(millis));
    }

    /**
     * 追加Line。
     *
     * @param buffer buffer 参数。
     * @param label label 参数。
     * @param value 待规范化或校验的原始值。
     */
    private void appendLine(StringBuilder buffer, String label, String value) {
        buffer.append(label).append(": ").append(StrUtil.blankToDefault(value, "-")).append('\n');
    }

    /**
     * 执行模型行相关逻辑。
     *
     * @param record 记录参数。
     * @return 返回模型Line结果。
     */
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

    /**
     * 执行次数Messages相关逻辑。
     *
     * @param ndjson ndjson 参数。
     * @return 返回次数Messages结果。
     */
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

    /**
     * 执行预览相关逻辑。
     *
     * @param text 待处理文本。
     * @return 返回preview结果。
     */
    private String preview(String text) {
        String value = StrUtil.nullToEmpty(text).replace('\r', ' ').replace('\n', ' ').trim();
        if (value.length() <= PREVIEW_LIMIT) {
            return value;
        }
        return value.substring(0, PREVIEW_LIMIT) + "...";
    }

    /** 承载会话Choice相关状态和辅助逻辑。 */
    private static class SessionChoice {
        /** 记录会话Choice中的会话标识。 */
        private final String sessionId;

        /** 记录会话Choice中的来源键。 */
        private final String sourceKey;

        /** 记录会话Choice中的branch名称。 */
        private final String branchName;

        /** 记录会话Choice中的标题。 */
        private final String title;

        /** 记录会话Choice中的更新时间。 */
        private final long updatedAt;

        /** 记录会话Choice中的totaltoken。 */
        private final long totalTokens;

        /**
         * 创建会话Choice实例，并注入运行所需依赖。
         *
         * @param record 记录参数。
         */
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
