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
     * 构建未配置密码时 sudo -S 的不可绕过提示，避免 Agent 通过标准输入猜测提权密码。
     *
     * @param toolName 触发拒绝的工具名称。
     * @param code 待执行命令。
     * @return 返回 sudo stdin 拒绝提示。
     */
    String buildSudoStdinMessage(String toolName, String code) {
        StringBuilder buffer = new StringBuilder();
        buffer.append(
                "BLOCKED: 未配置 solonclaw.terminal.sudoPassword 或 SOLONCLAW_SUDO_PASSWORD 时，不允许显式使用 sudo -S。该写法会让 Agent 通过标准输入猜测 sudo 密码。");
        buffer.append("请配置受管 sudo 密码，或在 Agent 外部终端手动执行该命令。");
        appendToolAndCommandPreview(buffer, toolName, code);
        return buffer.toString();
    }

    /**
     * 构建用户 deny 规则阻断消息，明确该配置不可通过审批绕过。
     *
     * @param denyReason 命中的用户规则说明。
     * @return 返回用户 deny 规则阻断提示。
     */
    String buildUserDenyMessage(String denyReason) {
        return "BLOCKED: 命令匹配用户配置的不可绕过 approvals.deny 规则: "
                + redactApprovalDisplay(denyReason, 1000)
                + "。如需修改请调整 approvals.deny 配置。";
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
