package com.jimuqu.solon.claw.tool.runtime;

import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.COMMAND_ARGUMENT_KEYS;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.DETACHED_TERMINAL_SESSION;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.HARDLINE_METADATA_URL_RULE_KEY;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.HARDLINE_RULES;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.INLINE_BACKGROUND_AMP;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.LONG_LIVED_FOREGROUND_PATTERNS;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.POWERSHELL_BACKGROUND_JOB;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.POWERSHELL_WAIT_FALSE_FLAG;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.POWERSHELL_WAIT_TRUE_FLAG;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.PYTHON_SHELL_EXEC_CALL;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.RULES;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.SHELL_LEVEL_BACKGROUND;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.TRAILING_BACKGROUND_AMP;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.hardlineRuleSamples;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.pattern;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.ruleSamples;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.AgentSettingConstants;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.DangerRule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.flow.FlowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 危险命令审批服务。 */
public class DangerousCommandApprovalService {
    /** 记录危险命令审批解析、观察者通知与持久化读取失败，避免关键审批路径静默吞错。 */
    private static final Logger log =
            LoggerFactory.getLogger(DangerousCommandApprovalService.class);

    /** 投递模式审批卡片的统一常量值。 */
    public static final String DELIVERY_MODE_APPROVAL_CARD = "dangerous_command_approval_card";

    /** 卡片ACTION键的统一常量值。 */
    public static final String CARD_ACTION_KEY = "solonclaw_action";

    /** 卡片范围键的统一常量值。 */
    public static final String CARD_SCOPE_KEY = "scope";

    /** 卡片审批标识键的统一常量值。 */
    public static final String CARD_APPROVAL_ID_KEY = "approvalId";

    /** 卡片ACTIONAPPROVE的统一常量值。 */
    public static final String CARD_ACTION_APPROVE = "dangerous_approve";

    /** 卡片ACTIONDENY的统一常量值。 */
    public static final String CARD_ACTION_DENY = "dangerous_deny";

    /** 上下文待恢复审批队列的统一常量值。 */
    static final String CONTEXT_PENDING_APPROVAL_QUEUE =
            "_dangerous_command_pending_queue_";

    /** 上下文会话APPROVALS的统一常量值。 */
    static final String CONTEXT_SESSION_APPROVALS = "_dangerous_command_session_approvals_";

    /** 会话级自动审批状态键，仅用于当前会话内跳过可恢复危险命令审批。 */
    private static final String CONTEXT_SESSION_AUTO_APPROVAL =
            "_dangerous_command_session_auto_approval_";

    /** 上下文ONCEAPPROVALS的统一常量值。 */
    static final String CONTEXT_ONCE_APPROVALS = "_dangerous_command_once_approvals_";

    /** 当前THREAD审批TTLMILLIS的统一常量值。 */
    static final long CURRENT_THREAD_APPROVAL_TTL_MILLIS = 30000L;

    /** 当前THREADAPPROVEDCOMMANDS的统一常量值。 */
    private static final ThreadLocal<Map<String, Long>> CURRENT_THREAD_APPROVED_COMMANDS =
            new ThreadLocal<Map<String, Long>>();

    /** 文件/URL 等安全策略审批Pattern的统一前缀。 */
    private static final String POLICY_PATTERN_PREFIX = "policy:";

    /** 文件写入工作区外策略键。 */
    private static final String POLICY_WORKSPACE_OUTSIDE_WRITE =
            POLICY_PATTERN_PREFIX + "workspace_outside_write";

    /** 审批选择器PREFIX最小LENGTH的统一常量值。 */
    static final int APPROVAL_SELECTOR_PREFIX_MIN_LENGTH = 8;

    /** 审批选择器token的统一常量值。 */
    static final Pattern APPROVAL_SELECTOR_TOKEN = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    /** 保存global设置仓储集合，维持调用顺序或去重语义。 */
    private final GlobalSettingRepository globalSettingRepository;

    /** 注入应用配置，用于Dangerous命令审批。 */
    private final AppConfig appConfig;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 注入tirith安全服务，用于调用对应业务能力。 */
    private final TirithSecurityService tirithSecurityService;

    /** 审批消息渲染器，集中处理低敏展示、代码块和渠道审批卡片字段。 */
    private final DangerousCommandApprovalMessageRenderer messageRenderer =
            new DangerousCommandApprovalMessageRenderer();

    /** 保存审批Observers集合，维持调用顺序或去重语义。 */
    private final List<ApprovalObserver> approvalObservers =
            new CopyOnWriteArrayList<ApprovalObserver>();

    /** 记录Dangerous命令审批中的smart审批Judge。 */
    private SmartApprovalJudge smartApprovalJudge;

    /**
     * 创建Dangerous命令审批服务实例，并注入运行所需依赖。
     *
     * @param globalSettingRepository globalSetting仓储依赖。
     */
    public DangerousCommandApprovalService(GlobalSettingRepository globalSettingRepository) {
        this(globalSettingRepository, null, null, null);
    }

    /**
     * 创建Dangerous命令审批服务实例，并注入运行所需依赖。
     *
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public DangerousCommandApprovalService(
            GlobalSettingRepository globalSettingRepository,
            SecurityPolicyService securityPolicyService) {
        this(globalSettingRepository, null, securityPolicyService, null);
    }

    /**
     * 创建Dangerous命令审批服务实例，并注入运行所需依赖。
     *
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param appConfig 应用运行配置。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public DangerousCommandApprovalService(
            GlobalSettingRepository globalSettingRepository,
            AppConfig appConfig,
            SecurityPolicyService securityPolicyService) {
        this(globalSettingRepository, appConfig, securityPolicyService, null);
    }

    /**
     * 创建Dangerous命令审批服务实例，并注入运行所需依赖。
     *
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param appConfig 应用运行配置。
     * @param securityPolicyService 安全策略服务依赖。
     * @param tirithSecurityService 待校验或访问的地址参数。
     */
    public DangerousCommandApprovalService(
            GlobalSettingRepository globalSettingRepository,
            AppConfig appConfig,
            SecurityPolicyService securityPolicyService,
            TirithSecurityService tirithSecurityService) {
        this.globalSettingRepository = globalSettingRepository;
        this.appConfig = appConfig;
        this.securityPolicyService = securityPolicyService;
        this.tirithSecurityService = tirithSecurityService;
    }

    /**
     * 写入Smart审批Judge。
     *
     * @param smartApprovalJudge smart审批Judge参数。
     */
    public void setSmartApprovalJudge(SmartApprovalJudge smartApprovalJudge) {
        this.smartApprovalJudge = smartApprovalJudge;
    }

    /**
     * 判断是否存在Smart审批Judge。
     *
     * @return 如果Smart审批Judge满足条件则返回 true，否则返回 false。
     */
    public boolean hasSmartApprovalJudge() {
        return smartApprovalJudge != null;
    }

    /**
     * 追加审批Observer。
     *
     * @param observer observer 参数。
     */
    public void addApprovalObserver(ApprovalObserver observer) {
        if (observer != null && !approvalObservers.contains(observer)) {
            approvalObservers.add(observer);
        }
    }

    /**
     * 移除审批Observer。
     *
     * @param observer observer 参数。
     */
    public void removeApprovalObserver(ApprovalObserver observer) {
        if (observer != null) {
            approvalObservers.remove(observer);
        }
    }

    /**
     * 构建Interceptor。
     *
     * @return 返回创建好的Interceptor。
     */
    public HITLInterceptor buildInterceptor() {
        HITLInterceptor interceptor =
                new HITLInterceptor()
                .onTool(
                        ToolNameConstants.EXECUTE_SHELL,
                        (trace, args) -> evaluate(trace, ToolNameConstants.EXECUTE_SHELL, args))
                .onTool(
                        ToolNameConstants.EXECUTE_PYTHON,
                        (trace, args) -> evaluate(trace, ToolNameConstants.EXECUTE_PYTHON, args))
                .onTool(
                        ToolNameConstants.EXECUTE_JS,
                        (trace, args) -> evaluate(trace, ToolNameConstants.EXECUTE_JS, args))
                .onTool(
                        ToolNameConstants.EXECUTE_CODE,
                        (trace, args) ->
                                evaluateCodeCommand(
                                        trace, ToolNameConstants.EXECUTE_CODE, codeArg(args)))
                .onTool(
                        ToolNameConstants.TERMINAL,
                        (trace, args) -> evaluateTerminalTool(trace, args))
                .onTool(
                        ToolNameConstants.PROCESS,
                        (trace, args) -> evaluateProcessTool(trace, args))
                .onTool("call_tool", (trace, args) -> evaluateGatewayCallTool(trace, args))
                .onTool(
                        ToolNameConstants.FILE_READ,
                        (trace, args) -> evaluateFileTool(trace, ToolNameConstants.FILE_READ, args))
                .onTool(
                        ToolNameConstants.FILE_WRITE,
                        (trace, args) ->
                                evaluateFileTool(trace, ToolNameConstants.FILE_WRITE, args))
                .onTool(
                        ToolNameConstants.READ_FILE,
                        (trace, args) -> evaluateFileTool(trace, ToolNameConstants.READ_FILE, args))
                .onTool(
                        ToolNameConstants.WRITE_FILE,
                        (trace, args) ->
                                evaluateFileTool(trace, ToolNameConstants.WRITE_FILE, args))
                .onTool(
                        ToolNameConstants.SEARCH_FILES,
                        (trace, args) ->
                                evaluateFileTool(trace, ToolNameConstants.SEARCH_FILES, args))
                .onTool(
                        ToolNameConstants.FILE_LIST,
                        (trace, args) -> evaluateFileTool(trace, ToolNameConstants.FILE_LIST, args))
                .onTool(
                        ToolNameConstants.FILE_DELETE,
                        (trace, args) ->
                                evaluateFileTool(trace, ToolNameConstants.FILE_DELETE, args))
                .onTool(
                        ToolNameConstants.PATCH,
                        (trace, args) -> evaluateFileTool(trace, ToolNameConstants.PATCH, args))
                .onTool(
                        ToolNameConstants.WEBFETCH,
                        (trace, args) -> evaluateUrlTool(trace, ToolNameConstants.WEBFETCH, args))
                .onTool(
                        ToolNameConstants.WEBSEARCH,
                        (trace, args) -> evaluateUrlTool(trace, ToolNameConstants.WEBSEARCH, args))
                .onTool(
                        ToolNameConstants.CODESEARCH,
                        (trace, args) ->
                                evaluateUrlTool(trace, ToolNameConstants.CODESEARCH, args));
        return interceptor;
    }

    /**
     * 读取Pending审批。
     *
     * @param session 会话参数。
     * @return 返回读取到的Pending审批。
     */
    public PendingApproval getPendingApproval(AgentSession session) {
        List<PendingApproval> pendingApprovals = listPendingApprovals(session);
        return pendingApprovals.isEmpty() ? null : pendingApprovals.get(0);
    }

    /**
     * 列出Pending Approvals。
     *
     * @param session 会话参数。
     * @return 返回Pending Approvals列表。
     */
    public List<PendingApproval> listPendingApprovals(AgentSession session) {
        if (session == null) {
            return new ArrayList<PendingApproval>();
        }
        List<PendingApproval> pending = pendingQueueFrom(session.getContext());
        if (prunePendingApprovals(session, pending)) {
            pending = pendingQueueFrom(session.getContext());
        }
        return pending;
    }

    /**
     * 执行select待恢复审批相关逻辑。
     *
     * @param session 会话参数。
     * @param selector 浏览器元素选择器。
     * @return 返回select Pending审批结果。
     */
    public PendingApproval selectPendingApproval(AgentSession session, String selector) {
        return findPendingApproval(session, selector);
    }

    /**
     * 读取Pending审批。
     *
     * @param sessionRecord 会话记录参数。
     * @return 返回读取到的Pending审批。
     */
    public PendingApproval getPendingApproval(
            com.jimuqu.solon.claw.core.model.SessionRecord sessionRecord) {
        List<PendingApproval> pendingApprovals = listPendingApprovals(sessionRecord);
        return pendingApprovals.isEmpty() ? null : pendingApprovals.get(0);
    }

    /**
     * 列出Pending Approvals。
     *
     * @param sessionRecord 会话记录参数。
     * @return 返回Pending Approvals列表。
     */
    public List<PendingApproval> listPendingApprovals(
            com.jimuqu.solon.claw.core.model.SessionRecord sessionRecord) {
        if (sessionRecord == null || StrUtil.isBlank(sessionRecord.getAgentSnapshotJson())) {
            return new ArrayList<PendingApproval>();
        }

        try {
            Object parsed = ONode.deserialize(sessionRecord.getAgentSnapshotJson(), Object.class);
            if (!(parsed instanceof Map)) {
                return new ArrayList<PendingApproval>();
            }
            Map<?, ?> snapshot = (Map<?, ?>) parsed;
            return filterActivePendingApprovals(pendingQueueFrom(snapshot));
        } catch (Exception e) {
            log.debug("Pending approval snapshot parsing failed; returning empty approval list: {}", exceptionSummary(e));
            return new ArrayList<PendingApproval>();
        }
    }

    /**
     * 创建审批通过决策。
     *
     * @param session 会话参数。
     * @param scope scope 参数。
     * @param approver approver 参数。
     * @return 返回approve结果。
     */
    public boolean approve(AgentSession session, ApprovalScope scope, String approver)
            throws Exception {
        return approve(session, null, scope, approver);
    }

    /**
     * 创建审批通过决策。
     *
     * @param session 会话参数。
     * @param selector 浏览器元素选择器。
     * @param scope scope 参数。
     * @param approver approver 参数。
     * @return 返回approve结果。
     */
    public boolean approve(
            AgentSession session, String selector, ApprovalScope scope, String approver)
            throws Exception {
        PendingApproval pending = findPendingApproval(session, selector);
        if (pending == null) {
            return false;
        }

        ApprovalScope effectiveScope =
                pending.isOnceOnlyApproval()
                        ? ApprovalScope.ONCE
                        : scope == null ? ApprovalScope.ONCE : scope;
        if (effectiveScope == ApprovalScope.SESSION) {
            for (String patternKey : pending.effectivePatternKeys()) {
                addSessionApproval(
                        session.getContext(), approvalPattern(pending.getToolName(), patternKey));
            }
        } else if (effectiveScope == ApprovalScope.ALWAYS) {
            for (String patternKey : pending.effectivePatternKeys()) {
                String approvalPattern = approvalPattern(pending.getToolName(), patternKey);
                if (isTirithPattern(patternKey)) {
                    addSessionApproval(session.getContext(), approvalPattern);
                } else {
                    addAlwaysApproval(approvalPattern);
                }
            }
        }

        String comment = effectiveScope.comment();
        if (StrUtil.isNotBlank(approver)) {
            comment = comment + " 审批人：" + redactedApprover(approver);
        }

        if (effectiveScope == ApprovalScope.ONCE) {
            addOnceApproval(session.getContext(), pending.approvalKey());
        }
        HITL.approve(session, pending.getToolName(), comment);
        removePendingApproval(session, pending);
        session.updateSnapshot();
        notifyApprovalResponse(
                session, pending, effectiveScope.name().toLowerCase(Locale.ROOT), approver);
        return true;
    }

