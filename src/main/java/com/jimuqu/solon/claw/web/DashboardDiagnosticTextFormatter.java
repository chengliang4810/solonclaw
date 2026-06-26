package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.ApprovalAuditEvent;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dashboard 诊断输出专用的脱敏预览、路径展示和异常摘要格式化工具。 */
final class DashboardDiagnosticTextFormatter {
    /** 记录诊断文本格式化失败的低敏摘要，帮助排查损坏审计字段但不暴露原始内容。 */
    private static final Logger log =
            LoggerFactory.getLogger(DashboardDiagnosticTextFormatter.class);

    /** 诊断输出中统一展示被隐藏文件路径的占位符，避免泄露本机目录结构。 */
    private static final String REDACTED_PATH = "[REDACTED_PATH]";

    /** 工具类不保存状态，禁止被实例化。 */
    private DashboardDiagnosticTextFormatter() {}

    /**
     * 将任意对象转换为安全展示文本，统一执行空值兜底、敏感值脱敏和最大长度裁剪。
     *
     * @param value 需要展示在诊断响应中的对象值。
     * @param maxLength 最大允许展示字符数。
     * @return 返回可写入诊断响应的脱敏文本。
     */
    static String safeObjectText(Object value, int maxLength) {
        return SecretRedactor.redact(
                StrUtil.nullToEmpty(value == null ? null : String.valueOf(value)), maxLength);
    }

    /**
     * 生成诊断审计字段的安全预览，保证空值输出为空字符串并隐藏令牌、密钥等敏感片段。
     *
     * @param value 原始诊断文本。
     * @param maxLength 最大允许展示字符数。
     * @return 返回安全预览文本。
     */
    static String safeAuditPreview(String value, int maxLength) {
        return StrUtil.nullToEmpty(SecretRedactor.redact(value, maxLength));
    }

    /**
     * 将字符串集合转换为诊断响应可展示的脱敏列表，空白项不会进入输出。
     *
     * @param source 原始字符串集合。
     * @param maxLength 单项最大允许展示字符数。
     * @return 返回脱敏后的非空文本列表。
     */
    static List<Object> redactedTextList(List<String> source, int maxLength) {
        List<Object> values = new ArrayList<Object>();
        if (source == null) {
            return values;
        }
        for (String item : source) {
            if (StrUtil.isNotBlank(item)) {
                values.add(safeAuditPreview(item, maxLength));
            }
        }
        return values;
    }

    /**
     * 将 JSON 数组文本转换为诊断响应可展示的脱敏列表，解析失败时返回空列表以保持诊断接口稳定。
     *
     * @param json 原始 JSON 数组文本。
     * @param maxLength 单项最大允许展示字符数。
     * @return 返回脱敏后的 JSON 列表内容。
     */
    static List<Object> redactedJsonList(String json, int maxLength) {
        List<Object> values = new ArrayList<Object>();
        for (Object item : parseJsonList(json)) {
            if (item instanceof String) {
                values.add(safeAuditPreview((String) item, maxLength));
            } else if (item != null) {
                values.add(SecretRedactor.redact(String.valueOf(item), maxLength));
            }
        }
        return values;
    }

    /**
     * 将审批键保留到最后一个分隔符前，隐藏命令摘要或策略尾段，避免长期授权明文泄露。
     *
     * @param approvalKey 原始审批键。
     * @return 返回可展示的审批键摘要。
     */
    static String redactedApprovalKey(String approvalKey) {
        String value = SecretRedactor.redact(StrUtil.nullToEmpty(approvalKey), 1000);
        int split = value.lastIndexOf(':');
        if (split >= 0 && split < value.length() - 1) {
            return value.substring(0, split + 1) + "***";
        }
        return redactedIdentifier(value);
    }

    /**
     * 将内部标识统一压缩成不可逆占位符，避免诊断输出暴露命令哈希、审批编号等关联信息。
     *
     * @param value 原始内部标识。
     * @return 返回空字符串或固定脱敏占位符。
     */
    static String redactedIdentifier(String value) {
        return StrUtil.isBlank(value) ? "" : "***";
    }

