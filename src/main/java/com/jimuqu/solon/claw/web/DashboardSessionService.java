package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.CheckpointRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.goal.GoalState;
import com.jimuqu.solon.claw.storage.repository.ReadOnlyProfileSessionRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteSessionRepository;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SessionArtifactService;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import com.jimuqu.solon.claw.support.StructuredMetadataSupport;
import com.jimuqu.solon.claw.support.ToolMessageStatusSupport;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

/** Dashboard 会话查询服务。 */
public class DashboardSessionService {
    /** 会话标题最大长度，与外部对标仓库的 Dashboard 更新契约一致。 */
    private static final int MAX_TITLE_LENGTH = 100;

    /** 会话归档标记在现有扩展元数据中的稳定字段名。 */
    private static final String ARCHIVED_METADATA_KEY = "archived";

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 注入检查点服务，用于调用对应业务能力。 */
    private final CheckpointService checkpointService;

    /** 注入会话Artifact服务，用于调用对应业务能力。 */
    private final SessionArtifactService sessionArtifactService;

    /** Agent 运行仓储用于在会话正文为空时补足失败运行的摘要统计。 */
    private final AgentRunRepository agentRunRepository;

    /**
     * 创建控制台会话服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     */
    public DashboardSessionService(SessionRepository sessionRepository) {
        this(sessionRepository, null);
    }

    /**
     * 创建控制台会话服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param checkpointService checkpoint服务依赖。
     */
    public DashboardSessionService(
            SessionRepository sessionRepository, CheckpointService checkpointService) {
        this(sessionRepository, checkpointService, new SessionArtifactService(), null);
    }

    /**
     * 创建控制台会话服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param sessionArtifactService 会话Artifact服务依赖。
     */
    public DashboardSessionService(
            SessionRepository sessionRepository,
            CheckpointService checkpointService,
            SessionArtifactService sessionArtifactService) {
        this(sessionRepository, checkpointService, sessionArtifactService, null);
    }

    /**
     * 创建控制台会话服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param sessionArtifactService 会话Artifact服务依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     */
    public DashboardSessionService(
            SessionRepository sessionRepository,
            CheckpointService checkpointService,
            SessionArtifactService sessionArtifactService,
            AgentRunRepository agentRunRepository) {
        this.sessionRepository = sessionRepository;
        this.checkpointService = checkpointService;
        this.sessionArtifactService =
                sessionArtifactService == null
                        ? new SessionArtifactService()
                        : sessionArtifactService;
        this.agentRunRepository = agentRunRepository;
    }

    /**
     * 读取Sessions。
     *
     * @param limit 最大返回数量。
     * @param offset 分页偏移量。
     * @return 返回读取到的Sessions。
     */
    public Map<String, Object> getSessions(int limit, int offset) throws Exception {
        return getSessions(SessionListOptions.singleProfile(limit, offset));
    }

    /**
     * 按完整 Dashboard 列表参数读取当前 Profile 会话。
     *
     * @param options 已校验的列表参数。
     * @return 过滤、排序并分页后的会话页。
     */
    public Map<String, Object> getSessions(SessionListOptions options) throws Exception {
        SessionListOptions effective = requireOptions(options);
        List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();
        int storedCount = Math.max(0, sessionRepository.countAll());
        List<SessionRecord> records =
                storedCount == 0
                        ? Collections.<SessionRecord>emptyList()
                        : sessionRepository.listRecent(storedCount, 0);
        for (SessionRecord record : records) {
            if (!isListable(record, effective)) {
                continue;
            }
            Map<String, Object> session = toSessionInfo(record, effective.isFull());
            if (intValue(session.get("message_count")) < effective.getMinMessages()) {
                continue;
            }
            filtered.add(session);
        }
        sortSessions(filtered, effective.getOrder());

        int from = Math.min(effective.getOffset(), filtered.size());
        int to = Math.min(from + effective.getLimit(), filtered.size());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessions", new ArrayList<Map<String, Object>>(filtered.subList(from, to)));
        result.put("total", Integer.valueOf(filtered.size()));
        result.put("limit", Integer.valueOf(effective.getLimit()));
        result.put("offset", Integer.valueOf(effective.getOffset()));
        return result;
    }

    /**
     * 读取指定 Profile 的会话列表；非当前 Profile 只打开其 SQLite 数据库的只读连接。
     *
     * @param limit 最大返回数量。
     * @param offset 分页偏移量。
     * @param scope 已校验 Profile Scope；空值表示当前 Profile。
     * @return 指定 Profile 的会话列表。
     */
    public Map<String, Object> getSessions(
            int limit, int offset, DashboardProfileContext.Scope scope) throws Exception {
        return getSessions(SessionListOptions.singleProfile(limit, offset), scope, true);
    }

    /**
     * 读取指定 Profile 的会话页，并按调用入口决定是否标记 Profile 归属。
     *
     * @param options 完整列表参数。
     * @param scope 已校验 Profile Scope；空值表示当前 Profile。
     * @param includeProfile 是否给返回行增加 Profile 标记。
     * @return 指定 Profile 的会话页。
     */
    public Map<String, Object> getSessions(
            SessionListOptions options, DashboardProfileContext.Scope scope, boolean includeProfile)
            throws Exception {
        if (scope == null || scope.isCurrent()) {
            Map<String, Object> result = getSessions(options);
            if (includeProfile && scope != null) {
                tagSessions(result, scope.getName());
            }
            return result;
        }
        Path stateDb = scope.getHome().resolve("data").resolve("state.db");
        if (!Files.isRegularFile(stateDb)) {
            return emptySessions(options);
        }
        DashboardSessionService scoped =
                new DashboardSessionService(
                        new ReadOnlyProfileSessionRepository(stateDb),
                        null,
                        new SessionArtifactService(),
                        null);
        Map<String, Object> result = scoped.getSessions(options);
        if (includeProfile) {
            tagSessions(result, scope.getName());
        }
        return result;
    }