    /**
     * 执行approve全部相关逻辑。
     *
     * @param session 会话参数。
     * @param scope scope 参数。
     * @param approver approver 参数。
     * @return 返回approve全部结果。
     */
    public int approveAll(AgentSession session, ApprovalScope scope, String approver)
            throws Exception {
        ApprovalScope effectiveScope = scope == null ? ApprovalScope.ONCE : scope;
        List<PendingApproval> pendingApprovals = listPendingApprovals(session);
        int approved = 0;
        for (PendingApproval pending : pendingApprovals) {
            String selector = approvalSelector(pending);
            if (approve(session, selector, effectiveScope, approver)) {
                approved++;
            }
        }
        return approved;
    }

    /**
     * 存储待恢复审批。
     *
     * @param session 会话参数。
     * @param toolName 工具名称。
     * @param patternKey pattern键标识或键值。
     * @param description 描述参数。
     * @param command 待执行或解析的命令文本。
     */
    public void storePendingApproval(
            AgentSession session,
            String toolName,
            String patternKey,
            String description,
            String command) {
        storePendingApproval(
                session,
                toolName,
                patternKey,
                Collections.singletonList(patternKey),
                description,
                command);
    }

    /**
     * 存储待恢复审批。
     *
     * @param session 会话参数。
     * @param toolName 工具名称。
     * @param patternKey pattern键标识或键值。
     * @param patternKeys patternKeys 参数。
     * @param description 描述参数。
     * @param command 待执行或解析的命令文本。
     */
    public void storePendingApproval(
            AgentSession session,
            String toolName,
            String patternKey,
            List<String> patternKeys,
            String description,
            String command) {
        if (session == null) {
            return;
        }

        DetectionResult detection = new DetectionResult();
        detection.setPatternKey(patternKey);
        detection.setPatternKeys(patternKeys);
        detection.setDescription(description);
        detection.setNormalizedCode(normalize(command));
        Map<String, Object> pendingMap = createPendingMap(toolName, detection, command);
        storePendingMap(session, pendingMap);
        session.pending(true, "dangerous_command_approval");
        session.updateSnapshot();
        notifyApprovalRequest(session, toPendingApproval(pendingMap));
    }

    /**
     * 追加会话审批。
     *
     * @param session 会话参数。
     * @param toolName 工具名称。
     * @param patternKey pattern键标识或键值。
     */
    public void addSessionApproval(AgentSession session, String toolName, String patternKey)
            throws Exception {
        if (session == null || StrUtil.hasBlank(toolName, patternKey)) {
            return;
        }
        addSessionApproval(session.getContext(), approvalPattern(toolName, patternKey));
        session.updateSnapshot();
    }

    /**
     * 追加Always审批。
     *
     * @param toolName 工具名称。
     * @param patternKey pattern键标识或键值。
     */
    public void addAlwaysApproval(String toolName, String patternKey) throws Exception {
        if (StrUtil.hasBlank(toolName, patternKey)) {
            return;
        }
        addAlwaysApproval(approvalPattern(toolName, patternKey));
    }

    /**
     * 执行reject相关逻辑。
     *
     * @param session 会话参数。
     * @param approver approver 参数。
     * @return 返回reject结果。
     */
    public boolean reject(AgentSession session, String approver) {
        return reject(session, null, approver);
    }

    /**
     * 执行reject相关逻辑。
     *
     * @param session 会话参数。
     * @param selector 浏览器元素选择器。
     * @param approver approver 参数。
     * @return 返回reject结果。
     */
    public boolean reject(AgentSession session, String selector, String approver) {
        PendingApproval pending = findPendingApproval(session, selector);
        if (pending == null) {
            return false;
        }

        String comment = "危险命令未获批准，已取消执行。";
        if (StrUtil.isNotBlank(approver)) {
            comment = comment + " 审批人：" + redactedApprover(approver);
        }

        HITL.reject(session, pending.getToolName(), comment);
        removePendingApproval(session, pending);
        session.updateSnapshot();
        notifyApprovalResponse(session, pending, "deny", approver);
        return true;
    }

    /**
     * 执行reject全部相关逻辑。
     *
     * @param session 会话参数。
     * @param approver approver 参数。
     * @return 返回reject全部结果。
     */
    public int rejectAll(AgentSession session, String approver) {
        List<PendingApproval> pendingApprovals = listPendingApprovals(session);
        int rejected = 0;
        for (PendingApproval pending : pendingApprovals) {
            String selector = approvalSelector(pending);
            if (reject(session, selector, approver)) {
                rejected++;
            }
        }
        return rejected;
    }

    /**
     * 执行detect相关逻辑。
     *
     * @param toolName 工具名称。
     * @param code code 参数。
     * @return 返回detect结果。
     */
    public DetectionResult detect(String toolName, String code) {
        String normalized = normalize(code);
        if (StrUtil.isBlank(normalized)) {
            return null;
        }

        for (DangerRule rule : RULES) {
            if (!rule.matches(toolName, normalized)) {
                continue;
            }

            DetectionResult result = new DetectionResult();
            result.setPatternKey(rule.getPatternKey());
            result.setPatternKeys(Collections.singletonList(rule.getPatternKey()));
            result.setDescription(rule.getDescription());
            result.setNormalizedCode(normalized);
            return result;
        }

        DetectionResult blockedPath = detectCommandPathForApproval(toolName, normalized);
        if (blockedPath != null) {
            return blockedPath;
        }

        return null;
    }

    /**
     * 执行detect命令路径For审批相关逻辑。
     *
     * @param toolName 工具名称。
     * @param normalized normalized 参数。
     * @return 返回detect命令路径For审批结果。
     */
    private DetectionResult detectCommandPathForApproval(String toolName, String normalized) {
        if (!isCommandSecurityTool(toolName)) {
            return null;
        }
        if (securityPolicyService == null) {
            return null;
        }
        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkConfiguredCredentialCommandPaths(normalized);
        if (verdict.isAllowed()) {
            return null;
        }
        DetectionResult result = new DetectionResult();
        result.setPatternKey("credential_command_path_access");
        result.setPatternKeys(Collections.singletonList("credential_command_path_access"));
        result.setDescription(StrUtil.blankToDefault(verdict.getMessage(), "命令访问敏感凭据路径"));
        result.setNormalizedCode(normalized);
        return result;
    }

    /**
     * 判断是否命令安全工具。
     *
     * @param toolName 工具名称。
     * @return 如果命令安全工具满足条件则返回 true，否则返回 false。
     */
    private boolean isCommandSecurityTool(String toolName) {
        return ToolNameConstants.EXECUTE_SHELL.equals(toolName)
                || ToolNameConstants.EXECUTE_PYTHON.equals(toolName)
                || ToolNameConstants.EXECUTE_JS.equals(toolName)
                || ToolNameConstants.EXECUTE_CODE.equals(toolName);
    }

