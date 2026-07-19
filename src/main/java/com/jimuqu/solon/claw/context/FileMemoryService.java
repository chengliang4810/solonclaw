package com.jimuqu.solon.claw.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.MemoryApprovalRequest;
import com.jimuqu.solon.claw.core.model.MemorySearchResult;
import com.jimuqu.solon.claw.core.model.MemorySnapshot;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.MemoryConstants;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;

/** 基于文件系统的长期记忆服务。 */
public class FileMemoryService implements MemoryService {
    /** 专题名称只允许字母、数字、中文、点、下划线和短横线，阻断路径穿越。 */
    private static final Pattern TOPIC_NAME_PATTERN = Pattern.compile("[\\p{L}\\p{N}._-]{1,80}");

    /** 批量处理全部待审批变更时使用的固定参数。 */
    private static final String ALL = "all";

    /** 文件创建竞争时最多重读并重试三次。 */
    private static final int APPEND_RETRY_ATTEMPTS = 3;

    /** 明显属于短期任务状态的关键词。 */
    private static final String[] TRANSIENT_PATTERNS =
            new String[] {"本会话", "临时", "rollback", "checkpoint", "sessionId", "session_id"};

    /** 需要在没有长期语义时拦截的弱短期状态关键词。 */
    private static final String[] WEAK_TRANSIENT_PATTERNS = new String[] {"TODO", "todo"};

    /** 明确表达长期记忆价值的中文前缀。 */
    private static final String[] LONG_TERM_PREFIXES =
            new String[] {"长期偏好", "长期记忆", "用户偏好", "项目约定", "环境细节", "常见纠正", "工具怪癖"};

    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 审批、归档和恢复共用的记忆文件锁。 */
    private final MemoryFileLock memoryFileLock;

    /** 构造文件记忆服务。 */
    public FileMemoryService(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.memoryFileLock = new MemoryFileLock(appConfig);
        FileUtil.mkdir(appConfig.getRuntime().getHome());
        FileUtil.mkdir(appConfig.getRuntime().getContextDir());
        FileUtil.mkdir(memoryDir());
    }

    /**
     * 加载Snapshot。
     *
     * @return 返回Snapshot结果。
     */
    @Override
    public MemorySnapshot loadSnapshot() throws Exception {
        MemorySnapshot snapshot = new MemorySnapshot();
        snapshot.setMemoryText(read(MemoryConstants.TARGET_MEMORY));
        snapshot.setUserText(read(MemoryConstants.TARGET_USER));
        snapshot.setDailyMemoryText(readTodayMemory());
        return snapshot;
    }

    /**
     * 执行read相关逻辑。
     *
     * @param target target 参数。
     * @return 返回read结果。
     */
    @Override
    public String read(String target) throws Exception {
        File file = fileForTarget(target);
        if (!file.exists()) {
            return "";
        }
        return FileUtil.readUtf8String(file).trim();
    }

