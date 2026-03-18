package com.jimuqu.claw.agent.store;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.jimuqu.claw.agent.model.AgentRun;
import com.jimuqu.claw.agent.model.ChannelType;
import com.jimuqu.claw.agent.model.ConversationEvent;
import com.jimuqu.claw.agent.model.InboundEnvelope;
import com.jimuqu.claw.agent.model.LatestReplyRoute;
import com.jimuqu.claw.agent.model.ReplyTarget;
import com.jimuqu.claw.agent.model.RunEvent;
import com.jimuqu.claw.agent.model.RunStatus;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 负责以文件形式持久化运行任务、会话事件、去重标记和路由信息。
 */
public class RuntimeStoreService {
    /** 运行时根目录。 */
    private final File runtimeDir;
    /** 运行任务目录。 */
    private final File runsDir;
    /** 会话事件目录。 */
    private final File conversationsDir;
    /** 去重标记目录。 */
    private final File dedupDir;
    /** 元数据目录。 */
    private final File metaDir;
    /** 媒体目录。 */
    private final File mediaDir;
    /** 文件路径锁表。 */
    private final Map<String, ReentrantLock> pathLocks = new ConcurrentHashMap<>();

    /**
     * 创建运行时存储服务。
     *
     * @param runtimeDir 运行时根目录
     */
    public RuntimeStoreService(File runtimeDir) {
        FileUtil.mkdir(runtimeDir);
        this.runtimeDir = runtimeDir;
        this.runsDir = FileUtil.mkdir(new File(runtimeDir, "runs"));
        this.conversationsDir = FileUtil.mkdir(new File(runtimeDir, "conversations"));
        this.dedupDir = FileUtil.mkdir(new File(runtimeDir, "dedup"));
        this.metaDir = FileUtil.mkdir(new File(runtimeDir, "meta"));
        this.mediaDir = FileUtil.mkdir(new File(runtimeDir, "media"));
        markIncompleteRunsAborted();
    }