    /**
     * 聚合机器上多个 Profile 的只读会话列表，并在合并排序后执行统一分页。
     *
     * @param limit 最大返回数量。
     * @param offset 分页偏移量。
     * @param scopes 待聚合的已校验 Profile Scope。
     * @return 带 Profile 归属、分项统计和读取错误的聚合结果。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getProfilesSessions(
            int limit, int offset, List<DashboardProfileContext.Scope> scopes) {
        return getProfilesSessions(SessionListOptions.aggregate(limit, offset), scopes);
    }

    /**
     * 按完整参数聚合多个 Profile 的只读会话，并在全局排序后统一分页。
     *
     * @param options 聚合列表参数。
     * @param scopes 待读取的 Profile Scope。
     * @return 带 Profile 分项统计和读取错误的聚合结果。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getProfilesSessions(
            SessionListOptions options, List<DashboardProfileContext.Scope> scopes) {
        SessionListOptions effective = requireOptions(options);
        int perProfile =
                Math.min(
                        Math.max(
                                effective.getLimit() + effective.getOffset(), effective.getLimit()),
                        500);
        SessionListOptions perProfileOptions = effective.withPage(perProfile, 0);
        List<Map<String, Object>> merged = new ArrayList<Map<String, Object>>();
        Map<String, Integer> profileTotals = new LinkedHashMap<String, Integer>();
        List<Map<String, String>> errors = new ArrayList<Map<String, String>>();
        int total = 0;
        List<DashboardProfileContext.Scope> targets =
                scopes == null ? Collections.<DashboardProfileContext.Scope>emptyList() : scopes;
        for (DashboardProfileContext.Scope scope : targets) {
            if (scope == null) {
                continue;
            }
            try {
                Map<String, Object> result = getSessions(perProfileOptions, scope, true);
                Object rawSessions = result.get("sessions");
                if (rawSessions instanceof List) {
                    merged.addAll((List<Map<String, Object>>) rawSessions);
                }
                int profileTotal = intValue(result.get("total"));
                profileTotals.put(scope.getName(), Integer.valueOf(profileTotal));
                total += profileTotal;
            } catch (Exception e) {
                Map<String, String> error = new LinkedHashMap<String, String>();
                error.put("profile", scope.getName());
                error.put(
                        "error",
                        SecretRedactor.redact(
                                StrUtil.blankToDefault(
                                        e.getMessage(), e.getClass().getSimpleName()),
                                1000));
                errors.add(error);
            }
        }
        sortSessions(merged, effective.getOrder());
        int from = Math.min(effective.getOffset(), merged.size());
        int to = Math.min(from + effective.getLimit(), merged.size());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessions", new ArrayList<Map<String, Object>>(merged.subList(from, to)));
        result.put("total", Integer.valueOf(total));
        result.put("profile_totals", profileTotals);
        result.put("limit", Integer.valueOf(effective.getLimit()));
        result.put("offset", Integer.valueOf(effective.getOffset()));
        result.put("errors", errors);
        return result;
    }

    /** 创建不存在 state.db 时的稳定空会话页。 */
    private Map<String, Object> emptySessions(SessionListOptions options) {
        SessionListOptions effective = requireOptions(options);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessions", Collections.emptyList());
        result.put("total", Integer.valueOf(0));
        result.put("limit", Integer.valueOf(effective.getLimit()));
        result.put("offset", Integer.valueOf(effective.getOffset()));
        return result;
    }