    /**
     * 生成诊断响应中的审批审计脱敏条目，统一 Dashboard 历史输出和安全探针输出字段。
     *
     * @param event 审批审计事件。
     * @return 返回脱敏后的审批审计条目。
     */
    static Map<String, Object> approvalAuditItem(ApprovalAuditEvent event) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("event_id", safeAuditPreview(event.getEventId(), 120));
        item.put("session_id", safeAuditPreview(event.getSessionId(), 240));
        item.put("event_type", safeAuditPreview(event.getEventType(), 80));
        item.put("choice", safeAuditPreview(event.getChoice(), 80));
        item.put("outcome", safeAuditPreview(event.getOutcome(), 80));
        item.put("status", safeAuditPreview(event.getStatus(), 80));
        item.put("approved", Boolean.valueOf(event.isApproved()));
        item.put("approver", SecretRedactor.redact(event.getApprover(), 200));
        item.put("tool_name", safeAuditPreview(event.getToolName(), 160));
        item.put("command_hash", redactedIdentifier(event.getCommandHash()));
        item.put("command_preview", safeAuditPreview(event.getCommandPreview(), 800));
        item.put("description", safeAuditPreview(event.getDescription(), 1000));
        item.put("pattern_keys", redactedJsonList(event.getPatternKeysJson(), 400));
        item.put("created_at", Long.valueOf(event.getCreatedAt()));
        item.put("approval_created_at", Long.valueOf(event.getApprovalCreatedAt()));
        item.put("approval_expires_at", Long.valueOf(event.getApprovalExpiresAt()));
        return item;
    }

    /**
     * 生成命令路径策略探针的安全 target，阻断命令或敏感路径命中时隐藏具体路径。
     *
     * @param command 原始命令文本。
     * @param path 命中的文件路径。
     * @param message 策略判定消息。
     * @param blocked 命令是否已被路径策略阻断。
     * @return 返回适合诊断响应展示的命令 target。
     */
    static String redactedCommandPathTarget(
            String command, String path, String message, boolean blocked) {
        if (StrUtil.isBlank(path) || (!blocked && !requiresCommandPathRedaction(message))) {
            return command;
        }
        String target = StrUtil.nullToEmpty(command);
        String normalizedPath = StrUtil.nullToEmpty(path);
        String redacted = target.replace(" " + normalizedPath, " " + REDACTED_PATH);
        redacted = redacted.replace("=" + normalizedPath, "=" + REDACTED_PATH);
        if (redacted.equals(target) && normalizedPath.startsWith("./")) {
            redacted = target.replace(" " + normalizedPath.substring(2), " " + REDACTED_PATH);
        }
        if (redacted.equals(target)) {
            redacted = target.replace(normalizedPath, REDACTED_PATH);
        }
        if (redacted.equals(target)) {
            redacted = target + " [path=" + REDACTED_PATH + "]";
        }
        return SecretRedactor.redactSensitivePaths(redacted);
    }

    /**
     * 根据策略消息判断路径是否需要整体隐藏，覆盖凭据、私钥和密钥相关诊断描述。
     *
     * @param message 策略判定消息。
     * @return 如果消息说明路径涉及敏感材料则返回 true。
     */
    static boolean requiresCommandPathRedaction(String message) {
        String value = StrUtil.nullToEmpty(message);
        return value.contains("凭据") || value.contains("私钥") || value.contains("密钥");
    }

    /**
     * 生成文件路径探针的安全 target，敏感策略命中时不展示原始路径。
     *
     * @param path 原始文件路径。
     * @param message 策略判定消息。
     * @return 返回安全展示用路径 target。
     */
    static String safePathProbeTarget(String path, String message) {
        if (requiresCommandPathRedaction(message)) {
            return REDACTED_PATH;
        }
        return safeAuditPreview(path, 400);
    }

    /**
     * 将诊断流程中的非致命异常压缩成单行脱敏摘要，避免日志泄露令牌、密钥或完整配置值。
     *
     * @param error 诊断 fallback 或清理流程捕获的异常。
     * @return 返回异常类型与脱敏后的短消息。
     */
    static String diagnosticFailureSummary(Exception error) {
        if (error == null) {
            return "";
        }
        String message =
                SecretRedactor.stripDisplayControls(
                        StrUtil.blankToDefault(error.getMessage(), error.getClass().getName()));
        message = message.replace('\r', ' ').replace('\n', ' ');
        message = SecretRedactor.redactSensitivePaths(SecretRedactor.redact(message, 500));
        return error.getClass().getSimpleName() + ": " + message;
    }

    /**
     * 解析诊断审计中存储的 JSON 数组文本，解析失败时返回空列表避免影响主诊断流程。
     *
     * @param json 原始 JSON 文本。
     * @return 返回解析后的数组元素。
     */
    @SuppressWarnings("unchecked")
    private static List<Object> parseJsonList(String json) {
        if (StrUtil.isBlank(json)) {
            return new ArrayList<Object>();
        }
        try {
            Object data = ONode.ofJson(json).toData();
            if (data instanceof List) {
                return (List<Object>) data;
            }
        } catch (Exception e) {
            log.debug(
                    "Dashboard diagnostic JSON list parsing failed; returning empty list: {}",
                    diagnosticFailureSummary(e));
        }
        return new ArrayList<Object>();
    }
}
