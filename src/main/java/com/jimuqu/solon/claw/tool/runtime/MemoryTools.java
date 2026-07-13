package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.MemorySearchResult;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.MemoryConstants;
import com.jimuqu.solon.claw.tui.TerminalUiRpcService;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 长期记忆工具。 */
public class MemoryTools {
    /** 从服务层稳定暂存结果中提取低敏待审批标识。 */
    private static final Pattern PENDING_ID_PATTERN = Pattern.compile("ID: ([0-9a-f]{8})");

    /** 记忆THREATS的统一常量值。 */
    private static final MemoryThreat[] MEMORY_THREATS =
            new MemoryThreat[] {
                threat(
                        "prompt_injection",
                        "ignore\\s+(?:\\w+\\s+)*(?:previous|all|above|prior)\\s+(?:\\w+\\s+)*instructions"),
                threat("role_hijack", "you\\s+are\\s+now\\s+"),
                threat("deception_hide", "do\\s+not\\s+tell\\s+the\\s+user"),
                threat("sys_prompt_override", "system\\s+prompt\\s+override"),
                threat(
                        "disregard_rules",
                        "disregard\\s+(your|all|any)\\s+(instructions|rules|guidelines)"),
                threat(
                        "bypass_restrictions",
                        "act\\s+as\\s+(if|though)\\s+you\\s+(have\\s+no|don'?t\\s+have)\\s+(restrictions|limits|rules)"),
                threat(
                        "exfil_curl",
                        "curl\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)"),
                threat(
                        "exfil_wget",
                        "wget\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)"),
                threat(
                        "read_secrets",
                        "cat\\s+[^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass|\\.npmrc|\\.pypirc)"),
                threat("ssh_backdoor", "authorized_keys"),
                threat("ssh_access", "\\$HOME/\\.ssh|~/\\.ssh"),
                threat("runtime_env", "\\$HOME/\\.solonclaw/\\.env|~/\\.solonclaw/\\.env")
            };

    /** 长期记忆服务。 */
    private final MemoryService memoryService;

    /** 前台终端记忆写入的一次性审批协调器。 */
    private final MemoryApprovalCoordinator approvalCoordinator;

    /** 创建使用共享前台审批协调器的记忆工具。 */
    public MemoryTools(MemoryService memoryService) {
        this(memoryService, MemoryApprovalCoordinator.shared());
    }

    /** 创建使用指定审批协调器的记忆工具，供聚焦测试隔离连接状态。 */
    public MemoryTools(MemoryService memoryService, MemoryApprovalCoordinator approvalCoordinator) {
        this.memoryService = memoryService;
        this.approvalCoordinator = approvalCoordinator;
    }

    /** 管理长期、用户、每日和专题记忆。 */
    @ToolMapping(
            name = "memory",
            description =
                    "Manage persistent memory. action supports add, replace, remove, read. target"
                            + " supports memory, user, today, or topic:<name>.")
    public String memory(
            @Param(name = "action", description = "操作类型：add、replace、remove、read") String action,
            @Param(name = "target", description = "目标存储：memory、user、today 或 topic:<名称>")
                    String target,
            @Param(name = "content", description = "新增或替换的内容", required = false) String content,
            @Param(name = "oldText", description = "replace/remove 时用于匹配旧条目的文本", required = false)
                    String oldText)
            throws Exception {
        String normalizedTarget = normalizeTarget(target);
        if (normalizedTarget == null) {
            return blockedResponse(
                    action, "", "Unsupported memory target. Expected memory, user, or today.");
        }
        if (MemoryConstants.ACTION_READ.equalsIgnoreCase(action)) {
            return new ONode()
                    .set("status", "success")
                    .set("action", MemoryConstants.ACTION_READ)
                    .set("target", safe(normalizedTarget, 200))
                    .set("content", safe(memoryService.read(normalizedTarget), 8000))
                    .set("message", "ok")
                    .toJson();
        }
        String result;
        boolean foreground = isForegroundTerminalRun(AgentRunContext.current());
        String origin = foreground ? "foreground" : "background_review";
        if (MemoryConstants.ACTION_ADD.equalsIgnoreCase(action)) {
            String blocked = scanMemoryContent(content);
            if (blocked != null) {
                return blockedResponse(action, normalizedTarget, blocked);
            }
            result = memoryService.add(normalizedTarget, content, origin);
        } else if (MemoryConstants.ACTION_REPLACE.equalsIgnoreCase(action)) {
            String blocked = scanMemoryContent(content);
            if (blocked == null) {
                blocked = scanDisplayInput(oldText, "oldText");
            }
            if (blocked != null) {
                return blockedResponse(action, normalizedTarget, blocked);
            }
            result = memoryService.replace(normalizedTarget, oldText, content, origin);
        } else if (MemoryConstants.ACTION_REMOVE.equalsIgnoreCase(action)) {
            String matchText = StrUtil.blankToDefault(oldText, content);
            String blocked = scanDisplayInput(matchText, "oldText");
            if (blocked != null) {
                return blockedResponse(action, normalizedTarget, blocked);
            }
            result = memoryService.remove(normalizedTarget, matchText, origin);
        } else {
            result = "Unsupported memory action";
        }
        return response(
                action,
                normalizedTarget,
                inlineApproval(action, normalizedTarget, content, oldText, result));
    }

