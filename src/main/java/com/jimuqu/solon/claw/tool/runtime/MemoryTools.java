package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.MemoryConstants;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 长期记忆工具。 */
@RequiredArgsConstructor
public class MemoryTools {
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
                threat("runtime_env", "\\$HOME/\\.solon-claw/\\.env|~/\\.solon-claw/\\.env")
            };

    /** 长期记忆服务。 */
    private final MemoryService memoryService;

    /** 管理 MEMORY.md 与 USER.md。 */
    @ToolMapping(
            name = "memory",
            description =
                    "Manage persistent memory. action supports add, replace, remove, read. target supports memory, user, or today.")
    public String memory(
            @Param(name = "action", description = "操作类型：add、replace、remove、read") String action,
            @Param(name = "target", description = "目标存储：memory、user 或 today") String target,
            @Param(name = "content", description = "新增或替换的内容", required = false) String content,
            @Param(name = "oldText", description = "replace/remove 时用于匹配旧条目的文本", required = false)
                    String oldText)
            throws Exception {
        String normalizedTarget = StrUtil.blankToDefault(target, MemoryConstants.TARGET_MEMORY);
        if (MemoryConstants.ACTION_READ.equalsIgnoreCase(action)) {
            return new ONode()
                    .set("success", true)
                    .set("action", MemoryConstants.ACTION_READ)
                    .set("target", safe(normalizedTarget, 200))
                    .set("content", safe(memoryService.read(normalizedTarget), 8000))
                    .set("message", "ok")
                    .toJson();
        }
        String result;
        if (MemoryConstants.ACTION_ADD.equalsIgnoreCase(action)) {
            String blocked = scanMemoryContent(content);
            if (blocked != null) {
                return blockedResponse(action, normalizedTarget, blocked);
            }
            result = memoryService.add(normalizedTarget, content);
        } else if (MemoryConstants.ACTION_REPLACE.equalsIgnoreCase(action)) {
            String blocked = scanMemoryContent(content);
            if (blocked != null) {
                return blockedResponse(action, normalizedTarget, blocked);
            }
            result = memoryService.replace(normalizedTarget, oldText, content);
        } else if (MemoryConstants.ACTION_REMOVE.equalsIgnoreCase(action)) {
            result =
                    memoryService.remove(
                            normalizedTarget, StrUtil.blankToDefault(oldText, content));
        } else {
            result = "Unsupported memory action";
        }
        return new ONode()
                .set("success", isSuccess(result))
                .set("action", StrUtil.nullToEmpty(action))
                .set("target", safe(normalizedTarget, 200))
                .set("message", safe(result, 1000))
                .toJson();
    }

    /**
     * 判断是否Success。
     *
     * @param message 平台消息或错误消息。
     * @return 如果Success满足条件则返回 true，否则返回 false。
     */
    private boolean isSuccess(String message) {
        String normalized = StrUtil.nullToEmpty(message).trim();
        if (normalized.length() == 0) {
            return false;
        }
        return !normalized.startsWith("Unsupported")
                && !normalized.contains("不能为空")
                && !normalized.contains("不会写入")
                && !normalized.startsWith("未");
    }

    /**
     * 执行scan记忆Content相关逻辑。
     *
     * @param content 待处理内容。
     * @return 返回scan记忆Content结果。
     */
    private String scanMemoryContent(String content) {
        if (StrUtil.isBlank(content)) {
            return null;
        }
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (isInvisibleInjectionChar(ch)) {
                return "Blocked: content contains invisible unicode U+"
                        + String.format(Locale.ROOT, "%04X", Integer.valueOf(ch))
                        + " (possible injection).";
            }
        }
        for (MemoryThreat threat : MEMORY_THREATS) {
            if (threat.pattern.matcher(content).find()) {
                return "Blocked: content matches threat pattern '"
                        + threat.id
                        + "'. Memory entries are injected into the system prompt and must not contain injection or exfiltration payloads.";
            }
        }
        return null;
    }

    /**
     * 执行阻断响应相关逻辑。
     *
     * @param action 操作参数。
     * @param target target 参数。
     * @param message 平台消息或错误消息。
     * @return 返回blocked响应结果。
     */
    private String blockedResponse(String action, String target, String message) {
        return new ONode()
                .set("success", false)
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