    /** 同步工作区记忆文件并使用 SQLite FTS5 搜索。 */
    @Override
    public synchronized List<MemorySearchResult> search(String query, int limit) throws Exception {
        if (StrUtil.isBlank(query)) {
            throw new IllegalArgumentException("记忆搜索词不能为空。");
        }
        int boundedLimit = Math.max(1, Math.min(limit, 20));
        try (Connection connection = memoryIndexConnection()) {
            rebuildMemoryIndex(connection);
            String sql =
                    "select path, snippet(memory_fts, 1, '[', ']', '...', 24), bm25(memory_fts) "
                            + "from memory_fts where memory_fts match ? order by bm25(memory_fts) limit ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ftsQuery(query));
                statement.setInt(2, boundedLimit);
                try (ResultSet rows = statement.executeQuery()) {
                    List<MemorySearchResult> results = new ArrayList<MemorySearchResult>();
                    while (rows.next()) {
                        MemorySearchResult result = new MemorySearchResult();
                        result.setPath(rows.getString(1));
                        result.setSnippet(rows.getString(2));
                        result.setRank(rows.getDouble(3));
                        results.add(result);
                    }
                    return results;
                }
            }
        }
    }

    /** 按索引返回的受控路径读取完整记忆文件。 */
    @Override
    public String get(String path) throws Exception {
        File target = resolveMemoryPath(path);
        return target.exists() && target.isFile() ? FileUtil.readUtf8String(target).trim() : "";
    }

    /** 打开与当前 Profile 状态库相同的 SQLite 数据库。 */
    private Connection memoryIndexConnection() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + appConfig.getRuntime().getStateDb());
    }

    /** 用当前磁盘内容重建小型记忆索引，确保外部编辑立即可检索。 */
    private void rebuildMemoryIndex(Connection connection) throws Exception {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "create virtual table if not exists memory_fts using fts5(path unindexed, content)");
                statement.execute("delete from memory_fts");
            }
            String sql = "insert into memory_fts(path, content) values(?, ?)";
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                indexFile(insert, MemoryConstants.MEMORY_FILE_NAME);
                indexFile(insert, MemoryConstants.USER_FILE_NAME);
                File directory = memoryDir();
                if (directory.exists()) {
                    for (File file :
                            FileUtil.loopFiles(
                                    directory,
                                    item -> item.isFile() && item.getName().endsWith(".md"))) {
                        String relative = directory.toPath().relativize(file.toPath()).toString();
                        indexFile(insert, MemoryConstants.DAILY_MEMORY_DIR_NAME + "/" + relative);
                    }
                }
            }
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    /** 将存在且非空的单个记忆文件加入索引。 */
    private void indexFile(PreparedStatement insert, String relativePath) throws Exception {
        File file = resolveMemoryPath(relativePath);
        if (!file.exists() || !file.isFile()) {
            return;
        }
        String content = FileUtil.readUtf8String(file);
        if (StrUtil.isBlank(content)) {
            return;
        }
        insert.setString(1, relativePath.replace(File.separatorChar, '/'));
        insert.setString(2, content);
        insert.executeUpdate();
    }

    /** 把普通搜索词转换为不解释运算符的 FTS5 AND 查询。 */
    private String ftsQuery(String query) {
        String[] tokens = StrUtil.nullToEmpty(query).trim().split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String token : tokens) {
            if (StrUtil.isBlank(token)) {
                continue;
            }
            if (result.length() > 0) {
                result.append(" AND ");
            }
            result.append('"').append(token.replace("\"", "\"\"")).append('"');
        }
        return result.toString();
    }

    /** 解析并约束索引路径只能落在当前 Profile 的记忆区域。 */
    private File resolveMemoryPath(String path) throws Exception {
        String normalized = StrUtil.nullToEmpty(path).replace('\\', '/');
        if (MemoryConstants.MEMORY_FILE_NAME.equals(normalized)
                || MemoryConstants.USER_FILE_NAME.equals(normalized)) {
            return FileUtil.file(appConfig.getRuntime().getHome(), normalized);
        }
        if (!normalized.startsWith(MemoryConstants.DAILY_MEMORY_DIR_NAME + "/")) {
            throw new IllegalArgumentException("不支持的记忆路径。");
        }
        File root = memoryDir().getCanonicalFile();
        File target =
                FileUtil.file(appConfig.getRuntime().getHome(), normalized).getCanonicalFile();
        if (!target.getPath().startsWith(root.getPath() + File.separator)) {
            throw new IllegalArgumentException("记忆路径超出工作区。");
        }
        return target;
    }

    /**
     * 执行add相关逻辑。
     *
     * @param target target 参数。
     * @param content 待处理内容。
     * @return 返回add结果。
     */
    @Override
    public synchronized String add(String target, String content) throws Exception {
        return add(target, content, "background_review");
    }

    /** 添加条目并记录待审批来源，前台终端与后台写入使用不同来源标识。 */
    @Override
    public synchronized String add(String target, String content, String origin) throws Exception {
        if (StrUtil.isBlank(content)) {
            return "记忆内容不能为空。";
        }

        if (MemoryConstants.TARGET_TODAY.equalsIgnoreCase(target)) {
            String normalizedDaily = normalizeDailyEntry(content);
            if (StrUtil.isBlank(normalizedDaily)) {
                return "今日记忆内容不能为空。";
            }
            return stageOrApply("add", target, normalizedDaily, null, origin);
        }

        String normalized = normalizeEntry(content);
        if (shouldReject(normalized)) {
            return "该内容更像临时任务状态或内部上下文，不会写入长期记忆。";
        }

        return stageOrApply("add", target, normalized, null, origin);
    }

    /** 绕过审批边界直接添加已校验的记忆内容。 */
    private String addDirect(String target, String content) throws Exception {
        if (MemoryConstants.TARGET_TODAY.equalsIgnoreCase(target)) {
            return appendTodayEntry(content);
        }
        String normalized = normalizeEntry(content);
        if (!appendEntryPreservingRaw(target, normalized)) {
            return "未执行：记忆文件持续被人工或外部编辑，后台追加未覆盖最新内容。";
        }
        return "已写入 " + normalizeTarget(target) + "。";
    }

    /**
     * 执行replace相关逻辑。
     *
     * @param target target 参数。
     * @param oldText old文本参数。
     * @param newContent newContent 参数。
     * @return 返回replace结果。
     */
    @Override
    public synchronized String replace(String target, String oldText, String newContent)
            throws Exception {
        return replace(target, oldText, newContent, "background_review");
    }

    /** 替换条目并记录待审批来源，便于区分前台确认与后台审查。 */
    @Override
    public synchronized String replace(
            String target, String oldText, String newContent, String origin) throws Exception {
        if (StrUtil.isBlank(oldText) || StrUtil.isBlank(newContent)) {
            return "replace 需要 oldText 和 newContent。";
        }

        String normalizedNew = normalizeEntry(newContent);
        if (shouldReject(normalizedNew)) {
            return "该内容更像临时任务状态或内部上下文，不会写入长期记忆。";
        }

        return stageOrApply("replace", target, normalizedNew, oldText.trim(), origin);
    }

    /** 绕过审批边界直接替换已校验的记忆内容。 */
    private String replaceDirect(String target, String oldText, String newContent)
            throws Exception {
        MemoryRewriteSnapshot snapshot = prepareMemoryRewrite(target);
        if (snapshot.failure != null) {
            return snapshot.failure;
        }
        String normalizedNew = normalizeEntry(newContent);
        List<String> entries = snapshot.entries;
        List<String> matches = normalizeMatchEntries(oldText);
        int matchIndex = findEntrySequence(entries, matches, 0);
        if (matchIndex < 0) {
            return "未找到可替换的记忆条目。";
        }

        for (int i = 0; i < matches.size(); i++) {
            entries.remove(matchIndex);
        }
        entries.add(matchIndex, normalizedNew);

        String concurrentFailure = writeEntriesIfUnchanged(target, snapshot.raw, entries);
        if (concurrentFailure != null) {
            return concurrentFailure;
        }
        return "已更新 " + normalizeTarget(target) + "。";
    }

    /**
     * 执行remove相关逻辑。
     *
     * @param target target 参数。
     * @param matchText match文本参数。
     * @return 返回remove结果。
     */
    @Override
    public synchronized String remove(String target, String matchText) throws Exception {
        return remove(target, matchText, "background_review");
    }

    /** 删除条目并记录待审批来源，便于审批列表解释变更来源。 */
    @Override
    public synchronized String remove(String target, String matchText, String origin)
            throws Exception {
        if (StrUtil.isBlank(matchText)) {
            return "remove 需要 matchText。";
        }

        return stageOrApply("remove", target, null, matchText.trim(), origin);
    }

    /** 绕过审批边界直接删除匹配的记忆内容。 */
    private String removeDirect(String target, String matchText) throws Exception {
        MemoryRewriteSnapshot snapshot = prepareMemoryRewrite(target);
        if (snapshot.failure != null) {
            return snapshot.failure;
        }
        List<String> entries = snapshot.entries;
        List<String> matches = normalizeMatchEntries(matchText);
        boolean removed = false;
        int searchFrom = 0;
        int matchIndex;
        while ((matchIndex = findEntrySequence(entries, matches, searchFrom)) >= 0) {
            for (int i = 0; i < matches.size(); i++) {
                entries.remove(matchIndex);
            }
            removed = true;
            searchFrom = matchIndex;
        }

        if (!removed) {
            return "未找到可删除的记忆条目。";
        }

        String concurrentFailure = writeEntriesIfUnchanged(target, snapshot.raw, entries);
        if (concurrentFailure != null) {
            return concurrentFailure;
        }
        return "已删除 " + normalizeTarget(target) + " 中的匹配条目。";
    }

    /** 在共享服务边界暂存或直接应用写入，确保工具、学习服务等所有调用方行为一致。 */
    private String stageOrApply(
            String action, String target, String content, String oldText, String origin)
            throws Exception {
        return withApprovalStateLock(
                () -> {
                    ApprovalState state = readApprovalState();
                    String normalizedOrigin = normalizeApprovalOrigin(origin);
                    boolean backgroundRewrite =
                            "background_review".equals(normalizedOrigin) && !"add".equals(action);
                    if (!state.enabled && !backgroundRewrite) {
                        return applyDirect(action, target, content, oldText);
                    }
                    String normalizedTarget = normalizePendingTarget(target);
                    if ("add".equals(action)
                            && !MemoryConstants.TARGET_TODAY.equalsIgnoreCase(normalizedTarget)
                            && containsEntry(readEntries(normalizedTarget), content)) {
                        return "记忆条目已存在，无需重复写入或审批。";
                    }
                    PendingMutation duplicate =
                            findPendingDuplicate(state, action, normalizedTarget, content, oldText);
                    if (duplicate != null) {
                        return "已存在相同待审批记忆变更，ID: " + duplicate.id + "。";
                    }
                    PendingMutation pending = new PendingMutation();
                    pending.id = newPendingId(state);
                    pending.action = action;
                    pending.target = normalizedTarget;
                    pending.content = content;
                    pending.oldText = oldText;
                    pending.origin = normalizedOrigin;
                    pending.createdAt = Instant.now().getEpochSecond();
                    state.pending.add(pending);
                    writeApprovalState(state);
                    return "已暂存待审批记忆变更，ID: " + pending.id + "。";
                });
    }

    /** 查找动作、目标和载荷完全一致的待审批变更，避免后台重试重复排队。 */
    private PendingMutation findPendingDuplicate(
            ApprovalState state, String action, String target, String content, String oldText) {
        for (PendingMutation pending : state.pending) {
            if (sameText(pending.action, action)
                    && sameText(pending.target, target)
                    && sameText(pending.content, content)
                    && sameText(pending.oldText, oldText)) {
                return pending;
            }
        }
        return null;
    }

    /** 对可空审批字段执行精确相等比较。 */
    private boolean sameText(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    /** 将审批来源限制为用户可理解的前台或后台审查语义。 */
    private String normalizeApprovalOrigin(String origin) {
        return "foreground".equalsIgnoreCase(StrUtil.nullToEmpty(origin).trim())
                ? "foreground"
                : "background_review";
    }

    /** 直接执行审批后的变更，不再次进入审批门禁。 */
    private String applyDirect(String action, String target, String content, String oldText)
            throws Exception {
        if ("add".equals(action)) {
            return addDirect(target, content);
        }
        if ("replace".equals(action)) {
            return replaceDirect(target, oldText, content);
        }
        if ("remove".equals(action)) {
            return removeDirect(target, oldText);
        }
        throw new IllegalArgumentException("不支持的记忆审批动作。");
    }

    /** 查询持久化审批开关。 */
    @Override
    public synchronized boolean isApprovalEnabled() throws Exception {
        return withApprovalStateLock(() -> Boolean.valueOf(readApprovalState().enabled))
                .booleanValue();
    }

    /** 持久化审批开关；按外部参考契约缺省保持关闭。 */
    @Override
    public synchronized String setApprovalEnabled(boolean enabled) throws Exception {
        return withApprovalStateLock(
                () -> {
                    ApprovalState state = readApprovalState();
                    state.enabled = enabled;
                    writeApprovalState(state);
                    return enabled ? "记忆写入审批已开启。" : "记忆写入审批已关闭。";
                });
    }

    /** 返回不含原始内容的待审批变更视图。 */
    @Override
    public synchronized List<MemoryApprovalRequest> listPendingApprovals() throws Exception {
        return withApprovalStateLock(() -> visiblePendingApprovals(readApprovalState()));
    }

    /** 将内部队列转换为不含原始内容的待审批视图。 */
    private List<MemoryApprovalRequest> visiblePendingApprovals(ApprovalState state) {
        List<MemoryApprovalRequest> visible = new ArrayList<MemoryApprovalRequest>();
        for (PendingMutation pending : state.pending) {
            String raw = "remove".equals(pending.action) ? pending.oldText : pending.content;
            Map<String, String> payload = new LinkedHashMap<String, String>();
            payload.put("target", pending.target);
            if (pending.content != null) {
                payload.put("content", SecretRedactor.redact(pending.content, 240));
            }
            if (pending.oldText != null) {
                payload.put("old_text", SecretRedactor.redact(pending.oldText, 240));
            }
            visible.add(
                    new MemoryApprovalRequest(
                            pending.id,
                            "memory",
                            pending.action,
                            pending.action
                                    + " "
                                    + pending.target
                                    + ": "
                                    + SecretRedactor.redact(raw, 160),
                            pending.origin,
                            pending.createdAt,
                            payload));
        }
        return visible;
    }

    /** 批准指定标识或全部变更，应用成功后从持久化队列移除。 */
    @Override
    public synchronized String approve(String idOrAll) throws Exception {
        return resolvePending(idOrAll, true);
    }

    /** 拒绝指定标识或全部变更，直接从持久化队列移除。 */
    @Override
    public synchronized String reject(String idOrAll) throws Exception {
        return resolvePending(idOrAll, false);
    }

    /** 解析单条或全部审批请求。 */
    private String resolvePending(String idOrAll, boolean approved) throws Exception {
        return withApprovalStateLock(() -> resolvePendingLocked(idOrAll, approved));
    }

    /** 在审批状态锁内解析单条或全部请求，逐项保留失败记录。 */
    private String resolvePendingLocked(String idOrAll, boolean approved) throws Exception {
        String requested = StrUtil.nullToEmpty(idOrAll).trim();
        if (requested.length() == 0) {
            return "审批标识不能为空。";
        }
        ApprovalState state = readApprovalState();
        List<PendingMutation> selected = new ArrayList<PendingMutation>();
        for (PendingMutation pending : state.pending) {
            if (ALL.equalsIgnoreCase(requested) || pending.id.equals(requested)) {
                selected.add(pending);
            }
        }
        if (selected.isEmpty()) {
            return "未找到待审批记忆变更。";
        }
        if (!approved) {
            state.pending.removeAll(selected);
            writeApprovalState(state);
            return "已拒绝并丢弃 " + selected.size() + " 条记忆变更。";
        }
        int applied = 0;
        List<String> failed = new ArrayList<String>();
        for (PendingMutation pending : selected) {
            try {
                String result =
                        applyDirect(
                                pending.action, pending.target, pending.content, pending.oldText);
                if (isMutationFailure(result)) {
                    throw new IllegalStateException(result);
                }
                state.pending.remove(pending);
                writeApprovalState(state);
                applied++;
            } catch (Exception e) {
                failed.add(pending.id + ": " + safeApprovalError(e));
            }
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException(
                    "已应用 " + applied + " 条，以下待审批变更失败并已保留：" + String.join("；", failed));
        }
        return "已批准并应用 " + applied + " 条记忆变更。";
    }

    /** 生成不含原始审批载荷的失败摘要。 */
    private String safeApprovalError(Exception error) {
        String message = error == null ? "unknown" : error.getMessage();
        return SecretRedactor.redact(StrUtil.blankToDefault(message, "unknown"), 160);
    }

    /** 判断底层字符串结果是否表示业务失败，失败项必须继续保留在审批队列。 */
    private boolean isMutationFailure(String result) {
        String normalized = StrUtil.nullToEmpty(result).trim();
        return normalized.length() == 0
                || normalized.startsWith("未")
                || normalized.contains("不能为空")
                || normalized.contains("不会写入")
                || normalized.contains("需要 ");
    }

    /** 读取审批状态；损坏文件会抛错，避免意外绕过已开启的门禁。 */
    @SuppressWarnings("unchecked")
    private ApprovalState readApprovalState() throws Exception {
        File file = approvalStateFile();
        ApprovalState state = new ApprovalState();
        if (!file.exists()) {
            return state;
        }
        String raw = FileUtil.readUtf8String(file);
        if (StrUtil.isBlank(raw)) {
            throw new IllegalStateException("记忆审批状态文件为空。请修复或删除该文件后重试。");
        }
        Object parsed = ONode.deserialize(raw, Object.class);
        if (!(parsed instanceof Map)) {
            throw new IllegalStateException("记忆审批状态文件格式无效。");
        }
        Map<String, Object> root = (Map<String, Object>) parsed;
        Object enabledValue = root.get("approval_enabled");
        if (!(enabledValue instanceof Boolean)) {
            throw new IllegalStateException("记忆审批状态缺少布尔字段: approval_enabled");
        }
        state.enabled = ((Boolean) enabledValue).booleanValue();
        Object pendingValue = root.get("pending");
        if (!(pendingValue instanceof List)) {
            throw new IllegalStateException("记忆审批队列格式无效。");
        }
        for (Object item : (List<Object>) pendingValue) {
            if (!(item instanceof Map)) {
                throw new IllegalStateException("记忆审批条目格式无效。");
            }
            Map<String, Object> values = (Map<String, Object>) item;
            PendingMutation pending = new PendingMutation();
            pending.id = requiredText(values, "id");
            pending.action = requiredText(values, "action");
            if (!"memory".equals(requiredText(values, "subsystem"))) {
                throw new IllegalStateException("记忆审批条目子系统无效。");
            }
            pending.origin = requiredText(values, "origin");
            pending.createdAt = requiredEpoch(values, "created_at");
            Object payloadValue = values.get("payload");
            if (!(payloadValue instanceof Map)) {
                throw new IllegalStateException("记忆审批条目 payload 格式无效。");
            }
            Map<String, Object> payload = (Map<String, Object>) payloadValue;
            pending.target = requiredText(payload, "target");
            pending.content = nullableText(payload.get("content"));
            pending.oldText = nullableText(payload.get("old_text"));
            state.pending.add(pending);
        }
        return state;
    }

    /** 原子写入审批开关和队列。 */
    private void writeApprovalState(ApprovalState state) throws Exception {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("approval_enabled", Boolean.valueOf(state.enabled));
        List<Map<String, Object>> pendingValues = new ArrayList<Map<String, Object>>();
        for (PendingMutation pending : state.pending) {
            Map<String, Object> values = new LinkedHashMap<String, Object>();
            values.put("id", pending.id);
            values.put("subsystem", "memory");
            values.put("action", pending.action);
            values.put("summary", pending.action + " " + pending.target);
            values.put("origin", pending.origin);
            values.put("created_at", pending.createdAt);
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("target", pending.target);
            payload.put("content", pending.content);
            payload.put("old_text", pending.oldText);
            values.put("payload", payload);
            pendingValues.add(values);
        }
        root.put("pending", pendingValues);
        writeUtf8Atomically(approvalStateFile().toPath(), ONode.serialize(root));
    }

    /** 生成队列内唯一的八位小写十六进制标识。 */
    private String newPendingId(ApprovalState state) {
        while (true) {
            String candidate = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            boolean duplicate = false;
            for (PendingMutation pending : state.pending) {
                if (candidate.equals(pending.id)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                return candidate;
            }
        }
    }

    /** 读取必填文本字段。 */
    private String requiredText(Map<String, Object> values, String key) {
        String value = nullableText(values.get(key));
        if (StrUtil.isBlank(value)) {
            throw new IllegalStateException("记忆审批条目缺少字段: " + key);
        }
        return value;
    }

    /** 读取必填 Unix epoch 数值字段。 */
    private long requiredEpoch(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Number)) {
            throw new IllegalStateException("记忆审批条目缺少数值字段: " + key);
        }
        return ((Number) value).longValue();
    }

    /** 将可空持久化值转换为字符串。 */
    private String nullableText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 限定待审批目标为受支持的记忆区域。 */
    private String normalizePendingTarget(String target) {
        if (MemoryConstants.TARGET_TODAY.equalsIgnoreCase(target)) {
            return MemoryConstants.TARGET_TODAY;
        }
        return normalizeTarget(target);
    }

    /** 返回审批状态文件。 */
    private File approvalStateFile() {
        return FileUtil.file(
                appConfig.getRuntime().getHome(), MemoryConstants.APPROVAL_STATE_FILE_NAME);
    }

    /** 在同一 Profile 的 JVM 路径锁和文件锁内执行审批状态读改写。 */
    private <T> T withApprovalStateLock(ApprovalStateAction<T> action) throws Exception {
        return memoryFileLock.withLock(action::run);
    }

    /** 可在审批状态锁内抛出受检异常的动作。 */
    private interface ApprovalStateAction<T> {
        /** 执行受保护的审批状态操作。 */
        T run() throws Exception;
    }

    /** 内部持久化审批状态，不向调用方暴露原始内容。 */
    private static final class ApprovalState {
        /** 是否启用记忆写入审批。 */
        private boolean enabled;

        /** 等待审批的记忆变更。 */
        private final List<PendingMutation> pending = new ArrayList<PendingMutation>();
    }

    /** 内部待审批写入，原始内容只用于批准后的准确重放。 */
    private static final class PendingMutation {
        /** 变更标识。 */
        private String id;

        /** 写入动作。 */
        private String action;

        /** 目标记忆区域。 */
        private String target;

        /** add/replace 的新内容。 */
        private String content;

        /** replace/remove 的匹配文本。 */
        private String oldText;

        /** 产生变更的服务边界。 */
        private String origin;

        /** 暂存时间，使用 Unix epoch 秒。 */
        private long createdAt;
    }

    /** 读取记忆条目列表。 */
    private List<String> readEntries(String target) throws Exception {
        File file = fileForTarget(target);
        String raw = file.exists() ? FileUtil.readUtf8String(file) : "";
        return parseEntries(raw);
    }

    /** 从当前工具支持的逐行列表格式解析记忆条目。 */
    private List<String> parseEntries(String raw) {
        List<String> entries = new ArrayList<String>();
        if (StrUtil.isBlank(raw)) {
            return entries;
        }

        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.length() == 0) {
                continue;
            }
            if (trimmed.startsWith("- ")) {
                trimmed = trimmed.substring(2).trim();
            }
            if (trimmed.length() > 0) {
                entries.add(normalizeEntry(trimmed));
            }
        }
        return entries;
    }

    /** 追加条目时只向文件尾部写入新增字节，避免用旧快照替换人工维护的完整文件。 */
    private boolean appendEntryPreservingRaw(String target, String entry) {
        File file = fileForTarget(target);
        for (int attempt = 0; attempt < APPEND_RETRY_ATTEMPTS; attempt++) {
            boolean existed = file.exists();
            String raw = existed ? FileUtil.readUtf8String(file) : "";
            if (containsEntry(parseEntries(raw), entry)) {
                return true;
            }
            try {
                if (!existed) {
                    createUtf8(file.toPath(), "- " + entry);
                    return true;
                }
                appendUtf8(file.toPath(), detectLineSeparator(raw) + "- " + entry);
                return true;
            } catch (java.nio.file.FileAlreadyExistsException e) {
                // 人工编辑器刚创建目标，重读新正文后再追加。
            } catch (IOException e) {
                throw new IllegalStateException("记忆文件追加失败。", e);
            }
        }
        return false;
    }

    /** 沿用已有文件换行风格，未知时使用当前系统换行符。 */
    private String detectLineSeparator(String raw) {
        if (raw.contains("\r\n")) {
            return "\r\n";
        }
        if (raw.contains("\n")) {
            return "\n";
        }
        if (raw.contains("\r")) {
            return "\r";
        }
        return System.lineSeparator();
    }

    /** 在 replace/remove 开始时读取一次原文，并从同一快照完成保护判断与条目解析。 */
    private MemoryRewriteSnapshot prepareMemoryRewrite(String target) throws Exception {
        File file = fileForTarget(target);
        String raw = file.exists() && file.isFile() ? FileUtil.readUtf8String(file) : "";
        List<String> entries = parseEntries(raw);
        if (StrUtil.isBlank(raw) || isListOnlyMemoryMarkdown(raw)) {
            return new MemoryRewriteSnapshot(raw, entries, null);
        }
        Path backup = backupDriftedMemory(file.toPath(), raw);
        return new MemoryRewriteSnapshot(
                raw,
                entries,
                "未执行：检测到人工或外部编辑的 Markdown，replace/remove 可能破坏原文；原文件保持不变，备份位于 "
                        + backup.toAbsolutePath()
                        + "。");
    }

    /** 仅包含空行和标准列表项的文件可无损按记忆条目语义改写。 */
    private boolean isListOnlyMemoryMarkdown(String raw) {
        for (String line : StrUtil.nullToEmpty(raw).split("\\R", -1)) {
            String trimmed = line.trim();
            if (StrUtil.isNotBlank(trimmed) && !trimmed.startsWith("- ")) {
                return false;
            }
        }
        return true;
    }

    /** 为无法无损重写的记忆文件创建不覆盖旧备份的恢复副本。 */
    private Path backupDriftedMemory(Path source, String raw) throws IOException {
        long timestamp = System.currentTimeMillis();
        int sequence = 0;
        while (true) {
            String suffix = ".bak." + timestamp + (sequence == 0 ? "" : "." + sequence);
            Path backup = source.resolveSibling(source.getFileName().toString() + suffix);
            try {
                Files.write(
                        backup,
                        raw.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                return backup;
            } catch (java.nio.file.FileAlreadyExistsException e) {
                sequence++;
            }
        }
    }

    /** 仅当文件仍与保护检查时的原文一致时写回；漂移时备份最新人工内容并拒绝覆盖。 */
    private String writeEntriesIfUnchanged(String target, String expectedRaw, List<String> entries)
            throws Exception {
        File file = fileForTarget(target);
        String current = file.exists() && file.isFile() ? FileUtil.readUtf8String(file) : "";
        if (!StrUtil.equals(current, expectedRaw)) {
            Path backup = backupDriftedMemory(file.toPath(), current);
            return "未执行：写入前检测到人工或外部编辑，原文件保持不变，备份位于 " + backup.toAbsolutePath() + "。";
        }
        try {
            if (!writeRawIfUnchanged(file, expectedRaw, renderEntries(entries))) {
                String latest = file.exists() && file.isFile() ? FileUtil.readUtf8String(file) : "";
                Path backup = backupDriftedMemory(file.toPath(), latest);
                return "未执行：写入前检测到人工或外部编辑，原文件保持不变，备份位于 " + backup.toAbsolutePath() + "。";
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("记忆文件写入失败。", e);
        }
    }

    /** replace/remove 使用的原文、解析条目与保护失败结果。 */
    private static final class MemoryRewriteSnapshot {
        /** 保护检查时读取的完整原文。 */
        private final String raw;

        /** 从同一原文解析出的标准条目。 */
        private final List<String> entries;

        /** 发现人工结构时返回给调用方的拒绝说明。 */
        private final String failure;

        /** 创建不可变的记忆重写快照。 */
        private MemoryRewriteSnapshot(String raw, List<String> entries, String failure) {
            this.raw = raw;
            this.entries = entries;
            this.failure = failure;
        }
    }

    /** 将条目序列化为本服务唯一可安全全量重写的标准列表格式。 */
    private String renderEntries(List<String> entries) {
        StringBuilder buffer = new StringBuilder();
        for (String entry : entries) {
            if (buffer.length() > 0) {
                buffer.append(System.lineSeparator());
            }
            buffer.append("- ").append(entry);
        }
        return buffer.toString();
    }

    /** 解析目标文件。 */
    private File fileForTarget(String target) {
        if (MemoryConstants.TARGET_USER.equalsIgnoreCase(target)) {
            return FileUtil.file(appConfig.getRuntime().getHome(), MemoryConstants.USER_FILE_NAME);
        }
        if (MemoryConstants.TARGET_TODAY.equalsIgnoreCase(target)) {
            return todayMemoryFile();
        }
        String topic = topicName(target);
        if (topic != null) {
            return FileUtil.file(memoryDir(), topic + ".md");
        }
        return FileUtil.file(appConfig.getRuntime().getHome(), MemoryConstants.MEMORY_FILE_NAME);
    }

    /** 统一目标名输出。 */
    private String normalizeTarget(String target) {
        if (MemoryConstants.TARGET_USER.equalsIgnoreCase(target)) {
            return MemoryConstants.TARGET_USER;
        }
        if (MemoryConstants.TARGET_TODAY.equalsIgnoreCase(target)) {
            return MemoryConstants.TARGET_TODAY;
        }
        String topic = topicName(target);
        return topic == null
                ? MemoryConstants.TARGET_MEMORY
                : MemoryConstants.TARGET_TOPIC_PREFIX + topic;
    }

    /** 校验并提取专题名称；非专题目标返回 null。 */
    private String topicName(String target) {
        String value = StrUtil.nullToEmpty(target).trim();
        if (!value.toLowerCase(java.util.Locale.ROOT)
                .startsWith(MemoryConstants.TARGET_TOPIC_PREFIX)) {
            return null;
        }
        String name = value.substring(MemoryConstants.TARGET_TOPIC_PREFIX.length()).trim();
        if (!TOPIC_NAME_PATTERN.matcher(name).matches() || ".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("专题名称格式无效。");
        }
        return name;
    }

    /** 统一归一化记忆条目。 */
    private String normalizeEntry(String content) {
        return StrUtil.nullToEmpty(content)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** 将 memory read 输出或普通文本统一解析为可与内部条目比较的序列。 */
    private List<String> normalizeMatchEntries(String content) {
        List<String> entries = new ArrayList<String>();
        for (String line : StrUtil.nullToEmpty(content).split("\\R")) {
            String normalized = line.trim();
            if (normalized.startsWith("- ")) {
                normalized = normalized.substring(2).trim();
            }
            normalized = normalizeEntry(normalized);
            if (StrUtil.isNotBlank(normalized)) {
                entries.add(normalized);
            }
        }
        return entries;
    }

    /** 查找连续匹配的记忆条目；每个查询条目沿用既有的包含匹配语义。 */
    private int findEntrySequence(List<String> entries, List<String> matches, int fromIndex) {
        if (matches.isEmpty() || entries.size() < matches.size()) {
            return -1;
        }
        int lastStart = entries.size() - matches.size();
        for (int i = Math.max(0, fromIndex); i <= lastStart; i++) {
            boolean matched = true;
            for (int j = 0; j < matches.size(); j++) {
                if (!entries.get(i + j).contains(matches.get(j))) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return i;
            }
        }
        return -1;
    }

    /** 判断内容是否像短期状态。 */
    private boolean shouldReject(String content) {
        if (StrUtil.isBlank(content)) {
            return true;
        }
        if (content.length() > 300) {
            return true;
        }
        if (MemoryContextBoundary.containsFence(content)
                || StrUtil.containsIgnoreCase(
                        content, "System note: The following is recalled memory context")) {
            return true;
        }
        for (String pattern : TRANSIENT_PATTERNS) {
            if (StrUtil.containsIgnoreCase(content, pattern)) {
                return true;
            }
        }
        if (hasExplicitLongTermPrefix(content)) {
            return false;
        }
        for (String pattern : WEAK_TRANSIENT_PATTERNS) {
            if (StrUtil.containsIgnoreCase(content, pattern)) {
                return true;
            }
        }
        return false;
    }

    /** 判断内容是否用稳定前缀明确表达长期记忆价值。 */
    private boolean hasExplicitLongTermPrefix(String content) {
        String normalized = StrUtil.nullToEmpty(content).trim();
        for (String prefix : LONG_TERM_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 判断是否已有相同或更泛化的条目。 */
    private boolean containsEntry(List<String> entries, String candidate) {
        for (String entry : entries) {
            if (entry.equals(candidate) || entry.contains(candidate) || candidate.contains(entry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取Today记忆。
     *
     * @return 返回读取到的Today记忆。
     */
    private String readTodayMemory() {
        File file = todayMemoryFile();
        if (!file.exists()) {
            return "";
        }
        return FileUtil.readUtf8String(file).trim();
    }

    /**
     * 追加Today Entry。
     *
     * @param content 待处理内容。
     * @return 返回Today Entry结果。
     */
    private String appendTodayEntry(String content) {
        String normalized = normalizeDailyEntry(content);
        if (StrUtil.isBlank(normalized)) {
            return "今日记忆内容不能为空。";
        }

        File file = todayMemoryFile();
        for (int attempt = 0; attempt < APPEND_RETRY_ATTEMPTS; attempt++) {
            String existing = file.exists() ? FileUtil.readUtf8String(file) : "";
            if (existing.contains(normalized)) {
                return "今日记忆已存在。";
            }

            try {
                if (!file.exists()) {
                    String initial =
                            "# "
                                    + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                                    + System.lineSeparator()
                                    + System.lineSeparator()
                                    + "- "
                                    + normalized
                                    + System.lineSeparator();
                    createUtf8(file.toPath(), initial);
                    return "已写入 " + MemoryConstants.TARGET_TODAY + "。";
                }
                StringBuilder suffix =
                        new StringBuilder(detectLineSeparator(existing))
                                .append("- ")
                                .append(normalized)
                                .append(detectLineSeparator(existing));
                appendUtf8(file.toPath(), suffix.toString());
                return "已写入 " + MemoryConstants.TARGET_TODAY + "。";
            } catch (java.nio.file.FileAlreadyExistsException e) {
                // 其他写者刚创建文件，重读后再判断重复并追加。
            } catch (IOException e) {
                throw new IllegalStateException("今日记忆文件写入失败。", e);
            }
        }
        return "未执行：今日记忆文件持续发生创建竞争，后台追加未覆盖最新内容。";
    }

    /** 使用操作系统追加模式写入新增内容，不读取后覆盖目标文件。 */
    protected void appendUtf8(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("记忆文件缺少父目录。");
        }
        Files.createDirectories(parent);
        Files.write(
                target,
                content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE);
    }

    /** 仅在目标不存在时创建 UTF-8 文件，供追加路径识别人工抢先创建竞态。 */
    protected void createUtf8(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("记忆文件缺少父目录。");
        }
        Files.createDirectories(parent);
        Files.write(
                target,
                content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    /** 比较当前完整原文后执行原子替换，返回 false 表示外部编辑已经改变目标。 */
    private boolean writeRawIfUnchanged(File file, String expectedRaw, String updated)
            throws IOException {
        String current = file.exists() && file.isFile() ? FileUtil.readUtf8String(file) : "";
        if (!StrUtil.equals(current, expectedRaw)) {
            return false;
        }
        writeUtf8Atomically(file.toPath(), updated);
        return true;
    }

    /**
     * 规范化Daily Entry。
     *
     * @param content 待处理内容。
     * @return 返回Daily Entry结果。
     */
    private String normalizeDailyEntry(String content) {
        String normalized = normalizeEntry(content);
        if (normalized.length() > 500) {
            return normalized.substring(0, 500).trim() + "...";
        }
        return normalized;
    }

    /**
     * 执行记忆目录相关逻辑。
     *
     * @return 返回记忆Dir结果。
     */
    private File memoryDir() {
        return FileUtil.file(
                appConfig.getRuntime().getHome(), MemoryConstants.DAILY_MEMORY_DIR_NAME);
    }

    /**
     * 执行today记忆文件相关逻辑。
     *
     * @return 返回today记忆文件结果。
     */
    private File todayMemoryFile() {
        return FileUtil.file(
                memoryDir(), LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md");
    }

    /** 使用同目录临时文件原子替换目标，文件系统不支持时降级为普通替换。 */
    private void writeUtf8Atomically(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("记忆文件缺少父目录。");
        }
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".solonclaw-memory-", ".tmp");
        try {
            Files.write(temp, StrUtil.nullToEmpty(content).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