    /**
     * 生成一个新的运行任务标识。
     *
     * @return 运行任务标识
     */
    public String newRunId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 为入站消息注册去重标记。
     *
     * @param channelType 渠道类型
     * @param messageId 消息标识
     * @return 若首次出现则返回 true
     */
    public boolean registerInbound(ChannelType channelType, String messageId) {
        if (StrUtil.isBlank(messageId)) {
            return true;
        }

        String safeName = DigestUtil.sha1Hex(channelType.name() + ":" + messageId);
        File marker = new File(dedupDir, safeName + ".flag");
        ReentrantLock lock = lock(marker);
        lock.lock();
        try {
            if (marker.exists()) {
                return false;
            }

            FileUtil.writeUtf8String(String.valueOf(System.currentTimeMillis()), marker);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 追加一条用户入站事件。
     *
     * @param inboundEnvelope 入站消息
     * @return 新事件版本号
     */
    public long appendInboundConversationEvent(InboundEnvelope inboundEnvelope) {
        ConversationEvent event = new ConversationEvent();
        event.setSessionKey(inboundEnvelope.getSessionKey());
        event.setEventType("user_message");
        event.setSourceMessageId(inboundEnvelope.getMessageId());
        event.setRole("user");
        event.setContent(inboundEnvelope.getContent());
        event.setCreatedAt(inboundEnvelope.getReceivedAt());
        return appendConversationEvent(inboundEnvelope.getSessionKey(), event);
    }

    /**
     * 追加一条助手回复事件。
     *
     * @param sessionKey 会话键
     * @param runId 运行任务标识
     * @param sourceMessageId 来源消息标识
     * @param sourceUserVersion 来源用户消息版本
     * @param content 回复内容
     * @return 新事件版本号
     */
    public long appendAssistantConversationEvent(String sessionKey, String runId, String sourceMessageId, long sourceUserVersion, String content) {
        ConversationEvent event = new ConversationEvent();
        event.setSessionKey(sessionKey);
        event.setEventType("assistant_reply");
        event.setRunId(runId);
        event.setSourceMessageId(sourceMessageId);
        event.setSourceUserVersion(sourceUserVersion);
        event.setRole("assistant");
        event.setContent(content);
        event.setCreatedAt(System.currentTimeMillis());
        return appendConversationEvent(sessionKey, event);
    }

    /**
     * 追加一条系统事件。
     *
     * @param sessionKey 会话键
     * @param runId 运行任务标识
     * @param content 事件内容
     * @return 新事件版本号
     */
    public long appendSystemConversationEvent(String sessionKey, String runId, String content) {
        ConversationEvent event = new ConversationEvent();
        event.setSessionKey(sessionKey);
        event.setEventType("system_event");
        event.setRunId(runId);
        event.setRole("system");
        event.setContent(content);
        event.setCreatedAt(System.currentTimeMillis());
        return appendConversationEvent(sessionKey, event);
    }

    /**
     * 在会话事件文件中追加一条事件。
     *
     * @param sessionKey 会话键
     * @param event 会话事件
     * @return 新事件版本号
     */
    private long appendConversationEvent(String sessionKey, ConversationEvent event) {
        File eventsFile = conversationEventsFile(sessionKey);
        ReentrantLock lock = lock(eventsFile);
        lock.lock();
        try {
            long nextVersion = countLines(eventsFile) + 1L;
            event.setVersion(nextVersion);
            FileUtil.appendUtf8String(JSONUtil.toJsonStr(event) + System.lineSeparator(), eventsFile);
            updateConversationMeta(sessionKey, nextVersion, event.getCreatedAt(), event.getRunId());
            return nextVersion;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读取某个版本之前的会话历史并重建成聊天消息列表。
     *
     * @param sessionKey 会话键
     * @param beforeUserVersion 截止用户消息版本
     * @return 聊天历史
     */
    public List<ChatMessage> loadConversationHistoryBefore(String sessionKey, long beforeUserVersion) {
        List<ConversationEvent> allEvents = readConversationEvents(sessionKey);
        List<ConversationEvent> userEvents = new ArrayList<>();
        Map<Long, List<ConversationEvent>> repliesBySource = new LinkedHashMap<>();

        for (ConversationEvent event : allEvents) {
            if ("user_message".equals(event.getEventType()) && event.getVersion() < beforeUserVersion) {
                userEvents.add(event);
            }
            if ("assistant_reply".equals(event.getEventType()) && event.getSourceUserVersion() < beforeUserVersion) {
                repliesBySource.computeIfAbsent(event.getSourceUserVersion(), key -> new ArrayList<>()).add(event);
            }
        }

        userEvents.sort(Comparator.comparingLong(ConversationEvent::getVersion));
        List<ChatMessage> history = new ArrayList<>();
        for (ConversationEvent userEvent : userEvents) {
            history.add(ChatMessage.ofUser(userEvent.getContent()));
            List<ConversationEvent> replies = repliesBySource.get(userEvent.getVersion());
            if (replies != null) {
                replies.sort(Comparator.comparingLong(ConversationEvent::getVersion));
                for (ConversationEvent reply : replies) {
                    history.add(ChatMessage.ofAssistant(reply.getContent()));
                }
            }
        }

        return history;
    }

    /**
     * 保存运行任务详情。
     *
     * @param agentRun 运行任务
     */
    public void saveRun(AgentRun agentRun) {
        File runFile = runFile(agentRun.getRunId());
        ReentrantLock lock = lock(runFile);
        lock.lock();
        try {
            FileUtil.writeUtf8String(JSONUtil.toJsonStr(agentRun), runFile);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读取运行任务详情。
     *
     * @param runId 运行任务标识
     * @return 运行任务；若不存在则返回 null
     */
    public AgentRun getRun(String runId) {
        File runFile = runFile(runId);
        if (!runFile.exists()) {
            return null;
        }
        return JSONUtil.toBean(FileUtil.readUtf8String(runFile), AgentRun.class);
    }

    /**
     * 追加一条运行事件。
     *
     * @param runId 运行任务标识
     * @param eventType 事件类型
     * @param message 事件消息
     * @return 运行事件
     */
    public RunEvent appendRunEvent(String runId, String eventType, String message) {
        File file = runEventsFile(runId);
        ReentrantLock lock = lock(file);
        lock.lock();
        try {
            RunEvent runEvent = new RunEvent();
            runEvent.setRunId(runId);
            runEvent.setEventType(eventType);
            runEvent.setMessage(message);
            runEvent.setCreatedAt(System.currentTimeMillis());
            runEvent.setSeq(countLines(file) + 1L);
            FileUtil.appendUtf8String(JSONUtil.toJsonStr(runEvent) + System.lineSeparator(), file);
            return runEvent;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读取指定序号之后的运行事件。
     *
     * @param runId 运行任务标识
     * @param afterSeq 起始序号
     * @return 运行事件列表
     */
    public List<RunEvent> getRunEvents(String runId, long afterSeq) {
        File file = runEventsFile(runId);
        if (!file.exists()) {
            return List.of();
        }

        List<RunEvent> events = new ArrayList<>();
        for (String line : FileUtil.readUtf8Lines(file)) {
            if (StrUtil.isBlank(line)) {
                continue;
            }
            RunEvent event = JSONUtil.toBean(line, RunEvent.class);
            if (event.getSeq() > afterSeq) {
                events.add(event);
            }
        }
        return events;
    }

    /**
     * 读取最近一次外部可回复路由。
     *
     * @return 最近一次外部路由
     */
    public LatestReplyRoute getLatestExternalRoute() {
        File file = latestReplyTargetFile();
        if (!file.exists()) {
            return null;
        }
        return JSONUtil.toBean(FileUtil.readUtf8String(file), LatestReplyRoute.class);
    }

    /**
     * 读取最近一次外部回复目标。
     *
     * @return 最近一次外部回复目标
     */
    public ReplyTarget getLatestExternalReplyTarget() {
        LatestReplyRoute route = getLatestExternalRoute();
        return route == null ? null : route.getReplyTarget();
    }

    /**
     * 记录最近一次外部回复目标。
     *
     * @param sessionKey 会话键
     * @param replyTarget 回复目标
     */
    public void rememberReplyTarget(String sessionKey, ReplyTarget replyTarget) {
        if (replyTarget == null || replyTarget.isDebugWeb()) {
            return;
        }

        File file = latestReplyTargetFile();
        ReentrantLock lock = lock(file);
        lock.lock();
        try {
            LatestReplyRoute route = new LatestReplyRoute();
            route.setSessionKey(sessionKey);
            route.setReplyTarget(replyTarget);
            FileUtil.writeUtf8String(JSONUtil.toJsonStr(route), file);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回运行时根目录。
     *
     * @return 运行时根目录
     */
    public File getRuntimeDir() {
        return runtimeDir;
    }

    /**
     * 按渠道返回媒体目录。
     *
     * @param channelType 渠道类型
     * @return 媒体目录
     */
    public File resolveMediaDir(ChannelType channelType) {
        return FileUtil.mkdir(new File(mediaDir, channelType.name().toLowerCase()));
    }

    /**
     * 读取某个会话的全部事件。
     *
     * @param sessionKey 会话键
     * @return 会话事件列表
     */
    public List<ConversationEvent> readConversationEvents(String sessionKey) {
        File file = conversationEventsFile(sessionKey);
        if (!file.exists()) {
            return List.of();
        }

        List<ConversationEvent> events = new ArrayList<>();
        for (String line : FileUtil.readUtf8Lines(file)) {
            if (StrUtil.isBlank(line)) {
                continue;
            }
            events.add(JSONUtil.toBean(line, ConversationEvent.class));
        }
        return events;
    }

    /**
     * 更新会话元数据文件。
     *
     * @param sessionKey 会话键
     * @param latestVersion 最新版本号
     * @param lastUpdatedAt 最后更新时间
     * @param lastRunId 最后关联的运行任务标识
     */
    private void updateConversationMeta(String sessionKey, long latestVersion, long lastUpdatedAt, String lastRunId) {
        File file = conversationMetaFile(sessionKey);
        Map<String, Object> meta = new HashMap<>();
        meta.put("sessionKey", sessionKey);
        meta.put("latestVersion", latestVersion);
        meta.put("lastUpdatedAt", lastUpdatedAt);
        meta.put("lastRunId", lastRunId);
        FileUtil.writeUtf8String(JSONUtil.toJsonStr(meta), file);
    }

    /**
     * 统计文件中的行数。
     *
     * @param file 目标文件
     * @return 行数
     */
    private long countLines(File file) {
        if (!file.exists()) {
            return 0L;
        }
        return FileUtil.readUtf8Lines(file).size();
    }

    /**
     * 在应用启动时将未完成任务标记为已中止。
     */
    private void markIncompleteRunsAborted() {
        List<File> runFiles = FileUtil.loopFiles(runsDir, file -> file.isFile() && file.getName().endsWith(".json"));
        for (File runFile : runFiles) {
            AgentRun run = JSONUtil.toBean(FileUtil.readUtf8String(runFile), AgentRun.class);
            if (run.getStatus() == RunStatus.QUEUED || run.getStatus() == RunStatus.RUNNING) {
                run.setStatus(RunStatus.ABORTED);
                run.setFinishedAt(System.currentTimeMillis());
                run.setErrorMessage("Application restarted before the run finished.");
                saveRun(run);
                appendRunEvent(run.getRunId(), "status", "aborted");
            }
        }
    }

    /**
     * 返回运行任务详情文件。
     *
     * @param runId 运行任务标识
     * @return 运行任务文件
     */
    private File runFile(String runId) {
        return new File(runsDir, runId + ".json");
    }

    /**
     * 返回运行事件文件。
     *
     * @param runId 运行任务标识
     * @return 运行事件文件
     */
    private File runEventsFile(String runId) {
        return new File(runsDir, runId + ".events.jsonl");
    }

    /**
     * 返回会话事件文件。
     *
     * @param sessionKey 会话键
     * @return 会话事件文件
     */
    private File conversationEventsFile(String sessionKey) {
        File dir = FileUtil.mkdir(new File(conversationsDir, safeSessionKey(sessionKey)));
        return new File(dir, "events.jsonl");
    }

    /**
     * 返回会话元数据文件。
     *
     * @param sessionKey 会话键
     * @return 会话元数据文件
     */
    private File conversationMetaFile(String sessionKey) {
        File dir = FileUtil.mkdir(new File(conversationsDir, safeSessionKey(sessionKey)));
        return new File(dir, "meta.json");
    }

    /**
     * 返回最近回复目标文件。
     *
     * @return 最近回复目标文件
     */
    private File latestReplyTargetFile() {
        return new File(metaDir, "latest-reply-target.json");
    }

    /**
     * 将会话键转换为安全目录名。
     *
     * @param sessionKey 会话键
     * @return 安全目录名
     */
    private String safeSessionKey(String sessionKey) {
        return DigestUtil.sha1Hex(StrUtil.blankToDefault(sessionKey, "default"));
    }

    /**
     * 为目标文件返回一个复用锁对象。
     *
     * @param file 目标文件
     * @return 文件锁
     */
    private ReentrantLock lock(File file) {
        return pathLocks.computeIfAbsent(file.getAbsolutePath(), key -> new ReentrantLock());
    }
}
