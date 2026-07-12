package com.jimuqu.solon.claw.tui;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.command.CommandDescriptor;
import com.jimuqu.solon.claw.command.CommandRegistry;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.CheckpointRecord;
import com.jimuqu.solon.claw.core.model.CompressionOutcome;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.model.RunBusyDecision;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.model.SkillDescriptor;
import com.jimuqu.solon.claw.core.model.SkillView;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.core.service.ContextCompressionService;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.skillhub.model.HubInstallRecord;
import com.jimuqu.solon.claw.skillhub.model.SkillBrowseResult;
import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.AttachmentPathResolver;
import com.jimuqu.solon.claw.support.MessageAttachmentSupport;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.SecretValueGuard;
import com.jimuqu.solon.claw.support.ToolMessageStatusSupport;
import com.jimuqu.solon.claw.support.TuiRuntimeProtocolService;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import com.jimuqu.solon.claw.support.constants.SkillConstants;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import com.jimuqu.solon.claw.tool.runtime.BrowserRuntimeService;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.web.DashboardSkillsService;
import com.jimuqu.solon.claw.web.DomesticQrSetupService;
import com.jimuqu.solon.claw.web.WeixinQrSetupService;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 终端 UI JSON-RPC 响应构造服务，负责把 Java 后端能力映射为终端 UI 前端期望的数据形态。 */
public class TerminalUiRpcService {
    /** TUI RPC 降级路径日志，默认只在 debug 下输出，避免污染交互式终端。 */
    private static final Logger log = LoggerFactory.getLogger(TerminalUiRpcService.class);

    /** 远程图片字节上传上限，与交互基线的单图片限制保持一致。 */
    private static final int MAX_IMAGE_UPLOAD_BYTES = 25 * 1024 * 1024;