    /** 使用 SQLite FTS5 统一搜索所有记忆文件。 */
    @ToolMapping(
            name = "memory_search",
            description =
                    "Search MEMORY.md, USER.md, daily memory, and topic memory with SQLite FTS5.")
    public String memorySearch(
            @Param(name = "query", description = "搜索关键词") String query,
            @Param(name = "limit", description = "最多返回条数", required = false) Integer limit)
            throws Exception {
        List<MemorySearchResult> results =
                memoryService.search(query, limit == null ? 5 : limit.intValue());
        return new ONode()
                .set("status", "success")
                .set("query", safe(query, 300))
                .set("results", results)
                .toJson();
    }

    /** 按 memory_search 返回的路径读取完整记忆。 */
    @ToolMapping(
            name = "memory_get",
            description = "Read a memory file by the safe relative path returned by memory_search.")
    public String memoryGet(@Param(name = "path", description = "记忆文件相对路径") String path)
            throws Exception {
        return new ONode()
                .set("status", "success")
                .set("path", safe(path, 300))
                .set("content", safe(memoryService.get(path), 12000))
                .toJson();
    }

    /** 对前台 TUI 会话的已暂存写入执行一次性内联审批，其他来源保持待审批状态。 */
    private String inlineApproval(
            String action, String target, String content, String oldText, String result)
            throws Exception {
        Matcher matcher = PENDING_ID_PATTERN.matcher(StrUtil.nullToEmpty(result));
        AgentRunContext context = AgentRunContext.current();
        if (!matcher.find() || !isForegroundTerminalRun(context)) {
            return result;
        }
        String pendingId = matcher.group(1);
        MemoryApprovalCoordinator.Decision decision =
                approvalCoordinator.request(
                        context.getSessionId(),
                        pendingId,
                        summary(action, target),
                        detail(action, content, oldText));
        if (decision == MemoryApprovalCoordinator.Decision.APPROVE) {
            return memoryService.approve(pendingId);
        }
        if (decision == MemoryApprovalCoordinator.Decision.DENY) {
            memoryService.reject(pendingId);
            return "未写入：用户拒绝了记忆变更。";
        }
        return result;
    }

    /** 判断当前运行是否为终端 UI 发起的前台会话或恢复运行。 */
    private boolean isForegroundTerminalRun(AgentRunContext context) {
        if (context == null
                || !StrUtil.startWith(
                        context.getSourceKey(), TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX)) {
            return false;
        }
        return "conversation".equals(context.getRunKind()) || "resume".equals(context.getRunKind())
                ? approvalCoordinator.canRequest(context.getSessionId())
                : false;
    }

    /** 生成不含正文的审批动作摘要。 */
    private String summary(String action, String target) {
        if (MemoryConstants.ACTION_ADD.equalsIgnoreCase(action)) {
            return "add to " + target;
        }
        if (MemoryConstants.ACTION_REPLACE.equalsIgnoreCase(action)) {
            return "replace in " + target;
        }
        return "remove from " + target;
    }

    /** 生成供终端用户核对的待写入详情。 */
    private String detail(String action, String content, String oldText) {
        if (MemoryConstants.ACTION_REPLACE.equalsIgnoreCase(action)) {
            return "old:\n"
                    + StrUtil.nullToEmpty(oldText)
                    + "\nnew:\n"
                    + StrUtil.nullToEmpty(content);
        }
        return MemoryConstants.ACTION_REMOVE.equalsIgnoreCase(action)
                ? StrUtil.blankToDefault(oldText, content)
                : StrUtil.nullToEmpty(content);
    }

    /** 生成普通记忆管理动作的结构化结果。 */
    private String response(String action, String target, String result) {
        ONode response =
                new ONode()
                        .set("status", status(result))
                        .set("action", StrUtil.nullToEmpty(action))
                        .set("target", safe(target, 200))
                        .set("message", safe(result, 1000));
        Matcher matcher = PENDING_ID_PATTERN.matcher(StrUtil.nullToEmpty(result));
        if (matcher.find()) {
            response.set("staged", true).set("pending_id", matcher.group(1));
        }
        return response.toJson();
    }