    /**
     * 执行detectHardline相关逻辑。
     *
     * @param toolName 工具名称。
     * @param code code 参数。
     * @return 返回detect Hardline结果。
     */
    public DetectionResult detectHardline(String toolName, String code) {
        String normalized = normalize(code);
        if (StrUtil.isBlank(normalized)) {
            return null;
        }
        for (DangerRule rule : HARDLINE_RULES) {
            if (!rule.matches(toolName, normalized)) {
                continue;
            }
            DetectionResult result = new DetectionResult();
            result.setPatternKey(rule.getPatternKey());
            result.setPatternKeys(Collections.singletonList(rule.getPatternKey()));
            result.setDescription(rule.getDescription());
            result.setNormalizedCode(normalized);
            result.setHardline(true);
            if (isHardlineAllowlisted(result)) {
                return null;
            }
            return result;
        }
        DetectionResult blockedUrl = detectHardlineCommandUrl(toolName, normalized);
        if (blockedUrl != null) {
            return blockedUrl;
        }
        if (ToolNameConstants.EXECUTE_PYTHON.equals(toolName)) {
            for (String shellCommand : DangerousCommandTextSupport.extractPythonShellCommands(normalized)) {
                DetectionResult result =
                        detectHardline(ToolNameConstants.EXECUTE_SHELL, shellCommand);
                if (result != null) {
                    return result;
                }
            }
        }
        if (ToolNameConstants.EXECUTE_JS.equals(toolName)) {
            String childProcessCommand = DangerousCommandTextSupport.extractJavaScriptChildProcessCommand(normalized);
            if (StrUtil.isNotBlank(childProcessCommand)) {
                DetectionResult result =
                        detectHardline(ToolNameConstants.EXECUTE_SHELL, childProcessCommand);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * 执行detectHardline命令URL相关逻辑。
     *
     * @param toolName 工具名称。
     * @param normalized normalized 参数。
     * @return 返回detect Hardline命令URL结果。
     */
    private DetectionResult detectHardlineCommandUrl(String toolName, String normalized) {
        if (securityPolicyService == null
                || (!ToolNameConstants.EXECUTE_SHELL.equals(toolName)
                        && !ToolNameConstants.EXECUTE_PYTHON.equals(toolName)
                        && !ToolNameConstants.EXECUTE_JS.equals(toolName)
                        && !ToolNameConstants.EXECUTE_CODE.equals(toolName))) {
            return null;
        }
        SecurityPolicyService.UrlVerdict verdict =
                securityPolicyService.checkCommandAlwaysBlockedUrls(normalized);
        if (verdict.isAllowed()) {
            return null;
        }
        DetectionResult result = new DetectionResult();
        result.setPatternKey(HARDLINE_METADATA_URL_RULE_KEY);
        result.setPatternKeys(Collections.singletonList(HARDLINE_METADATA_URL_RULE_KEY));
        result.setDescription(verdict.getMessage());
        result.setNormalizedCode(normalized);
        result.setHardline(true);
        if (isHardlineAllowlisted(result)) {
            return null;
        }
        return result;
    }

    /**
     * 判断是否Hardline Allowlisted。
     *
     * @param result 结果响应或执行结果。
     * @return 如果Hardline Allowlisted满足条件则返回 true，否则返回 false。
     */
    private boolean isHardlineAllowlisted(DetectionResult result) {
        if (result == null || appConfig == null || appConfig.getSecurity() == null) {
            return false;
        }
        List<String> configured = appConfig.getSecurity().getHardlineAllowlist();
        if (configured == null || configured.isEmpty()) {
            return false;
        }
        Set<String> allowed = new LinkedHashSet<String>();
        for (String item : configured) {
            if (StrUtil.isBlank(item)) {
                continue;
            }
            allowed.add(item.trim().toLowerCase(Locale.ROOT));
        }
        if (allowed.contains("*")) {
            return true;
        }
        for (String key : result.effectivePatternKeys()) {
            if (StrUtil.isNotBlank(key) && allowed.contains(key.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行前台进程BackgroundGuidance相关逻辑。
     *
     * @param toolName 工具名称。
     * @param code code 参数。
     * @return 返回foreground Background Guidance结果。
     */
    public String foregroundBackgroundGuidance(String toolName, String code) {
        String normalized = normalize(code);
        if (StrUtil.isBlank(normalized) || DangerousCommandTextSupport.looksLikeHelpOrVersionCommand(normalized)) {
            return null;
        }
        if (ToolNameConstants.EXECUTE_PYTHON.equals(toolName)) {
            for (String shellCommand : DangerousCommandTextSupport.extractPythonShellCommands(normalized)) {
                String guidance =
                        foregroundBackgroundGuidance(ToolNameConstants.EXECUTE_SHELL, shellCommand);
                if (guidance != null) {
                    return "BLOCKED: Python 脚本中的 shell 调用需要改用受管后台进程能力。\n" + guidance;
                }
            }
            return null;
        }
        if (ToolNameConstants.EXECUTE_JS.equals(toolName)) {
            String childProcessCommand = DangerousCommandTextSupport.extractJavaScriptChildProcessCommand(normalized);
            if (StrUtil.isNotBlank(childProcessCommand)) {
                String guidance =
                        foregroundBackgroundGuidance(
                                ToolNameConstants.EXECUTE_SHELL, childProcessCommand);
                if (guidance != null) {
                    return "BLOCKED: Node 脚本中的 child_process 调用需要改用受管后台进程能力。\n" + guidance;
                }
            }
            return null;
        }
        if (!ToolNameConstants.EXECUTE_SHELL.equals(toolName)) {
            return null;
        }

        if (SHELL_LEVEL_BACKGROUND.matcher(normalized).find()) {
            return "BLOCKED: 前台命令使用了 shell 级后台包装（nohup/disown/setsid）。请使用受管的后台进程能力，以便 Agent 跟踪生命周期和输出，然后再单独执行就绪检查或测试。";
        }
        if (DETACHED_TERMINAL_SESSION.matcher(normalized).find()) {
            return "BLOCKED: 前台命令使用了脱离当前终端的会话启动器（tmux/screen/systemd-run/start /B）。请使用受管的后台进程能力，以便 Agent 跟踪生命周期和输出。";
        }
        if (isPowerShellBackgroundLaunch(normalized)) {
            return "BLOCKED: 前台命令使用了 PowerShell 后台启动命令（Start-Process/Start-Job/Start-ThreadJob）。请使用受管的后台进程能力，以便 Agent 跟踪生命周期和输出，然后再单独执行就绪检查或测试。";
        }
        if (INLINE_BACKGROUND_AMP.matcher(normalized).find()
                || TRAILING_BACKGROUND_AMP.matcher(normalized).find()) {
            return "BLOCKED: 前台命令使用了 '&' 后台执行。请使用受管的后台进程能力启动长驻进程，然后在后续命令中执行健康检查或测试。";
        }
        for (Pattern pattern : LONG_LIVED_FOREGROUND_PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                return "BLOCKED: 该前台命令看起来会启动长驻服务或 watch 进程。请改用受管的后台进程能力，等待健康检查或日志信号后，再用单独命令运行测试。";
            }
        }
        return null;
    }

    /**
     * 判断是否Power Shell Background Launch。
     *
     * @param normalized normalized 参数。
     * @return 如果Power Shell Background Launch满足条件则返回 true，否则返回 false。
     */
    private boolean isPowerShellBackgroundLaunch(String normalized) {
        if (!POWERSHELL_BACKGROUND_JOB.matcher(normalized).find()) {
            return false;
        }
        if (!pattern("\\bstart-process\\b").matcher(normalized).find()) {
            return true;
        }
        return POWERSHELL_WAIT_FALSE_FLAG.matcher(normalized).find()
                || !POWERSHELL_WAIT_TRUE_FLAG.matcher(normalized).find();
    }

    /**
     * 执行审批策略摘要相关逻辑。
     *
     * @return 返回审批策略Summary结果。
     */
    public Map<String, Object> approvalPolicySummary() {
        return policySummaries().approvalPolicySummary();
    }

    /** 执行hardline策略摘要相关逻辑。 */
    public Map<String, Object> hardlinePolicySummary() {
        return policySummaries().hardlinePolicySummary();
    }

    /** 执行smart审批策略摘要相关逻辑。 */
    public Map<String, Object> smartApprovalPolicySummary() {
        return policySummaries().smartApprovalPolicySummary();
    }

    /** 执行tirith审批策略摘要相关逻辑。 */
    public Map<String, Object> tirithApprovalPolicySummary() {
        return policySummaries().tirithApprovalPolicySummary();
    }

    /** 执行定时任务审批策略摘要相关逻辑。 */
    public Map<String, Object> cronApprovalPolicySummary() {
        return policySummaries().cronApprovalPolicySummary();
    }

    /** 执行子Agent审批策略摘要相关逻辑。 */
    public Map<String, Object> subagentApprovalPolicySummary() {
        return policySummaries().subagentApprovalPolicySummary();
    }

    /** 执行斜杠命令Confirm策略摘要相关逻辑。 */
    public Map<String, Object> slashConfirmPolicySummary() {
        return policySummaries().slashConfirmPolicySummary();
    }

    /** 执行审批Card策略摘要相关逻辑。 */
    public Map<String, Object> approvalCardPolicySummary() {
        return policySummaries().approvalCardPolicySummary();
    }

    /** 执行审批审计策略摘要相关逻辑。 */
    public Map<String, Object> approvalAuditPolicySummary() {
        return policySummaries().approvalAuditPolicySummary();
    }

    /** 执行MCP Reload策略摘要相关逻辑。 */
    public Map<String, Object> mcpReloadPolicySummary() {
        return policySummaries().mcpReloadPolicySummary();
    }

    /** 执行审批生命周期策略摘要相关逻辑。 */
    public Map<String, Object> approvalLifecyclePolicySummary() {
        return policySummaries().approvalLifecyclePolicySummary();
    }

    /** 执行终端防护策略摘要相关逻辑。 */
    public Map<String, Object> terminalGuardrailPolicySummary() {
        return policySummaries().terminalGuardrailPolicySummary();
    }

    /** 创建审批策略摘要生成器。 */
    private DangerousCommandApprovalPolicySummaries policySummaries() {
        return new DangerousCommandApprovalPolicySummaries(
                this,
                appConfig,
                tirithSecurityService != null,
                securityPolicyService != null,
                approvalObservers.size());
    }

    /**
     * 构建投递Extras。
     *
     * @param platform 平台参数。
     * @param pending 待恢复参数。
     * @return 返回创建好的投递Extras。
     */
    public Map<String, Object> buildDeliveryExtras(PlatformType platform, PendingApproval pending) {
        if ((platform != PlatformType.FEISHU && platform != PlatformType.QQBOT)
                || pending == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> extras = new LinkedHashMap<String, Object>();
        extras.put("mode", DELIVERY_MODE_APPROVAL_CARD);
        extras.put("approvalId", safeApprovalSelector(pending));
        extras.put(
                "approvalCommand",
                messageRenderer.redactApprovalDisplay(pending.getCommand(), 3000));
        extras.put(
                "approvalDescription",
                messageRenderer.redactApprovalDisplay(pending.getDescription(), 1000));
        extras.put(
                "approvalToolName",
                messageRenderer.redactApprovalDisplay(pending.getToolName(), 200));
        extras.put("approvalAllowAlways", Boolean.valueOf(pending.isPermanentApprovalAllowed()));
        return extras;
    }

    /**
     * 判断是否会话Approved。
     *
     * @param session 会话参数。
     * @param patternKey pattern键标识或键值。
     * @return 如果会话Approved满足条件则返回 true，否则返回 false。
     */
    public boolean isSessionApproved(AgentSession session, String patternKey) {
        if (session == null || StrUtil.isBlank(patternKey)) {
            return false;
        }
        return containsPattern(loadSessionApprovals(session.getContext()), patternKey);
    }

    /**
     * 判断是否会话Approved。
     *
     * @param session 会话参数。
     * @param toolName 工具名称。
     * @param patternKey pattern键标识或键值。
     * @param command 待执行或解析的命令文本。
     * @return 如果会话Approved满足条件则返回 true，否则返回 false。
     */
    public boolean isSessionApproved(
            AgentSession session, String toolName, String patternKey, String command) {
        if (session == null || StrUtil.hasBlank(toolName, patternKey)) {
            return false;
        }
        Set<String> approvals = loadSessionApprovals(session.getContext());
        return approvals.contains(approvalPattern(toolName, patternKey))
                || approvals.contains(approvalKey(toolName, patternKey, normalize(command)));
    }

    /**
     * 判断是否Always Approved。
     *
     * @param patternKey pattern键标识或键值。
     * @return 如果Always Approved满足条件则返回 true，否则返回 false。
     */
    public boolean isAlwaysApproved(String patternKey) {
        if (StrUtil.isBlank(patternKey)) {
            return false;
        }
        return containsPattern(loadAlwaysApprovedPatterns(), patternKey);
    }

    /**
     * 判断是否Always Approved。
     *
     * @param toolName 工具名称。
     * @param patternKey pattern键标识或键值。
     * @param command 待执行或解析的命令文本。
     * @return 如果Always Approved满足条件则返回 true，否则返回 false。
     */
    public boolean isAlwaysApproved(String toolName, String patternKey, String command) {
        if (StrUtil.hasBlank(toolName, patternKey)) {
            return false;
        }
        Set<String> approvals = loadAlwaysApprovedPatterns();
        return approvals.contains(approvalPattern(toolName, patternKey))
                || approvals.contains(approvalKey(toolName, patternKey, normalize(command)));
    }

    /**
     * 消费当前Thread审批。
     *
     * @param toolName 工具名称。
     * @param command 待执行或解析的命令文本。
     * @return 返回consume当前Thread审批结果。
     */
    public static boolean consumeCurrentThreadApproval(String toolName, String command) {
        Map<String, Long> approvals = CURRENT_THREAD_APPROVED_COMMANDS.get();
        if (approvals == null || approvals.isEmpty()) {
            return false;
        }
        removeExpiredCurrentThreadApprovals(approvals);
        String key = currentThreadApprovalKey(toolName, command);
        Long expiresAt = approvals.remove(key);
        if (approvals.isEmpty()) {
            CURRENT_THREAD_APPROVED_COMMANDS.remove();
        }
        return expiresAt != null && expiresAt.longValue() >= System.currentTimeMillis();
    }

    /**
     * 列出会话Approvals。
     *
     * @param session 会话参数。
     * @return 返回会话Approvals列表。
     */
    public List<String> listSessionApprovals(AgentSession session) {
        if (session == null) {
            return new ArrayList<String>();
        }
        return new ArrayList<String>(loadSessionApprovals(session.getContext()));
    }

    /**
     * 列出Always Approvals。
     *
     * @return 返回Always Approvals列表。
     */
    public List<String> listAlwaysApprovals() {
        return new ArrayList<String>(loadAlwaysApprovedPatterns());
    }

    /**
     * 执行revokeAlways审批相关逻辑。
     *
     * @param approvalPattern 审批Pattern参数。
     * @return 返回revoke Always审批结果。
     */
    public boolean revokeAlwaysApproval(String approvalPattern) throws Exception {
        String normalized = cleanApprovalValue(approvalPattern);
        if (StrUtil.isBlank(normalized)) {
            return false;
        }
        Set<String> approvals = loadAlwaysApprovedPatterns();
        boolean removed = approvals.remove(normalized);
        if (!removed) {
            return false;
        }
        if (globalSettingRepository != null) {
            globalSettingRepository.set(
                    AgentSettingConstants.DANGEROUS_COMMAND_ALWAYS_PATTERNS,
                    ONode.serialize(new ArrayList<String>(approvals)));
        }
        return true;
    }

    /**
     * 清理会话Approvals。
     *
     * @param session 会话参数。
     */
    public void clearSessionApprovals(AgentSession session) throws Exception {
        if (session == null) {
            return;
        }
        session.getContext().remove(CONTEXT_SESSION_APPROVALS);
        session.getContext().remove(CONTEXT_ONCE_APPROVALS);
        session.getContext().remove(CONTEXT_PENDING_APPROVAL_QUEUE);
        session.getContext().remove(CONTEXT_SESSION_AUTO_APPROVAL);
        session.updateSnapshot();
    }

    /**
     * 启用当前会话的可恢复危险命令自动审批。
     *
     * @param session 会话参数。
     * @return 返回会话自动审批结果。
     */
    public boolean enableSessionAutoApproval(AgentSession session) throws Exception {
        return setSessionAutoApproval(session, true);
    }

    /**
     * 禁用当前会话的可恢复危险命令自动审批。
     *
     * @param session 会话参数。
     * @return 返回会话自动审批结果。
     */
    public boolean disableSessionAutoApproval(AgentSession session) throws Exception {
        return setSessionAutoApproval(session, false);
    }

    /**
     * 切换当前会话的可恢复危险命令自动审批。
     *
     * @param session 会话参数。
     * @return 返回切换后的会话自动审批状态。
     */
    public boolean toggleSessionAutoApproval(AgentSession session) throws Exception {
        return setSessionAutoApproval(session, !isSessionAutoApprovalEnabled(session));
    }

    /**
     * 判断当前会话是否启用可恢复危险命令自动审批。
     *
     * @param session 会话参数。
     * @return 如果会话自动审批启用则返回 true，否则返回 false。
     */
    public boolean isSessionAutoApprovalEnabled(AgentSession session) {
        return session != null
                && truthy(session.getContext().get(CONTEXT_SESSION_AUTO_APPROVAL));
    }

    /** 清理Always Approvals。 */
    public void clearAlwaysApprovals() throws Exception {
        if (globalSettingRepository != null) {
            globalSettingRepository.set(
                    AgentSettingConstants.DANGEROUS_COMMAND_ALWAYS_PATTERNS,
                    ONode.serialize(new ArrayList<String>()));
        }
    }

    /**
     * 执行命令From卡片Action载荷相关逻辑。
     *
     * @param raw 原始输入值。
     * @return 返回命令From Card Action Payload结果。
     */
    public static String commandFromCardActionPayload(Object raw) {
        Map<?, ?> map = raw instanceof Map ? (Map<?, ?>) raw : parseStaticMap(raw);
        if (map == null) {
            return null;
        }

        String action = safeCardToken(map.get(CARD_ACTION_KEY)).toLowerCase(Locale.ROOT);
        String approvalId = safeApprovalSelectorToken(map.get(CARD_APPROVAL_ID_KEY));
        if (approvalId == null) {
            return null;
        }
        if (CARD_ACTION_DENY.equals(action)) {
            return StrUtil.isBlank(approvalId) ? "/deny" : "/deny " + approvalId;
        }
        if (!CARD_ACTION_APPROVE.equals(action)) {
            return null;
        }

        String scope = safeCardToken(map.get(CARD_SCOPE_KEY)).toLowerCase(Locale.ROOT);
        String selector = StrUtil.isBlank(approvalId) ? "" : approvalId + " ";
        if ("always".equals(scope)) {
            return "/approve " + selector + "always";
        }
        if ("session".equals(scope)) {
            return "/approve " + selector + "session";
        }
        return StrUtil.isBlank(approvalId) ? "/approve" : "/approve " + approvalId;
    }

    /**
     * 生成安全展示用的卡片token。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Card token结果。
     */
    private static String safeCardToken(Object value) {
        return SecretRedactor.stripDisplayControls(
                        TerminalAnsiSanitizer.stripAnsi(stringValueStatic(value)))
                .trim();
    }

    /**
     * 生成安全展示用的审批选择器token。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe审批Selector token结果。
     */
    public static String safeApprovalSelectorToken(Object value) {
        String token = safeCardToken(value);
        if (StrUtil.isBlank(token)) {
            return "";
        }
        if (!APPROVAL_SELECTOR_TOKEN.matcher(token).matches()) {
            return null;
        }
        return token.equals(SecretRedactor.redact(token, token.length() + 128)) ? token : null;
    }

    /**
     * 执行evaluate相关逻辑。
     *
     * @param trace trace 参数。
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     * @return 返回evaluate结果。
     */
    private String evaluate(ReActTrace trace, String toolName, Map<String, Object> args) {
        return evaluateCommand(trace, toolName, toolName, codeArg(args));
    }

    /**
     * 执行codeArg相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回code Arg结果。
     */
    private String codeArg(Map<String, Object> args) {
        return commandLikeArg(args);
    }

    /**
     * 执行evaluate终端工具相关逻辑。
     *
     * @param trace trace 参数。
     * @param args 工具或命令参数。
     * @return 返回evaluate终端工具结果。
     */
    private String evaluateTerminalTool(ReActTrace trace, Map<String, Object> args) {
        String command = commandLikeArg(args);
        return evaluateCommand(
                trace, ToolNameConstants.TERMINAL, ToolNameConstants.EXECUTE_SHELL, command);
    }

    /**
     * 执行evaluate进程工具相关逻辑。
     *
     * @param trace trace 参数。
     * @param args 工具或命令参数。
     * @return 返回evaluate进程工具结果。
     */
    private String evaluateProcessTool(ReActTrace trace, Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        String action =
                args.get("action") == null
                        ? ""
                        : StrUtil.nullToEmpty(String.valueOf(args.get("action")))
                                .trim()
                                .toLowerCase(Locale.ROOT);
        if (!"start".equals(action)) {
            return null;
        }
        String command = commandLikeArg(args);
        return evaluateCommand(
                trace, ToolNameConstants.PROCESS, ToolNameConstants.EXECUTE_SHELL, command);
    }

    /**
     * 执行命令LikeArg相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回命令Like Arg结果。
     */
    private String commandLikeArg(Map<String, Object> args) {
        return commandLikeArg(args, 0);
    }

    /**
     * 执行命令LikeArg相关逻辑。
     *
     * @param raw 原始输入值。
     * @param depth depth 参数。
     * @return 返回命令Like Arg结果。
     */
    private String commandLikeArg(Object raw, int depth) {
        if (raw == null || depth > 6) {
            return null;
        }
        if (raw instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) raw;
            for (String key : COMMAND_ARGUMENT_KEYS) {
                if (map.containsKey(key)) {
                    String value = commandValueToString(map.get(key), depth + 1);
                    if (StrUtil.isNotBlank(value)) {
                        return value;
                    }
                }
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ROOT);
                if (COMMAND_ARGUMENT_KEYS.contains(key)) {
                    String value = commandValueToString(entry.getValue(), depth + 1);
                    if (StrUtil.isNotBlank(value)) {
                        return value;
                    }
                }
            }
            for (Object value : map.values()) {
                String nested = commandLikeArg(value, depth + 1);
                if (StrUtil.isNotBlank(nested)) {
                    return nested;
                }
            }
            return null;
        }
        if (raw instanceof Iterable) {
            for (Object value : (Iterable<?>) raw) {
                String nested = commandLikeArg(value, depth + 1);
                if (StrUtil.isNotBlank(nested)) {
                    return nested;
                }
            }
            return null;
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                String nested = commandLikeArg(java.lang.reflect.Array.get(raw, i), depth + 1);
                if (StrUtil.isNotBlank(nested)) {
                    return nested;
                }
            }
        }
        return null;
    }

    /**
     * 执行命令值To字符串相关逻辑。
     *
     * @param raw 原始输入值。
     * @param depth depth 参数。
     * @return 返回命令Value To String结果。
     */
    private String commandValueToString(Object raw, int depth) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof CharSequence) {
            return String.valueOf(raw);
        }
        if (raw instanceof Iterable) {
            StringBuilder buffer = new StringBuilder();
            for (Object value : (Iterable<?>) raw) {
                if (value == null) {
                    continue;
                }
                if (value instanceof CharSequence
                        || value instanceof Number
                        || value instanceof Boolean) {
                    if (buffer.length() > 0) {
                        buffer.append('\n');
                    }
                    buffer.append(String.valueOf(value));
                    continue;
                }
                String nested = commandLikeArg(value, depth + 1);
                if (StrUtil.isNotBlank(nested)) {
                    if (buffer.length() > 0) {
                        buffer.append('\n');
                    }
                    buffer.append(nested);
                }
            }
            return buffer.length() == 0 ? null : buffer.toString();
        }
        if (raw.getClass().isArray()) {
            StringBuilder buffer = new StringBuilder();
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                Object value = java.lang.reflect.Array.get(raw, i);
                if (value == null) {
                    continue;
                }
                if (value instanceof CharSequence
                        || value instanceof Number
                        || value instanceof Boolean) {
                    if (buffer.length() > 0) {
                        buffer.append('\n');
                    }
                    buffer.append(String.valueOf(value));
                    continue;
                }
                String nested = commandLikeArg(value, depth + 1);
                if (StrUtil.isNotBlank(nested)) {
                    if (buffer.length() > 0) {
                        buffer.append('\n');
                    }
                    buffer.append(nested);
                }
            }
            return buffer.length() == 0 ? null : buffer.toString();
        }
        if (raw instanceof Number || raw instanceof Boolean) {
            return String.valueOf(raw);
        }
        return commandLikeArg(raw, depth + 1);
    }

    /**
     * 执行evaluate消息网关Call工具相关逻辑。
     *
     * @param trace trace 参数。
     * @param args 工具或命令参数。
     * @return 返回evaluate消息网关Call工具结果。
     */
    private String evaluateGatewayCallTool(ReActTrace trace, Map<String, Object> args) {
        String toolName = gatewayToolName(args);
        if (StrUtil.isBlank(toolName)) {
            return null;
        }
        String normalized = canonicalGatewayToolName(toolName);
        GatewayToolArgsResult parsedArgs = gatewayToolArgs(args);
        if (isGatewaySecurityTool(normalized) && !parsedArgs.isValid()) {
            blockMalformedGatewayToolArgs(trace, normalized, parsedArgs);
            return null;
        }
        Map<String, Object> toolArgs = parsedArgs.getArgs();
        boolean hadInnerDecision =
                trace != null
                        && trace.getContext() != null
                        && trace.getContext().getAs(HITL.DECISION_PREFIX + normalized) != null;
        String result;
        if (ToolNameConstants.EXECUTE_SHELL.equals(normalized)
                || ToolNameConstants.EXECUTE_PYTHON.equals(normalized)
                || ToolNameConstants.EXECUTE_JS.equals(normalized)) {
            result = evaluate(trace, normalized, toolArgs);
        } else if (ToolNameConstants.EXECUTE_CODE.equals(normalized)) {
            result = evaluateCodeCommand(trace, ToolNameConstants.EXECUTE_CODE, codeArg(toolArgs));
        } else if (ToolNameConstants.TERMINAL.equals(normalized)) {
            result = evaluateTerminalTool(trace, toolArgs);
        } else if (ToolNameConstants.PROCESS.equals(normalized)) {
            result = evaluateProcessTool(trace, toolArgs);
        } else if (ToolNameConstants.isFileSecurityTool(normalized)) {
            result = evaluateFileTool(trace, normalized, toolArgs);
        } else if (isUrlSecurityTool(normalized)) {
            result = evaluateUrlTool(trace, normalized, toolArgs);
        } else {
            return null;
        }
        if (hadInnerDecision) {
            clearGatewayInnerDecisionAfterApproval(trace, normalized, result);
        }
        return result;
    }

    /**
     * 执行规范消息网关工具名称相关逻辑。
     *
     * @param toolName 工具名称。
     * @return 返回规范消息网关工具名称结果。
     */
    private String canonicalGatewayToolName(String toolName) {
        String normalized = StrUtil.nullToEmpty(toolName).trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (isCurrentGatewayToolName(lower)) {
            return lower;
        }
        if ("browser".equals(lower)
                || "browser_create".equals(lower)
                || "browser_navigate".equals(lower)
                || "browser_click".equals(lower)
                || "browser_type".equals(lower)
                || "browser_screenshot".equals(lower)
                || "browser_extract".equals(lower)
                || "browser_close".equals(lower)) {
            return ToolNameConstants.BROWSER;
        }
        return lower;
    }

    /**
     * 判断工具网关传入的名称是否为当前公开工具名，避免继续接受历史别名入口。
     *
     * @param toolName 工具名称。
     * @return 当前工具名返回 true。
     */
    private boolean isCurrentGatewayToolName(String toolName) {
        return ToolNameConstants.EXECUTE_SHELL.equals(toolName)
                || ToolNameConstants.EXECUTE_PYTHON.equals(toolName)
                || ToolNameConstants.EXECUTE_JS.equals(toolName)
                || ToolNameConstants.EXECUTE_CODE.equals(toolName)
                || ToolNameConstants.TERMINAL.equals(toolName)
                || ToolNameConstants.PROCESS.equals(toolName)
                || ToolNameConstants.FILE_READ.equals(toolName)
                || ToolNameConstants.FILE_WRITE.equals(toolName)
                || ToolNameConstants.READ_FILE.equals(toolName)
                || ToolNameConstants.WRITE_FILE.equals(toolName)
                || ToolNameConstants.SEARCH_FILES.equals(toolName)
                || ToolNameConstants.FILE_LIST.equals(toolName)
                || ToolNameConstants.FILE_DELETE.equals(toolName)
                || ToolNameConstants.PATCH.equals(toolName)
                || ToolNameConstants.WEBFETCH.equals(toolName)
                || ToolNameConstants.WEBSEARCH.equals(toolName)
                || ToolNameConstants.CODESEARCH.equals(toolName)
                || ToolNameConstants.CONFIG_GET.equals(toolName)
                || ToolNameConstants.CONFIG_SET.equals(toolName)
                || ToolNameConstants.CONFIG_SET_SECRET.equals(toolName);
    }

    /**
     * 执行消息网关工具名称相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回消息网关工具名称结果。
     */
    private String gatewayToolName(Map<String, Object> args) {
        if (args == null) {
            return "";
        }
        Object value = args.get("tool_name");
        if (value == null) {
            value = args.get("name");
        }
        if (value == null) {
            value = args.get("tool");
        }
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 执行消息网关工具参数相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回消息网关工具参数结果。
     */
    @SuppressWarnings("unchecked")
    private GatewayToolArgsResult gatewayToolArgs(Map<String, Object> args) {
        if (args == null) {
            return GatewayToolArgsResult.valid(new LinkedHashMap<String, Object>());
        }
        Object raw = args.get("tool_args");
        if (raw == null) {
            raw = args.get("args");
        }
        if (raw instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return GatewayToolArgsResult.valid(result);
        }
        String text = raw == null ? "" : String.valueOf(raw).trim();
        if (text.length() == 0) {
            return GatewayToolArgsResult.valid(new LinkedHashMap<String, Object>());
        }
        try {
            Object parsed = ONode.deserialize(text, Object.class);
            if (parsed instanceof Map) {
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
                    if (entry.getKey() != null) {
                        result.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                return GatewayToolArgsResult.valid(result);
            }
            return GatewayToolArgsResult.invalid("tool_args 必须是 JSON 对象。", text);
        } catch (Exception e) {
            log.debug("Gateway tool args JSON parsing failed: {}", exceptionSummary(e));
            return GatewayToolArgsResult.invalid("tool_args 不是合法 JSON。", text);
        }
    }

    /**
     * 判断是否消息网关安全工具。
     *
     * @param toolName 工具名称。
     * @return 如果消息网关安全工具满足条件则返回 true，否则返回 false。
     */
    private boolean isGatewaySecurityTool(String toolName) {
        return ToolNameConstants.EXECUTE_SHELL.equals(toolName)
                || ToolNameConstants.EXECUTE_PYTHON.equals(toolName)
                || ToolNameConstants.EXECUTE_JS.equals(toolName)
                || ToolNameConstants.EXECUTE_CODE.equals(toolName)
                || ToolNameConstants.TERMINAL.equals(toolName)
                || ToolNameConstants.PROCESS.equals(toolName)
                || ToolNameConstants.isFileSecurityTool(toolName)
                || isUrlSecurityTool(toolName);
    }

    /**
     * 执行阻断Malformed消息网关工具参数相关逻辑。
     *
     * @param trace trace 参数。
     * @param toolName 工具名称。
     * @param parsedArgs parsedArgs 参数。
     */
    private void blockMalformedGatewayToolArgs(
            ReActTrace trace, String toolName, GatewayToolArgsResult parsedArgs) {
        if (trace == null) {
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append("BLOCKED: 工具网关参数格式无效，无法安全检查内层工具调用。");
        message.append("\n工具：").append(messageRenderer.toolLabel(toolName));
        message.append("\n原因：").append(parsedArgs.getMessage());
        if (StrUtil.isNotBlank(parsedArgs.getRawText())) {
            message.append("\n参数预览：").append(SecretRedactor.redact(parsedArgs.getRawText(), 400));
        }
        message.append("\n请把 tool_args 改为 JSON 对象后再重试。");
        trace.setFinalAnswer(message.toString());
        trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
        persistTraceSnapshot(trace);
    }

    /**
     * 判断是否URL安全工具。
     *
     * @param toolName 工具名称。
     * @return 如果URL安全工具满足条件则返回 true，否则返回 false。
     */
    private boolean isUrlSecurityTool(String toolName) {
        return ToolNameConstants.WEBFETCH.equals(toolName)
                || ToolNameConstants.WEBSEARCH.equals(toolName)
                || ToolNameConstants.CODESEARCH.equals(toolName)
                || ToolNameConstants.BROWSER.equals(toolName);
    }

    /**
     * 清理消息网关Inner Decision After审批。
     *
     * @param trace trace 参数。
     * @param toolName 工具名称。
     * @param result 结果响应或执行结果。
     */
    private void clearGatewayInnerDecisionAfterApproval(
            ReActTrace trace, String toolName, String result) {
        if (trace == null
                || trace.getContext() == null
                || StrUtil.isBlank(toolName)
                || result != null
                || org.noear.solon.ai.agent.Agent.ID_END.equals(trace.getRoute())) {
            return;
        }
        trace.getContext().remove(HITL.DECISION_PREFIX + toolName);
        trace.getContext().remove(HITL.LAST_INTERVENED);
    }

    /**
     * 执行evaluate命令相关逻辑。
     *
     * @param trace trace 参数。
     * @param approvalToolName 审批工具名称参数。
     * @param ruleToolName rule工具名称参数。
     * @param code code 参数。
     * @return 返回evaluate命令结果。
     */
    private String evaluateCommand(
            ReActTrace trace, String approvalToolName, String ruleToolName, String code) {
        DetectionResult hardline = detectHardline(ruleToolName, code);
        if (hardline != null) {
            trace.setFinalAnswer(
                    messageRenderer.buildHardlineMessage(approvalToolName, hardline, code));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }
        return evaluateCommandWithoutHardline(trace, approvalToolName, ruleToolName, code);
    }

    /**
     * 执行evaluateCode命令相关逻辑。
     *
     * @param trace trace 参数。
     * @param approvalToolName 审批工具名称参数。
     * @param code code 参数。
     * @return 返回evaluate Code命令结果。
     */
    private String evaluateCodeCommand(ReActTrace trace, String approvalToolName, String code) {
        DetectionResult hardline = detectHardline(ToolNameConstants.EXECUTE_PYTHON, code);
        if (hardline != null) {
            trace.setFinalAnswer(
                    messageRenderer.buildHardlineMessage(approvalToolName, hardline, code));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }
        return evaluateCommandWithoutHardline(
                trace, approvalToolName, ToolNameConstants.EXECUTE_PYTHON, code);
    }

    /**
     * 执行evaluate命令WithoutHardline相关逻辑。
     *
     * @param trace trace 参数。
     * @param approvalToolName 审批工具名称参数。
     * @param ruleToolName rule工具名称参数。
     * @param code code 参数。
     * @return 返回evaluate命令Without Hardline结果。
     */
    private String evaluateCommandWithoutHardline(
            ReActTrace trace, String approvalToolName, String ruleToolName, String code) {
        String guardrailMode = guardrailMode();

        SecurityPolicyService.FileVerdict fileVerdict = detectHardBlockedCommandPath(code);
        if (fileVerdict != null) {
            trace.setFinalAnswer(messageRenderer.buildFilePolicyMessage(approvalToolName, fileVerdict));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }

        SecurityPolicyService.UrlVerdict urlVerdict = detectHardBlockedCommandUrl(code);
        if (urlVerdict != null) {
            trace.setFinalAnswer(messageRenderer.buildUrlPolicyMessage(urlVerdict));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }

        if ("bypass".equals(guardrailMode)) {
            persistTraceSnapshot(trace);
            return null;
        }

        if (isSessionAutoApprovalEnabled(trace.getSession())) {
            persistTraceSnapshot(trace);
            return null;
        }

        DetectionResult detection = detectCombined(ruleToolName, code);
        if (detection != null) {
            return evaluateDangerousCommandApproval(
                    trace, approvalToolName, code, guardrailMode, detection);
        }

        fileVerdict = detectApprovalRequiredCommandPath(code);
        if (fileVerdict != null) {
            return evaluatePolicyApproval(trace, approvalToolName, filePolicyDetection(fileVerdict));
        }

        urlVerdict = detectApprovalRequiredCommandUrl(code);
        if (urlVerdict != null) {
            return evaluatePolicyApproval(trace, approvalToolName, urlPolicyDetection(urlVerdict));
        }

        persistTraceSnapshot(trace);
        return null;
    }

    /**
     * 执行危险命令审批主流程。
     *
     * @param trace trace 参数。
     * @param approvalToolName 审批工具名称参数。
     * @param code code 参数。
     * @param guardrailMode 安全护栏模式参数。
     * @param detection 危险命令检测结果。
     * @return 返回审批提示或 null。
     */
    private String evaluateDangerousCommandApproval(
            ReActTrace trace,
            String approvalToolName,
            String code,
            String guardrailMode,
            DetectionResult detection) {
        SecurityPolicyService.FileVerdict fileVerdict = detectUnsafeCommandPath(code);
        if (fileVerdict != null && !fileVerdict.isApprovalRequired()) {
            trace.setFinalAnswer(messageRenderer.buildFilePolicyMessage(approvalToolName, fileVerdict));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }

        SecurityPolicyService.UrlVerdict urlVerdict = detectUnsafeCommandUrl(code);
        if (urlVerdict != null && !urlVerdict.isApprovalRequired()) {
            trace.setFinalAnswer(messageRenderer.buildUrlPolicyMessage(urlVerdict));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }

        if ("strict".equals(guardrailMode)) {
            trace.setFinalAnswer(
                    messageRenderer.buildStrictDeniedMessage(approvalToolName, detection, code));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }

        String approvalKey = combinedApprovalKey(approvalToolName, detection);
        PendingApproval pending = getPendingApproval(trace.getSession());
        if (trace.getContext().getAs(HITL.DECISION_PREFIX + approvalToolName) != null) {
            if ((pending != null && approvalKey.equals(pending.approvalKey()))
                    || consumeOnceApproval(trace.getContext(), approvalKey)) {
                markCurrentThreadApproval(approvalToolName, code);
                removePendingApproval(trace.getSession(), pending);
                persistTraceSnapshot(trace);
                return null;
            }
            trace.getContext().remove(HITL.DECISION_PREFIX + approvalToolName);
            persistTraceSnapshot(trace);
        }

        if (isApproved(trace.getContext(), approvalKey)) {
            if (pending != null && approvalKey.equals(pending.approvalKey())) {
                removePendingApproval(trace.getSession(), pending);
            }
            markCurrentThreadApproval(approvalToolName, code);
            persistTraceSnapshot(trace);
            return null;
        }

        SmartApprovalDecision smartDecision =
                "smart".equals(guardrailMode)
                        ? smartApprove(approvalToolName, code, detection, trace.getContext())
                        : null;
        if (smartDecision != null && smartDecision.isApproved()) {
            if (pending != null && approvalKey.equals(pending.approvalKey())) {
                removePendingApproval(trace.getSession(), pending);
            }
            markCurrentThreadApproval(approvalToolName, code);
            persistTraceSnapshot(trace);
            return null;
        }
        if (smartDecision != null && smartDecision.isDenied()) {
            trace.setFinalAnswer(messageRenderer.buildSmartDeniedMessage(detection, smartDecision));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }

        if (isSubagentRun()) {
            if (isSubagentAutoApproveEnabled()) {
                markCurrentThreadApproval(approvalToolName, code);
                persistTraceSnapshot(trace);
                return null;
            }
            trace.setFinalAnswer(messageRenderer.buildSubagentDeniedMessage(detection));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }

        Map<String, Object> pendingMap = createPendingMap(approvalToolName, detection, code);
        storePendingMap(trace.getSession(), pendingMap);
        trace.getSession().pending(true, "dangerous_command_approval");
        persistTraceSnapshot(trace);
        notifyApprovalRequest(trace.getSession(), toPendingApproval(pendingMap));
        return messageRenderer.buildPendingMessage(approvalToolName, detection, code);
    }

    /**
     * 执行smartApprove相关逻辑。
     *
     * @param toolName 工具名称。
     * @param code code 参数。
     * @param detection detection 参数。
     * @param context 当前请求或运行上下文。
     * @return 返回smart Approve结果。
     */
    private SmartApprovalDecision smartApprove(
            String toolName, String code, DetectionResult detection, FlowContext context) {
        if (smartApprovalJudge == null || detection == null) {
            return null;
        }
        try {
            SmartApprovalDecision decision =
                    smartApprovalJudge.judge(toolName, code, detection.getDescription());
            if (decision == null || !decision.isApproved()) {
                return decision;
            }
            for (String patternKey : detection.effectivePatternKeys()) {
                addSessionApproval(context, approvalPattern(toolName, patternKey));
            }
            return decision;
        } catch (Exception e) {
            log.debug(
                    "Smart approval judge failed; falling back to manual approval handling: {}",
                    exceptionSummary(e));
            return null;
        }
    }

    /**
     * 追加Once审批。
     *
     * @param context 当前请求或运行上下文。
     * @param approvalKey 审批键标识或键值。
     */
    private void addOnceApproval(FlowContext context, String approvalKey) {
        if (context == null || StrUtil.isBlank(approvalKey)) {
            return;
        }
        Set<String> approvals = loadOnceApprovals(context);
        approvals.add(approvalKey.trim());
        context.put(CONTEXT_ONCE_APPROVALS, new ArrayList<String>(approvals));
    }

    /**
     * 消费Once审批。
     *
     * @param context 当前请求或运行上下文。
     * @param approvalKey 审批键标识或键值。
     * @return 返回consume Once审批结果。
     */
    private boolean consumeOnceApproval(FlowContext context, String approvalKey) {
        if (context == null || StrUtil.isBlank(approvalKey)) {
            return false;
        }
        Set<String> approvals = loadOnceApprovals(context);
        boolean consumed = approvals.remove(approvalKey.trim());
        if (consumed) {
            if (approvals.isEmpty()) {
                context.remove(CONTEXT_ONCE_APPROVALS);
            } else {
                context.put(CONTEXT_ONCE_APPROVALS, new ArrayList<String>(approvals));
            }
        }
        return consumed;
    }

    /**
     * 加载Once Approvals。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回Once Approvals结果。
     */
    @SuppressWarnings("unchecked")
    private Set<String> loadOnceApprovals(FlowContext context) {
        Set<String> approvals = new LinkedHashSet<String>();
        if (context == null) {
            return approvals;
        }
        Object raw = context.get(CONTEXT_ONCE_APPROVALS);
        if (raw instanceof Collection) {
            for (Object value : (Collection<Object>) raw) {
                if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                    approvals.add(String.valueOf(value).trim());
                }
            }
            return approvals;
        }
        String text = raw == null ? "" : String.valueOf(raw).trim();
        if (text.length() == 0) {
            return approvals;
        }
        try {
            Object parsed = ONode.deserialize(text, Object.class);
            if (parsed instanceof Collection) {
                for (Object value : (Collection<Object>) parsed) {
                    if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                        approvals.add(String.valueOf(value).trim());
                    }
                }
                return approvals;
            }
        } catch (Exception e) {
            log.debug("Once approval JSON parsing failed; treating raw text as approval key: {}", exceptionSummary(e));
        }
        approvals.add(text);
        return approvals;
    }

    /**
     * 执行detectCombined相关逻辑。
     *
     * @param toolName 工具名称。
     * @param code code 参数。
     * @return 返回detect Combined结果。
     */
    private DetectionResult detectCombined(String toolName, String code) {
        DetectionResult local = detect(toolName, code);
        TirithSecurityService.ScanResult scan = scanWithTirith(toolName, code);
        if (scan == null || !scan.requiresApproval()) {
            return local;
        }

        List<String> keys = new ArrayList<String>();
        List<String> descriptions = new ArrayList<String>();
        keys.addAll(tirithPatternKeys(scan));
        descriptions.add(tirithDescription(scan));
        if (local != null) {
            keys.addAll(local.effectivePatternKeys());
            descriptions.add(local.getDescription());
        }

        DetectionResult combined = new DetectionResult();
        combined.setPatternKeys(unique(keys));
        combined.setPatternKey(
                combined.getPatternKeys().isEmpty()
                        ? "tirith:security_scan"
                        : combined.getPatternKeys().get(0));
        combined.setDescription(joinDescriptions(descriptions));
        combined.setNormalizedCode(normalize(code));
        return combined;
    }

    /**
     * 执行scanWithTirith相关逻辑。
     *
     * @param toolName 工具名称。
     * @param code code 参数。
     * @return 返回scan With Tirith结果。
     */
    private TirithSecurityService.ScanResult scanWithTirith(String toolName, String code) {
        if (tirithSecurityService == null) {
            return null;
        }
        return tirithSecurityService.checkCommandSecurityForTool(toolName, code);
    }

    /**
     * 执行tirithPatternKeys相关逻辑。
     *
     * @param scan scan 参数。
     * @return 返回tirith Pattern Keys结果。
     */
    private List<String> tirithPatternKeys(TirithSecurityService.ScanResult scan) {
        List<String> keys = new ArrayList<String>();
        if (scan != null) {
            for (TirithSecurityService.Finding finding : scan.getFindings()) {
                if (finding != null && StrUtil.isNotBlank(finding.getRuleId())) {
                    keys.add("tirith:" + finding.getRuleId().trim());
                }
            }
        }
        if (keys.isEmpty()) {
            keys.add("tirith:security_scan");
        }
        return keys;
    }

    /**
     * 执行tirithDescription相关逻辑。
     *
     * @param scan scan 参数。
     * @return 返回tirith Description结果。
     */
    private String tirithDescription(TirithSecurityService.ScanResult scan) {
        StringBuilder buffer = new StringBuilder("Security scan");
        if (scan != null && StrUtil.isNotBlank(scan.getAction())) {
            buffer.append(" ").append(scan.getAction());
        }
        if (scan != null && StrUtil.isNotBlank(scan.getSummary())) {
            buffer.append(": ").append(scan.getSummary().trim());
        }
        if (scan != null && !scan.getFindings().isEmpty()) {
            buffer.append(" (");
            int count = 0;
            for (TirithSecurityService.Finding finding : scan.getFindings()) {
                String label = tirithFindingLabel(finding);
                if (StrUtil.isBlank(label)) {
                    continue;
                }
                if (count > 0) {
                    buffer.append("; ");
                }
                buffer.append(label);
                count++;
                if (count >= 3) {
                    break;
                }
            }
            buffer.append(")");
        }
        return buffer.toString();
    }

    /**
     * 执行tirithFindingLabel相关逻辑。
     *
     * @param finding finding 参数。
     * @return 返回tirith Finding Label结果。
     */
    private String tirithFindingLabel(TirithSecurityService.Finding finding) {
        if (finding == null) {
            return "";
        }
        String label =
                StrUtil.blankToDefault(
                        finding.getTitle(),
                        StrUtil.blankToDefault(finding.getRuleId(), finding.getDescription()));
        if (StrUtil.isNotBlank(finding.getSeverity())) {
            label = finding.getSeverity() + " " + label;
        }
        return StrUtil.nullToEmpty(label).trim();
    }

    /**
     * 执行unique相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回unique结果。
     */
    private List<String> unique(Collection<String> values) {
        List<String> result = new ArrayList<String>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            if (StrUtil.isBlank(value)) {
                continue;
            }
            String trimmed = value.trim();
            if (!result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 执行joinDescriptions相关逻辑。
     *
     * @param descriptions descriptions 参数。
     * @return 返回join Descriptions结果。
     */
    private String joinDescriptions(Collection<String> descriptions) {
        StringBuilder buffer = new StringBuilder();
        if (descriptions != null) {
            for (String description : descriptions) {
                if (StrUtil.isBlank(description)) {
                    continue;
                }
                if (buffer.length() > 0) {
                    buffer.append("; ");
                }
                buffer.append(description.trim());
            }
        }
        return buffer.length() == 0 ? "Security scan warning" : buffer.toString();
    }

    /**
     * 执行detectUn安全命令路径相关逻辑。
     *
     * @param code code 参数。
     * @return 返回detect Unsafe命令路径。
     */
    private SecurityPolicyService.FileVerdict detectUnsafeCommandPath(String code) {
        if (securityPolicyService == null) {
            return null;
        }
        if (!SolonClawCodeExecutionSkills.isFileGuardrailEnabled(appConfig)) {
            return null;
        }
        SecurityPolicyService.FileVerdict verdict = securityPolicyService.checkCommandPaths(code);
        return verdict.isAllowed() ? null : verdict;
    }

    /**
     * 检测命令中的硬阻断文件策略，不消费一次性策略审批。
     *
     * @param code code 参数。
     * @return 返回硬阻断文件策略。
     */
    private SecurityPolicyService.FileVerdict detectHardBlockedCommandPath(String code) {
        SecurityPolicyService.FileVerdict verdict =
                SecurityPolicyService.previewPolicyApprovals(
                        () -> detectUnsafeCommandPath(code));
        return verdict != null && !verdict.isApprovalRequired() ? verdict : null;
    }

    /**
     * 检测命令中需要人工审批的文件策略，不消费线程内策略审批。
     *
     * @param code code 参数。
     * @return 返回需审批文件策略。
     */
    private SecurityPolicyService.FileVerdict detectApprovalRequiredCommandPath(String code) {
        SecurityPolicyService.FileVerdict verdict =
                SecurityPolicyService.previewPolicyApprovals(
                        () -> detectUnsafeCommandPath(code));
        return verdict != null && verdict.isApprovalRequired() ? verdict : null;
    }

    /**
     * 执行detectUn安全命令URL相关逻辑。
     *
     * @param code code 参数。
     * @return 返回detect Unsafe命令URL结果。
     */
    private SecurityPolicyService.UrlVerdict detectUnsafeCommandUrl(String code) {
        if (securityPolicyService == null) {
            return null;
        }
        if (!SolonClawCodeExecutionSkills.isUrlGuardrailEnabled(appConfig)) {
            return null;
        }
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkCommandUrls(code);
        return verdict.isAllowed() ? null : verdict;
    }

    /**
     * 检测命令中的硬阻断 URL 策略，不消费一次性策略审批。
     *
     * @param code code 参数。
     * @return 返回硬阻断 URL 策略。
     */
    private SecurityPolicyService.UrlVerdict detectHardBlockedCommandUrl(String code) {
        SecurityPolicyService.UrlVerdict verdict =
                SecurityPolicyService.previewPolicyApprovals(() -> detectUnsafeCommandUrl(code));
        return verdict != null && !verdict.isApprovalRequired() ? verdict : null;
    }

    /**
     * 检测命令中需要人工审批的 URL 策略，不消费线程内策略审批。
     *
     * @param code code 参数。
     * @return 返回需审批 URL 策略。
     */
    private SecurityPolicyService.UrlVerdict detectApprovalRequiredCommandUrl(String code) {
        SecurityPolicyService.UrlVerdict verdict =
                SecurityPolicyService.previewPolicyApprovals(() -> detectUnsafeCommandUrl(code));
        return verdict != null && verdict.isApprovalRequired() ? verdict : null;
    }

    /**
     * 执行evaluate文件工具相关逻辑。
     *
     * @param trace trace 参数。
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     * @return 返回evaluate文件工具结果。
     */
    private String evaluateFileTool(ReActTrace trace, String toolName, Map<String, Object> args) {
        if (securityPolicyService == null) {
            return null;
        }
        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs(toolName, args);
        if (verdict.isAllowed()) {
            return null;
        }
        if (verdict.isApprovalRequired()) {
            return evaluatePolicyApproval(trace, toolName, filePolicyDetection(verdict));
        }
        trace.setFinalAnswer(messageRenderer.buildFilePolicyMessage(toolName, verdict));
        trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
        persistTraceSnapshot(trace);
        return null;
    }

    /**
     * 执行evaluateURL工具相关逻辑。
     *
     * @param trace trace 参数。
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     * @return 返回evaluate URL工具结果。
     */
    private String evaluateUrlTool(ReActTrace trace, String toolName, Map<String, Object> args) {
        if (securityPolicyService == null) {
            return null;
        }
        SecurityPolicyService.UrlVerdict verdict =
                securityPolicyService.checkToolArgs(toolName, args);
        if (verdict.isAllowed()) {
            return null;
        }
        if (verdict.isApprovalRequired()) {
            return evaluatePolicyApproval(trace, toolName, urlPolicyDetection(verdict));
        }
        trace.setFinalAnswer(messageRenderer.buildUrlPolicyMessage(verdict));
        trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
        persistTraceSnapshot(trace);
        return null;
    }

    /**
     * 执行文件或 URL 策略的人工审批流程，支持本次、会话和永久同类审批。
     *
     * @param trace trace 参数。
     * @param toolName 工具名称。
     * @param detection 审批检测结果。
     * @return 返回待用户审批消息或 null。
     */
    private String evaluatePolicyApproval(
            ReActTrace trace, String toolName, DetectionResult detection) {
        if (trace == null || detection == null) {
            return null;
        }
        String approvalKey = combinedApprovalKey(toolName, detection);
        PendingApproval pending = getPendingApproval(trace.getSession());
        if (trace.getContext() != null
                && trace.getContext().getAs(HITL.DECISION_PREFIX + toolName) != null) {
            if ((pending != null && approvalKey.equals(pending.approvalKey()))
                    || consumeOnceApproval(trace.getContext(), approvalKey)) {
                markCurrentThreadPolicyApproval(detection);
                removePendingApproval(trace.getSession(), pending);
                persistTraceSnapshot(trace);
                return null;
            }
            trace.getContext().remove(HITL.DECISION_PREFIX + toolName);
            persistTraceSnapshot(trace);
        }
        if (isApproved(trace.getContext(), approvalKey)) {
            if (pending != null && approvalKey.equals(pending.approvalKey())) {
                removePendingApproval(trace.getSession(), pending);
            }
            markCurrentThreadPolicyApproval(detection);
            persistTraceSnapshot(trace);
            return null;
        }
        if (isSubagentRun()) {
            trace.setFinalAnswer(messageRenderer.buildSubagentDeniedMessage(detection));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }
        Map<String, Object> pendingMap =
                createPendingMap(toolName, detection, detection.getNormalizedCode());
        storePendingMap(trace.getSession(), pendingMap);
        trace.getSession().pending(true, "dangerous_command_approval");
        persistTraceSnapshot(trace);
        notifyApprovalRequest(trace.getSession(), toPendingApproval(pendingMap));
        return messageRenderer.buildPendingMessage(toolName, detection, detection.getNormalizedCode());
    }

    /**
     * 把文件策略判定转换为审批检测结果。
     *
     * @param verdict 文件策略判定。
     * @return 返回检测结果。
     */
    private DetectionResult filePolicyDetection(SecurityPolicyService.FileVerdict verdict) {
        DetectionResult detection = new DetectionResult();
        detection.setPatternKey(POLICY_PATTERN_PREFIX + verdict.getPolicyKey());
        detection.setDescription(verdict.getMessage());
        detection.setNormalizedCode(StrUtil.nullToEmpty(verdict.getPath()));
        return detection;
    }

    /**
     * 把 URL 策略判定转换为审批检测结果。
     *
     * @param verdict URL 策略判定。
     * @return 返回检测结果。
     */
    private DetectionResult urlPolicyDetection(SecurityPolicyService.UrlVerdict verdict) {
        DetectionResult detection = new DetectionResult();
        detection.setPatternKey(POLICY_PATTERN_PREFIX + verdict.getPolicyKey());
        detection.setDescription(verdict.getMessage());
        detection.setNormalizedCode(StrUtil.nullToEmpty(verdict.getUrl()));
        return detection;
    }

    /**
     * 标记当前线程通过的一次性文件或 URL 策略。
     *
     * @param detection 审批检测结果。
     */
    private void markCurrentThreadPolicyApproval(DetectionResult detection) {
        if (detection == null) {
            return;
        }
        for (String patternKey : detection.effectivePatternKeys()) {
            if (POLICY_WORKSPACE_OUTSIDE_WRITE.equals(patternKey)) {
                SecurityPolicyService.approveFilePolicyForCurrentThread(
                        "workspace_outside_write", detection.getNormalizedCode());
            }
        }
    }

    /**
     * 执行persistTraceSnapshot相关逻辑。
     *
     * @param trace trace 参数。
     */
    private void persistTraceSnapshot(ReActTrace trace) {
        if (trace != null && trace.getSession() != null) {
            trace.getSession().updateSnapshot();
        }
    }

    /**
     * 执行notify审批请求相关逻辑。
     *
     * @param session 会话参数。
     * @param pending 待恢复参数。
     */
    private void notifyApprovalRequest(AgentSession session, PendingApproval pending) {
        if (pending == null || approvalObservers.isEmpty()) {
            return;
        }
        ApprovalRequestEvent event = new ApprovalRequestEvent(sessionId(session), pending);
        for (ApprovalObserver observer : approvalObservers) {
            try {
                observer.onApprovalRequest(event);
            } catch (Exception e) {
                log.debug("Approval request observer failed; continuing other observers: {}", exceptionSummary(e));
                // 保留此处实现约束，避免后续维护时破坏既有行为。
            }
        }
    }

    /**
     * 执行notify审批响应相关逻辑。
     *
     * @param session 会话参数。
     * @param pending 待恢复参数。
     * @param choice choice 参数。
     * @param approver approver 参数。
     */
    private void notifyApprovalResponse(
            AgentSession session, PendingApproval pending, String choice, String approver) {
        if (pending == null || approvalObservers.isEmpty()) {
            return;
        }
        ApprovalResponseEvent event =
                new ApprovalResponseEvent(
                        sessionId(session), pending, StrUtil.nullToEmpty(choice), approver);
        for (ApprovalObserver observer : approvalObservers) {
            try {
                observer.onApprovalResponse(event);
            } catch (Exception e) {
                log.debug("Approval response observer failed; continuing other observers: {}", exceptionSummary(e));
                // 保留此处实现约束，避免后续维护时破坏既有行为。
            }
        }
    }

    /**
     * 执行会话标识相关逻辑。
     *
     * @param session 会话参数。
     * @return 返回会话标识。
     */
    private String sessionId(AgentSession session) {
        if (session == null) {
            return "";
        }
        return StrUtil.nullToEmpty(session.getSessionId());
    }

    /**
     * 读取当前 Agent 工具安全护栏模式。
     *
     * @return 返回标准护栏模式。
     */
    public String guardrailMode() {
        return normalizeGuardrailMode(
                appConfig == null || appConfig.getSecurity() == null
                        ? ""
                        : appConfig.getSecurity().getGuardrailMode());
    }

    /**
     * 规范化面向用户展示的安全护栏枚举，只接受当前配置文件声明的取值。
     *
     * @param raw 原始配置值。
     * @return 返回标准护栏模式。
     */
    private String normalizeGuardrailMode(String raw) {
        String mode = StrUtil.blankToDefault(raw, "approval").trim().toLowerCase(Locale.ROOT);
        if ("bypass".equals(mode)
                || "approval".equals(mode)
                || "strict".equals(mode)
                || "smart".equals(mode)) {
            return mode;
        }
        throw new IllegalStateException(
                "security.guardrailMode 只支持 approval、strict、bypass、smart，当前值：" + raw);
    }

    /**
     * 判断是否Subagent Auto Approve 启用。
     *
     * @return 如果Subagent Auto Approve 启用满足条件则返回 true，否则返回 false。
     */
    public boolean isSubagentAutoApproveEnabled() {
        return appConfig != null
                && appConfig.getApprovals() != null
                && appConfig.getApprovals().isSubagentAutoApprove();
    }

    /**
     * 判断是否Sudo密码Configured。
     *
     * @return 如果Sudo密码Configured满足条件则返回 true，否则返回 false。
     */
    private boolean isSudoPasswordConfigured() {
        return appConfig != null
                && appConfig.getTerminal() != null
                && StrUtil.isNotBlank(appConfig.getTerminal().getSudoPassword());
    }

    /**
     * 执行max前台进程TimeoutSeconds相关逻辑。
     *
     * @return 返回max Foreground Timeout Seconds结果。
     */
    private int maxForegroundTimeoutSeconds() {
        return appConfig == null || appConfig.getTerminal() == null
                ? 0
                : appConfig.getTerminal().getMaxForegroundTimeoutSeconds();
    }

    /**
     * 执行前台进程MaxRetries相关逻辑。
     *
     * @return 返回foreground Max Retries结果。
     */
    private int foregroundMaxRetries() {
        return appConfig == null || appConfig.getTerminal() == null
                ? 0
                : appConfig.getTerminal().getForegroundMaxRetries();
    }

    /**
     * 执行前台进程重试基础DelaySeconds相关逻辑。
     *
     * @return 返回foreground Retry Base Delay Seconds结果。
     */
    private int foregroundRetryBaseDelaySeconds() {
        return appConfig == null || appConfig.getTerminal() == null
                ? 0
                : appConfig.getTerminal().getForegroundRetryBaseDelaySeconds();
    }

    /**
     * 标记当前Thread审批。
     *
     * @param toolName 工具名称。
     * @param command 待执行或解析的命令文本。
     */
    private void markCurrentThreadApproval(String toolName, String command) {
        grantCurrentThreadApproval(toolName, command);
    }

    /**
     * 执行grant当前Thread审批相关逻辑。
     *
     * @param toolName 工具名称。
     * @param command 待执行或解析的命令文本。
     */
    public static void grantCurrentThreadApproval(String toolName, String command) {
        if (StrUtil.hasBlank(toolName, command)) {
            return;
        }
        Map<String, Long> approvals = CURRENT_THREAD_APPROVED_COMMANDS.get();
        if (approvals == null) {
            approvals = new LinkedHashMap<String, Long>();
            CURRENT_THREAD_APPROVED_COMMANDS.set(approvals);
        }
        approvals.put(
                currentThreadApprovalKey(toolName, command),
                Long.valueOf(System.currentTimeMillis() + CURRENT_THREAD_APPROVAL_TTL_MILLIS));
    }

    /**
     * 执行当前Thread审批键相关逻辑。
     *
     * @param toolName 工具名称。
     * @param command 待执行或解析的命令文本。
     * @return 返回当前Thread审批键结果。
     */
    private static String currentThreadApprovalKey(String toolName, String command) {
        return StrUtil.nullToEmpty(toolName).trim()
                + ":"
                + DangerousCommandTextSupport.normalizeCommand(command);
    }

    /**
     * 移除Expired当前Thread Approvals。
     *
     * @param approvals approvals 参数。
     */
    private static void removeExpiredCurrentThreadApprovals(Map<String, Long> approvals) {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<String>();
        for (Map.Entry<String, Long> entry : approvals.entrySet()) {
            Long expiresAt = entry.getValue();
            if (expiresAt == null || expiresAt.longValue() < now) {
                expired.add(entry.getKey());
            }
        }
        for (String key : expired) {
            approvals.remove(key);
        }
    }

    /**
     * 写入当前会话自动审批状态。
     *
     * @param session 会话参数。
     * @param enabled 启用状态开关值。
     * @return 返回会话自动审批结果。
     */
    private boolean setSessionAutoApproval(AgentSession session, boolean enabled)
            throws Exception {
        if (session == null) {
            return false;
        }
        if (enabled) {
            session.getContext().put(CONTEXT_SESSION_AUTO_APPROVAL, Boolean.TRUE);
        } else {
            session.getContext().remove(CONTEXT_SESSION_AUTO_APPROVAL);
        }
        session.updateSnapshot();
        return enabled;
    }

    /**
     * 执行truthy相关逻辑。
     *
     * @param raw 原始输入值。
     * @return 返回truthy结果。
     */
    private boolean truthy(Object raw) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof Boolean) {
            return ((Boolean) raw).booleanValue();
        }
        String value = String.valueOf(raw).trim();
        return "true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value);
    }

    /**
     * 执行定时任务审批模式相关逻辑。
     *
     * @return 返回定时任务审批模式结果。
     */
    public String guardrailCronMode() {
        String mode =
                appConfig == null || appConfig.getSecurity() == null
                        ? ""
                        : appConfig.getSecurity().getGuardrailCronMode();
        return normalizeCronApprovalMode(mode);
    }

    /**
     * 规范化定时任务审批模式。
     *
     * @param raw 原始输入值。
     * @return 返回定时任务审批模式结果。
     */
    private String normalizeCronApprovalMode(String raw) {
        String mode = StrUtil.blankToDefault(raw, "strict").trim().toLowerCase(Locale.ROOT);
        if ("approval".equals(mode)
                || "strict".equals(mode)
                || "bypass".equals(mode)
                || "approve".equals(mode)) {
            return mode;
        }
        throw new IllegalStateException(
                "security.guardrailCronMode 只支持 approval、strict、bypass、approve，当前值：" + raw);
    }

    /**
     * 判断是否Subagent运行。
     *
     * @return 如果Subagent运行满足条件则返回 true，否则返回 false。
     */
    private boolean isSubagentRun() {
        AgentRunContext current = AgentRunContext.current();
        return current != null
                && "subagent".equalsIgnoreCase(StrUtil.nullToEmpty(current.getRunKind()));
    }

    /**
     * 执行审批TimeoutSeconds相关逻辑。
     *
     * @return 返回审批Timeout Seconds结果。
     */
    public int approvalTimeoutSeconds() {
        int value =
                appConfig == null || appConfig.getApprovals() == null
                        ? 60
                        : appConfig.getApprovals().getTimeoutSeconds();
        return value > 0 ? value : 60;
    }

    /**
     * 执行审批消息网关TimeoutSeconds相关逻辑。
     *
     * @return 返回审批消息网关Timeout Seconds结果。
     */
    public int approvalGatewayTimeoutSeconds() {
        int value =
                appConfig == null || appConfig.getApprovals() == null
                        ? 300
                        : appConfig.getApprovals().getGatewayTimeoutSeconds();
        return value > 0 ? value : Math.max(approvalTimeoutSeconds(), 300);
    }

    /**
     * 执行审批消息网关TimeoutMillis相关逻辑。
     *
     * @return 返回审批消息网关Timeout Millis结果。
     */
    private long approvalGatewayTimeoutMillis() {
        return approvalGatewayTimeoutSeconds() * 1000L;
    }

    /**
     * 创建Pending Map。
     *
     * @param toolName 工具名称。
     * @param detection detection 参数。
     * @param code code 参数。
     * @return 返回创建好的Pending Map。
     */
    private Map<String, Object> createPendingMap(
            String toolName, DetectionResult detection, String code) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("approvalId", IdSupport.newId());
        payload.put("toolName", cleanApprovalText(toolName));
        payload.put("patternKey", cleanApprovalText(detection.getPatternKey()));
        payload.put("patternKeys", cleanApprovalList(detection.effectivePatternKeys()));
        payload.put("description", detection.getDescription());
        payload.put("command", StrUtil.nullToEmpty(code));
        payload.put("commandHash", commandHash(detection.getNormalizedCode()));
        payload.put("approvalKey", combinedApprovalKey(toolName, detection));
        payload.put("onceOnly", Boolean.valueOf(detection.isOnceOnly()));
        payload.put("createdAt", System.currentTimeMillis());
        payload.put("expiresAt", System.currentTimeMillis() + approvalGatewayTimeoutMillis());
        return payload;
    }

    /**
     * 存储待恢复映射。
     *
     * @param session 会话参数。
     * @param pendingMap 待恢复映射参数。
     */
    private void storePendingMap(AgentSession session, Map<String, Object> pendingMap) {
        if (session == null || pendingMap == null) {
            return;
        }
        List<Map<String, Object>> queue = pendingMapQueueFrom(session.getContext());
        String approvalKey = stringValue(pendingMap.get("approvalKey"));
        boolean replaced = false;
        for (int i = 0; i < queue.size(); i++) {
            PendingApproval item = toPendingApproval(queue.get(i));
            if (item != null && approvalKey.equals(item.approvalKey())) {
                queue.set(i, pendingMap);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            queue.add(pendingMap);
        }
        session.getContext().put(CONTEXT_PENDING_APPROVAL_QUEUE, queue);
    }

    /**
     * 执行prune待恢复Approvals相关逻辑。
     *
     * @param session 会话参数。
     * @param pending 待恢复参数。
     * @return 返回prune Pending Approvals结果。
     */
    private boolean prunePendingApprovals(AgentSession session, List<PendingApproval> pending) {
        if (session == null) {
            return false;
        }
        List<PendingApproval> active = filterActivePendingApprovals(pending);
        if (active.size() == pending.size()) {
            return false;
        }
        for (PendingApproval item : pending) {
            if (item != null && isPendingExpired(item)) {
                notifyApprovalResponse(session, item, "timeout", "");
            }
        }
        writePendingApprovals(session, active);
        session.updateSnapshot();
        return true;
    }

    /**
     * 执行过滤器Active待恢复Approvals相关逻辑。
     *
     * @param pending 待恢复参数。
     * @return 返回filter Active Pending Approvals结果。
     */
    private List<PendingApproval> filterActivePendingApprovals(List<PendingApproval> pending) {
        List<PendingApproval> active = new ArrayList<PendingApproval>();
        for (PendingApproval item : pending) {
            if (item != null && !isPendingExpired(item)) {
                active.add(item);
            }
        }
        return active;
    }

    /**
     * 移除Pending审批。
     *
     * @param session 会话参数。
     * @param target target 参数。
     */
    private void removePendingApproval(AgentSession session, PendingApproval target) {
        if (session == null || target == null) {
            return;
        }
        List<PendingApproval> retained = new ArrayList<PendingApproval>();
        for (PendingApproval item : pendingQueueFrom(session.getContext())) {
            if (!samePendingApproval(item, target)) {
                retained.add(item);
            }
        }
        writePendingApprovals(session, retained);
    }

    /**
     * 写入Pending Approvals。
     *
     * @param session 会话参数。
     * @param pending 待恢复参数。
     */
    private void writePendingApprovals(AgentSession session, List<PendingApproval> pending) {
        List<Map<String, Object>> queue = new ArrayList<Map<String, Object>>();
        for (PendingApproval item : pending) {
            if (item != null && !isPendingExpired(item)) {
                queue.add(pendingMap(item));
            }
        }
        if (queue.isEmpty()) {
            session.getContext().remove(CONTEXT_PENDING_APPROVAL_QUEUE);
            return;
        }
        session.getContext().put(CONTEXT_PENDING_APPROVAL_QUEUE, queue);
    }

    /**
     * 查找Pending审批。
     *
     * @param session 会话参数。
     * @param selector 浏览器元素选择器。
     * @return 返回Pending审批结果。
     */
    private PendingApproval findPendingApproval(AgentSession session, String selector) {
        List<PendingApproval> pending = listPendingApprovals(session);
        if (pending.isEmpty()) {
            return null;
        }
        if (StrUtil.isBlank(selector)) {
            return pending.get(0);
        }
        String normalized = SecretRedactor.stripDisplayControls(selector).trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        try {
            int index = Integer.parseInt(normalized);
            if (index >= 1 && index <= pending.size()) {
                return pending.get(index - 1);
            }
        } catch (Exception e) {
            log.debug("Approval selector index parsing failed; trying selector match: {}", exceptionSummary(e));
            // 保留此处实现约束，避免后续维护时破坏既有行为。
        }
        for (PendingApproval item : pending) {
            if (selectorMatches(item, normalized)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 执行选择器Matches相关逻辑。
     *
     * @param item item 参数。
     * @param selector 浏览器元素选择器。
     * @return 返回selector Matches结果。
     */
    private boolean selectorMatches(PendingApproval item, String selector) {
        if (item == null || StrUtil.isBlank(selector)) {
            return false;
        }
        String value = SecretRedactor.stripDisplayControls(selector).trim();
        String approvalId = item.getApprovalId();
        String safeApprovalId = safeApprovalSelectorToken(approvalId);
        String approvalKey = item.approvalKey();
        String opaqueSelector = approvalSelector(item);
        return (StrUtil.isNotBlank(safeApprovalId) && value.equals(safeApprovalId))
                || value.equals(opaqueSelector)
                || (StrUtil.isNotBlank(safeApprovalId)
                        && value.length() >= APPROVAL_SELECTOR_PREFIX_MIN_LENGTH
                        && safeApprovalId.startsWith(value))
                || (StrUtil.isNotBlank(opaqueSelector)
                        && value.length() >= APPROVAL_SELECTOR_PREFIX_MIN_LENGTH
                        && opaqueSelector.startsWith(value));
    }

    /**
     * 执行审批选择器相关逻辑。
     *
     * @param pending 待恢复参数。
     * @return 返回审批Selector结果。
     */
    public static String approvalSelector(PendingApproval pending) {
        if (pending == null) {
            return "";
        }
        String safeApprovalId = safeApprovalSelectorToken(pending.getApprovalId());
        if (StrUtil.isNotBlank(safeApprovalId)) {
            return safeApprovalId;
        }
        return approvalSelectorFromKey(pending.approvalKey());
    }

    /**
     * 生成安全展示用的审批选择器。
     *
     * @param pending 待恢复参数。
     * @return 返回safe审批Selector结果。
     */
    private static String safeApprovalSelector(PendingApproval pending) {
        return approvalSelector(pending);
    }

    /**
     * 执行审批选择器From键相关逻辑。
     *
     * @param approvalKey 审批键标识或键值。
     * @return 返回审批Selector From键结果。
     */
    public static String approvalSelectorFromKey(String approvalKey) {
        String value = StrUtil.nullToEmpty(approvalKey).trim();
        return value.isEmpty() ? "" : "key_" + sha256Hex(value).substring(0, 24);
    }

    /**
     * 执行same待恢复审批相关逻辑。
     *
     * @param left 左侧比较对象。
     * @param right 右侧比较对象。
     * @return 返回same Pending审批结果。
     */
    private boolean samePendingApproval(PendingApproval left, PendingApproval right) {
        if (left == null || right == null) {
            return false;
        }
        if (StrUtil.isNotBlank(left.getApprovalId()) && StrUtil.isNotBlank(right.getApprovalId())) {
            return left.getApprovalId().equals(right.getApprovalId());
        }
        return left.approvalKey().equals(right.approvalKey());
    }

    /**
     * 执行待恢复队列From相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回pending Queue From结果。
     */
    private List<PendingApproval> pendingQueueFrom(Object context) {
        List<PendingApproval> pending = new ArrayList<PendingApproval>();
        if (context == null) {
            return pending;
        }
        Object queue = contextValue(context, CONTEXT_PENDING_APPROVAL_QUEUE);
        pending.addAll(toPendingApprovalList(queue));
        return pending;
    }

    /**
     * 执行上下文值相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @param key 配置键或映射键。
     * @return 返回上下文Value结果。
     */
    private Object contextValue(Object context, String key) {
        if (context instanceof FlowContext) {
            return ((FlowContext) context).get(key);
        }
        if (context instanceof Map) {
            Map<?, ?> snapshot = (Map<?, ?>) context;
            Object directValue = snapshot.get(key);
            if (directValue != null) {
                return directValue;
            }
            Object data = snapshot.get("data");
            if (data instanceof Map) {
                Object dataValue = ((Map<?, ?>) data).get(key);
                if (dataValue != null) {
                    return dataValue;
                }
            }
        }
        return null;
    }

    /**
     * 执行待恢复映射队列From相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回pending Map Queue From结果。
     */
    private List<Map<String, Object>> pendingMapQueueFrom(Object context) {
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (PendingApproval item : filterActivePendingApprovals(pendingQueueFrom(context))) {
            values.add(pendingMap(item));
        }
        return values;
    }

    /**
     * 转换为Pending审批List。
     *
     * @param raw 原始输入值。
     * @return 返回转换后的Pending审批List。
     */
    private List<PendingApproval> toPendingApprovalList(Object raw) {
        List<PendingApproval> values = new ArrayList<PendingApproval>();
        if (raw == null) {
            return values;
        }
        Object parsed = raw;
        if (!(raw instanceof Collection)) {
            try {
                parsed = ONode.deserialize(String.valueOf(raw), Object.class);
            } catch (Exception e) {
                log.debug("Pending approval list JSON parsing failed; returning empty list: {}", exceptionSummary(e));
                parsed = null;
            }
        }
        if (parsed instanceof Collection) {
            for (Object item : (Collection<?>) parsed) {
                PendingApproval pending = toPendingApproval(item);
                if (pending != null) {
                    values.add(pending);
                }
            }
        }
        return values;
    }

    /**
     * 执行待恢复映射相关逻辑。
     *
     * @param pending 待恢复参数。
     * @return 返回pending Map结果。
     */
    private Map<String, Object> pendingMap(PendingApproval pending) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("approvalId", pending.getApprovalId());
        payload.put("toolName", pending.getToolName());
        payload.put("patternKey", pending.getPatternKey());
        payload.put("patternKeys", new ArrayList<String>(pending.effectivePatternKeys()));
        payload.put("description", pending.getDescription());
        payload.put("command", StrUtil.nullToEmpty(pending.getCommand()));
        payload.put("commandHash", pending.getCommandHash());
        payload.put("approvalKey", pending.approvalKey());
        payload.put("onceOnly", Boolean.valueOf(pending.isOnceOnlyApproval()));
        payload.put("createdAt", pending.getCreatedAt());
        payload.put("expiresAt", pending.getExpiresAt());
        return payload;
    }

    /**
     * 判断是否Pending Expired。
     *
     * @param pending 待恢复参数。
     * @return 如果Pending Expired满足条件则返回 true，否则返回 false。
     */
    private boolean isPendingExpired(PendingApproval pending) {
        if (pending == null) {
            return false;
        }
        long expiresAt = pending.getExpiresAt();
        if (expiresAt <= 0L && pending.getCreatedAt() > 0L) {
            expiresAt = pending.getCreatedAt() + approvalGatewayTimeoutMillis();
        }
        return expiresAt > 0L && System.currentTimeMillis() > expiresAt;
    }

    /**
     * 执行redactedApprover相关逻辑。
     *
     * @param approver approver 参数。
     * @return 返回redacted Approver结果。
     */
    private static String redactedApprover(String approver) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(approver).trim(), 200);
    }

    /**
     * 判断是否Approved。
     *
     * @param context 当前请求或运行上下文。
     * @param approvalKey 审批键标识或键值。
     * @return 如果Approved满足条件则返回 true，否则返回 false。
     */
    private boolean isApproved(FlowContext context, String approvalKey) {
        ApprovalKeyParts parts = parseApprovalKey(approvalKey);
        if (parts == null) {
            return loadSessionApprovals(context).contains(approvalKey)
                    || loadAlwaysApprovedPatterns().contains(approvalKey);
        }

        if (parts.patternKey.indexOf('+') >= 0) {
            String[] patternKeys = parts.patternKey.split("\\+");
            for (String patternKey : patternKeys) {
                if (StrUtil.isBlank(patternKey)) {
                    continue;
                }
                if (!containsApprovalForPattern(
                        loadSessionApprovals(context),
                        loadAlwaysApprovedPatterns(),
                        parts.toolName,
                        patternKey)) {
                    return false;
                }
            }
            return true;
        }

        return containsApprovalForPattern(
                        loadSessionApprovals(context),
                        loadAlwaysApprovedPatterns(),
                        parts.toolName,
                        parts.patternKey)
                || containsApprovalKey(loadSessionApprovals(context), approvalKey)
                || containsApprovalKey(loadAlwaysApprovedPatterns(), approvalKey);
    }

    /**
     * 追加会话审批。
     *
     * @param context 当前请求或运行上下文。
     * @param patternKey pattern键标识或键值。
     */
    private void addSessionApproval(FlowContext context, String patternKey) {
        Set<String> approvals = loadSessionApprovals(context);
        approvals.add(patternKey);
        context.put(CONTEXT_SESSION_APPROVALS, new ArrayList<String>(approvals));
    }

    /**
     * 加载会话Approvals。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回会话Approvals结果。
     */
    private Set<String> loadSessionApprovals(FlowContext context) {
        return stringSetFrom(context == null ? null : context.get(CONTEXT_SESSION_APPROVALS));
    }

    /**
     * 追加Always审批。
     *
     * @param patternKey pattern键标识或键值。
     */
    private void addAlwaysApproval(String patternKey) throws Exception {
        Set<String> approvals = loadAlwaysApprovedPatterns();
        approvals.add(patternKey);
        globalSettingRepository.set(
                AgentSettingConstants.DANGEROUS_COMMAND_ALWAYS_PATTERNS,
                ONode.serialize(new ArrayList<String>(approvals)));
    }

    /**
     * 加载Always Approved Patterns。
     *
     * @return 返回Always Approved Patterns结果。
     */
    private Set<String> loadAlwaysApprovedPatterns() {
        if (globalSettingRepository == null) {
            return new LinkedHashSet<String>();
        }

        try {
            return stringSetFrom(
                    globalSettingRepository.get(
                            AgentSettingConstants.DANGEROUS_COMMAND_ALWAYS_PATTERNS));
        } catch (Exception e) {
            log.debug("Always approved pattern loading failed; returning empty pattern set: {}", exceptionSummary(e));
            return new LinkedHashSet<String>();
        }
    }

    /**
     * 执行stringSetFrom相关逻辑。
     *
     * @param raw 原始输入值。
     * @return 返回string Set From结果。
     */
    private Set<String> stringSetFrom(Object raw) {
        Set<String> values = new LinkedHashSet<String>();
        if (raw == null) {
            return values;
        }

        if (raw instanceof Collection) {
            for (Object item : (Collection<?>) raw) {
                String value = cleanApprovalValue(item);
                if (StrUtil.isNotBlank(value)) {
                    values.add(value);
                }
            }
            return values;
        }

        String text = cleanApprovalValue(raw);
        if (text.length() == 0) {
            return values;
        }
        if (text.startsWith("[") || text.startsWith("{")) {
            try {
                Object parsed = ONode.deserialize(text, Object.class);
                if (parsed instanceof Collection) {
                    for (Object item : (Collection<?>) parsed) {
                        String value = cleanApprovalValue(item);
                        if (StrUtil.isNotBlank(value)) {
                            values.add(value);
                        }
                    }
                    return values;
                }
            } catch (Exception e) {
                log.debug("Always approved pattern JSON parsing failed; treating value as plain text: {}", exceptionSummary(e));
                // 非 JSON 审批值按普通文本处理，避免手写审批参数解析失败。
            }
        }

        values.add(text);
        return values;
    }

    /**
     * 清理审批值。
     *
     * @param raw 原始输入值。
     * @return 返回clean审批Value结果。
     */
    private String cleanApprovalValue(Object raw) {
        if (raw == null) {
            return "";
        }
        return SecretRedactor.stripDisplayControls(String.valueOf(raw)).trim();
    }

    /**
     * 判断是否包含Pattern。
     *
     * @param approvals approvals 参数。
     * @param patternKey pattern键标识或键值。
     * @return 返回contains Pattern结果。
     */
    private boolean containsPattern(Set<String> approvals, String patternKey) {
        if (approvals == null || StrUtil.isBlank(patternKey)) {
            return false;
        }
        return containsApprovalForPattern(
                approvals, Collections.<String>emptySet(), "", patternKey);
    }

    /**
     * 判断是否包含审批ForPattern。
     *
     * @param sessionApprovals 会话Approvals参数。
     * @param alwaysApprovals alwaysApprovals 参数。
     * @param toolName 工具名称。
     * @param patternKey pattern键标识或键值。
     * @return 返回contains审批For Pattern结果。
     */
    private boolean containsApprovalForPattern(
            Set<String> sessionApprovals,
            Set<String> alwaysApprovals,
            String toolName,
            String patternKey) {
        if (containsApprovalKey(sessionApprovals, patternKey)
                || containsApprovalKey(alwaysApprovals, patternKey)) {
            return true;
        }
        if (StrUtil.isNotBlank(toolName)) {
            String toolPattern = approvalPattern(toolName, patternKey);
            return containsApprovalKey(sessionApprovals, toolPattern)
                    || containsApprovalKey(alwaysApprovals, toolPattern);
        }
        return containsApprovalPatternAnyTool(sessionApprovals, patternKey)
                || containsApprovalPatternAnyTool(alwaysApprovals, patternKey);
    }

    /**
     * 判断是否包含审批PatternAny工具。
     *
     * @param approvals approvals 参数。
     * @param patternKey pattern键标识或键值。
     * @return 返回contains审批Pattern Any工具结果。
     */
    private boolean containsApprovalPatternAnyTool(Set<String> approvals, String patternKey) {
        if (approvals == null || StrUtil.isBlank(patternKey)) {
            return false;
        }
        for (String approval : approvals) {
            ApprovalKeyParts parts = parseApprovalKey(approval);
            if (parts != null && patternKey.equals(parts.patternKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否包含审批键。
     *
     * @param approvals approvals 参数。
     * @param approvalKey 审批键标识或键值。
     * @return 返回contains审批键结果。
     */
    private boolean containsApprovalKey(Set<String> approvals, String approvalKey) {
        if (approvals == null || StrUtil.isBlank(approvalKey)) {
            return false;
        }
        String normalizedKey = approvalKey.trim();
        if (approvals.contains(normalizedKey)) {
            return true;
        }
        ApprovalKeyParts expected = parseApprovalKey(normalizedKey);
        if (expected == null) {
            return false;
        }
        for (String approval : approvals) {
            ApprovalKeyParts actual = parseApprovalKey(approval);
            if (actual != null
                    && expected.toolName.equals(actual.toolName)
                    && expected.patternKey.equals(actual.patternKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 转换为Pending审批。
     *
     * @param raw 原始输入值。
     * @return 返回转换后的Pending审批。
     */
    private PendingApproval toPendingApproval(Object raw) {
        if (raw == null) {
            return null;
        }

        Map<?, ?> map = raw instanceof Map ? (Map<?, ?>) raw : parseMap(String.valueOf(raw));
        if (map == null) {
            return null;
        }

        String toolName = cleanApprovalText(map.get("toolName"));
        String patternKey = cleanApprovalText(map.get("patternKey"));
        String description = stringValue(map.get("description"));
        String command = stringValue(map.get("command"));
        String commandHash = cleanApprovalText(map.get("commandHash"));
        String approvalKey = cleanApprovalText(map.get("approvalKey"));
        String approvalId = cleanApprovalText(map.get("approvalId"));
        boolean onceOnly = truthy(map.get("onceOnly"));
        long createdAt = longValue(map.get("createdAt"));
        long expiresAt = longValue(map.get("expiresAt"));
        if (StrUtil.hasBlank(toolName, patternKey)) {
            return null;
        }

        PendingApproval pending = new PendingApproval();
        pending.setApprovalId(approvalId);
        pending.setToolName(toolName);
        pending.setPatternKey(patternKey);
        pending.setPatternKeys(cleanApprovalList(listValue(map.get("patternKeys"))));
        pending.setDescription(description);
        pending.setCommand(command);
        pending.setCommandHash(commandHash);
        pending.setApprovalKey(approvalKey);
        pending.setOnceOnly(onceOnly);
        pending.setCreatedAt(createdAt);
        pending.setExpiresAt(expiresAt);
        return pending;
    }

    /**
     * 解析Map。
     *
     * @param text 待处理文本。
     * @return 返回解析后的Map。
     */
    private Map<?, ?> parseMap(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            Object parsed = ONode.deserialize(text, Object.class);
            return parsed instanceof Map ? (Map<?, ?>) parsed : null;
        } catch (Exception e) {
            log.debug("Approval map JSON parsing failed; returning null map: {}", exceptionSummary(e));
            return null;
        }
    }

    /**
     * 解析静态资源Map。
     *
     * @param raw 原始输入值。
     * @return 返回解析后的静态资源Map。
     */
    private static Map<?, ?> parseStaticMap(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map) {
            return (Map<?, ?>) raw;
        }
        String text = String.valueOf(raw).trim();
        if (text.length() == 0) {
            return null;
        }
        try {
            Object parsed = ONode.deserialize(text, Object.class);
            return parsed instanceof Map ? (Map<?, ?>) parsed : null;
        } catch (Exception e) {
            log.debug("Static approval map JSON parsing failed; returning null map: {}", exceptionSummary(e));
            return null;
        }
    }

    /**
     * 将输入对象转换为去除首尾空白的字符串。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回string Value结果。
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 清理审批文本。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回clean审批Text结果。
     */
    private static String cleanApprovalText(Object value) {
        if (value == null) {
            return "";
        }
        return SecretRedactor.stripDisplayControls(String.valueOf(value)).trim();
    }

    /**
     * 清理审批列表。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回clean审批List结果。
     */
    private static List<String> cleanApprovalList(List<String> values) {
        List<String> cleaned = new ArrayList<String>();
        if (values == null) {
            return cleaned;
        }
        for (String value : values) {
            String item = cleanApprovalText(value);
            if (StrUtil.isNotBlank(item) && !cleaned.contains(item)) {
                cleaned.add(item);
            }
        }
        return cleaned;
    }

    /**
     * 将输入对象转换为长整型数值。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回long Value结果。
     */
    private long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            log.debug("Long value parsing failed; returning 0: {}", exceptionSummary(e));
            return 0L;
        }
    }

    /**
     * 将审批解析异常压缩成单行脱敏摘要，避免观察者或配置解析失败时刷出完整栈。
     *
     * @param error 解析、读取或观察者通知过程中捕获的异常。
     * @return 返回异常类型与脱敏消息摘要。
     */
    private static String exceptionSummary(Exception error) {
        if (error == null) {
            return "";
        }
        String message =
                SecretRedactor.redact(
                        StrUtil.blankToDefault(error.getMessage(), error.getClass().getName()),
                        500);
        return error.getClass().getSimpleName() + ": " + message;
    }

    /**
     * 列出Value。
     *
     * @param raw 原始输入值。
     * @return 返回Value列表。
     */
    private List<String> listValue(Object raw) {
        List<String> values = new ArrayList<String>();
        if (raw instanceof Collection) {
            for (Object item : (Collection<?>) raw) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    values.add(String.valueOf(item).trim());
                }
            }
        }
        return values;
    }

    /**
     * 执行string值静态资源相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回string Value静态资源结果。
     */
    private static String stringValueStatic(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 执行规范化相关逻辑。
     *
     * @param code code 参数。
     * @return 返回规范化结果。
     */
    private String normalize(String code) {
        return DangerousCommandTextSupport.normalizeCommand(code);
    }

    /**
     * 执行审批键相关逻辑。
     *
     * @param toolName 工具名称。
     * @param patternKey pattern键标识或键值。
     * @param normalizedCode normalizedCode 参数。
     * @return 返回审批键结果。
     */
    private String approvalKey(String toolName, String patternKey, String normalizedCode) {
        return approvalPattern(toolName, patternKey) + ":" + commandHash(normalizedCode);
    }

    /**
     * 执行combined审批键相关逻辑。
     *
     * @param toolName 工具名称。
     * @param detection detection 参数。
     * @return 返回combined审批键结果。
     */
    private String combinedApprovalKey(String toolName, DetectionResult detection) {
        if (detection == null) {
            return approvalKey(toolName, "", "");
        }
        List<String> patternKeys = detection.effectivePatternKeys();
        if (patternKeys.size() <= 1) {
            return approvalKey(toolName, detection.getPatternKey(), detection.getNormalizedCode());
        }
        StringBuilder buffer = new StringBuilder();
        for (String patternKey : patternKeys) {
            if (buffer.length() > 0) {
                buffer.append('+');
            }
            buffer.append(patternKey);
        }
        return approvalPattern(toolName, buffer.toString())
                + ":"
                + commandHash(detection.getNormalizedCode());
    }

    /**
     * 执行审批Pattern相关逻辑。
     *
     * @param toolName 工具名称。
     * @param patternKey pattern键标识或键值。
     * @return 返回审批Pattern结果。
     */
    private String approvalPattern(String toolName, String patternKey) {
        return cleanApprovalText(toolName) + ":" + cleanApprovalText(patternKey);
    }

    /**
     * 判断是否Tirith Pattern。
     *
     * @param patternKey pattern键标识或键值。
     * @return 如果Tirith Pattern满足条件则返回 true，否则返回 false。
     */
    private boolean isTirithPattern(String patternKey) {
        return StrUtil.nullToEmpty(patternKey).startsWith("tirith:");
    }

    /**
     * 解析审批键。
     *
     * @param approvalKey 审批键标识或键值。
     * @return 返回解析后的审批键。
     */
    private ApprovalKeyParts parseApprovalKey(String approvalKey) {
        if (StrUtil.isBlank(approvalKey)) {
            return null;
        }
        String text = approvalKey.trim();
        int firstColon = text.indexOf(':');
        if (firstColon <= 0 || firstColon >= text.length() - 1) {
            return null;
        }
        String toolName = text.substring(0, firstColon);
        String patternKey = text.substring(firstColon + 1);
        int lastColon = patternKey.lastIndexOf(':');
        if (lastColon > 0 && looksLikeSha256(patternKey.substring(lastColon + 1))) {
            patternKey = patternKey.substring(0, lastColon);
        }
        if (StrUtil.hasBlank(toolName, patternKey)) {
            return null;
        }
        return new ApprovalKeyParts(toolName, patternKey);
    }

    /**
     * 判断是否具有Sha256特征。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回looks Like Sha256结果。
     */
    private boolean looksLikeSha256(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex =
                    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * 执行命令哈希相关逻辑。
     *
     * @param normalizedCode normalizedCode 参数。
     * @return 返回命令Hash结果。
     */
    private String commandHash(String normalizedCode) {
        return sha256Hex(StrUtil.nullToEmpty(normalizedCode));
    }

    /**
     * 执行sha256Hex相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回sha256 Hex结果。
     */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash =
                    digest.digest(StrUtil.nullToEmpty(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : hash) {
                String hex = Integer.toHexString(item & 0xff);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash approval value", e);
        }
    }


    /** 枚举审批范围的可选值，保证状态表达在各模块间一致。 */
    public enum ApprovalScope {
        /** 表示ONCE枚举值。 */
        ONCE,
        /** 表示会话枚举值。 */
        SESSION,
        /** 表示ALWAYS枚举值。 */
        ALWAYS;

        /**
         * 执行comment相关逻辑。
         *
         * @return 返回comment结果。
         */
        public String comment() {
            if (this == SESSION) {
                return "批准执行，并记住当前会话中的危险命令模式。";
            }
            if (this == ALWAYS) {
                return "批准执行，并永久记住危险命令模式。";
            }
            return "批准执行本次危险命令。";
        }
    }

    /** 承载待恢复审批相关状态和辅助逻辑。 */
    public static class PendingApproval extends DangerousPendingApprovalBase {
    }

    /** 表示Detection结果，携带调用方后续判断所需信息。 */
    public static class DetectionResult extends DangerousDetectionResultBase {
    }

    /** 定义审批Observer的抽象契约，供不同运行时实现保持一致行为。 */
    public interface ApprovalObserver {
        /**
         * 响应审批请求事件。
         *
         * @param event 事件参数。
         */
        void onApprovalRequest(ApprovalRequestEvent event);

        /**
         * 响应审批响应事件。
         *
         * @param event 事件参数。
         */
        void onApprovalResponse(ApprovalResponseEvent event);
    }

    /** 承载审批请求事件相关状态和辅助逻辑。 */
    public static class ApprovalRequestEvent extends DangerousApprovalRequestEventBase {
        /**
         * 创建审批请求事件实例，并注入运行所需依赖。
         *
         * @param sessionId 当前会话标识。
         * @param pendingApproval 待恢复审批参数。
         */
        private ApprovalRequestEvent(String sessionId, PendingApproval pendingApproval) {
            super(sessionId, pendingApproval);
        }
    }

    /** 承载审批响应事件相关状态和辅助逻辑。 */
    public static class ApprovalResponseEvent extends ApprovalRequestEvent {
        /** OUTCOMEAPPROVED的统一常量值。 */
        public static final String OUTCOME_APPROVED = DangerousApprovalResponseEventBase.OUTCOME_APPROVED;

        /** OUTCOME拒绝的统一常量值。 */
        public static final String OUTCOME_DENIED = DangerousApprovalResponseEventBase.OUTCOME_DENIED;

        /** OUTCOMETIMEDOUT的统一常量值。 */
        public static final String OUTCOME_TIMED_OUT = DangerousApprovalResponseEventBase.OUTCOME_TIMED_OUT;

        /** OUTCOMEREVOKED的统一常量值。 */
        public static final String OUTCOME_REVOKED = DangerousApprovalResponseEventBase.OUTCOME_REVOKED;

        /** 响应事件委托对象，复用脱敏和状态计算逻辑。 */
        private final DangerousApprovalResponseEventBase delegate;

        /**
         * 创建审批响应事件实例，并注入运行所需依赖。
         *
         * @param sessionId 当前会话标识。
         * @param pendingApproval 待恢复审批参数。
         * @param choice choice 参数。
         * @param approver approver 参数。
         */
        private ApprovalResponseEvent(
                String sessionId, PendingApproval pendingApproval, String choice, String approver) {
            super(sessionId, pendingApproval);
            this.delegate =
                    new DangerousApprovalResponseEventBase(
                            sessionId, pendingApproval, choice, approver);
        }

        /** 读取Choice。 */
        public String getChoice() {
            return delegate.getChoice();
        }

        /** 读取Outcome。 */
        public String getOutcome() {
            return delegate.getOutcome();
        }

        /** 读取状态。 */
        public String getStatus() {
            return delegate.getStatus();
        }

        /** 判断是否Approved。 */
        public boolean isApproved() {
            return delegate.isApproved();
        }

        /** 读取Approver。 */
        public String getApprover() {
            return delegate.getApprover();
        }
    }

    /** 承载审批键Parts相关状态和辅助逻辑。 */
    private static class ApprovalKeyParts {
        /** 记录审批键Parts中的工具名称。 */
        private final String toolName;

        /** 记录审批键Parts中的pattern键。 */
        private final String patternKey;

        /**
         * 创建审批键Parts实例，并注入运行所需依赖。
         *
         * @param toolName 工具名称。
         * @param patternKey pattern键标识或键值。
         */
        private ApprovalKeyParts(String toolName, String patternKey) {
            this.toolName = toolName;
            this.patternKey = patternKey;
        }
    }


}