    /** image.attach 与 image.attach_bytes 接受的图片扩展名。 */
    private static final Set<String> IMAGE_EXTENSIONS =
            new HashSet<String>(
                    java.util.Arrays.asList(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp"));

    /** 应用运行配置，用于读取当前模型、工作区等展示信息。 */
    private final AppConfig appConfig;

    /** 会话仓储，用于让终端 UI 的会话生命周期落到 Java 持久化会话上。 */
    private final SessionRepository sessionRepository;

    /** 本地技能服务，用于驱动 TUI 技能列表、查看和重载动作。 */
    private final LocalSkillService localSkillService;

    /** Skills Hub 服务，用于驱动 TUI 社区技能搜索、浏览和安装动作。 */
    private final SkillHubService skillHubService;

    /** Checkpoint 服务，用于驱动 TUI rollback 列表、预览和恢复动作。 */
    private final CheckpointService checkpointService;

    /** Dashboard 技能服务，用于复用现有工具集定义和开关状态。 */
    private final DashboardSkillsService dashboardSkillsService;

    /** 偏好存储，用于持久化 TUI 工具开关动作。 */
    private final SqlitePreferenceStore preferenceStore;

    /** 浏览器运行时，用于把 TUI /browser 命令接入内置浏览器自动化。 */
    private final BrowserRuntimeService browserRuntimeService;

    /** 上下文压缩服务，用于驱动终端 UI /compact 操作。 */
    private final ContextCompressionService contextCompressionService;

    /** 终端本地附件解析服务，用于驱动终端 UI 图片/文件 attach 操作。 */
    private final AttachmentPathResolver attachmentResolver;

    /** 附件缓存服务，用于接收远程终端上传的 base64 图片字节。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 会话级待提交附件服务，用于保证每次 prompt.submit 只消费自己的附件快照。 */
    private final TerminalUiPendingAttachmentService pendingAttachmentService;

    /** 后台进程注册表，用于驱动终端 UI /stop 操作。 */
    private final ProcessRegistry processRegistry;

    /** MCP 运行时，用于驱动终端 UI /reload-mcp 操作。 */
    private final McpRuntimeService mcpRuntimeService;

    /** 配置刷新服务，用于驱动终端 UI /reload 操作。 */
    private final GatewayRuntimeRefreshService gatewayRuntimeRefreshService;

    /** 子代理委托服务，用于驱动终端 UI /agents 暂停、状态和中断操作。 */
    private final DelegationService delegationService;

    /** Agent run 控制服务，用于驱动 steer 与后台化等运行中控制操作。 */
    private final AgentRunControlService agentRunControlService;

    /** Agent run 仓储，用于从现有运行轨迹生成 TUI 可读取的 spawn tree 归档列表。 */
    private final AgentRunRepository agentRunRepository;

    /** 工作区配置服务，用于让 TUI 配置命令写入真实后端配置。 */
    private final RuntimeSettingsService runtimeSettingsService;

    /** 共享初始化协议服务，用于复用模型与国内渠道 setup 写入规则。 */
    private final TuiRuntimeProtocolService runtimeProtocolService;

    /** 全局设置仓储，用于保存仅属于终端 UI 的显示偏好。 */
    private final GlobalSettingRepository globalSettingRepository;

    /** 当前 JVM 内仍保持打开状态的 TUI 会话，用于驱动 session.active_list。 */
    private final List<String> liveTerminalSessionIds = new ArrayList<String>();

    /** 当前 TUI 连接创建的浏览器租约 ID，用于 status/disconnect 的轻量状态保持。 */
    private volatile String tuiBrowserSessionId;

    /** 创建终端 UI RPC 响应构造服务。 */
    public TerminalUiRpcService(AppConfig appConfig) {
        this(appConfig, null);
    }

    /** 创建带会话仓储的终端 UI RPC 响应构造服务。 */
    public TerminalUiRpcService(AppConfig appConfig, SessionRepository sessionRepository) {
        this(
                appConfig,
                sessionRepository,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** 创建完整后端服务注入的终端 UI RPC 响应构造服务。 */
    public TerminalUiRpcService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DashboardSkillsService dashboardSkillsService,
            SqlitePreferenceStore preferenceStore,
            BrowserRuntimeService browserRuntimeService,
            ContextCompressionService contextCompressionService,
            AttachmentPathResolver attachmentResolver,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            DelegationService delegationService,
            AgentRunControlService agentRunControlService,
            AgentRunRepository agentRunRepository,
            RuntimeSettingsService runtimeSettingsService,
            GlobalSettingRepository globalSettingRepository) {
        this(
                appConfig,
                sessionRepository,
                localSkillService,
                skillHubService,
                checkpointService,
                dashboardSkillsService,
                preferenceStore,
                browserRuntimeService,
                contextCompressionService,
                attachmentResolver,
                processRegistry,
                mcpRuntimeService,
                gatewayRuntimeRefreshService,
                delegationService,
                agentRunControlService,
                agentRunRepository,
                runtimeSettingsService,
                globalSettingRepository,
                null,
                null);
    }

    /** 创建完整后端服务注入且带渠道扫码能力的终端 UI RPC 响应构造服务。 */
    public TerminalUiRpcService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DashboardSkillsService dashboardSkillsService,
            SqlitePreferenceStore preferenceStore,
            BrowserRuntimeService browserRuntimeService,
            ContextCompressionService contextCompressionService,
            AttachmentPathResolver attachmentResolver,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            DelegationService delegationService,
            AgentRunControlService agentRunControlService,
            AgentRunRepository agentRunRepository,
            RuntimeSettingsService runtimeSettingsService,
            GlobalSettingRepository globalSettingRepository,
            WeixinQrSetupService weixinQrSetupService,
            DomesticQrSetupService domesticQrSetupService) {
        this(
                appConfig,
                sessionRepository,
                localSkillService,
                skillHubService,
                checkpointService,
                dashboardSkillsService,
                preferenceStore,
                browserRuntimeService,
                contextCompressionService,
                attachmentResolver,
                processRegistry,
                mcpRuntimeService,
                gatewayRuntimeRefreshService,
                delegationService,
                agentRunControlService,
                agentRunRepository,
                runtimeSettingsService,
                globalSettingRepository,
                weixinQrSetupService,
                domesticQrSetupService,
                appConfig == null ? null : new AttachmentCacheService(appConfig),
                new TerminalUiPendingAttachmentService());
    }

    /** 创建完整后端服务注入且带附件会话状态的终端 UI RPC 响应构造服务。 */
    public TerminalUiRpcService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DashboardSkillsService dashboardSkillsService,
            SqlitePreferenceStore preferenceStore,
            BrowserRuntimeService browserRuntimeService,
            ContextCompressionService contextCompressionService,
            AttachmentPathResolver attachmentResolver,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            DelegationService delegationService,
            AgentRunControlService agentRunControlService,
            AgentRunRepository agentRunRepository,
            RuntimeSettingsService runtimeSettingsService,
            GlobalSettingRepository globalSettingRepository,
            WeixinQrSetupService weixinQrSetupService,
            DomesticQrSetupService domesticQrSetupService,
            AttachmentCacheService attachmentCacheService,
            TerminalUiPendingAttachmentService pendingAttachmentService) {
        this.appConfig = appConfig;
        this.sessionRepository = sessionRepository;
        this.localSkillService = localSkillService;
        this.skillHubService = skillHubService;
        this.checkpointService = checkpointService;
        this.dashboardSkillsService = dashboardSkillsService;
        this.preferenceStore = preferenceStore;
        this.browserRuntimeService = browserRuntimeService;
        this.contextCompressionService = contextCompressionService;
        this.attachmentResolver = attachmentResolver;
        this.attachmentCacheService = attachmentCacheService;
        this.pendingAttachmentService =
                pendingAttachmentService == null
                        ? new TerminalUiPendingAttachmentService()
                        : pendingAttachmentService;
        this.processRegistry = processRegistry;
        this.mcpRuntimeService = mcpRuntimeService;
        this.gatewayRuntimeRefreshService = gatewayRuntimeRefreshService;
        this.delegationService = delegationService;
        this.agentRunControlService = agentRunControlService;
        this.agentRunRepository = agentRunRepository;
        this.runtimeSettingsService = runtimeSettingsService;
        this.runtimeProtocolService =
                new TuiRuntimeProtocolService(
                        appConfig, weixinQrSetupService, domesticQrSetupService);
        this.globalSettingRepository = globalSettingRepository;
    }

    /** 返回模型与工作区等当前会话展示信息。 */
    public Map<String, Object> sessionInfo() {
        return sessionInfo(null);
    }

    /** 返回指定会话的模型、工作区与累计用量等展示信息。 */
    private Map<String, Object> sessionInfo(SessionRecord session) {
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("cwd", new File(System.getProperty("user.dir")).getAbsolutePath());
        info.put("model", session == null ? currentModel() : model(session));
        info.put("skills", new LinkedHashMap<String, Object>());
        info.put("tools", new LinkedHashMap<String, Object>());
        info.put("usage", session == null ? usage() : usage(session));
        info.put("version", new AppVersionService(appConfig).currentVersion());
        return info;
    }

    /** 创建新会话响应，并把前端活动会话 ID 绑定到 Java 后端真实会话。 */
    public Map<String, Object> sessionCreate(String sourceKey) throws Exception {
        SessionRecord session = null;
        if (sessionRepository != null) {
            session = sessionRepository.bindNewSession(sourceKey);
            session.setSourceKey(terminalSourceKey(session.getSessionId()));
            sessionRepository.save(session);
            bindTerminalSource(session.getSessionId(), session.getSessionId());
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String sessionId =
                session == null
                        ? newSessionId()
                        : StrUtil.blankToDefault(session.getSessionId(), newSessionId());
        rememberLiveSession(sessionId);
        result.put("session_id", sessionId);
        result.put("info", sessionInfo(session));
        return result;
    }

    /** 激活指定会话并返回终端 UI 可重放的 transcript。 */
    public Map<String, Object> sessionActivate(String sessionId) throws Exception {
        Map<String, Object> result = sessionResume(sessionId);
        if (!result.containsKey("started_at")) {
            result.put("started_at", Long.valueOf(toEpochSeconds(startedAt(sessionId))));
        }
        return result;
    }

    /** 恢复会话响应，绑定 source key 并返回持久化 transcript。 */
    public Map<String, Object> sessionResume(String sessionId) throws Exception {
        SessionRecord session = findSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException(
                    "session not found: " + StrUtil.nullToEmpty(sessionId));
        }
        bindTerminalSource(session.getSessionId(), session.getSessionId());
        rememberLiveSession(session.getSessionId());
        String effectiveSessionId = session.getSessionId();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("session_id", effectiveSessionId);
        result.put("info", sessionInfo(session));
        result.put("messages", transcript(session));
        result.put("message_count", Integer.valueOf(messageCount(session)));
        result.put("resumed", effectiveSessionId);
        AgentRunRecord activeRun = latestActiveRun(effectiveSessionId);
        boolean running = activeRun != null;
        result.put("running", Boolean.valueOf(running));
        result.put("status", liveSessionStatus(activeRun));
        result.put(
                "started_at",
                Long.valueOf(
                        running
                                ? toEpochSeconds(activeRun.getStartedAt())
                                : toEpochSeconds(startedAt(effectiveSessionId))));
        result.put("inflight", inflightTurn(activeRun));
        return result;
    }

    /** 关闭一个 TUI live 会话；历史会话仍保留给 session.list 恢复。 */
    public Map<String, Object> sessionClose(String sessionId) {
        forgetLiveSession(sessionId);
        pendingAttachmentService.clear(sessionId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", Boolean.TRUE);
        result.put("closed", Boolean.valueOf(StrUtil.isNotBlank(sessionId)));
        return result;
    }

    /** 原子消费指定会话下一轮待提交附件，消费后即使运行失败也不会自动回填。 */
    public List<MessageAttachment> drainPendingAttachments(String sessionId) {
        return pendingAttachmentService.drain(sessionId);
    }

    /** 清理指定会话尚未提交的附件，用于 interrupt 和连接生命周期收尾。 */
    public void clearPendingAttachments(String sessionId) {
        pendingAttachmentService.clear(sessionId);
    }

    /** 返回最近一个可恢复会话，供终端 UI 自动恢复入口使用。 */
    public Map<String, Object> sessionMostRecent() throws Exception {
        List<SessionRecord> records = listRecentSessions(1);
        if (records.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        SessionRecord record = records.get(0);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("session_id", record.getSessionId());
        result.put("source", record.getSourceKey());
        result.put("started_at", Long.valueOf(record.getCreatedAt()));
        result.put("title", title(record));
        return result;
    }

    /** 返回当前 JVM 内仍打开的 TUI live 会话列表，供 session switcher 直接切换。 */
    public Map<String, Object> activeSessions(String currentSessionId) throws Exception {
        List<Map<String, Object>> sessions = new ArrayList<Map<String, Object>>();
        boolean currentIncluded = false;
        for (String liveSessionId : liveTerminalSessionIds()) {
            SessionRecord record = findSession(liveSessionId);
            if (record == null) {
                continue;
            }
            AgentRunRecord activeRun = latestActiveRun(record.getSessionId());
            boolean current = record.getSessionId().equals(currentSessionId);
            currentIncluded = currentIncluded || current;
            sessions.add(activeSessionItem(record, current, activeRun));
        }
        if (!currentIncluded) {
            SessionRecord current = findSession(currentSessionId);
            if (current != null) {
                sessions.add(
                        activeSessionItem(current, true, latestActiveRun(current.getSessionId())));
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessions", sessions);
        return result;
    }

    /** 返回可恢复历史会话列表，供终端 UI 的 session switcher 使用。 */
    public Map<String, Object> sessionList(int limit) throws Exception {
        List<Map<String, Object>> sessions = new ArrayList<Map<String, Object>>();
        for (SessionRecord record : listRecentSessions(Math.max(1, Math.min(limit, 200)))) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", record.getSessionId());
            item.put("title", title(record));
            item.put("preview", preview(record));
            item.put("source", record.getSourceKey());
            item.put("started_at", Long.valueOf(record.getCreatedAt()));
            item.put("message_count", Integer.valueOf(messageCount(record)));
            sessions.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessions", sessions);
        return result;
    }

    /** 删除指定持久化会话，匹配终端 UI 历史列表删除动作。 */
    public Map<String, Object> sessionDelete(String sessionId) throws Exception {
        if (sessionRepository != null && StrUtil.isNotBlank(sessionId)) {
            sessionRepository.delete(sessionId);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("deleted", StrUtil.nullToEmpty(sessionId));
        return result;
    }

    /** 返回终端 UI 命令目录结构，用于 slash 补全和命令归类展示。 */
    public Map<String, Object> commandsCatalog() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, String> canon = new LinkedHashMap<String, String>();
        Map<String, List<String>> sub = new LinkedHashMap<String, List<String>>();
        Map<String, List<List<String>>> grouped = new LinkedHashMap<String, List<List<String>>>();
        List<List<String>> pairs = new ArrayList<List<String>>();

        for (CommandDescriptor descriptor : CommandRegistry.all()) {
            List<String> pair = pair(descriptor.slashName(), descriptor.getDescription());
            pairs.add(pair);
            append(grouped, descriptor.getCategory(), pair);
            canon.put(descriptor.slashName(), descriptor.slashName());
            for (String alias : descriptor.getAliases()) {
                canon.put("/" + alias, descriptor.slashName());
            }
        }

        List<Map<String, Object>> categories = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, List<List<String>>> entry : grouped.entrySet()) {
            Map<String, Object> category = new LinkedHashMap<String, Object>();
            category.put("name", entry.getKey());
            category.put("pairs", entry.getValue());
            categories.add(category);
        }

        result.put("canon", canon);
        result.put("categories", categories);
        result.put("pairs", pairs);
        result.put("skill_count", Integer.valueOf(localSkillCount()));
        result.put("sub", sub);
        return result;
    }

    /** 根据当前输入生成 slash 命令补全候选。 */
    public Map<String, Object> completeSlash(String text) {
        String query = StrUtil.nullToEmpty(text).trim();
        if (query.startsWith("/")) {
            query = query.substring(1);
        }
        query = query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (CommandDescriptor descriptor : CommandRegistry.all()) {
            if (descriptor.getName().startsWith(query)) {
                appendLocalSlashCompletion(
                        items, query, descriptor.getName(), descriptor.getDescription());
            }
            for (String alias : descriptor.getAliases()) {
                appendLocalSlashCompletion(items, query, alias, descriptor.getDescription());
            }
        }
        appendLocalSlashCompletion(items, query, "doctor", "检查模型、渠道与工作区配置");
        appendLocalSlashCompletion(items, query, "setup", "配置模型、消息渠道与初始化设置");
        appendLocalSlashCompletion(items, query, "config", "查看或写入本地运行配置");
        appendLocalSlashCompletion(items, query, "gateway", "查看或配置国内消息渠道");
        appendLocalSlashCompletion(items, query, "auth", "查看模型 provider 认证状态");
        appendLocalSlashCompletion(items, query, "proxy", "查看代理配置提示");
        appendTuiLocalSlashCompletions(items, query);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("replace_from", Integer.valueOf(0));
        return result;
    }

    /** 根据当前光标词生成本地路径补全候选。 */
    public Map<String, Object> completePath(String word) {
        String raw = StrUtil.nullToEmpty(word);
        String unquoted = unquote(raw);
        File target = expandHome(unquoted);
        File directory = target.isDirectory() ? target : target.getParentFile();
        String prefix = target.isDirectory() ? "" : StrUtil.nullToEmpty(target.getName());
        if (directory == null) {
            directory = new File(System.getProperty("user.dir"));
        }

        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.getName().startsWith(prefix)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                String candidate = rebuildPath(raw, unquoted, directory, file);
                item.put("text", candidate + (file.isDirectory() ? "/" : ""));
                item.put("display", file.getName() + (file.isDirectory() ? "/" : ""));
                item.put("meta", file.isDirectory() ? "dir" : "file");
                items.add(item);
                if (items.size() >= 80) {
                    break;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("replace_from", Integer.valueOf(0));
        return result;
    }

    /** 返回完整配置的 TUI 相关默认值，避免前端启动时因缺少 display 配置而降级。 */
    public Map<String, Object> fullConfig() {
        Map<String, Object> runtimeFull = runtimeProtocolService.configGet("full");
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeConfig =
                runtimeFull.get("config") instanceof Map
                        ? (Map<String, Object>) runtimeFull.get("config")
                        : new LinkedHashMap<String, Object>();
        Map<String, Object> display = new LinkedHashMap<String, Object>();
        display.put(
                "bell_on_complete", Boolean.valueOf(booleanPreference("bell_on_complete", false)));
        display.put("busy_input_mode", currentBusyPolicy());
        display.put("details_mode", textPreference("details_mode", "collapsed"));
        display.put("inline_diffs", Boolean.valueOf(booleanPreference("inline_diffs", true)));
        display.put("mouse_tracking", textPreference("mouse_tracking", "all"));
        display.put("show_cost", Boolean.valueOf(booleanPreference("show_cost", false)));
        display.put("show_reasoning", Boolean.valueOf(showReasoning()));
        display.put("streaming", Boolean.valueOf(booleanPreference("streaming", true)));
        display.put(
                "tui_auto_resume_recent",
                Boolean.valueOf(booleanPreference("tui_auto_resume_recent", false)));
        display.put("tui_compact", Boolean.valueOf(booleanPreference("tui_compact", false)));
        display.put("tui_status_indicator", textPreference("indicator", "kaomoji"));
        display.put("tui_statusbar", textPreference("statusbar", "top"));

        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("display", display);
        config.put("model", runtimeConfig.get("model"));
        config.put("provider", runtimeConfig.get("provider"));
        config.put("workspace_config", runtimeConfig.get("workspace_config"));
        config.put("providers", runtimeConfig.get("providers"));
        config.put("paste_collapse_char_threshold", Integer.valueOf(2000));
        config.put("paste_collapse_threshold", Integer.valueOf(5));
        config.put("voice", new LinkedHashMap<String, Object>());

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("config", config);
        return result;
    }

    /** 返回当前会话用量基础结构，未绑定会话时只包含默认模型与零值统计。 */
    public Map<String, Object> usage() {
        Map<String, Object> usage = new LinkedHashMap<String, Object>();
        usage.put("calls", Integer.valueOf(0));
        usage.put("input", Integer.valueOf(0));
        usage.put("output", Integer.valueOf(0));
        usage.put("total", Integer.valueOf(0));
        usage.put("model", currentModel());
        usage.put("active_subagents", Integer.valueOf(activeSubagentItems().size()));
        return usage;
    }

    /** 将持久化会话累计用量转换为终端 UI 可识别的 usage 结构。 */
    private Map<String, Object> usage(SessionRecord session) {
        Map<String, Object> usage = usage();
        if (session == null) {
            return usage;
        }
        usage.put("input", Long.valueOf(Math.max(0L, session.getCumulativeInputTokens())));
        usage.put("output", Long.valueOf(Math.max(0L, session.getCumulativeOutputTokens())));
        usage.put("reasoning", Long.valueOf(Math.max(0L, session.getCumulativeReasoningTokens())));
        usage.put("cache_read", Long.valueOf(Math.max(0L, session.getCumulativeCacheReadTokens())));
        usage.put(
                "cache_write", Long.valueOf(Math.max(0L, session.getCumulativeCacheWriteTokens())));
        usage.put("total", Long.valueOf(Math.max(0L, session.getCumulativeTotalTokens())));
        usage.put("model", model(session));
        usage.put("calls", Long.valueOf(usageCallCount(session)));
        return usage;
    }

    /** 返回当前会话已落库的模型调用次数，仓储不可用时保持旧的消息存在性降级。 */
    private long usageCallCount(SessionRecord session) {
        if (session == null) {
            return 0L;
        }
        if (agentRunRepository != null && StrUtil.isNotBlank(session.getSessionId())) {
            try {
                return Math.max(
                        0L, agentRunRepository.countUsageRunsBySession(session.getSessionId()));
            } catch (Exception e) {
                // 仅记录异常类型与脱敏后的会话标识，避免原始 Throwable 堆栈携带敏感上下文进入日志
                log.debug(
                        "Failed to count TUI usage runs, fallback to message count: session={} error={}",
                        session.getSessionId(),
                        exceptionSummary(e));
            }
        }
        return messageCount(session) > 0 ? 1L : 0L;
    }

    /** 返回当前会话状态文本。 */
    public Map<String, Object> sessionStatus(String sessionId) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        SessionRecord session = findSession(sessionId);
        if (session == null) {
            result.put("output", "session: " + StrUtil.blankToDefault(sessionId, "default"));
            return result;
        }
        result.put(
                "output",
                "session="
                        + session.getSessionId()
                        + ", branch="
                        + StrUtil.blankToDefault(session.getBranchName(), "-")
                        + ", messages="
                        + messageCount(session)
                        + ", model="
                        + model(session)
                        + ", tokens="
                        + session.getCumulativeTotalTokens());
        return result;
    }

    /** 返回后端可用性的 setup 检查结果。 */
    public Map<String, Object> setupStatus() {
        return runtimeProtocolService.setupStatus();
    }

    /** 返回空的活动会话列表，保持终端 UI sessions overlay 的 RPC 契约。 */
    public Map<String, Object> emptySessions(String key) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(key, new ArrayList<Object>());
        return result;
    }

    /** 构造通用成功响应。 */
    public Map<String, Object> ok() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", Boolean.TRUE);
        return result;
    }

    /** 构造配置写入响应，当前先回显写入值并附带当前 session info。 */
    public Map<String, Object> configSet(String key, String value, String sessionId)
            throws Exception {
        if ("model".equals(StrUtil.nullToEmpty(key).trim())) {
            Map<String, Object> result = runtimeProtocolService.configSet(key, value, sessionId);
            result.put("info", sessionInfo(findSession(sessionId)));
            return result;
        }
        String stored = applyConfigSet(key, value, sessionId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("value", StrUtil.nullToEmpty(stored));
        result.put("info", sessionInfo());
        return result;
    }

    /** 返回单项配置值，保持终端 UI slash 命令读取配置时的轻量响应。 */
    public Map<String, Object> configValue(String key) {
        String normalized = StrUtil.blankToDefault(key, "");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if ("model".equals(normalized)) {
            result.putAll(runtimeProtocolService.configGet(normalized));
        } else if ("reasoning".equals(normalized)) {
            result.put("value", reasoningEffort());
            result.put("display", "show");
        } else if ("skin".equals(normalized)) {
            result.put("value", textPreference("skin", "default"));
        } else if ("indicator".equals(normalized)) {
            result.put("value", textPreference("indicator", "kaomoji"));
        } else if ("details_mode".equals(normalized)) {
            result.put("value", textPreference("details_mode", "collapsed"));
        } else if ("busy".equals(normalized)) {
            result.put("value", currentBusyPolicy());
        } else if ("fast".equals(normalized)) {
            result.put("value", textPreference("fast", "normal"));
        } else if ("verbose".equals(normalized)) {
            result.put("value", textPreference("verbose", "off"));
        } else {
            result.put("value", "");
        }
        result.put("home", workspaceHome());
        return result;
    }

    /** 返回配置修改时间占位，供前端定期同步配置时判断是否需要刷新。 */
    public Map<String, Object> configMtime() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("mtime", Long.valueOf(System.currentTimeMillis() / 1000L));
        return result;
    }

    /** 返回终端 UI 皮肤切换事件载荷，保持前端原生主题系统接管具体渲染。 */
    public Map<String, Object> skinPayload(String skin) {
        Map<String, Object> branding = new LinkedHashMap<String, Object>();
        branding.put("agent_name", "solonclaw Agent");

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", StrUtil.blankToDefault(skin, "default"));
        result.put("colors", new LinkedHashMap<String, Object>());
        result.put("branding", branding);
        return result;
    }

    /** 返回模型选择器需要的 provider/model 列表。 */
    public Map<String, Object> modelOptions() {
        return runtimeProtocolService.modelOptions("");
    }

    /** 保存模型提供方 API Key，并返回模型选择器需要的 provider 状态。 */
    public Map<String, Object> modelSaveKey(String slug, String apiKey) {
        return runtimeProtocolService.modelSaveKey(slug, apiKey, "");
    }

    /** 清空模型提供方 API Key，并返回断开状态。 */
    public Map<String, Object> modelDisconnect(String slug) {
        String providerSlug = StrUtil.blankToDefault(slug, providerName());
        boolean persisted = setRuntimeSecret("providers." + providerSlug + ".apiKey", "");
        AppConfig.ProviderConfig provider =
                appConfig == null ? null : appConfig.getProviders().get(providerSlug);
        if (provider != null) {
            provider.setApiKey("");
        }
        if (isCurrentProvider(providerSlug) && appConfig != null && appConfig.getLlm() != null) {
            appConfig.getLlm().setApiKey("");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("disconnected", Boolean.TRUE);
        result.put("provider", modelProviderItem(providerSlug, false));
        result.put("persisted", Boolean.valueOf(persisted));
        return result;
    }

    /** 返回独立终端 UI 需要展示的国内渠道 setup 清单。 */
    public Map<String, Object> channelOptions() {
        return runtimeProtocolService.channelOptions();
    }

    /** 返回单个国内渠道的配置状态。 */
    public Map<String, Object> channelStatus(String channel) {
        return runtimeProtocolService.channelStatus(channel);
    }

    /** 保存独立终端 UI 提交的国内渠道配置字段。 */
    public Map<String, Object> channelSave(
            String channel, Map<String, String> values, String sessionId) {
        return runtimeProtocolService.channelSave(channel, values, sessionId);
    }

    /** 启动国内渠道二维码绑定流程。 */
    public Map<String, Object> channelQrStart(String channel, String sessionId) {
        return runtimeProtocolService.channelQrStart(channel, sessionId);
    }

    /** 查询国内渠道二维码绑定状态。 */
    public Map<String, Object> channelQrGet(String channel, String ticket, String sessionId) {
        return runtimeProtocolService.channelQrGet(channel, ticket, sessionId);
    }

    /** 返回会话保存文件路径；当前 Java 后端会话已经实时持久化。 */
    public Map<String, Object> sessionSave(String sessionId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("file", workspaceHome() + "/state.db");
        result.put("session_id", StrUtil.nullToEmpty(sessionId));
        return result;
    }

    /** 强制压缩当前会话并返回终端 UI /compact 期望的 transcript 更新。 */
    public Map<String, Object> sessionCompress(String sessionId) throws Exception {
        return sessionCompress(sessionId, "");
    }

    /** 强制压缩当前会话，并把用户在 /compact 后输入的关注主题传递给摘要生成逻辑。 */
    public Map<String, Object> sessionCompress(String sessionId, String focusTopic)
            throws Exception {
        SessionRecord session = findSession(sessionId);
        Map<String, Object> busy = runningSessionMutation(sessionId, "compress session");
        if (busy != null) {
            return busy;
        }
        int beforeMessages = messageCount(session);
        int beforeTokens = session == null ? 0 : (int) session.getCumulativeTotalTokens();
        CompressionOutcome outcome = null;
        if (session != null && beforeMessages > 0 && contextCompressionService != null) {
            outcome =
                    contextCompressionService.compressNowWithOutcome(
                            session, "", StrUtil.nullToEmpty(focusTopic));
            if (outcome != null && outcome.getSession() != null && sessionRepository != null) {
                session = outcome.getSession();
                sessionRepository.save(session);
            }
        }
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("headline", compressionHeadline(outcome));
        summary.put("noop", Boolean.valueOf(outcome == null || outcome.isSkipped()));
        summary.put("note", compressionNote(outcome, session));
        summary.put("token_line", compressionTokenLine(outcome));

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("removed", Integer.valueOf(Math.max(0, beforeMessages - messageCount(session))));
        result.put("before_messages", Integer.valueOf(beforeMessages));
        result.put("after_messages", Integer.valueOf(messageCount(session)));
        result.put("before_tokens", Integer.valueOf(beforeTokens));
        result.put(
                "after_tokens",
                Long.valueOf(session == null ? 0L : session.getCumulativeTotalTokens()));
        result.put("messages", transcript(session));
        result.put("info", sessionInfo());
        result.put("usage", sessionUsage(sessionId));
        result.put("summary", summary);
        return result;
    }

    /** 将运行中追加指令注入当前会话的 active run。 */
    public Map<String, Object> sessionSteer(String sessionId, String text) throws Exception {
        if (agentRunControlService != null) {
            RunBusyDecision decision =
                    agentRunControlService.steerIncoming(
                            terminalSourceKey(sessionId),
                            sessionId,
                            terminalMessage(sessionId, text));
            return runBusyDecision(decision);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", "rejected");
        result.put("text", StrUtil.nullToEmpty(text));
        return result;
    }

    /** 将当前会话最近的 active run 标记为后台运行。 */
    public Map<String, Object> promptBackground(String sessionId, String text) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("task_id", "");
        AgentRunRecord record = latestActiveRun(sessionId);
        if (record == null || agentRunControlService == null) {
            return result;
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("prompt", StrUtil.nullToEmpty(text));
        Map<String, Object> control =
                agentRunControlService.controlRun(record.getRunId(), "background", payload);
        result.put("task_id", record.getRunId());
        result.put("status", control.get("status"));
        result.put("ok", control.get("ok"));
        return result;
    }

    /** 兼容旧调用入口；没有会话标识时只会返回未附加结果。 */
    public Map<String, Object> imageAttach(String path) {
        return imageAttach("", path);
    }

    /** 解析本地图片路径、写入指定会话的下一轮附件队列并返回 attach 结果。 */
    public Map<String, Object> imageAttach(String sessionId, String path) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        File file = new File(StrUtil.nullToEmpty(path));
        result.put("attached", Boolean.FALSE);
        result.put("message", "image not attached");
        if (attachmentResolver != null && StrUtil.isNotBlank(path)) {
            AttachmentPathResolver.ResolvedInput resolved = attachmentResolver.resolve(path);
            if (!resolved.getAttachments().isEmpty()) {
                MessageAttachment attachment = resolved.getAttachments().get(0);
                if (!"image".equals(StrUtil.nullToEmpty(attachment.getKind()))) {
                    result.put("message", "unsupported image: " + file.getName());
                    return result;
                }
                if (StrUtil.isBlank(sessionId)) {
                    result.put("message", "session_id is required");
                    return result;
                }
                int count = pendingAttachmentService.add(sessionId, attachment);
                return attachedImageResult(attachment, count, "");
            }
        }
        return result;
    }

    /** 从 base64 字节接收远程客户端图片，并写入指定会话的下一轮附件队列。 */
    public Map<String, Object> imageAttachBytes(
            String sessionId, String contentBase64, String filename, String extensionHint) {
        if (StrUtil.isBlank(sessionId)) {
            throw new IllegalArgumentException("session_id is required");
        }
        if (attachmentCacheService == null) {
            throw new IllegalStateException("attachment cache is not available");
        }
        byte[] bytes = decodeImageBytes(contentBase64);
        if (bytes.length == 0) {
            throw new IllegalArgumentException("image is empty");
        }
        if (bytes.length > MAX_IMAGE_UPLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "image too large ("
                            + bytes.length
                            + " bytes; cap is "
                            + (MAX_IMAGE_UPLOAD_BYTES / 1024 / 1024)
                            + " MB)");
        }

        String extension = imageExtension(bytes, filename, extensionHint);
        if (!IMAGE_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("unsupported image extension: " + extension);
        }
        String safeFilename = imageFilename(filename, extension);
        MessageAttachment attachment =
                attachmentCacheService.cacheBytes(
                        PlatformType.MEMORY,
                        "image",
                        safeFilename,
                        imageMimeType(extension),
                        false,
                        "",
                        bytes);
        int count = pendingAttachmentService.add(sessionId, attachment);
        Map<String, Object> result = attachedImageResult(attachment, count, "");
        result.put("bytes", Integer.valueOf(bytes.length));
        return result;
    }

    /** 兼容旧调用入口；没有会话标识时无法安全绑定剪贴板附件。 */
    public Map<String, Object> clipboardPaste() {
        return clipboardPaste("");
    }

    /** 返回服务端剪贴板不可用提示；远程客户端应使用 image.attach_bytes 上传本机图片。 */
    public Map<String, Object> clipboardPaste(String sessionId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("attached", Boolean.FALSE);
        if (StrUtil.isBlank(sessionId)) {
            result.put("message", "session_id is required");
            return result;
        }
        result.put(
                "message",
                "No image found in server clipboard; remote clients should use image.attach_bytes");
        return result;
    }

    /** 构造统一图片附加响应，路径只保留在后端附件模型中，不回显主机绝对路径。 */
    private Map<String, Object> attachedImageResult(
            MessageAttachment attachment, int count, String remainder) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String name = StrUtil.blankToDefault(attachment.getOriginalName(), "image");
        result.put("attached", Boolean.TRUE);
        result.put("name", name);
        result.put("count", Integer.valueOf(Math.max(1, count)));
        result.put("remainder", StrUtil.nullToEmpty(remainder));
        result.put("text", "[User attached image: " + name + "]");
        result.put(
                "token_estimate",
                Integer.valueOf(MessageAttachmentSupport.estimatedTokenCost(attachment)));
        result.put("mime_type", StrUtil.nullToEmpty(attachment.getMimeType()));
        result.put("kind", StrUtil.nullToEmpty(attachment.getKind()));
        result.put("size_bytes", Long.valueOf(attachment.getSizeBytes()));
        return result;
    }

    /** 严格解码纯 base64 或 data:image/...;base64 载荷。 */
    private byte[] decodeImageBytes(String contentBase64) {
        String raw = StrUtil.nullToEmpty(contentBase64).trim();
        if (StrUtil.isBlank(raw)) {
            throw new IllegalArgumentException("content_base64 required");
        }
        if (raw.startsWith("data:")) {
            int comma = raw.indexOf(',');
            String header = comma < 0 ? raw : raw.substring(0, comma);
            if (comma < 0 || !header.startsWith("data:image/") || !header.endsWith(";base64")) {
                throw new IllegalArgumentException("data is not valid base64");
            }
            raw = raw.substring(comma + 1);
        }
        String compact = raw.replaceAll("\\s+", "");
        if (compact.length() > ((MAX_IMAGE_UPLOAD_BYTES + 2L) / 3L) * 4L + 16L) {
            throw new IllegalArgumentException("image too large");
        }
        try {
            return Base64.getDecoder().decode(compact);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("data is not valid base64", e);
        }
    }

    /** 按文件名提示或魔数确定图片扩展名，未知格式按基线行为回退 PNG。 */
    private String imageExtension(byte[] bytes, String filename, String extensionHint) {
        String suffix = extensionOf(filename);
        if (StrUtil.isBlank(suffix)) {
            suffix = StrUtil.nullToEmpty(extensionHint).trim().toLowerCase(Locale.ROOT);
            if (StrUtil.isNotBlank(suffix) && !suffix.startsWith(".")) {
                suffix = "." + suffix;
            }
        }
        if (StrUtil.isNotBlank(suffix)) {
            return suffix;
        }
        if (startsWith(bytes, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
            return ".png";
        }
        if (startsWith(bytes, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return ".jpg";
        }
        if (startsWith(bytes, "GIF87a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                || startsWith(
                        bytes, "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            return ".gif";
        }
        if (bytes.length >= 12
                && startsWith(bytes, "RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                && "WEBP"
                        .equals(
                                new String(
                                        bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII))) {
            return ".webp";
        }
        if (startsWith(bytes, new byte[] {0x42, 0x4D})) {
            return ".bmp";
        }
        return ".png";
    }

    /** 读取文件名最后一个扩展名并转为小写。 */
    private String extensionOf(String filename) {
        String name = new File(StrUtil.nullToEmpty(filename)).getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** 构造去除客户端目录信息且与实际扩展名一致的缓存文件名。 */
    private String imageFilename(String filename, String extension) {
        String name = new File(StrUtil.nullToEmpty(filename)).getName();
        if (StrUtil.isBlank(name)) {
            return "upload" + extension;
        }
        return StrUtil.isBlank(extensionOf(name)) ? name + extension : name;
    }

    /** 将图片扩展名映射为标准 MIME。 */
    private String imageMimeType(String extension) {
        if (".jpg".equals(extension) || ".jpeg".equals(extension)) {
            return "image/jpeg";
        }
        if (".gif".equals(extension)) {
            return "image/gif";
        }
        if (".webp".equals(extension)) {
            return "image/webp";
        }
        if (".bmp".equals(extension)) {
            return "image/bmp";
        }
        return "image/png";
    }

    /** 判断字节数组是否具有指定前缀。 */
    private boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes == null || prefix == null || bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    /** 返回粘贴折叠结果，当前不改写输入文本。 */
    public Map<String, Object> pasteCollapse(String text) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("path", "");
        result.put("text", StrUtil.nullToEmpty(text));
        return result;
    }

    /** 停止当前后端注册表中的后台进程。 */
    public Map<String, Object> processStop() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(
                "killed", Integer.valueOf(processRegistry == null ? 0 : processRegistry.stopAll()));
        return result;
    }

    /** 重新发现已启用 MCP 服务的工具列表。 */
    public Map<String, Object> reloadMcp() {
        return reloadMcp(false, false);
    }

    /**
     * 按 TUI 明确确认参数重新发现 MCP 工具，避免误触发会改变下一轮工具 schema 的操作。
     *
     * @param confirmed 用户是否已在本次请求中明确确认。
     * @param rememberAlways 是否把确认要求持久关闭。
     * @return 返回 MCP 重载状态。
     */
    public Map<String, Object> reloadMcp(boolean confirmed, boolean rememberAlways) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (appConfig != null
                && appConfig.getApprovals() != null
                && appConfig.getApprovals().isMcpReloadConfirm()
                && !confirmed) {
            result.put("status", "confirm_required");
            result.put(
                    "message",
                    "/reload-mcp 会刷新下一轮工具 schema。请使用 /reload-mcp now 确认本次执行，或 /reload-mcp always 以后不再提示。");
            return result;
        }
        if (rememberAlways && appConfig != null && appConfig.getApprovals() != null) {
            appConfig.getApprovals().setMcpReloadConfirm(false);
            if (runtimeSettingsService != null) {
                runtimeSettingsService.setConfigValue("approvals.mcpReloadConfirm", "false");
            }
        }
        if (mcpRuntimeService == null) {
            result.put("status", "reloaded");
            result.put("message", "MCP runtime is not configured");
            return result;
        }
        try {
            List<McpRuntimeService.McpToolRefreshResult> refreshed =
                    mcpRuntimeService
                            .refreshAllEnabledLiveToolsAsync(false)
                            .get(30, TimeUnit.SECONDS);
            result.put("status", "reloaded");
            result.put("message", "MCP servers reloaded: " + refreshed.size());
            result.put("servers", Integer.valueOf(refreshed.size()));
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", StrUtil.blankToDefault(e.getMessage(), "MCP reload failed"));
        }
        return result;
    }

    /** 重新读取工作区配置文件。 */
    public Map<String, Object> reloadEnv() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (gatewayRuntimeRefreshService == null) {
            result.put("updated", Integer.valueOf(0));
            return result;
        }
        GatewayRuntimeRefreshService.RefreshResult refresh =
                gatewayRuntimeRefreshService.refreshNow();
        result.put("updated", Integer.valueOf(refresh != null && refresh.isSuccess() ? 1 : 0));
        if (refresh != null) {
            result.put("message", StrUtil.nullToEmpty(refresh.getMessage()));
            result.put("success", Boolean.valueOf(refresh.isSuccess()));
        }
        return result;
    }

    /** 返回浏览器连接状态，并把 TUI /browser 命令接入后端浏览器运行时。 */
    public Map<String, Object> browserManage(String action, String url) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<String> messages = new ArrayList<String>();
        String normalized = StrUtil.blankToDefault(action, "status").toLowerCase(Locale.ROOT);
        if (browserRuntimeService == null) {
            result.put("connected", Boolean.FALSE);
            result.put("url", "");
            result.put(
                    "messages",
                    java.util.Collections.singletonList("browser runtime is not enabled"));
            return result;
        }
        if ("connect".equals(normalized)) {
            BrowserRuntimeService.BrowserResult browserResult =
                    browserRuntimeService.create("tui-browser");
            if (browserResult != null && browserResult.isSuccess()) {
                tuiBrowserSessionId = browserResult.getSessionId();
                messages.add("browser session created: " + tuiBrowserSessionId);
                if (StrUtil.isNotBlank(url)) {
                    BrowserRuntimeService.BrowserResult navigate =
                            browserRuntimeService.navigate(tuiBrowserSessionId, url, null);
                    appendBrowserMessage(messages, navigate);
                }
            } else {
                appendBrowserMessage(messages, browserResult);
            }
        } else if ("disconnect".equals(normalized)) {
            if (StrUtil.isNotBlank(tuiBrowserSessionId)) {
                appendBrowserMessage(messages, browserRuntimeService.close(tuiBrowserSessionId));
                tuiBrowserSessionId = null;
            } else {
                messages.add("browser session is not connected");
            }
        } else if (!"status".equals(normalized)) {
            messages.add("unsupported browser action: " + normalized);
        }
        boolean connected = StrUtil.isNotBlank(tuiBrowserSessionId);
        result.put("connected", Boolean.valueOf(connected));
        result.put("url", connected ? tuiBrowserSessionId : "");
        result.put("messages", messages);
        return result;
    }

    /** 返回当前会话可用 checkpoint 列表。 */
    public Map<String, Object> rollbackList(String sessionId) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> checkpoints = new ArrayList<Map<String, Object>>();
        String sourceKey = sourceKeyForSession(sessionId);
        if (checkpointService != null && StrUtil.isNotBlank(sourceKey)) {
            for (CheckpointRecord record : checkpointService.listRecent(sourceKey, 50)) {
                checkpoints.add(toRollbackCheckpoint(record));
            }
        }
        result.put("enabled", Boolean.valueOf(checkpointService != null));
        result.put("checkpoints", checkpoints);
        return result;
    }