    /** 校验列表参数对象非空。 */
    private SessionListOptions requireOptions(SessionListOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("Session list options are required.");
        }
        return options;
    }

    /** 判断会话是否满足子会话、来源、目录和归档过滤规则。 */
    private boolean isListable(SessionRecord record, SessionListOptions options) {
        if (record == null) {
            return false;
        }
        // 显式 /branch 会话是用户可继续的独立对话，必须出现在列表；
        // 默认 main 父子会话仍按压缩延续或委托子会话隐藏。
        if (StrUtil.isNotBlank(record.getParentSessionId()) && !isExplicitBranch(record)) {
            return false;
        }
        String source = parseSource(record.getSourceKey());
        if (StrUtil.isNotBlank(options.getSource())
                && !options.getSource().equalsIgnoreCase(source)) {
            return false;
        }
        if (options.getExcludeSources().contains(source.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (!matchesCwd(record, options.getCwdPrefix())) {
            return false;
        }
        boolean archived = isArchived(record);
        if ("only".equals(options.getArchived())) {
            return archived;
        }
        return "include".equals(options.getArchived()) || !archived;
    }

    /** 按创建时间或最后活跃时间倒序排列会话，并用 ID 保证稳定顺序。 */
    private void sortSessions(List<Map<String, Object>> sessions, String order) {
        final String sortKey = "recent".equals(order) ? "last_active" : "started_at";
        Collections.sort(
                sessions,
                new Comparator<Map<String, Object>>() {
                    /** 比较两条 Dashboard 会话记录。 */
                    @Override
                    public int compare(Map<String, Object> left, Map<String, Object> right) {
                        int time =
                                Long.compare(
                                        longValue(right.get(sortKey)),
                                        longValue(left.get(sortKey)));
                        if (time != 0) {
                            return time;
                        }
                        return String.valueOf(right.get("id"))
                                .compareTo(String.valueOf(left.get("id")));
                    }
                });
    }

    /** 给会话列表写入 Profile 归属，供机器级 Dashboard 路由后续操作。 */
    @SuppressWarnings("unchecked")
    private void tagSessions(Map<String, Object> result, String profile) {
        Object rawSessions = result == null ? null : result.get("sessions");
        if (!(rawSessions instanceof List)) {
            return;
        }
        for (Object raw : (List<?>) rawSessions) {
            if (raw instanceof Map) {
                Map<String, Object> session = (Map<String, Object>) raw;
                session.put("profile", profile);
                session.put("is_default_profile", Boolean.valueOf("default".equals(profile)));
            }
        }
    }

    /** 将分页统计字段转换为非负整数。 */
    private int intValue(Object value) {
        if (value instanceof Number) {
            return Math.max(0, ((Number) value).intValue());
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value)));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 将排序字段转换为长整型时间戳。 */
    private long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 读取会话Messages。
     *
     * @param sessionId 当前会话标识。
     * @return 返回读取到的会话Messages。
     */
    public Map<String, Object> getSessionMessages(String sessionId) throws Exception {
        return getSessionMessages(sessionId, null, 0);
    }

    /**
     * 读取当前 Profile 的会话消息并执行可选分页。
     *
     * @param sessionId 当前会话标识。
     * @param limit 可选最大消息数；非空时最多 500。
     * @param offset 消息分页偏移量。
     * @return 会话详情、消息和分页信息。
     */
    public Map<String, Object> getSessionMessages(String sessionId, Integer limit, int offset)
            throws Exception {
        SessionRecord record = resolveResumeSession(requireSession(sessionId));

        List<ChatMessage> loadedMessages = visibleMessages(record);
        MessageSupport.dropCurrentSummaryArtifacts(loadedMessages, record.getCompressedSummary());
        List<Map<String, Object>> allMessages = new ArrayList<Map<String, Object>>();
        for (ChatMessage message : loadedMessages) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("role", message.getRole().name().toLowerCase(Locale.ROOT));
            String content = message.getContent();
            if (message instanceof AssistantMessage) {
                content = MessageSupport.assistantText((AssistantMessage) message);
            }
            item.put("content", SecretRedactor.redact(content, 8000));
            item.put("timestamp", null);

            if (message instanceof AssistantMessage) {
                AssistantMessage assistant = (AssistantMessage) message;
                item.put("reasoning", SecretRedactor.redact(assistant.getReasoning(), 8000));
                if (assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty()) {
                    List<Map<String, Object>> toolCalls = new ArrayList<Map<String, Object>>();
                    for (ToolCall call : assistant.getToolCalls()) {
                        Map<String, Object> function = new LinkedHashMap<String, Object>();
                        function.put("name", call.getName());
                        function.put(
                                "arguments",
                                SecretRedactor.redact(
                                        StrUtil.blankToDefault(
                                                call.getArgumentsStr(),
                                                ONode.serialize(call.getArguments())),
                                        4000));

                        Map<String, Object> toolCall = new LinkedHashMap<String, Object>();
                        toolCall.put("id", safe(call.getId(), 400));
                        toolCall.put("function", function);
                        toolCalls.add(toolCall);
                    }
                    item.put("tool_calls", toolCalls);
                }
            }

            if (message instanceof ToolMessage) {
                ToolMessage toolMessage = (ToolMessage) message;
                item.put("tool_name", safe(toolMessage.getName(), 400));
                item.put("tool_call_id", safe(toolMessage.getToolCallId(), 400));
                item.put("tool_status", ToolMessageStatusSupport.statusOf(toolMessage));
            }

            allMessages.add(item);
        }

        Integer safeLimit =
                limit == null ? null : Integer.valueOf(Math.min(Math.max(0, limit), 500));
        int safeOffset = Math.max(0, offset);
        int from = Math.min(safeOffset, allMessages.size());
        int to =
                safeLimit == null
                        ? allMessages.size()
                        : Math.min(from + safeLimit.intValue(), allMessages.size());
        List<Map<String, Object>> messages =
                new ArrayList<Map<String, Object>>(allMessages.subList(from, to));

        Map<String, Object> result = toSessionDetail(record);
        result.put("messages", messages);
        Map<String, Object> pagination = new LinkedHashMap<String, Object>();
        pagination.put("limit", safeLimit);
        pagination.put("offset", Integer.valueOf(safeOffset));
        pagination.put("returned", Integer.valueOf(messages.size()));
        result.put("pagination", pagination);
        return result;
    }

    /**
     * 从指定 Profile 的 SQLite 只读会话仓储读取消息详情。
     *
     * @param sessionId 当前会话标识。
     * @param scope 已校验 Profile Scope；空值表示当前 Profile。
     * @return 会话消息详情，并在跨 Profile 时标记归属。
     */
    public Map<String, Object> getSessionMessages(
            String sessionId, DashboardProfileContext.Scope scope) throws Exception {
        return getSessionMessages(sessionId, scope, null, 0);
    }

    /**
     * 从指定 Profile 的 SQLite 只读仓储读取分页消息。
     *
     * @param sessionId 当前会话标识。
     * @param scope 已校验 Profile Scope；空值表示当前 Profile。
     * @param limit 可选最大消息数。
     * @param offset 消息分页偏移量。
     * @return 会话消息详情，并在显式跨 Profile 时标记归属。
     */
    public Map<String, Object> getSessionMessages(
            String sessionId, DashboardProfileContext.Scope scope, Integer limit, int offset)
            throws Exception {
        DashboardSessionService scoped = readOnlyService(scope);
        if (scoped == null) {
            throw new SessionNotFoundException(sessionId);
        }
        Map<String, Object> result = scoped.getSessionMessages(sessionId, limit, offset);
        tagSession(result, scope);
        return result;
    }

    /**
     * 读取当前 Profile 的完整会话详情，不附带消息列表。
     *
     * @param sessionId 当前会话标识。
     * @return 会话详情。
     */
    public Map<String, Object> getSessionDetail(String sessionId) throws Exception {
        return toSessionDetail(requireSession(sessionId));
    }

    /**
     * 从指定 Profile 读取完整会话详情。
     *
     * @param sessionId 当前会话标识。
     * @param scope 已校验 Profile Scope；空值表示当前 Profile。
     * @return 会话详情，并在跨 Profile 时标记归属。
     */
    public Map<String, Object> getSessionDetail(
            String sessionId, DashboardProfileContext.Scope scope) throws Exception {
        DashboardSessionService scoped = readOnlyService(scope);
        if (scoped == null) {
            throw new SessionNotFoundException(sessionId);
        }
        Map<String, Object> result = scoped.getSessionDetail(sessionId);
        tagSession(result, scope);
        return result;
    }

    /**
     * 执行recap相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @param maxExchanges maxExchanges 参数。
     * @return 返回recap结果。
     */
    public Map<String, Object> recap(String sessionId, int maxExchanges) throws Exception {
        SessionRecord record = sessionRepository.findById(sessionId);
        if (record == null) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("session_id", safe(sessionId, 400));
            empty.put("entries", Collections.emptyList());
            empty.put("text", "当前会话不存在。");
            return empty;
        }
        return sessionArtifactService.recap(recordForArtifacts(record), maxExchanges);
    }

    /**
     * 执行trajectory相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @param userQuery 用户查询参数。
     * @param completed completed 参数。
     * @return 返回trajectory结果。
     */
    public Map<String, Object> trajectory(String sessionId, String userQuery, boolean completed)
            throws Exception {
        SessionRecord record = sessionRepository.findById(sessionId);
        if (record == null) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("session_id", safe(sessionId, 400));
            empty.put("completed", Boolean.valueOf(completed));
            empty.put("conversations", Collections.emptyList());
            return empty;
        }
        return sessionArtifactService.trajectory(recordForArtifacts(record), userQuery, completed);
    }

    /**
     * 保存Trajectory。
     *
     * @param sessionId 当前会话标识。
     * @param userQuery 用户查询参数。
     * @param completed completed 参数。
     * @return 返回Trajectory结果。
     */
    public Map<String, Object> saveTrajectory(String sessionId, String userQuery, boolean completed)
            throws Exception {
        SessionRecord record = sessionRepository.findById(sessionId);
        if (record == null) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("session_id", safe(sessionId, 400));
            empty.put("saved", Boolean.FALSE);
            empty.put("error", "当前会话不存在。");
            return empty;
        }
        Map<String, Object> saved =
                sessionArtifactService.saveTrajectory(
                        recordForArtifacts(record), userQuery, completed);
        saved.put("saved", Boolean.TRUE);
        return saved;
    }

    /**
     * 删除会话。
     *
     * @param sessionId 当前会话标识。
     * @return 返回会话结果。
     */
    public Map<String, Object> deleteSession(String sessionId) throws Exception {
        SessionRecord record = sessionRepository.findById(sessionId);
        if (record == null) {
            Map<String, Object> absent = new LinkedHashMap<String, Object>();
            absent.put("ok", Boolean.TRUE);
            absent.put("already_absent", Boolean.TRUE);
            return absent;
        }
        sessionRepository.delete(record.getSessionId());
        return Collections.singletonMap("ok", true);
    }

    /**
     * 删除当前或目标 Profile 的会话；目标 Profile 直接操作其独立数据库，不依赖网关在线。
     *
     * @param sessionId 当前会话标识。
     * @param scope 已校验 Profile Scope；空值表示当前 Profile。
     * @return 幂等删除结果。
     */
    public Map<String, Object> deleteSession(String sessionId, DashboardProfileContext.Scope scope)
            throws Exception {
        if (scope == null || scope.isCurrent()) {
            return deleteSession(sessionId);
        }
        SqliteDatabase database = new SqliteDatabase(scope.getConfig());
        try {
            return new DashboardSessionService(new SqliteSessionRepository(database))
                    .deleteSession(sessionId);
        } finally {
            database.shutdown();
        }
    }

    /**
     * 更新会话。
     *
     * @param sessionId 当前会话标识。
     * @param body 请求体或消息正文内容。
     * @return 返回会话结果。
     */
    public Map<String, Object> updateSession(String sessionId, Map<String, Object> body)
            throws Exception {
        SessionRecord record = requireSession(sessionId);
        boolean updateTitle = body != null && body.containsKey("title");
        boolean updateArchived = body != null && body.containsKey("archived");
        if (!updateTitle && !updateArchived) {
            throw new IllegalArgumentException(
                    "Nothing to update; provide 'title' and/or 'archived'.");
        }
        if (updateTitle) {
            String title = sanitizeTitle(body.get("title"));
            ensureUniqueTitle(record.getSessionId(), title);
            record.setTitle(title);
        }
        Boolean archived = null;
        if (updateArchived) {
            archived = Boolean.valueOf(booleanValue(body.get("archived"), "archived"));
            Map<String, Object> metadata = metadata(record);
            metadata.put(ARCHIVED_METADATA_KEY, archived);
            record.setMetadataJson(ONode.serialize(metadata));
        }
        sessionRepository.save(record);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", Boolean.TRUE);
        result.put("title", StrUtil.nullToEmpty(record.getTitle()));
        if (archived != null) {
            result.put("archived", archived);
        }
        return result;
    }

    /**
     * 更新当前或目标 Profile 的会话；目标 Profile 直接写入其独立数据库。
     *
     * @param sessionId 当前会话标识。
     * @param body title、archived 与机器级 profile 路由字段。
     * @param scope 已校验 Profile Scope；空值表示当前 Profile。
     * @return 更新结果。
     */
    public Map<String, Object> updateSession(
            String sessionId, Map<String, Object> body, DashboardProfileContext.Scope scope)
            throws Exception {
        if (scope == null || scope.isCurrent()) {
            return updateSession(sessionId, body);
        }
        SqliteDatabase database = new SqliteDatabase(scope.getConfig());
        try {
            return new DashboardSessionService(new SqliteSessionRepository(database))
                    .updateSession(sessionId, body);
        } finally {
            database.shutdown();
        }
    }

    /**
     * 执行会话Tree相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @return 返回会话Tree结果。
     */
    public Map<String, Object> sessionTree(String sessionId) throws Exception {
        SessionRecord root = sessionRepository.findById(sessionId);
        if (root == null) {
            return Collections.singletonMap("nodes", Collections.emptyList());
        }
        List<SessionRecord> lineage = sessionRepository.listLineage(root.getSessionId());
        List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
        for (SessionRecord record : lineage) {
            Map<String, Object> node = toSessionInfo(record);
            node.put("parent_session_id", safe(record.getParentSessionId(), 400));
            node.put("branch_name", safe(record.getBranchName(), 400));
            nodes.add(node);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("root_session_id", safe(sessionId, 400));
        result.put("nodes", nodes);
        return result;
    }

    /**
     * 从指定 Profile 的 SQLite 只读会话仓储读取分支树。
     *
     * @param sessionId 当前会话标识。
     * @param scope 已校验 Profile Scope；空值表示当前 Profile。
     * @return 会话分支树，并在跨 Profile 时标记节点归属。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> sessionTree(String sessionId, DashboardProfileContext.Scope scope)
            throws Exception {
        DashboardSessionService scoped = readOnlyService(scope);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (scoped == null) {
            result.put("nodes", Collections.emptyList());
        } else {
            result.putAll(scoped.sessionTree(sessionId));
        }
        if (scope != null && !scope.isCurrent()) {
            Object rawNodes = result.get("nodes");
            if (rawNodes instanceof List) {
                for (Object raw : (List<?>) rawNodes) {
                    if (raw instanceof Map) {
                        ((Map<String, Object>) raw).put("profile", scope.getName());
                    }
                }
            }
            result.put("profile", scope.getName());
        }
        return result;
    }

    /**
     * 执行latestDescendant相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @return 返回latest Descendant结果。
     */
    public Map<String, Object> latestDescendant(String sessionId) throws Exception {
        SessionRecord root = requireSession(sessionId);

        List<String> path = sessionRepository.latestDescendantPath(root.getSessionId());
        String current = path.isEmpty() ? root.getSessionId() : path.get(path.size() - 1);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("requested_session_id", safe(root.getSessionId(), 400));
        result.put("session_id", safe(current, 400));
        result.put("path", safeList(path, 400));
        result.put("changed", Boolean.valueOf(!root.getSessionId().equals(current)));
        return result;
    }

    /**
     * 从指定 Profile 的 SQLite 只读会话仓储解析最新后代。
     *
     * @param sessionId 当前会话标识。
     * @param scope 已校验 Profile Scope；空值表示当前 Profile。
     * @return 最新后代链路，并在跨 Profile 时标记归属。
     */
    public Map<String, Object> latestDescendant(
            String sessionId, DashboardProfileContext.Scope scope) throws Exception {
        DashboardSessionService scoped = readOnlyService(scope);
        if (scoped == null) {
            throw new SessionNotFoundException(sessionId);
        }
        Map<String, Object> result = scoped.latestDescendant(sessionId);
        tagSession(result, scope);
        return result;
    }

    /** 为当前或目标 Profile 创建只读详情服务；目标数据库不存在时返回空值。 */
    private DashboardSessionService readOnlyService(DashboardProfileContext.Scope scope)
            throws Exception {
        if (scope == null || scope.isCurrent()) {
            return this;
        }
        Path stateDb = scope.getHome().resolve("data").resolve("state.db");
        if (!Files.isRegularFile(stateDb)) {
            return null;
        }
        return new DashboardSessionService(
                new ReadOnlyProfileSessionRepository(stateDb),
                null,
                new SessionArtifactService(),
                null);
    }

    /** 给单个跨 Profile 响应标记归属，当前 Profile 维持原有字段契约。 */
    private void tagSession(Map<String, Object> result, DashboardProfileContext.Scope scope) {
        if (result != null && scope != null && !scope.isCurrent()) {
            result.put("profile", scope.getName());
        }
    }

    /**
     * 执行checkpoints相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @return 返回checkpoints结果。
     */
    public Map<String, Object> checkpoints(String sessionId) throws Exception {
        if (checkpointService == null) {
            return Collections.singletonMap("checkpoints", Collections.emptyList());
        }
        SessionRecord record = sessionRepository.findById(sessionId);
        if (record == null) {
            return Collections.singletonMap("checkpoints", Collections.emptyList());
        }
        List<Map<String, Object>> checkpoints = new ArrayList<Map<String, Object>>();
        for (CheckpointRecord checkpoint :
                checkpointService.listRecent(record.getSourceKey(), 50)) {
            checkpoints.add(toCheckpoint(checkpoint));
        }
        return Collections.singletonMap("checkpoints", checkpoints);
    }

    /**
     * 执行检查点预览相关逻辑。
     *
     * @param checkpointId checkpoint标识。
     * @return 返回检查点Preview结果。
     */
    public Map<String, Object> checkpointPreview(String checkpointId) throws Exception {
        if (checkpointService == null) {
            return Collections.emptyMap();
        }
        return checkpointService.preview(checkpointId);
    }

    /**
     * 执行回滚检查点相关逻辑。
     *
     * @param checkpointId checkpoint标识。
     * @return 返回回滚检查点结果。
     */
    public Map<String, Object> rollbackCheckpoint(String checkpointId) throws Exception {
        if (checkpointService == null) {
            throw new IllegalStateException("checkpoint service is not configured");
        }
        CheckpointRecord record = checkpointService.rollback(checkpointId);
        return toCheckpoint(record);
    }

    /**
     * 转换为会话Info。
     *
     * @param record 记录参数。
     * @return 返回转换后的会话Info。
     */
    private Map<String, Object> toSessionInfo(SessionRecord record) throws Exception {
        return toSessionInfo(record, false);
    }

    /**
     * 转换为会话列表信息，并按 full 参数决定是否包含重字段。
     *
     * @param record 会话记录。
     * @param full 是否包含系统提示词和模型配置快照。
     * @return Dashboard 会话列表记录。
     */
    private Map<String, Object> toSessionInfo(SessionRecord record, boolean full) throws Exception {
        List<ChatMessage> messages = visibleMessages(record);
        List<AgentRunRecord> runs = recentRuns(record);
        int messageCount = messages.size();
        if (messageCount == 0) {
            messageCount = visibleRunMessageCount(runs);
        }
        int toolCallCount = 0;
        for (ChatMessage message : messages) {
            if (message instanceof AssistantMessage) {
                AssistantMessage assistant = (AssistantMessage) message;
                if (assistant.getToolCalls() != null) {
                    toolCallCount += assistant.getToolCalls().size();
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", safe(record.getSessionId(), 400));
        result.put("source", parseSource(record.getSourceKey()));
        result.put(
                "model",
                safe(
                        StrUtil.blankToDefault(
                                record.getLastResolvedModel(),
                                StrUtil.blankToDefault(record.getModelOverride(), null)),
                        400));
        result.put(
                "provider",
                safe(StrUtil.blankToDefault(record.getLastResolvedProvider(), null), 400));
        result.put("title", safe(record.getTitle(), 400));
        result.put("started_at", record.getCreatedAt());
        result.put("ended_at", null);
        result.put("last_active", record.getUpdatedAt());
        result.put(
                "is_active",
                record.getUpdatedAt() >= System.currentTimeMillis() - 5L * 60L * 1000L);
        result.put("message_count", messageCount);
        result.put("tool_call_count", toolCallCount);
        result.put("input_tokens", record.getCumulativeInputTokens());
        result.put("output_tokens", record.getCumulativeOutputTokens());
        result.put("reasoning_tokens", record.getCumulativeReasoningTokens());
        result.put("cache_read_tokens", record.getCumulativeCacheReadTokens());
        result.put("cache_write_tokens", record.getCumulativeCacheWriteTokens());
        result.put("total_tokens", record.getCumulativeTotalTokens());
        result.put("last_total_tokens", record.getLastTotalTokens());
        result.put("last_usage_at", record.getLastUsageAt());
        result.put("parent_session_id", safe(record.getParentSessionId(), 400));
        result.put("branch_name", safe(record.getBranchName(), 400));
        Map<String, Object> metadata = metadata(record);
        result.put("cwd", safe(text(metadata.get("cwd")), 2000));
        result.put("git_branch", safe(text(metadata.get("git_branch")), 400));
        result.put("git_repo_root", safe(text(metadata.get("git_repo_root")), 2000));
        result.put("archived", Boolean.valueOf(isArchived(metadata)));
        result.put("goal_state", goalState(record));
        result.put(
                "compressed_summary", SecretRedactor.redact(record.getCompressedSummary(), 8000));
        result.put("last_compression_at", record.getLastCompressionAt());
        result.put("last_compression_input_tokens", record.getLastCompressionInputTokens());
        result.put("compression_failure_count", record.getCompressionFailureCount());
        result.put("preview", safe(trim(previewText(record, runs), 160), 160));
        if (full) {
            result.put(
                    "system_prompt",
                    SecretRedactor.redact(record.getSystemPromptSnapshot(), 100_000));
            result.put("model_config", redactedMetadata(record));
        }
        return result;
    }

    /**
     * 转换为会话详情，包含完整使用量和恢复所需元数据。
     *
     * @param record 会话记录。
     * @return Dashboard 会话详情。
     */
    private Map<String, Object> toSessionDetail(SessionRecord record) throws Exception {
        Map<String, Object> result = toSessionInfo(record, true);
        result.put("session_id", safe(record.getSessionId(), 400));
        result.put("last_input_tokens", record.getLastInputTokens());
        result.put("last_output_tokens", record.getLastOutputTokens());
        result.put("last_reasoning_tokens", record.getLastReasoningTokens());
        result.put("last_cache_read_tokens", record.getLastCacheReadTokens());
        result.put("last_cache_write_tokens", record.getLastCacheWriteTokens());
        result.put("last_usage_at", record.getLastUsageAt());
        return result;
    }

    /**
     * 读取会话最近运行记录；读取失败时返回空列表，避免列表接口被运行索引异常拖垮。
     *
     * @param record 会话记录。
     * @return 返回最近运行记录。
     */
    private List<AgentRunRecord> recentRuns(SessionRecord record) {
        if (agentRunRepository == null
                || record == null
                || StrUtil.isBlank(record.getSessionId())) {
            return Collections.emptyList();
        }
        try {
            List<AgentRunRecord> runs =
                    agentRunRepository.listBySession(record.getSessionId(), 100);
            return runs == null ? Collections.<AgentRunRecord>emptyList() : runs;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 读取 Dashboard 可见消息；会话正文为空但存在失败运行时，用运行预览补足详情、摘要和导出。
     *
     * @param record 会话记录。
     * @return 返回可展示消息列表。
     */
    private List<ChatMessage> visibleMessages(SessionRecord record) throws Exception {
        List<ChatMessage> messages = MessageSupport.loadMessages(record.getNdjson());
        if (!messages.isEmpty()) {
            return messages;
        }
        return runPreviewMessages(recentRuns(record));
    }

    /**
     * 为 artifact 服务生成只读会话副本，避免把运行预览补足内容写回持久化会话正文。
     *
     * @param record 会话记录。
     * @return 返回用于摘要和轨迹导出的会话记录。
     */
    private SessionRecord recordForArtifacts(SessionRecord record) throws Exception {
        List<ChatMessage> messages = visibleMessages(record);
        if (MessageSupport.loadMessages(record.getNdjson()).size() == messages.size()) {
            return record;
        }

        SessionRecord copy = new SessionRecord();
        copy.setSessionId(record.getSessionId());
        copy.setTitle(record.getTitle());
        copy.setLastResolvedModel(record.getLastResolvedModel());
        copy.setLastResolvedProvider(record.getLastResolvedProvider());
        copy.setNdjson(MessageSupport.toNdjson(messages));
        return copy;
    }

    /**
     * 将最近运行预览转换为用户可读消息，用于失败运行尚未落正文时的 Dashboard 只读展示。
     *
     * @param runs 最近运行记录。
     * @return 返回按时间顺序排列的消息。
     */
    private List<ChatMessage> runPreviewMessages(List<AgentRunRecord> runs) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        for (int i = runs.size() - 1; i >= 0; i--) {
            AgentRunRecord run = runs.get(i);
            if (run == null) {
                continue;
            }
            if (StrUtil.isNotBlank(run.getInputPreview())) {
                messages.add(ChatMessage.ofUser(run.getInputPreview()));
            }
            String assistant = StrUtil.blankToDefault(run.getFinalReplyPreview(), run.getError());
            if (StrUtil.isNotBlank(assistant)) {
                messages.add(ChatMessage.ofAssistant(assistant));
            }
        }
        return messages;
    }

    /**
     * 统计运行记录中可被用户看到的输入和结束状态，用于失败运行未落入会话正文时补足列表数量。
     *
     * @param runs 最近运行记录。
     * @return 返回可见消息数量。
     */
    private int visibleRunMessageCount(List<AgentRunRecord> runs) {
        int count = 0;
        for (AgentRunRecord run : runs) {
            if (run == null) {
                continue;
            }
            if (StrUtil.isNotBlank(run.getInputPreview())) {
                count++;
            }
            if (StrUtil.isNotBlank(run.getFinalReplyPreview())
                    || StrUtil.isNotBlank(run.getError())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 生成会话列表预览，正文为空时回退到最近运行的用户输入预览。
     *
     * @param record 会话记录。
     * @param runs 最近运行记录。
     * @return 返回预览文本。
     */
    private String previewText(SessionRecord record, List<AgentRunRecord> runs) throws Exception {
        String preview =
                StrUtil.blankToDefault(
                        MessageSupport.getLastUserMessage(record.getNdjson()),
                        record.getCompressedSummary());
        if (StrUtil.isNotBlank(preview)) {
            return preview;
        }
        for (AgentRunRecord run : runs) {
            if (run != null && StrUtil.isNotBlank(run.getInputPreview())) {
                return run.getInputPreview();
            }
        }
        return "";
    }

    /**
     * 执行目标状态相关逻辑。
     *
     * @param record 记录参数。
     * @return 返回goal状态。
     */
    private Map<String, Object> goalState(SessionRecord record) {
        GoalState state = GoalState.fromJson(record.getGoalStateJson());
        if (state == null || GoalState.STATUS_CLEARED.equals(state.getStatus())) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("goal", SecretRedactor.redact(state.getGoal(), 2000));
        result.put("status", state.getStatus());
        result.put("turns_used", state.getTurnsUsed());
        result.put("max_turns", state.getMaxTurns());
        result.put("created_at", state.getCreatedAt());
        result.put("last_turn_at", state.getLastTurnAt());
        result.put("last_verdict", state.getLastVerdict());
        result.put("last_reason", SecretRedactor.redact(state.getLastReason(), 2000));
        result.put("paused_reason", SecretRedactor.redact(state.getPausedReason(), 1000));
        return result;
    }

    /**
     * 转换为检查点。
     *
     * @param checkpoint checkpoint 参数。
     * @return 返回转换后的检查点。
     */
    private Map<String, Object> toCheckpoint(CheckpointRecord checkpoint) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("checkpoint_id", safe(checkpoint.getCheckpointId(), 400));
        item.put("source_key", safe(checkpoint.getSourceKey(), 400));
        item.put("session_id", safe(checkpoint.getSessionId(), 400));
        item.put("created_at", checkpoint.getCreatedAt());
        item.put("restored_at", checkpoint.getRestoredAt());
        return item;
    }

    /**
     * 沿会话延续链解析实际恢复节点，同时排除显式分支和委托子会话。
     *
     * <p>父会话 ID 同时用于压缩/模型切换延续、显式 /branch 和子 Agent；Dashboard 消息读取只应自动跟随前一类，避免旧会话点击后误跳到分支或委托结果。
     */
    private SessionRecord resolveResumeSession(SessionRecord requested) throws Exception {
        if (requested == null || StrUtil.isBlank(requested.getSessionId())) {
            return requested;
        }
        List<SessionRecord> lineage = sessionRepository.listLineage(requested.getSessionId());
        Map<String, List<SessionRecord>> children =
                new LinkedHashMap<String, List<SessionRecord>>();
        for (SessionRecord candidate : lineage) {
            if (candidate == null || StrUtil.isBlank(candidate.getParentSessionId())) {
                continue;
            }
            List<SessionRecord> siblings = children.get(candidate.getParentSessionId());
            if (siblings == null) {
                siblings = new ArrayList<SessionRecord>();
                children.put(candidate.getParentSessionId(), siblings);
            }
            siblings.add(candidate);
        }

        SessionRecord current = requested;
        Set<String> seen = new LinkedHashSet<String>();
        seen.add(current.getSessionId());
        while (children.containsKey(current.getSessionId())) {
            SessionRecord newest = null;
            for (SessionRecord candidate : children.get(current.getSessionId())) {
                if (!isResumeContinuation(candidate) || seen.contains(candidate.getSessionId())) {
                    continue;
                }
                if (newest == null
                        || candidate.getCreatedAt() > newest.getCreatedAt()
                        || (candidate.getCreatedAt() == newest.getCreatedAt()
                                && candidate.getSessionId().compareTo(newest.getSessionId()) > 0)) {
                    newest = candidate;
                }
            }
            if (newest == null) {
                break;
            }
            current = newest;
            seen.add(current.getSessionId());
        }
        return current;
    }

    /** 判断子会话是否可作为自动恢复的延续节点。 */
    private boolean isResumeContinuation(SessionRecord candidate) {
        if (candidate == null
                || StrUtil.isBlank(candidate.getSessionId())
                || isExplicitBranch(candidate)) {
            return false;
        }
        String[] source = SourceKeySupport.split(candidate.getSourceKey());
        String threadId = source.length > 3 ? StrUtil.nullToEmpty(source[3]) : "";
        return !threadId.contains("delegate-");
    }

    /** 默认 main 只是普通会话分支标记，只有父会话下的非 main 名称才表示显式 /branch。 */
    private boolean isExplicitBranch(SessionRecord record) {
        String branch = record == null ? null : StrUtil.nullToEmpty(record.getBranchName()).trim();
        return StrUtil.isNotBlank(branch) && !"main".equalsIgnoreCase(branch);
    }

    /** 读取会话，不存在时抛出可映射为 HTTP 404 的异常。 */
    private SessionRecord requireSession(String sessionId) throws Exception {
        SessionRecord record = sessionRepository.findById(sessionId);
        if (record == null) {
            throw new SessionNotFoundException(sessionId);
        }
        return record;
    }

    /** 解析会话扩展元数据；空值或损坏 JSON 回退为空对象。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> metadata(SessionRecord record) {
        if (record == null || StrUtil.isBlank(record.getMetadataJson())) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Object parsed = ONode.deserialize(record.getMetadataJson(), Object.class);
            if (parsed instanceof Map) {
                return new LinkedHashMap<String, Object>((Map<String, Object>) parsed);
            }
        } catch (Exception ignored) {
            // 损坏的历史元数据不能拖垮会话列表和详情读取。
        }
        return new LinkedHashMap<String, Object>();
    }

    /** 返回经过结构化脱敏的会话扩展元数据。 */
    private Object redactedMetadata(SessionRecord record) {
        if (record == null || StrUtil.isBlank(record.getMetadataJson())) {
            return Collections.emptyMap();
        }
        try {
            return ONode.deserialize(
                    StructuredMetadataSupport.redactJson(record.getMetadataJson()), Object.class);
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    /** 判断会话是否已归档。 */
    private boolean isArchived(SessionRecord record) {
        return isArchived(metadata(record));
    }

    /** 从元数据对象读取归档布尔值。 */
    private boolean isArchived(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get(ARCHIVED_METADATA_KEY);
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return "true".equalsIgnoreCase(StrUtil.nullToEmpty(String.valueOf(value)).trim())
                || "1".equals(StrUtil.nullToEmpty(String.valueOf(value)).trim());
    }

    /** 判断会话 cwd 是否等于或位于指定目录前缀之下。 */
    private boolean matchesCwd(SessionRecord record, String cwdPrefix) {
        if (StrUtil.isBlank(cwdPrefix)) {
            return true;
        }
        String cwd = text(metadata(record).get("cwd"));
        if (cwd == null) {
            return false;
        }
        String prefix = cwdPrefix.replaceAll("[/\\\\]+$", "");
        if (prefix.length() == 0) {
            prefix = cwdPrefix;
        }
        return cwd.equals(prefix) || cwd.startsWith(prefix + "/") || cwd.startsWith(prefix + "\\");
    }

    /** 规范化会话标题，移除控制字符、折叠空白并执行长度校验。 */
    private String sanitizeTitle(Object raw) {
        String title = raw == null ? "" : String.valueOf(raw);
        title = title.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]", "");
        title =
                title.replaceAll(
                        "[\\u200b-\\u200f\\u2028-\\u202e\\u2060-\\u2069\\ufeff\\ufffc\\ufff9-\\ufffb]",
                        "");
        title = title.replaceAll("\\s+", " ").trim();
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException(
                    "Title too long (" + title.length() + " chars, max " + MAX_TITLE_LENGTH + ")");
        }
        return title.length() == 0 ? null : title;
    }

    /** 拒绝与其他会话重复的非空标题。 */
    private void ensureUniqueTitle(String sessionId, String title) throws Exception {
        if (StrUtil.isBlank(title)) {
            return;
        }
        int count = Math.max(0, sessionRepository.countAll());
        if (count == 0) {
            return;
        }
        for (SessionRecord candidate : sessionRepository.listRecent(count, 0)) {
            if (candidate != null
                    && !StrUtil.equals(candidate.getSessionId(), sessionId)
                    && StrUtil.equals(candidate.getTitle(), title)) {
                throw new IllegalArgumentException("Title already in use: " + safe(title, 100));
            }
        }
    }

    /** 严格解析更新请求中的布尔字段。 */
    private boolean booleanValue(Object raw, String field) {
        if (raw instanceof Boolean) {
            return ((Boolean) raw).booleanValue();
        }
        String value = StrUtil.nullToEmpty(String.valueOf(raw)).trim();
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException(field + " must be a boolean");
    }

    /** 读取非空文本。 */
    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.length() == 0 ? null : text;
    }

    /**
     * 解析来源。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回解析后的来源。
     */
    private String parseSource(String sourceKey) {
        String[] parts = SourceKeySupport.split(sourceKey);
        if ("MEMORY".equalsIgnoreCase(parts[0])) {
            return "local";
        }
        return parts[0].toLowerCase(Locale.ROOT);
    }

    /** Dashboard 会话列表的完整参数对象。 */
    public static final class SessionListOptions {
        /** 分页返回上限。 */
        private final int limit;

        /** 分页偏移量。 */
        private final int offset;

        /** 最少可见消息数。 */
        private final int minMessages;

        /** 归档过滤模式：exclude、only 或 include。 */
        private final String archived;

        /** 排序模式：created 或 recent。 */
        private final String order;

        /** 可选单一来源过滤。 */
        private final String source;

        /** 需要排除的来源集合。 */
        private final Set<String> excludeSources;

        /** 可选工作目录前缀。 */
        private final String cwdPrefix;

        /** 是否返回系统提示词和模型配置重字段。 */
        private final boolean full;

        /**
         * 创建完整会话列表参数。
         *
         * @param limit 分页返回上限。
         * @param offset 分页偏移量。
         * @param minMessages 最少消息数。
         * @param archived 归档过滤模式。
         * @param order 排序模式。
         * @param source 单一来源过滤。
         * @param excludeSources 排除来源列表。
         * @param cwdPrefix 工作目录前缀。
         * @param full 是否返回重字段。
         */
        public SessionListOptions(
                int limit,
                int offset,
                int minMessages,
                String archived,
                String order,
                String source,
                List<String> excludeSources,
                String cwdPrefix,
                boolean full) {
            this.limit = Math.max(0, limit);
            this.offset = Math.max(0, offset);
            this.minMessages = Math.max(0, minMessages);
            this.archived = normalizeArchived(archived);
            this.order = normalizeOrder(order);
            this.source = normalizeText(source);
            this.excludeSources = normalizeSources(excludeSources);
            this.cwdPrefix = normalizeText(cwdPrefix);
            this.full = full;
        }

        /** 创建普通单 Profile 列表的默认参数。 */
        public static SessionListOptions singleProfile(int limit, int offset) {
            return new SessionListOptions(
                    limit,
                    offset,
                    0,
                    "exclude",
                    "created",
                    null,
                    Collections.<String>emptyList(),
                    null,
                    false);
        }

        /** 创建跨 Profile 聚合列表的默认参数。 */
        public static SessionListOptions aggregate(int limit, int offset) {
            return new SessionListOptions(
                    limit,
                    offset,
                    0,
                    "exclude",
                    "recent",
                    null,
                    Collections.<String>emptyList(),
                    null,
                    false);
        }

        /** 创建只改变分页窗口的副本。 */
        private SessionListOptions withPage(int pageLimit, int pageOffset) {
            return new SessionListOptions(
                    pageLimit,
                    pageOffset,
                    minMessages,
                    archived,
                    order,
                    source,
                    new ArrayList<String>(excludeSources),
                    cwdPrefix,
                    full);
        }

        /** 返回分页上限。 */
        public int getLimit() {
            return limit;
        }

        /** 返回分页偏移量。 */
        public int getOffset() {
            return offset;
        }

        /** 返回最少消息数。 */
        public int getMinMessages() {
            return minMessages;
        }

        /** 返回归档过滤模式。 */
        public String getArchived() {
            return archived;
        }

        /** 返回排序模式。 */
        public String getOrder() {
            return order;
        }

        /** 返回单一来源过滤。 */
        public String getSource() {
            return source;
        }

        /** 返回不可变排除来源集合。 */
        public Set<String> getExcludeSources() {
            return excludeSources;
        }

        /** 返回工作目录前缀。 */
        public String getCwdPrefix() {
            return cwdPrefix;
        }

        /** 返回是否包含重字段。 */
        public boolean isFull() {
            return full;
        }

        /** 校验并规范化归档模式。 */
        private static String normalizeArchived(String value) {
            String normalized = StrUtil.blankToDefault(value, "exclude").trim();
            if (!"exclude".equals(normalized)
                    && !"only".equals(normalized)
                    && !"include".equals(normalized)) {
                throw new IllegalArgumentException(
                        "archived must be one of: exclude, only, include");
            }
            return normalized;
        }

        /** 校验并规范化排序模式。 */
        private static String normalizeOrder(String value) {
            String normalized = StrUtil.blankToDefault(value, "created").trim();
            if (!"created".equals(normalized) && !"recent".equals(normalized)) {
                throw new IllegalArgumentException("order must be one of: created, recent");
            }
            return normalized;
        }

        /** 规范化可选文本。 */
        private static String normalizeText(String value) {
            String normalized = StrUtil.nullToEmpty(value).trim();
            return normalized.length() == 0 ? null : normalized;
        }

        /** 规范化排除来源并返回不可变集合。 */
        private static Set<String> normalizeSources(List<String> values) {
            Set<String> result = new LinkedHashSet<String>();
            if (values != null) {
                for (String value : values) {
                    String normalized = normalizeText(value);
                    if (normalized != null) {
                        result.add(normalized.toLowerCase(Locale.ROOT));
                    }
                }
            }
            return Collections.unmodifiableSet(result);
        }
    }

    /** 会话不存在异常，由 Dashboard 控制器稳定映射为 HTTP 404。 */
    public static final class SessionNotFoundException extends IllegalArgumentException {
        /** 使用脱敏后的会话 ID 创建异常。 */
        public SessionNotFoundException(String sessionId) {
            super("Session not found: " + SecretRedactor.redact(sessionId, 400));
        }
    }

    /**
     * 执行trim相关逻辑。
     *
     * @param text 待处理文本。
     * @param limit 最大返回数量。
     * @return 返回trim结果。
     */
    private String trim(String text, int limit) {
        String normalized = StrUtil.nullToEmpty(text).replace('\n', ' ').trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit) + "...";
    }

    /**
     * 执行安全相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回safe结果。
     */
    private String safe(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }

    /**
     * 生成安全展示用的列表。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param maxLength 最大保留字符数。
     * @return 返回safe List结果。
     */
    private List<String> safeList(List<String> values, int maxLength) {
        List<String> result = new ArrayList<String>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            result.add(safe(value, maxLength));
        }
        return result;
    }
}