    /**
     * 判断记忆工具调用状态。
     *
     * @param message 平台消息或错误消息。
     * @return 返回当前工具结果状态。
     */
    private String status(String message) {
        String normalized = StrUtil.nullToEmpty(message).trim();
        if (normalized.length() == 0) {
            return "error";
        }
        boolean ok =
                !normalized.startsWith("Unsupported")
                        && !normalized.contains("不能为空")
                        && !normalized.contains("不会写入")
                        && !normalized.startsWith("未");
        return ok ? "success" : "error";
    }

    /**
     * 扫描即将写入系统提示词的记忆内容，阻断提示词注入、秘密外泄和隐形字符载荷。
     *
     * @param content 待写入的记忆内容。
     * @return 命中威胁时返回阻断原因，否则返回 null。
     */
    private String scanMemoryContent(String content) {
        if (StrUtil.isBlank(content)) {
            return null;
        }
        String blocked = scanDisplayInput(content, "content");
        if (blocked != null) {
            return blocked;
        }
        for (MemoryThreat threat : MEMORY_THREATS) {
            if (threat.pattern.matcher(content).find()) {
                return "Blocked: content matches threat pattern '"
                        + threat.id
                        + "'. Memory entries are injected into the system prompt and must not"
                        + " contain injection or exfiltration payloads.";
            }
        }
        return null;
    }

    /** 校验会进入审批展示或匹配逻辑的文本，拒绝终端控制序列和隐形注入字符。 */
    private String scanDisplayInput(String value, String field) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        if (!value.equals(TerminalAnsiSanitizer.stripAnsi(value))) {
            return "Blocked: " + field + " contains terminal control sequences.";
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (isInvisibleInjectionChar(ch)) {
                return "Blocked: "
                        + field
                        + " contains invisible unicode U+"
                        + String.format(Locale.ROOT, "%04X", Integer.valueOf(ch))
                        + " (possible injection).";
            }
        }
        return null;
    }

    /** 将工具目标规范化为服务真实支持的存储区，拒绝静默回退到 MEMORY.md。 */
    private String normalizeTarget(String target) {
        String normalized =
                StrUtil.blankToDefault(target, MemoryConstants.TARGET_MEMORY)
                        .trim()
                        .toLowerCase(Locale.ROOT);
        if (normalized.startsWith(MemoryConstants.TARGET_TOPIC_PREFIX)) {
            String name = normalized.substring(MemoryConstants.TARGET_TOPIC_PREFIX.length());
            return name.matches("[\\p{L}\\p{N}._-]{1,80}")
                            && !".".equals(name)
                            && !"..".equals(name)
                    ? normalized
                    : null;
        }
        return MemoryConstants.TARGET_MEMORY.equals(normalized)
                        || MemoryConstants.TARGET_USER.equals(normalized)
                        || MemoryConstants.TARGET_TODAY.equals(normalized)
                ? normalized
                : null;
    }

    /**
     * 返回结构化阻断结果，且不把危险内容回显给调用方。
     *
     * @param action 请求的记忆操作。
     * @param target 目标记忆文件。
     * @param message 阻断原因。
     * @return 结构化错误 JSON。
     */
    private String blockedResponse(String action, String target, String message) {
        return new ONode()
                .set("status", "error")
                .set("action", StrUtil.nullToEmpty(action))
                .set("target", safe(target, 200))
                .set("message", safe(message, 1000))
                .toJson();
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
     * 判断是否Invisible Injection Char。
     *
     * @param ch ch 参数。
     * @return 如果Invisible Injection Char满足条件则返回 true，否则返回 false。
     */
    private static boolean isInvisibleInjectionChar(char ch) {
        return ch == '\u200b'
                || ch == '\u200c'
                || ch == '\u200d'
                || ch == '\u2060'
                || ch == '\ufeff'
                || ch == '\u202a'
                || ch == '\u202b'
                || ch == '\u202c'
                || ch == '\u202d'
                || ch == '\u202e';
    }

    /**
     * 执行threat相关逻辑。
     *
     * @param id 标识。
     * @param regex regex 参数。
     * @return 返回threat结果。
     */
    private static MemoryThreat threat(String id, String regex) {
        return new MemoryThreat(id, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    /** 承载记忆Threat相关状态和辅助逻辑。 */
    private static class MemoryThreat {
        /** 记录记忆Threat中的标识。 */
        private final String id;

        /** 记录记忆Threat中的pattern。 */
        private final Pattern pattern;

        /**
         * 创建记忆Threat实例，并注入运行所需依赖。
         *
         * @param id 标识。
         * @param pattern pattern 参数。
         */
        private MemoryThreat(String id, Pattern pattern) {
            this.id = id;
            this.pattern = pattern;
        }
    }
}