    /** 返回 checkpoint 预览文本，当前后端尚未提供文件级 diff API。 */
    public Map<String, Object> rollbackDiff(String checkpointId) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (checkpointService == null) {
            result.put("diff", "");
            result.put("rendered", "");
            result.put("stat", "checkpoints are not enabled");
            return result;
        }
        Map<String, Object> preview;
        try {
            preview = checkpointService.preview(checkpointId);
        } catch (Exception e) {
            result.put("diff", "");
            result.put("rendered", "");
            result.put("stat", StrUtil.blankToDefault(e.getMessage(), "checkpoint preview failed"));
            return result;
        }
        String rendered = renderCheckpointPreview(preview);
        result.put("diff", rendered);
        result.put("rendered", rendered);
        result.put("stat", checkpointStat(preview));
        return result;
    }

    /** 恢复指定 checkpoint；文件级恢复等待后端提供独立 API 后再接入。 */
    public Map<String, Object> rollbackRestore(
            String sessionId, String checkpointId, String filePath) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (checkpointService == null) {
            result.put("success", Boolean.FALSE);
            result.put("message", "checkpoints are not enabled");
            result.put("history_removed", Integer.valueOf(0));
            return result;
        }
        if (StrUtil.isNotBlank(filePath)) {
            result.put("success", Boolean.FALSE);
            result.put(
                    "message",
                    "file-level checkpoint restore is not available in this backend yet");
            result.put("history_removed", Integer.valueOf(0));
            return result;
        }
        Map<String, Object> busy = runningSessionMutation(sessionId, "restore checkpoint");
        if (busy != null) {
            return busy;
        }
        SessionRecord session = findSession(sessionId);
        int historyRemoved;
        try {
            historyRemoved =
                    checkpointService.rollbackSession(checkpointId, session, sessionRepository);
        } catch (Exception e) {
            result.put("success", Boolean.FALSE);
            result.put(
                    "message", StrUtil.blankToDefault(e.getMessage(), "checkpoint restore failed"));
            result.put("history_removed", Integer.valueOf(0));
            return result;
        }
        result.put("success", Boolean.TRUE);
        result.put("message", "restored checkpoint " + checkpointId);
        result.put("restored_to", checkpointId);
        result.put("history_removed", Integer.valueOf(historyRemoved));
        return result;
    }

    /** 返回技能管理响应，复用本地技能目录与 Skills Hub 服务。 */
    public Map<String, Object> skillsManage(String action, String query, int page)
            throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String normalized = StrUtil.blankToDefault(action, "list");
        if ("inspect".equals(normalized)) {
            result.put("info", skillInfo(query));
        } else if ("search".equals(normalized)) {
            result.put("results", skillSearch(query));
        } else if ("install".equals(normalized)) {
            HubInstallRecord record =
                    skillHubService == null ? null : skillHubService.install(query, null, true);
            result.put("installed", Boolean.valueOf(record != null));
            result.put("name", record == null ? StrUtil.nullToEmpty(query) : record.getName());
        } else if ("browse".equals(normalized)) {
            SkillBrowseResult browse =
                    skillHubService == null
                            ? new SkillBrowseResult()
                            : skillHubService.browse("all", Math.max(1, page), 20);
            result.put("items", skillMetaItems(browse.getItems()));
            result.put("page", Integer.valueOf(Math.max(1, browse.getPage())));
            result.put("total", Integer.valueOf(Math.max(0, browse.getTotal())));
            result.put(
                    "total_pages",
                    Integer.valueOf(
                            totalPages(browse.getTotal(), Math.max(1, browse.getPageSize()))));
        } else {
            result.put("skills", groupedLocalSkills());
        }
        return result;
    }

    /** 返回技能重载文本。 */
    public Map<String, Object> skillsReload() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("output", "skills reloaded");
        return result;
    }

    /** 返回工具配置响应，并持久化全局工具集开关。 */
    public Map<String, Object> toolsConfigure(String action, List<String> names) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<String> changed = new ArrayList<String>();
        List<String> unknown = new ArrayList<String>();
        Map<String, List<String>> toolsets = knownToolsets();
        boolean enable = !"disable".equalsIgnoreCase(StrUtil.nullToEmpty(action));
        if (preferenceStore != null && names != null) {
            for (String name : names) {
                String normalized = StrUtil.nullToEmpty(name).trim();
                List<String> tools = toolsets.get(normalized);
                if (tools == null) {
                    unknown.add(normalized);
                    continue;
                }
                for (String tool : tools) {
                    preferenceStore.setToolEnabledGlobal(tool, enable);
                }
                changed.add(normalized);
            }
        } else if (names != null) {
            unknown.addAll(names);
        }
        result.put("changed", changed);
        result.put("unknown", unknown);
        result.put("missing_servers", new ArrayList<Object>());
        result.put("enabled_toolsets", enabledToolsets());
        result.put("reset", Boolean.valueOf(!changed.isEmpty()));
        result.put("info", sessionInfo());
        result.put("action", StrUtil.nullToEmpty(action));
        return result;
    }

    /** 返回语音模式状态，当前不启用本地录音入口。 */
    public Map<String, Object> voiceToggle() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("enabled", Boolean.FALSE);
        result.put("tts", Boolean.FALSE);
        result.put("available", Boolean.FALSE);
        result.put("audio_available", Boolean.FALSE);
        result.put("stt_available", Boolean.FALSE);
        result.put("record_key", "ctrl+b");
        result.put("details", "voice mode is not enabled in this terminal backend");
        return result;
    }

    /** 返回语音录制状态，当前不启用本地录音入口。 */
    public Map<String, Object> voiceRecord() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", "stopped");
        result.put("text", "");
        return result;
    }

    /** 设置并返回子代理 spawn 暂停状态。 */
    public Map<String, Object> delegationPause(boolean paused) {
        if (delegationService != null) {
            delegationService.setSpawnPaused(paused);
        }
        Map<String, Object> result = delegationStatus();
        return result;
    }

    /** 返回子代理委托运行状态。 */
    public Map<String, Object> delegationStatus() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("active", activeSubagentItems());
        result.put(
                "paused",
                Boolean.valueOf(delegationService != null && delegationService.isSpawnPaused()));
        result.put(
                "max_concurrent_children",
                Integer.valueOf(
                        appConfig == null
                                ? 0
                                : Math.max(1, appConfig.getTask().getSubagentMaxConcurrency())));
        result.put(
                "max_spawn_depth",
                Integer.valueOf(
                        appConfig == null
                                ? 0
                                : Math.max(1, appConfig.getTask().getSubagentMaxDepth())));
        return result;
    }

    /** 返回子 Agent 中断状态。 */
    public Map<String, Object> subagentInterrupt(String subagentId) {
        boolean found =
                delegationService != null && delegationService.interruptSubagent(subagentId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("found", Boolean.valueOf(found));
        result.put("subagent_id", StrUtil.nullToEmpty(subagentId));
        return result;
    }

    /** 返回已落库的子代理运行树归档列表。 */
    public Map<String, Object> spawnTreeList(String sessionId, int limit) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        if (agentRunRepository != null && StrUtil.isNotBlank(sessionId)) {
            for (AgentRunRecord run :
                    agentRunRepository.listBySession(sessionId, Math.max(1, Math.min(limit, 50)))) {
                Map<String, Object> tree = spawnTreeEntry(run);
                if (tree != null) {
                    entries.add(tree);
                }
            }
        }
        result.put("entries", entries);
        return result;
    }

    /** 按 run 路径读取已落库的子代理运行树。 */
    public Map<String, Object> spawnTreeLoad(String path) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        AgentRunRecord run = runFromSpawnPath(path);
        result.put(
                "subagents",
                run == null ? new ArrayList<Object>() : subagentsForRun(run.getRunId()));
        if (run != null) {
            result.put("session_id", run.getSessionId());
            result.put("started_at", Long.valueOf(toEpochSeconds(run.getStartedAt())));
            result.put("finished_at", Long.valueOf(toEpochSeconds(run.getFinishedAt())));
            result.put("label", spawnTreeLabel(run));
        }
        return result;
    }

    /** 返回交互确认类 RPC 的通用成功响应。 */
    public Map<String, Object> acknowledge() {
        return ok();
    }

    /** 返回终端 UI session.usage 需要的当前会话用量结构。 */
    public Map<String, Object> sessionUsage(String sessionId) throws Exception {
        SessionRecord session = findSession(sessionId);
        return usage(session);
    }

    /** 更新或读取当前会话标题，保留终端 UI /title 操作的 RPC 契约。 */
    public Map<String, Object> sessionTitle(String sessionId, String title) throws Exception {
        SessionRecord session = findSession(sessionId);
        if (session != null && StrUtil.isNotBlank(title)) {
            session.setTitle(title.trim());
            session.setUpdatedAt(System.currentTimeMillis());
            sessionRepository.save(session);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("title", session == null ? StrUtil.nullToEmpty(title) : title(session));
        result.put("session_key", session == null ? sessionId : session.getSessionId());
        return result;
    }

    /** 创建当前会话分支并返回新会话 ID。 */
    public Map<String, Object> sessionBranch(String sessionId, String name) throws Exception {
        SessionRecord source = findSession(sessionId);
        Map<String, Object> busy = runningSessionMutation(sessionId, "create session branch");
        if (busy != null) {
            return busy;
        }
        if (sessionRepository == null || source == null) {
            return sessionCreate(
                    "MEMORY:terminal-ui:" + StrUtil.blankToDefault(sessionId, "terminal-ui"));
        }
        String branchName = StrUtil.blankToDefault(name, "branch-" + System.currentTimeMillis());
        SessionRecord branch =
                sessionRepository.cloneSession(
                        terminalSourceKey(source.getSessionId()),
                        source.getSessionId(),
                        branchName);
        branch.setSourceKey(terminalSourceKey(branch.getSessionId()));
        sessionRepository.save(branch);
        bindTerminalSource(branch.getSessionId(), branch.getSessionId());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("session_id", branch.getSessionId());
        result.put("title", title(branch));
        return result;
    }

    /** 运行中拒绝会改写会话或工作区历史的 TUI 直连操作。 */
    private Map<String, Object> runningSessionMutation(String sessionId, String action)
            throws Exception {
        String sourceKey = sourceKeyForSession(sessionId);
        if (agentRunControlService == null
                || StrUtil.isBlank(sourceKey)
                || !agentRunControlService.isRunning(sourceKey)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", Boolean.FALSE);
        result.put("status", "running");
        result.put("error", "session is running; cannot " + action);
        result.put("message", "stop the active run or wait for it to finish");
        return result;
    }

    /** 生成 TUI 压缩结果标题。 */
    private String compressionHeadline(CompressionOutcome outcome) {
        if (outcome == null) {
            return "nothing to compress";
        }
        if (outcome.isFailed()) {
            return "compression failed";
        }
        if (outcome.isCompressed()) {
            return "conversation compressed";
        }
        return "nothing to compress";
    }

    /** 生成 TUI 压缩结果说明。 */
    private String compressionNote(CompressionOutcome outcome, SessionRecord session) {
        if (outcome == null) {
            return contextCompressionService == null
                    ? "backend compression is not enabled for terminal UI yet"
                    : "session not found";
        }
        if (outcome.isFailed()) {
            return StrUtil.blankToDefault(outcome.getErrorMessage(), outcome.getWarning());
        }
        if (outcome.isCompressed()) {
            return trim(
                    StrUtil.nullToEmpty(session == null ? "" : session.getCompressedSummary()),
                    240);
        }
        return "message count is below compression threshold";
    }

    /** 生成 TUI 压缩 token 预算说明。 */
    private String compressionTokenLine(CompressionOutcome outcome) {
        if (outcome == null || outcome.getEstimatedTokens() <= 0) {
            return "";
        }
        return outcome.getEstimatedTokens() + " tokens / threshold " + outcome.getThresholdTokens();
    }

    /** 统计本地技能数量，失败时按空技能集处理。 */
    private int localSkillCount() {
        try {
            return localSkillService == null ? 0 : localSkillService.listSkills(null).size();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 将本地技能按分类组织为终端 UI Skills Hub overlay 期望的 Map。 */
    private Map<String, Object> groupedLocalSkills() throws Exception {
        Map<String, Object> grouped = new LinkedHashMap<String, Object>();
        if (localSkillService == null) {
            return grouped;
        }
        for (SkillDescriptor descriptor : localSkillService.listSkills(null)) {
            String category =
                    StrUtil.blankToDefault(
                            descriptor.getCategory(), SkillConstants.DEFAULT_CATEGORY);
            if (!grouped.containsKey(category)) {
                grouped.put(category, new ArrayList<String>());
            }
            @SuppressWarnings("unchecked")
            List<String> names = (List<String>) grouped.get(category);
            names.add(descriptor.canonicalName());
        }
        return grouped;
    }

    /** 返回本地或 Hub 技能详情。 */
    private Map<String, Object> skillInfo(String query) throws Exception {
        if (StrUtil.isBlank(query)) {
            return new LinkedHashMap<String, Object>();
        }
        if (localSkillService != null) {
            try {
                SkillView view = localSkillService.viewSkill(query, null);
                if (view != null && view.getDescriptor() != null) {
                    return localSkillInfo(view);
                }
            } catch (Exception e) {
                logRecoverableRpcFailure("local skill inspect", e);
                // 本地没有命中时继续尝试 Hub inspect。
            }
        }
        if (skillHubService != null) {
            SkillMeta meta = skillHubService.inspect(query);
            if (meta != null) {
                return skillMetaInfo(meta);
            }
        }
        return new LinkedHashMap<String, Object>();
    }

    /** 将本地技能视图转换为终端 UI 技能详情。 */
    private Map<String, Object> localSkillInfo(SkillView view) {
        SkillDescriptor descriptor = view.getDescriptor();
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("name", descriptor.canonicalName());
        info.put(
                "category",
                StrUtil.blankToDefault(descriptor.getCategory(), SkillConstants.DEFAULT_CATEGORY));
        info.put("description", StrUtil.nullToEmpty(descriptor.getDescription()));
        info.put("path", StrUtil.nullToEmpty(descriptor.getSkillDir()));
        return info;
    }

    /** 将 Hub 技能元数据转换为终端 UI 技能详情。 */
    private Map<String, Object> skillMetaInfo(SkillMeta meta) {
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("name", StrUtil.nullToEmpty(meta.getName()));
        info.put("category", StrUtil.blankToDefault(meta.getSource(), "hub"));
        info.put("description", StrUtil.nullToEmpty(meta.getDescription()));
        info.put("path", StrUtil.blankToDefault(meta.getPath(), meta.getIdentifier()));
        return info;
    }

    /** 执行 Hub 搜索并转换为终端 UI /skills search 输出形态。 */
    private List<Map<String, Object>> skillSearch(String query) throws Exception {
        if (skillHubService == null || StrUtil.isBlank(query)) {
            return new ArrayList<Map<String, Object>>();
        }
        SkillBrowseResult search = skillHubService.search(query, "all", 20);
        return skillMetaItems(search.getItems());
    }

    /** 将 Hub 元数据列表转换为终端 UI 表格项。 */
    private List<Map<String, Object>> skillMetaItems(List<SkillMeta> items) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (items == null) {
            return result;
        }
        for (SkillMeta meta : items) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", StrUtil.nullToEmpty(meta.getName()));
            item.put("description", StrUtil.nullToEmpty(meta.getDescription()));
            item.put("source", StrUtil.nullToEmpty(meta.getSource()));
            item.put("trust", StrUtil.nullToEmpty(meta.getTrustLevel()));
            item.put("identifier", StrUtil.nullToEmpty(meta.getIdentifier()));
            result.add(item);
        }
        return result;
    }

    /** 计算分页总页数。 */
    private int totalPages(int total, int pageSize) {
        if (total <= 0) {
            return 0;
        }
        return (total + Math.max(1, pageSize) - 1) / Math.max(1, pageSize);
    }

    /** 把浏览器运行结果追加为 TUI 可展示的短消息。 */
    private void appendBrowserMessage(
            List<String> messages, BrowserRuntimeService.BrowserResult browserResult) {
        if (browserResult == null) {
            messages.add("browser runtime returned no result");
            return;
        }
        if (browserResult.isSuccess()) {
            messages.add("browser " + StrUtil.blankToDefault(browserResult.getStatus(), "ok"));
            return;
        }
        BrowserRuntimeService.BrowserError error = browserResult.getError();
        messages.add(
                "browser error: "
                        + (error == null
                                ? "unknown"
                                : error.getCode() + " - " + error.getMessage()));
    }

    /** 应用 TUI config.set，并把可映射项写入真实后端配置。 */
    private String applyConfigSet(String key, String value, String sessionId) throws Exception {
        String normalized = StrUtil.nullToEmpty(key).trim();
        String raw = StrUtil.nullToEmpty(value).trim();
        if ("model".equals(normalized)) {
            if (StrUtil.isNotBlank(raw)) {
                setRuntimeConfig("model.default", raw);
            }
            return currentModel();
        }
        if ("reasoning".equals(normalized)) {
            return applyReasoning(raw, sessionId);
        }
        if ("busy".equals(normalized)) {
            String policy = normalizeBusyPolicy(raw);
            setRuntimeConfig("task.busyPolicy", policy);
            if (appConfig != null && appConfig.getTask() != null) {
                appConfig.getTask().setBusyPolicy(policy);
            }
            return policy;
        }
        if ("fast".equals(normalized)) {
            String mode = normalizeFastMode(raw);
            writePreference("fast", mode);
            return mode;
        }
        if ("verbose".equals(normalized)) {
            String mode = normalizeVerboseMode(raw);
            writePreference("verbose", mode);
            return mode;
        }
        if ("indicator".equals(normalized)) {
            String indicator = normalizeIndicator(raw);
            writePreference("indicator", indicator);
            return indicator;
        }
        if ("skin".equals(normalized)) {
            String skin = StrUtil.blankToDefault(raw, "default");
            writePreference("skin", skin);
            return skin;
        }
        if ("statusbar".equals(normalized)) {
            String statusbar = normalizeStatusbar(raw);
            writePreference("statusbar", statusbar);
            return statusbar;
        }
        if ("compact".equals(normalized)) {
            boolean enabled = normalizeBooleanToggle(raw, booleanPreference("tui_compact", false));
            writePreference("tui_compact", enabled ? "true" : "false");
            return enabled ? "1" : "0";
        }
        if ("mouse".equals(normalized)) {
            String tracking = normalizeMouseTracking(raw);
            writePreference("mouse_tracking", tracking);
            return tracking;
        }
        if ("display.details_mode".equals(normalized) || "details_mode".equals(normalized)) {
            String mode = normalizeDetailsMode(raw);
            writePreference("details_mode", mode);
            return mode;
        }
        if (normalized.startsWith("details_mode.")) {
            writePreference(normalized, raw);
            return raw;
        }
        if (isRuntimeConfigKey(normalized)) {
            Map<String, Object> result =
                    runtimeProtocolService.configSet(normalized, raw, sessionId);
            Object stored = result.get("value");
            return stored == null ? raw : String.valueOf(stored);
        }
        writePreference(normalized, raw);
        return raw;
    }

    /** 应用 reasoning 显示或推理强度设置。 */
    private String applyReasoning(String value, String sessionId) throws Exception {
        String normalized = StrUtil.blankToDefault(value, "show").trim().toLowerCase(Locale.ROOT);
        if ("show".equals(normalized) || "hide".equals(normalized)) {
            boolean visible = "show".equals(normalized);
            writePreference("show_reasoning", visible ? "true" : "false");
            if (runtimeSettingsService != null) {
                setRuntimeConfig("display.showReasoning", visible ? "true" : "false");
            }
            return normalized;
        }
        if (normalized.length() > 0) {
            setRuntimeConfig("llm.reasoningEffort", normalized);
            if (appConfig != null && appConfig.getLlm() != null) {
                appConfig.getLlm().setReasoningEffort(normalized);
            }
        }
        return reasoningEffort();
    }

    /** 写入工作区配置；配置服务不存在时直接更新内存配置的可识别项。 */
    private void setRuntimeConfig(String key, String value) {
        if (runtimeSettingsService != null) {
            runtimeSettingsService.setConfigValue(key, value);
            return;
        }
        if (appConfig == null) {
            return;
        }
        if ("model.default".equals(key)) {
            appConfig.getModel().setDefault(value);
        } else if ("llm.reasoningEffort".equals(key)) {
            appConfig.getLlm().setReasoningEffort(value);
        } else if ("task.busyPolicy".equals(key)) {
            appConfig.getTask().setBusyPolicy(value);
        } else if ("display.showReasoning".equals(key)) {
            appConfig.getDisplay().setShowReasoning(Boolean.parseBoolean(value));
        }
    }

    /** 写入运行时密钥配置；非默认 provider 尚未进入配置白名单时允许前端继续使用内存状态。 */
    private boolean setRuntimeSecret(String key, String value) {
        if (runtimeSettingsService == null) {
            return false;
        }
        try {
            runtimeSettingsService.setSecretValue(key, value);
            return true;
        } catch (IllegalStateException e) {
            if (StrUtil.containsIgnoreCase(e.getMessage(), "Unsupported workspace config item")) {
                return false;
            }
            throw e;
        }
    }

    /**
     * 判断配置键是否应写入 workspace/config.yml，而不是仅作为终端 UI 偏好保存。
     *
     * @param key 配置键。
     * @return runtime 配置键返回 true。
     */
    private boolean isRuntimeConfigKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).trim();
        return "fallbackProviders".equals(normalized)
                || normalized.startsWith("providers.")
                || normalized.startsWith("model.")
                || normalized.startsWith("solonclaw.")
                || normalized.startsWith("llm.")
                || normalized.startsWith("task.")
                || normalized.startsWith("gateway.")
                || normalized.startsWith("security.")
                || normalized.startsWith("approvals.")
                || normalized.startsWith("scheduler.")
                || normalized.startsWith("compression.")
                || normalized.startsWith("react.")
                || normalized.startsWith("skills.");
    }

    /** 判断指定 provider 是否为当前会话实际使用的 provider。 */
    private boolean isCurrentProvider(String providerSlug) {
        return StrUtil.equals(providerSlug, providerName());
    }

    /** 构造模型选择器 provider 项。 */
    private Map<String, Object> modelProviderItem(String slug, boolean authenticated) {
        String providerSlug = StrUtil.blankToDefault(slug, providerName());
        AppConfig.ProviderConfig configured =
                appConfig == null ? null : appConfig.getProviders().get(providerSlug);
        String model =
                configured == null
                        ? currentModel()
                        : StrUtil.blankToDefault(configured.getDefaultModel(), currentModel());
        boolean hasKey =
                authenticated
                        && (configured == null
                                ? runtimeSettingsService == null
                                : SecretValueGuard.hasUsableSecret(configured.getApiKey()));
        Map<String, Object> provider = new LinkedHashMap<String, Object>();
        provider.put("slug", providerSlug);
        provider.put(
                "name",
                configured == null
                        ? providerSlug
                        : StrUtil.blankToDefault(configured.getName(), providerSlug));
        provider.put("auth_type", "api_key");
        provider.put("key_env", providerSlug.toUpperCase(Locale.ROOT) + "_API_KEY");
        provider.put("authenticated", Boolean.valueOf(hasKey));
        provider.put("is_current", Boolean.valueOf(StrUtil.equals(providerSlug, providerName())));
        provider.put(
                "models",
                hasKey ? java.util.Collections.singletonList(model) : new ArrayList<Object>());
        provider.put("total_models", Integer.valueOf(hasKey ? 1 : 0));
        if (!hasKey) {
            provider.put("warning", "paste API key to activate");
        }
        return provider;
    }

    /** 返回当前 busy 策略，优先使用内存配置以反映最新生效状态。 */
    private String currentBusyPolicy() {
        if (appConfig != null && appConfig.getTask() != null) {
            return normalizeBusyPolicy(appConfig.getTask().getBusyPolicy());
        }
        return "queue";
    }

    /** 返回当前 reasoning 显示偏好。 */
    private boolean showReasoning() {
        return booleanPreference(
                "show_reasoning",
                appConfig == null
                        || appConfig.getDisplay() == null
                        || appConfig.getDisplay().isShowReasoning());
    }

    /** 读取 TUI 文本偏好。 */
    private String textPreference(String key, String fallback) {
        if (globalSettingRepository == null || StrUtil.isBlank(key)) {
            return fallback;
        }
        try {
            return StrUtil.blankToDefault(
                    globalSettingRepository.get(preferenceKey(key)), fallback);
        } catch (Exception e) {
            logRecoverableRpcFailure("text preference read", e);
            return fallback;
        }
    }

    /** 读取 TUI 布尔偏好。 */
    private boolean booleanPreference(String key, boolean fallback) {
        String value = textPreference(key, fallback ? "true" : "false");
        return "true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value);
    }

    /** 写入 TUI 偏好。 */
    private void writePreference(String key, String value) throws Exception {
        if (globalSettingRepository == null || StrUtil.isBlank(key)) {
            return;
        }
        globalSettingRepository.set(preferenceKey(key), StrUtil.nullToEmpty(value));
    }

    /** 构造 TUI 偏好键，隔离于普通业务设置。 */
    private String preferenceKey(String key) {
        return "terminal-ui." + key;
    }

    /** 归一化 TUI busy 输入策略。 */
    private String normalizeBusyPolicy(String value) {
        String normalized = StrUtil.blankToDefault(value, "queue").trim().toLowerCase(Locale.ROOT);
        if ("interrupt".equals(normalized)
                || "queue".equals(normalized)
                || "steer".equals(normalized)) {
            return normalized;
        }
        return "queue";
    }

    /** 归一化 fast 模式。 */
    private String normalizeFastMode(String value) {
        String normalized = StrUtil.blankToDefault(value, "toggle").trim().toLowerCase(Locale.ROOT);
        String current = textPreference("fast", "normal");
        if ("toggle".equals(normalized)) {
            return "fast".equals(current) ? "normal" : "fast";
        }
        if ("fast".equals(normalized) || "on".equals(normalized)) {
            return "fast";
        }
        return "normal";
    }

    /** 归一化 verbose 模式。 */
    private String normalizeVerboseMode(String value) {
        String normalized = StrUtil.blankToDefault(value, "cycle").trim().toLowerCase(Locale.ROOT);
        if ("cycle".equals(normalized)) {
            return "off".equals(textPreference("verbose", "off")) ? "on" : "off";
        }
        if ("on".equals(normalized) || "true".equals(normalized) || "1".equals(normalized)) {
            return "on";
        }
        return "off";
    }

    /** 归一化显式布尔开关；空值和 toggle 表示反转当前值。 */
    private boolean normalizeBooleanToggle(String value, boolean current) {
        String normalized = StrUtil.blankToDefault(value, "toggle").trim().toLowerCase(Locale.ROOT);
        if ("toggle".equals(normalized)) {
            return !current;
        }
        if ("on".equals(normalized)
                || "true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)) {
            return true;
        }
        if ("off".equals(normalized)
                || "false".equals(normalized)
                || "0".equals(normalized)
                || "no".equals(normalized)) {
            return false;
        }
        return current;
    }

    /** 归一化鼠标追踪预设，保持和终端 UI 前端支持的 wheel/buttons/all/off 一致。 */
    private String normalizeMouseTracking(String value) {
        String normalized = StrUtil.blankToDefault(value, "all").trim().toLowerCase(Locale.ROOT);
        if ("off".equals(normalized) || "false".equals(normalized) || "0".equals(normalized)) {
            return "off";
        }
        if ("wheel".equals(normalized) || "scroll".equals(normalized)) {
            return "wheel";
        }
        if ("buttons".equals(normalized) || "click".equals(normalized)) {
            return "buttons";
        }
        return "all";
    }

    /** 归一化状态指示器样式。 */
    private String normalizeIndicator(String value) {
        String normalized =
                StrUtil.blankToDefault(value, "kaomoji").trim().toLowerCase(Locale.ROOT);
        if ("kaomoji".equals(normalized)
                || "emoji".equals(normalized)
                || "unicode".equals(normalized)
                || "ascii".equals(normalized)) {
            return normalized;
        }
        return "kaomoji";
    }

    /** 归一化状态栏位置。 */
    private String normalizeStatusbar(String value) {
        String normalized = StrUtil.blankToDefault(value, "top").trim().toLowerCase(Locale.ROOT);
        if ("off".equals(normalized) || "bottom".equals(normalized) || "top".equals(normalized)) {
            return normalized;
        }
        if ("on".equals(normalized)) {
            return "top";
        }
        return "top";
    }

    /** 归一化详情展示模式。 */
    private String normalizeDetailsMode(String value) {
        String normalized =
                StrUtil.blankToDefault(value, "collapsed").trim().toLowerCase(Locale.ROOT);
        if ("hidden".equals(normalized)
                || "collapsed".equals(normalized)
                || "expanded".equals(normalized)) {
            return normalized;
        }
        return "collapsed";
    }

    /** 将运行中调度决策转换成终端 UI steer RPC 的响应形态。 */
    private Map<String, Object> runBusyDecision(RunBusyDecision decision) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (decision == null) {
            result.put("status", "rejected");
            return result;
        }
        result.put("policy", StrUtil.nullToEmpty(decision.getPolicy()));
        result.put("status", StrUtil.nullToEmpty(decision.getStatus()));
        result.put("message", StrUtil.nullToEmpty(decision.getMessage()));
        result.put("run_id", StrUtil.nullToEmpty(decision.getRunId()));
        result.put("queue_id", StrUtil.nullToEmpty(decision.getQueueId()));
        result.put("queued", Boolean.valueOf(decision.isQueued()));
        result.put("rejected", Boolean.valueOf(decision.isRejected()));
        result.put("should_run_now", Boolean.valueOf(decision.isShouldRunNow()));
        return result;
    }

    /** 构造 TUI 会话专用入站消息，用于运行中 steer 等控制入口。 */
    private GatewayMessage terminalMessage(String sessionId, String text) {
        GatewayMessage message =
                new GatewayMessage(
                        PlatformType.MEMORY, "terminal-ui", "local", StrUtil.nullToEmpty(text));
        message.setSourceKeyOverride(terminalSourceKey(sessionId));
        message.setChatName("Terminal UI");
        message.setUserName("local");
        return message;
    }

    /** 查找当前会话最近仍处于 active 状态的 run。 */
    private AgentRunRecord latestActiveRun(String sessionId) throws Exception {
        if (agentRunRepository == null) {
            return null;
        }
        String sourceKey = sourceKeyForSession(sessionId);
        if (StrUtil.isNotBlank(sourceKey)) {
            List<AgentRunRecord> active = agentRunRepository.listActiveBySource(sourceKey, 1);
            if (!active.isEmpty()) {
                return active.get(0);
            }
        }
        if (StrUtil.isNotBlank(sessionId)) {
            for (AgentRunRecord run : agentRunRepository.listBySession(sessionId, 5)) {
                if (isActiveRun(run)) {
                    return run;
                }
            }
        }
        return null;
    }

    /** 判断 run 是否仍处于可控制状态。 */
    private boolean isActiveRun(AgentRunRecord run) {
        if (run == null) {
            return false;
        }
        String status = StrUtil.nullToEmpty(run.getStatus()).toLowerCase(Locale.ROOT);
        return "queued".equals(status)
                || "running".equals(status)
                || "waiting_approval".equals(status)
                || "backgrounded".equals(status)
                || "paused".equals(status)
                || "interrupting".equals(status)
                || "recoverable".equals(status);
    }

    /** 将后端 run 状态压缩成终端 UI live session 状态枚举。 */
    private String liveSessionStatus(AgentRunRecord run) {
        if (run == null) {
            return "idle";
        }
        String status = StrUtil.nullToEmpty(run.getStatus()).toLowerCase(Locale.ROOT);
        if ("waiting_approval".equals(status) || "paused".equals(status)) {
            return "waiting";
        }
        if ("queued".equals(status)) {
            return "starting";
        }
        return "working";
    }

    /**
     * 将活跃运行的已持久化输入和可用输出预览映射为 TUI 恢复所需的未完成轮次。
     *
     * <p>运行中的用户输入尚未进入会话 transcript，因此必须从 run 记录恢复；等待审批等非流式阶段 不应伪造 assistant 增量。
     */
    private Map<String, Object> inflightTurn(AgentRunRecord run) {
        if (run == null) {
            return null;
        }
        Map<String, Object> inflight = new LinkedHashMap<String, Object>();
        inflight.put("user", StrUtil.nullToEmpty(run.getInputPreview()));
        inflight.put("assistant", StrUtil.nullToEmpty(run.getFinalReplyPreview()));
        String status = StrUtil.nullToEmpty(run.getStatus()).toLowerCase(Locale.ROOT);
        inflight.put(
                "streaming",
                Boolean.valueOf(
                        "queued".equals(status)
                                || "running".equals(status)
                                || "backgrounded".equals(status)
                                || "interrupting".equals(status)));
        return inflight;
    }

    /** 返回当前仍在运行的子代理列表。 */
    private List<Map<String, Object>> activeSubagentItems() {
        List<Map<String, Object>> active = new ArrayList<Map<String, Object>>();
        if (delegationService == null) {
            return active;
        }
        for (Map<String, Object> raw : delegationService.activeSubagents()) {
            active.add(toDelegationActiveItem(raw));
        }
        return active;
    }

    /** 将后端 active 子代理记录转换成 TUI 状态栏/面板字段。 */
    private Map<String, Object> toDelegationActiveItem(Map<String, Object> raw) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("subagent_id", stringValue(raw, "subagent_id"));
        item.put("parent_id", stringValue(raw, "parent_run_id"));
        item.put("status", normalizeSubagentStatus(stringValue(raw, "status")));
        item.put("depth", intValue(raw, "depth"));
        item.put("started_at", Long.valueOf(toEpochSeconds(longValue(raw, "started_at"))));
        item.put("goal", StrUtil.blankToDefault(stringValue(raw, "goal"), "subagent"));
        item.put("model", stringValue(raw, "model"));
        item.put("tool_count", Integer.valueOf(0));
        return item;
    }

    /** 将单个 run 转换成可加载的 spawn tree 归档项。 */
    private Map<String, Object> spawnTreeEntry(AgentRunRecord run) throws Exception {
        List<Map<String, Object>> subagents = subagentsForRun(run.getRunId());
        if (subagents.isEmpty()) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("path", "run:" + run.getRunId());
        item.put("session_id", StrUtil.nullToEmpty(run.getSessionId()));
        item.put("count", Integer.valueOf(subagents.size()));
        item.put("label", spawnTreeLabel(run));
        item.put("started_at", Long.valueOf(toEpochSeconds(run.getStartedAt())));
        item.put("finished_at", Long.valueOf(toEpochSeconds(run.getFinishedAt())));
        return item;
    }

    /** 按 TUI path 解析对应 run。 */
    private AgentRunRecord runFromSpawnPath(String path) throws Exception {
        if (agentRunRepository == null) {
            return null;
        }
        String raw = StrUtil.nullToEmpty(path).trim();
        String runId = raw.startsWith("run:") ? raw.substring("run:".length()) : raw;
        if (StrUtil.isBlank(runId)) {
            return null;
        }
        return agentRunRepository.findRun(runId);
    }

    /** 读取某个父 run 下所有子代理并转换成前端 replay 可识别的结构。 */
    private List<Map<String, Object>> subagentsForRun(String runId) throws Exception {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (agentRunRepository == null || StrUtil.isBlank(runId)) {
            return result;
        }
        for (com.jimuqu.solon.claw.core.model.SubagentRunRecord record :
                agentRunRepository.listSubagents(runId)) {
            result.add(toSubagentProgress(record));
        }
        return result;
    }

    /** 转换子代理持久化记录为 React Ink 前端内部进度结构。 */
    private Map<String, Object> toSubagentProgress(
            com.jimuqu.solon.claw.core.model.SubagentRunRecord record) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("id", StrUtil.nullToEmpty(record.getSubagentId()));
        item.put("goal", StrUtil.blankToDefault(record.getGoalPreview(), record.getName()));
        item.put("status", normalizeSubagentStatus(record.getStatus()));
        item.put("depth", Integer.valueOf(Math.max(0, record.getDepth())));
        item.put("index", Integer.valueOf(Math.max(0, record.getTaskIndex())));
        item.put("parentId", StrUtil.blankToDefault(record.getParentRunId(), null));
        item.put("taskCount", Integer.valueOf(1));
        item.put("toolCount", Integer.valueOf(0));
        item.put("tools", new ArrayList<Object>());
        item.put("notes", new ArrayList<Object>());
        item.put("thinking", new ArrayList<Object>());
        item.put("startedAt", Long.valueOf(record.getStartedAt()));
        item.put(
                "durationSeconds",
                Long.valueOf(durationSeconds(record.getStartedAt(), record.getFinishedAt())));
        item.put("outputTail", parseOutputTail(record.getOutputTailJson()));
        if (StrUtil.isNotBlank(record.getError())) {
            item.put("summary", record.getError());
        }
        return item;
    }

    /** 将后端子代理状态归一为前端枚举。 */
    private String normalizeSubagentStatus(String status) {
        String value = StrUtil.nullToEmpty(status).toLowerCase(Locale.ROOT);
        if ("success".equals(value)) {
            return "completed";
        }
        if ("interrupting".equals(value)) {
            return "interrupted";
        }
        if ("running".equals(value)
                || "queued".equals(value)
                || "completed".equals(value)
                || "failed".equals(value)
                || "error".equals(value)
                || "interrupted".equals(value)
                || "timeout".equals(value)) {
            return value;
        }
        return "running";
    }

    /** 从 output_tail JSON 中恢复 TUI 需要的输出片段。 */
    private List<Map<String, Object>> parseOutputTail(String json) {
        List<Map<String, Object>> output = new ArrayList<Map<String, Object>>();
        if (StrUtil.isBlank(json)) {
            return output;
        }
        try {
            Object data = ONode.ofJson(json).toData();
            if (!(data instanceof List)) {
                return output;
            }
            for (Object raw : (List<?>) data) {
                if (!(raw instanceof Map)) {
                    continue;
                }
                Map<?, ?> item = (Map<?, ?>) raw;
                Map<String, Object> line = new LinkedHashMap<String, Object>();
                line.put("preview", StrUtil.nullToEmpty(stringFrom(item.get("preview"))));
                line.put("tool", StrUtil.nullToEmpty(stringFrom(item.get("tool"))));
                line.put("isError", Boolean.valueOf(booleanFrom(item.get("is_error"))));
                output.add(line);
            }
        } catch (Exception e) {
            logRecoverableRpcFailure("output tail replay parse", e);
            return new ArrayList<Map<String, Object>>();
        }
        return output;
    }

    /** 将未知类型转换成字符串，避免 JSON 解析类型差异影响 TUI replay。 */
    private String stringFrom(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 将未知类型转换成布尔值，兼容 Snack4 对 JSON 基础类型的解析结果。 */
    private boolean booleanFrom(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /** 生成 spawn tree 列表标题。 */
    private String spawnTreeLabel(AgentRunRecord run) {
        return StrUtil.blankToDefault(
                run.getInputPreview(), "run " + StrUtil.nullToEmpty(run.getRunId()));
    }

    /** 毫秒时间戳转换为秒级时间戳。 */
    private long toEpochSeconds(long millis) {
        return millis <= 0L ? 0L : millis / 1000L;
    }

    /** 计算子代理持续时间，单位秒。 */
    private long durationSeconds(long startedAt, long finishedAt) {
        if (startedAt <= 0L) {
            return 0L;
        }
        long end = finishedAt > 0L ? finishedAt : System.currentTimeMillis();
        return Math.max(0L, (end - startedAt) / 1000L);
    }

    /** 从 map 中安全读取字符串字段。 */
    private String stringValue(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** 从 map 中安全读取整数字段。 */
    private Integer intValue(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number) {
            return Integer.valueOf(((Number) value).intValue());
        }
        return Integer.valueOf(0);
    }

    /** 从 map 中安全读取长整数字段。 */
    private long longValue(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    /** 根据 TUI 会话 ID 解析 checkpoint 使用的来源键。 */
    private String sourceKeyForSession(String sessionId) throws Exception {
        SessionRecord session = findSession(sessionId);
        if (session != null && StrUtil.isNotBlank(session.getSourceKey())) {
            return session.getSourceKey();
        }
        return StrUtil.isBlank(sessionId) ? "" : terminalSourceKey(sessionId);
    }

    /** 将 checkpoint 记录转换为终端 UI rollback 列表项。 */
    private Map<String, Object> toRollbackCheckpoint(CheckpointRecord record) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("hash", record.getCheckpointId());
        item.put("message", "session=" + StrUtil.blankToDefault(record.getSessionId(), "-"));
        item.put("timestamp", DateUtil.formatDateTime(new java.util.Date(record.getCreatedAt())));
        return item;
    }

    /** 将 checkpoint 预览转换为可分页文本。 */
    @SuppressWarnings("unchecked")
    private String renderCheckpointPreview(Map<String, Object> preview) {
        if (preview == null || preview.isEmpty()) {
            return "";
        }
        StringBuilder buffer = new StringBuilder();
        buffer.append("checkpoint: ")
                .append(StrUtil.nullToEmpty(String.valueOf(preview.get("checkpoint_id"))));
        appendPreviewFiles(buffer, "files", (List<Map<String, Object>>) preview.get("files"));
        appendPreviewFiles(buffer, "skipped", (List<Map<String, Object>>) preview.get("skipped"));
        return buffer.toString();
    }

    /** 向 checkpoint 预览文本中追加文件列表。 */
    private void appendPreviewFiles(
            StringBuilder buffer, String title, List<Map<String, Object>> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        buffer.append('\n').append(title).append(':');
        for (Map<String, Object> file : files) {
            buffer.append('\n')
                    .append("- ")
                    .append(StrUtil.nullToEmpty(String.valueOf(file.get("path"))));
            if (file.containsKey("reason")) {
                buffer.append(" · ")
                        .append(StrUtil.nullToEmpty(String.valueOf(file.get("reason"))));
            }
        }
    }

    /** 生成 checkpoint 预览统计行。 */
    private String checkpointStat(Map<String, Object> preview) {
        int files = listSize(preview == null ? null : preview.get("files"));
        int skipped = listSize(preview == null ? null : preview.get("skipped"));
        return files + " files, " + skipped + " skipped";
    }

    /** 返回对象列表长度，类型不匹配时返回 0。 */
    private int listSize(Object value) {
        return value instanceof List ? ((List<?>) value).size() : 0;
    }

    /** 返回 TUI 支持的工具集到后端工具名的映射。 */
    @SuppressWarnings("unchecked")
    private Map<String, List<String>> knownToolsets() {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        result.put(
                "web",
                java.util.Arrays.asList(
                        ToolNameConstants.WEBSEARCH,
                        ToolNameConstants.WEBFETCH,
                        ToolNameConstants.CODESEARCH,
                        ToolNameConstants.BROWSER));
        result.put(
                "terminal",
                java.util.Arrays.asList(
                        ToolNameConstants.EXECUTE_SHELL,
                        ToolNameConstants.TERMINAL,
                        ToolNameConstants.PROCESS));
        result.put(
                "file",
                java.util.Arrays.asList(
                        ToolNameConstants.FILE_READ,
                        ToolNameConstants.FILE_WRITE,
                        ToolNameConstants.READ_FILE,
                        ToolNameConstants.WRITE_FILE,
                        ToolNameConstants.SEARCH_FILES,
                        ToolNameConstants.FILE_LIST,
                        ToolNameConstants.FILE_DELETE,
                        ToolNameConstants.PATCH));
        if (dashboardSkillsService != null) {
            try {
                for (Map<String, Object> item : dashboardSkillsService.getToolsets()) {
                    Object name = item.get("name");
                    Object tools = item.get("tools");
                    if (name != null && tools instanceof List) {
                        result.put(
                                String.valueOf(name), new ArrayList<String>((List<String>) tools));
                    }
                }
            } catch (Exception e) {
                logRecoverableRpcFailure("dashboard toolsets read", e);
                return result;
            }
        }
        return result;
    }

    /** 返回当前全局启用的工具集名。 */
    private List<String> enabledToolsets() throws Exception {
        List<String> enabled = new ArrayList<String>();
        if (preferenceStore == null) {
            return enabled;
        }
        for (Map.Entry<String, List<String>> entry : knownToolsets().entrySet()) {
            boolean allEnabled = true;
            for (String tool : entry.getValue()) {
                allEnabled = allEnabled && preferenceStore.isToolEnabledGlobal(tool);
            }
            if (allEnabled) {
                enabled.add(entry.getKey());
            }
        }
        return enabled;
    }

    /** 读取当前全局默认模型名称。 */
    private String currentModel() {
        Map<String, Object> config = runtimeProtocolService.configGet("model");
        Object runtimeModel = config.get("value");
        if (runtimeModel != null && StrUtil.isNotBlank(String.valueOf(runtimeModel))) {
            return String.valueOf(runtimeModel).trim();
        }
        if (appConfig != null
                && appConfig.getModel() != null
                && StrUtil.isNotBlank(appConfig.getModel().getDefault())) {
            return appConfig.getModel().getDefault().trim();
        }
        if (appConfig != null
                && appConfig.getLlm() != null
                && StrUtil.isNotBlank(appConfig.getLlm().getModel())) {
            return appConfig.getLlm().getModel().trim();
        }
        return "default";
    }

    /** 读取当前模型提供方名称。 */
    private String providerName() {
        Map<String, Object> config = runtimeProtocolService.configGet("model");
        Object runtimeProvider = config.get("provider");
        if (runtimeProvider != null && StrUtil.isNotBlank(String.valueOf(runtimeProvider))) {
            return String.valueOf(runtimeProvider).trim();
        }
        if (appConfig != null
                && appConfig.getLlm() != null
                && StrUtil.isNotBlank(appConfig.getLlm().getProvider())) {
            return appConfig.getLlm().getProvider().trim();
        }
        return "openai";
    }

    /**
     * 补充只在 React TUI 前端执行的本地命令与别名，保证 Tab 补全不会漏掉可执行命令。
     *
     * @param items 当前补全候选。
     * @param query 用户输入的命令前缀，不含斜杠。
     */
    private void appendTuiLocalSlashCompletions(List<Map<String, Object>> items, String query) {
        appendLocalSlashCompletion(items, query, "help", "列出命令与快捷键");
        appendLocalSlashCompletion(items, query, "commands", "列出命令与快捷键");
        appendLocalSlashCompletion(items, query, "quit", "退出 TUI");
        appendLocalSlashCompletion(items, query, "exit", "退出 TUI");
        appendLocalSlashCompletion(items, query, "update", "退出并更新 solonclaw");
        appendLocalSlashCompletion(items, query, "mouse", "设置终端鼠标跟踪模式");
        appendLocalSlashCompletion(items, query, "scroll", "设置终端鼠标跟踪模式");
        appendLocalSlashCompletion(items, query, "clear", "清空当前会话");
        appendLocalSlashCompletion(items, query, "new", "开始新会话");
        appendLocalSlashCompletion(items, query, "reset", "开始新会话");
        appendLocalSlashCompletion(items, query, "redraw", "强制重绘终端界面");
        appendLocalSlashCompletion(items, query, "status", "显示当前会话状态");
        appendLocalSlashCompletion(items, query, "title", "查看或设置当前会话标题");
        appendLocalSlashCompletion(items, query, "density", "切换紧凑显示密度");
        appendLocalSlashCompletion(items, query, "dense", "切换紧凑显示密度");
        appendLocalSlashCompletion(items, query, "details", "控制 Agent 细节显示");
        appendLocalSlashCompletion(items, query, "detail", "控制 Agent 细节显示");
        appendLocalSlashCompletion(items, query, "fortune", "显示本地提示语");
        appendLocalSlashCompletion(items, query, "copy", "复制选中内容或助手消息");
        appendLocalSlashCompletion(items, query, "paste", "粘贴剪贴板内容");
        appendLocalSlashCompletion(items, query, "terminal-setup", "配置 IDE 终端快捷键");
        appendLocalSlashCompletion(items, query, "logs", "查看网关日志");
        appendLocalSlashCompletion(items, query, "history", "查看当前 TUI 转录内容");
        appendLocalSlashCompletion(items, query, "save", "保存当前转录内容");
        appendLocalSlashCompletion(items, query, "statusbar", "设置状态栏位置");
        appendLocalSlashCompletion(items, query, "sb", "设置状态栏位置");
        appendLocalSlashCompletion(items, query, "queue", "查看或追加排队消息");
        appendLocalSlashCompletion(items, query, "q", "查看或追加排队消息");
        appendLocalSlashCompletion(items, query, "steer", "在下一次工具调用后插入消息");
        appendLocalSlashCompletion(items, query, "undo", "撤销上一轮对话");
        appendLocalSlashCompletion(items, query, "retry", "重试上一条用户消息");
        appendLocalSlashCompletion(items, query, "background", "启动后台提示任务");
        appendLocalSlashCompletion(items, query, "bg", "启动后台提示任务");
        appendLocalSlashCompletion(items, query, "btw", "启动后台提示任务");
        appendLocalSlashCompletion(items, query, "model", "切换或查看模型");
        appendLocalSlashCompletion(items, query, "sessions", "浏览或切换会话");
        appendLocalSlashCompletion(items, query, "switch", "浏览或切换会话");
        appendLocalSlashCompletion(items, query, "session", "浏览或切换会话");
        appendLocalSlashCompletion(items, query, "resume", "恢复会话");
        appendLocalSlashCompletion(items, query, "image", "附加图片");
        appendLocalSlashCompletion(items, query, "personality", "切换当前会话人格");
        appendLocalSlashCompletion(items, query, "compress", "压缩当前上下文");
        appendLocalSlashCompletion(items, query, "compact", "压缩当前上下文");
        appendLocalSlashCompletion(items, query, "branch", "分支当前会话");
        appendLocalSlashCompletion(items, query, "fork", "分支当前会话");
        appendLocalSlashCompletion(items, query, "voice", "查看或切换语音能力");
        appendLocalSlashCompletion(items, query, "skin", "切换界面皮肤");
        appendLocalSlashCompletion(items, query, "indicator", "切换忙碌指示器");
        appendLocalSlashCompletion(items, query, "yolo", "切换会话级跳过危险命令审批");
        appendLocalSlashCompletion(items, query, "reasoning", "查看或设置推理展示");
        appendLocalSlashCompletion(items, query, "fast", "查看或切换快速模式");
        appendLocalSlashCompletion(items, query, "busy", "设置忙碌输入模式");
        appendLocalSlashCompletion(items, query, "verbose", "切换工具输出详细模式");
        appendLocalSlashCompletion(items, query, "usage", "显示当前会话用量");
        appendLocalSlashCompletion(items, query, "stop", "停止后台进程");
        appendLocalSlashCompletion(items, query, "reload-mcp", "刷新 MCP 服务器");
        appendLocalSlashCompletion(items, query, "reload_mcp", "刷新 MCP 服务器");
        appendLocalSlashCompletion(items, query, "reload", "重新读取本地环境变量");
        appendLocalSlashCompletion(items, query, "browser", "管理浏览器 CDP 连接");
        appendLocalSlashCompletion(items, query, "rollback", "查看或恢复检查点");
        appendLocalSlashCompletion(items, query, "tasks", "打开子代理任务面板");
        appendLocalSlashCompletion(items, query, "replay", "回放已完成的子代理树");
        appendLocalSlashCompletion(items, query, "replay-diff", "对比两个子代理树");
        appendLocalSlashCompletion(items, query, "reload-skills", "重新扫描技能");
        appendLocalSlashCompletion(items, query, "reload_skills", "重新扫描技能");
        appendLocalSlashCompletion(items, query, "skills", "浏览、检查或安装技能");
        appendLocalSlashCompletion(items, query, "tools", "启用或禁用工具");
        appendLocalSlashCompletion(items, query, "heapdump", "写入 V8 堆快照");
        appendLocalSlashCompletion(items, query, "mem", "显示 TUI 内存指标");
    }

    /**
     * 合并本地命令到 slash 补全候选，避免复制来的前端只能补全后端命令注册表。
     *
     * @param items 当前补全候选。
     * @param query 用户输入的命令前缀，不含斜杠。
     * @param command 命令名称。
     * @param description 用户可见描述。
     */
    private void appendLocalSlashCompletion(
            List<Map<String, Object>> items, String query, String command, String description) {
        if (!command.startsWith(query)) {
            return;
        }
        String text = "/" + command;
        for (Map<String, Object> item : items) {
            if (text.equals(item.get("text"))) {
                return;
            }
        }
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("text", text);
        item.put("display", text);
        item.put("meta", description);
        items.add(item);
    }

    /** 读取当前推理强度配置。 */
    private String reasoningEffort() {
        if (appConfig != null
                && appConfig.getLlm() != null
                && StrUtil.isNotBlank(appConfig.getLlm().getReasoningEffort())) {
            return appConfig.getLlm().getReasoningEffort().trim();
        }
        return "medium";
    }

    /** 返回工作区目录，用于配置与保存提示。 */
    private String workspaceHome() {
        if (appConfig != null
                && appConfig.getRuntime() != null
                && StrUtil.isNotBlank(appConfig.getRuntime().getHome())) {
            return appConfig.getRuntime().getHome().trim();
        }
        return new File(RuntimePathConstants.WORKSPACE_HOME).getAbsolutePath();
    }

    /** 生成终端 UI 默认会话编号。 */
    private String newSessionId() {
        return "solonclaw-" + Long.toString(System.currentTimeMillis(), 36);
    }

    /** 将终端 UI 活动会话 ID 映射到 Java 本地终端 source key。 */
    private void bindTerminalSource(String sessionId, String boundSessionId) throws Exception {
        if (sessionRepository == null
                || StrUtil.isBlank(sessionId)
                || StrUtil.isBlank(boundSessionId)) {
            return;
        }
        sessionRepository.bindSource(terminalSourceKey(sessionId), boundSessionId);
    }

    /** 终端 UI 会话来源键前缀，必须与 CliRuntime 在 TUI 场景下使用的前缀保持一致。 */
    public static final String TERMINAL_SOURCE_KEY_PREFIX = "MEMORY:terminal-ui:";

    /** 构造本地 TUI 会话专用 source key，保持前端会话 ID 与后端绑定关系稳定。 */
    private String terminalSourceKey(String sessionId) {
        return TERMINAL_SOURCE_KEY_PREFIX + StrUtil.blankToDefault(sessionId, "terminal-ui");
    }

    /** 记录一个仍在当前 JVM 内打开的 TUI 会话。 */
    private void rememberLiveSession(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return;
        }
        synchronized (liveTerminalSessionIds) {
            if (!liveTerminalSessionIds.contains(sessionId)) {
                liveTerminalSessionIds.add(sessionId);
            }
        }
    }

    /** 从当前 JVM live 列表移除一个 TUI 会话。 */
    private void forgetLiveSession(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return;
        }
        synchronized (liveTerminalSessionIds) {
            liveTerminalSessionIds.remove(sessionId);
        }
    }

    /** 读取 live 会话快照，避免迭代期间被 WebSocket 其它线程修改。 */
    private List<String> liveTerminalSessionIds() {
        synchronized (liveTerminalSessionIds) {
            return new ArrayList<String>(liveTerminalSessionIds);
        }
    }

    /** 转换当前活动会话为终端 UI session.active_list 项，并附带真实运行状态。 */
    private Map<String, Object> activeSessionItem(
            SessionRecord record, boolean current, AgentRunRecord activeRun) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("id", record.getSessionId());
        item.put("session_key", record.getSessionId());
        item.put("title", title(record));
        item.put("preview", preview(record));
        item.put("model", model(record));
        item.put("message_count", Integer.valueOf(messageCount(record)));
        item.put("started_at", Long.valueOf(record.getCreatedAt()));
        item.put("last_active", Long.valueOf(record.getUpdatedAt()));
        item.put("status", liveSessionStatus(activeRun));
        item.put("current", Boolean.valueOf(current));
        return item;
    }

    /** 按 ID 查询会话，空 ID 时返回空。 */
    private SessionRecord findSession(String sessionId) throws Exception {
        if (sessionRepository == null || StrUtil.isBlank(sessionId)) {
            return null;
        }
        return sessionRepository.findById(sessionId);
    }

    /** 读取最近会话并过滤异常空记录。 */
    private List<SessionRecord> listRecentSessions(int limit) throws Exception {
        List<SessionRecord> result = new ArrayList<SessionRecord>();
        if (sessionRepository == null) {
            return result;
        }
        List<SessionRecord> records = sessionRepository.listRecent(Math.max(1, limit));
        if (records == null) {
            return result;
        }
        for (SessionRecord record : records) {
            if (record != null && StrUtil.isNotBlank(record.getSessionId())) {
                result.add(record);
            }
        }
        return result;
    }

    /** 返回会话创建时间，查不到时返回当前时间。 */
    private long startedAt(String sessionId) throws Exception {
        SessionRecord session = findSession(sessionId);
        return session == null ? System.currentTimeMillis() : session.getCreatedAt();
    }

    /** 转换持久化消息为终端 UI transcript 结构。 */
    private List<Map<String, Object>> transcript(SessionRecord session) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (session == null || StrUtil.isBlank(session.getNdjson())) {
            return result;
        }
        try {
            for (ChatMessage message : MessageSupport.loadMessages(session.getNdjson())) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("role", role(message));
                item.put("text", messageText(message));
                if (message instanceof ToolMessage) {
                    ToolMessage toolMessage = (ToolMessage) message;
                    String status = ToolMessageStatusSupport.statusOf(toolMessage);
                    String preview = toolResultPreview(toolMessage, status);
                    item.put("name", StrUtil.nullToEmpty(toolMessage.getName()));
                    item.put("status", status);
                    item.put("preview", preview);
                    if (ToolMessageStatusSupport.STATUS_ERROR.equals(status)) {
                        item.put("error", preview);
                    }
                }
                result.add(item);
            }
        } catch (IOException e) {
            logRecoverableRpcFailure("session transcript parse", e);
            return new ArrayList<Map<String, Object>>();
        }
        return result;
    }

    /** 统计会话消息数量，解析失败时返回 0。 */
    private int messageCount(SessionRecord session) {
        if (session == null || StrUtil.isBlank(session.getNdjson())) {
            return 0;
        }
        try {
            return MessageSupport.countMessages(session.getNdjson());
        } catch (IOException e) {
            return 0;
        }
    }

    /** 生成会话列表标题。 */
    private String title(SessionRecord record) {
        return StrUtil.blankToDefault(record.getTitle(), "(未命名会话)");
    }

    /** 生成会话列表预览文本。 */
    private String preview(SessionRecord record) {
        if (record == null) {
            return "";
        }
        try {
            String lastUser = MessageSupport.getLastUserMessage(record.getNdjson());
            return trim(StrUtil.blankToDefault(lastUser, record.getCompressedSummary()), 160);
        } catch (IOException e) {
            return "";
        }
    }

    /** 返回会话实际模型或当前默认模型。 */
    private String model(SessionRecord record) {
        if (record == null) {
            return currentModel();
        }
        return StrUtil.blankToDefault(
                record.getLastResolvedModel(),
                StrUtil.blankToDefault(record.getModelOverride(), currentModel()));
    }

    /** 转换 Solon AI 消息角色为终端 UI transcript 角色。 */
    private String role(ChatMessage message) {
        ChatRole role = message.getRole();
        return role == null ? "system" : role.name().toLowerCase(Locale.ROOT);
    }

    /** 提取可显示消息文本，assistant 消息优先展示最终内容。 */
    private String messageText(ChatMessage message) {
        if (message instanceof AssistantMessage) {
            return StrUtil.nullToEmpty(((AssistantMessage) message).getResultContent());
        }
        return StrUtil.nullToEmpty(message.getContent());
    }

    /**
     * 提取工具结果的紧凑展示文本；统一 envelope 优先展示 error 或 preview，旧的纯文本结果保持可见。
     *
     * @param message 已持久化的工具消息。
     * @param status 工具终态。
     * @return 适合 TUI 工具轨迹展示的结果预览。
     */
    private String toolResultPreview(ToolMessage message, String status) {
        String content = messageText(message);
        try {
            Object parsed = ONode.deserialize(content, Object.class);
            if (parsed instanceof Map) {
                Map<?, ?> values = (Map<?, ?>) parsed;
                String primary =
                        ToolMessageStatusSupport.STATUS_ERROR.equals(status)
                                ? stringFrom(values.get("error"))
                                : stringFrom(values.get("preview"));
                if (StrUtil.isNotBlank(primary)) {
                    return primary;
                }
                String summary = stringFrom(values.get("summary"));
                if (StrUtil.isNotBlank(summary)) {
                    return summary;
                }
            }
        } catch (Exception ignored) {
            // 旧会话允许保存纯文本工具结果，无需因为无法解析 envelope 而中断 transcript 回放。
        }
        return content;
    }

    /** 截断较长文本，避免 session overlay 被历史长消息撑开。 */
    private String trim(String text, int max) {
        String value = StrUtil.nullToEmpty(text).replace('\n', ' ').trim();
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "...";
    }

    /** 去掉路径补全词外层引号。 */
    private String unquote(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (text.length() >= 2
                && ((text.startsWith("\"") && text.endsWith("\""))
                        || (text.startsWith("'") && text.endsWith("'")))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** 展开用户主目录，保持路径补全能处理 ~/ 前缀。 */
    private File expandHome(String word) {
        String value = StrUtil.nullToEmpty(word);
        if ("~".equals(value) || value.startsWith("~/")) {
            return new File(System.getProperty("user.home") + value.substring(1));
        }
        return new File(value);
    }

    /** 用原输入风格重组补全候选路径。 */
    private String rebuildPath(String raw, String unquoted, File directory, File file) {
        String name = file.getName();
        String value = StrUtil.nullToEmpty(unquoted);
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf(File.separatorChar));
        String prefix = slash < 0 ? "" : value.substring(0, slash + 1);
        if (directory.isAbsolute() && slash < 0 && raw.startsWith("/")) {
            prefix = directory.getAbsolutePath() + File.separator;
        }
        return prefix + name;
    }

    /** 记录 TUI RPC 可恢复降级异常，避免静默吞掉异常，同时不改变前端降级行为。 */
    private void logRecoverableRpcFailure(String action, Exception error) {
        if (log.isDebugEnabled()) {
            log.debug("TUI RPC recoverable failure during {}: {}", action, exceptionSummary(error));
        }
    }

    /** 生成不包含请求参数、配置值或异常消息的摘要，避免 debug 日志误带敏感会话内容。 */
    private String exceptionSummary(Exception error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }

    /** 构造命令名与说明二元组。 */
    private List<String> pair(String name, String description) {
        List<String> pair = new ArrayList<String>();
        pair.add(name);
        pair.add(StrUtil.nullToEmpty(description));
        return pair;
    }

    /** 向分类 Map 中追加一条命令记录。 */
    private void append(
            Map<String, List<List<String>>> grouped, String category, List<String> pair) {
        String key = StrUtil.blankToDefault(category, "other");
        if (!grouped.containsKey(key)) {
            grouped.put(key, new ArrayList<List<String>>());
        }
        grouped.get(key).add(pair);
    }
}
