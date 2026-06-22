package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;

/** 渲染危险命令审批、人审拒绝和安全策略阻断消息，避免主审批服务混入展示细节。 */
final class DangerousCommandApprovalMessageRenderer {
    /** 命令预览最大字符数，避免审批卡片和日志展示过长命令。 */
    private static final int COMMAND_PREVIEW_LENGTH = 400;

    /** 创建危险命令审批消息渲染器。 */
    DangerousCommandApprovalMessageRenderer() {}

    /**
     * 构建待人工审批消息，保持 slash 审批指引和既有输出字段语义不变。
     *
     * @param toolName 触发审批的工具名称。
     * @param detection 危险命令或策略检测结果。
     * @param code 待执行命令或策略目标。
     * @return 返回可投递给用户的审批提示。
     */
    String buildPendingMessage(
            String toolName,
            DangerousCommandApprovalService.DetectionResult detection,
            String code) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("⚠️ 危险命令需要审批：\n");
        buffer.append("工具：").append(toolLabel(toolName)).append('\n');
        buffer.append("原因：")
                .append(redactApprovalDisplay(detection.getDescription(), 1000))
                .append("\n\n");
        buffer.append("```").append(codeFence(toolName)).append('\n');
        buffer.append(redactApprovalDisplay(trimPreview(code), 2000));
        buffer.append("\n```\n\n");
        if (detection != null && detection.isOnceOnly()) {
            buffer.append("该操作只支持单次审批。回复 `/approve` 执行一次，或 `/deny` 取消。");
        } else if (containsTirith(detection)) {
            buffer.append(
                    "该安全扫描结果只支持本次或当前会话审批，不能永久记住。回复 `/approve` 执行一次，`/approve session` 记住当前会话，或 `/deny` 取消。");
        } else {
            buffer.append(
                    "回复 `/approve` 执行一次，`/approve session` 记住当前会话，`/approve always` 永久记住，或 `/deny` 取消。");
        }
        return buffer.toString();
    }

    /**
     * 构建 smart 审批拒绝消息，确保拒绝原因和命令描述都经过低敏展示。
     *
     * @param detection 危险命令或策略检测结果。
     * @param decision smart 审批器返回的拒绝决策。
     * @return 返回 smart 审批拒绝提示。
     */
    String buildSmartDeniedMessage(
            DangerousCommandApprovalService.DetectionResult detection,
            SmartApprovalDecision decision) {
        String description =
                detection == null
                        ? "dangerous command"
                        : StrUtil.blankToDefault(
                                detection.getDescription(), detection.getPatternKey());
        StringBuilder buffer = new StringBuilder();
        buffer.append("BLOCKED by smart approval: ")
                .append(redactApprovalDisplay(description, 1000))
                .append(". The command was assessed as genuinely dangerous. Do NOT retry.");
        if (decision != null && StrUtil.isNotBlank(decision.getReason())) {
            buffer.append("\n原因：").append(redactApprovalDisplay(decision.getReason(), 1000));
        }
        return buffer.toString();
    }

    /**
     * 构建 strict 模式拒绝消息，说明当前策略不会进入人工审批。
     *
     * @param toolName 触发拒绝的工具名称。
     * @param detection 危险命令检测结果。
     * @param code 待执行命令。
     * @return 返回 strict 模式拒绝提示。
     */
    String buildStrictDeniedMessage(
            String toolName,
            DangerousCommandApprovalService.DetectionResult detection,
            String code) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("BLOCKED (strict): ");
        buffer.append(
                redactApprovalDisplay(
                        detection == null ? "dangerous command" : detection.getDescription(),
                        1000));
        buffer.append("。当前安全策略为 strict，命中可审批危险策略时不会进入人工审批。");
        appendToolAndCommandPreview(buffer, toolName, code);
        return buffer.toString();
    }

    /**
     * 构建子 Agent 拒绝消息，提示只能通过显式可信批处理配置放行。
     *
     * @param detection 危险命令或策略检测结果。
     * @return 返回子 Agent 拒绝提示。
     */
    String buildSubagentDeniedMessage(
            DangerousCommandApprovalService.DetectionResult detection) {
        String description =
                detection == null
                        ? "dangerous command"
                        : StrUtil.blankToDefault(
                                detection.getDescription(), detection.getPatternKey());
        return "BLOCKED: 子 Agent 默认拒绝可审批危险命令："
                + redactApprovalDisplay(description, 1000)
                + "。如确实需要在可信批处理里允许，请设置 approvals.subagentAutoApprove=true。";
    }

    /**
     * 构建 hardline 阻断消息，明确该类操作不能通过审批绕过。
     *
     * @param toolName 触发阻断的工具名称。
     * @param detection hardline 检测结果。
     * @param code 待执行命令。
     * @return 返回 hardline 阻断提示。
     */
    String buildHardlineMessage(
            String toolName,
            DangerousCommandApprovalService.DetectionResult detection,
            String code) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("BLOCKED (hardline): ");
        buffer.append(detection.getDescription());
        buffer.append("。该命令属于不可通过 Agent 执行的高危操作，不能通过 /approve、/approve always 或会话审批绕过。");
        appendToolAndCommandPreview(buffer, toolName, code);
        return buffer.toString();
    }

    /**
     * 构建文件策略阻断消息，路径只做脱敏短预览。
     *
     * @param toolName 触发阻断的工具名称。
     * @param verdict 文件安全策略判定。
     * @return 返回文件策略阻断提示。
     */
    String buildFilePolicyMessage(
            String toolName, SecurityPolicyService.FileVerdict verdict) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("BLOCKED: 文件安全策略阻止访问：");
        buffer.append(verdict.getMessage());
        buffer.append("\n工具：").append(toolLabel(toolName));
        buffer.append("\n路径：").append(SecretRedactor.redact(verdict.getPath(), 400));
        buffer.append("\n请改用工作区内的普通项目文件，敏感凭据文件不能通过 Agent 工具读取、写入或删除。");
        return buffer.toString();
    }

    /**
     * 构建 URL 策略阻断消息，URL 通过统一密钥遮蔽逻辑展示。
     *
     * @param verdict URL 安全策略判定。
     * @return 返回 URL 策略阻断提示。
     */
    String buildUrlPolicyMessage(SecurityPolicyService.UrlVerdict verdict) {
        return "BLOCKED: URL 安全策略阻止访问："
                + verdict.getMessage()
                + "\nURL: "
                + SecretRedactor.maskUrl(verdict.getUrl())
                + "\n请换用公开、可信且符合网站访问策略的地址。";
    }

    /**
     * 脱敏审批展示文本，移除 ANSI 和控制字符后再按最大长度截断。
     *
     * @param value 待展示的原始文本。
     * @param maxLength 最大保留字符数。
     * @return 返回可安全展示的审批文本。
     */
    String redactApprovalDisplay(String value, int maxLength) {
        String normalized =
                SecretRedactor.stripDisplayControls(
                        TerminalAnsiSanitizer.stripAnsi(StrUtil.nullToEmpty(value)));
        return SecretRedactor.redact(normalized, maxLength);
    }

    /**
     * 生成工具展示名，保持 execute_python / execute_js / execute_shell 的原有展示行为。
     *
     * @param toolName 原始工具名称。
     * @return 返回用户可读的工具名。
     */
    String toolLabel(String toolName) {
        if (ToolNameConstants.EXECUTE_PYTHON.equals(toolName)) {
            return "execute_python";
        }
        if (ToolNameConstants.EXECUTE_JS.equals(toolName)) {
            return "execute_js";
        }
        if (StrUtil.isNotBlank(toolName) && !ToolNameConstants.EXECUTE_SHELL.equals(toolName)) {
            return toolName;
        }
        return "execute_shell";
    }

    /**
     * 追加工具名和命令代码块预览，统一 strict 与 hardline 的消息结构。
     *
     * @param buffer 目标消息缓冲区。
     * @param toolName 触发阻断的工具名称。
     * @param code 待展示命令。
     */
    private void appendToolAndCommandPreview(StringBuilder buffer, String toolName, String code) {
        buffer.append("\n工具：").append(toolLabel(toolName)).append("\n\n");
        buffer.append("```").append(codeFence(toolName)).append('\n');
        buffer.append(redactApprovalDisplay(trimPreview(code), 2000));
        buffer.append("\n```");
    }

    /**
     * 根据工具类型选择 Markdown 代码块语言，便于终端和渠道侧高亮。
     *
     * @param toolName 触发审批的工具名称。
     * @return 返回代码块语言标识。
     */
    private String codeFence(String toolName) {
        if (ToolNameConstants.EXECUTE_PYTHON.equals(toolName)) {
            return "python";
        }
        if (ToolNameConstants.EXECUTE_JS.equals(toolName)) {
            return "javascript";
        }
        return "shell";
    }

    /**
     * 截断命令预览，避免长脚本撑爆审批消息或渠道卡片。
     *
     * @param code 原始命令或脚本文本。
     * @return 返回最多 400 字符的预览。
     */
    private String trimPreview(String code) {
        String normalized = StrUtil.nullToEmpty(code).trim();
        if (normalized.length() <= COMMAND_PREVIEW_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, COMMAND_PREVIEW_LENGTH) + "\n...";
    }

    /**
     * 判断检测结果是否包含安全扫描策略键，用于隐藏永久审批选项。
     *
     * @param detection 危险命令或策略检测结果。
     * @return 包含安全扫描策略键时返回 true。
     */
    private boolean containsTirith(DangerousCommandApprovalService.DetectionResult detection) {
        if (detection == null) {
            return false;
        }
        for (String patternKey : detection.effectivePatternKeys()) {
            if (StrUtil.nullToEmpty(patternKey).startsWith("tirith:")) {
                return true;
            }
        }
        return false;
    }
}
