package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.skills.sys.NodejsSkill;
import org.noear.solon.ai.skills.sys.PythonSkill;

/** Solon AI code execution skills wrapped with Hermes-style safety checks. */
public class HermesCodeExecutionSkills {
    private HermesCodeExecutionSkills() {}

    public static class SafePythonSkill extends PythonSkill {
        private final SecurityPolicyService securityPolicyService;

        public SafePythonSkill(
                String workDir, String pythonCommand, SecurityPolicyService securityPolicyService) {
            super(workDir, pythonCommand);
            this.securityPolicyService = securityPolicyService;
        }

        @Override
        @ToolMapping(name = "execute_python", description = "执行 Python 代码，并返回标准输出。")
        public String execute(
                @Param("code") String code,
                @Param(name = "timeout", required = false, defaultValue = "120000", description = "可选超时时间，单位为毫秒")
                        Integer timeout) {
            assertSafe(ToolNameConstants.EXECUTE_PYTHON, code, securityPolicyService);
            return super.execute(code, timeout);
        }
    }

    public static class SafeNodejsSkill extends NodejsSkill {
        private final SecurityPolicyService securityPolicyService;

        public SafeNodejsSkill(String workDir, SecurityPolicyService securityPolicyService) {
            super(workDir);
            this.securityPolicyService = securityPolicyService;
        }

        @Override
        @ToolMapping(name = "execute_js", description = "执行 Node.js JavaScript 代码，并返回标准输出。")
        public String execute(
                @Param("code") String code,
                @Param(name = "timeout", required = false, defaultValue = "120000", description = "可选超时时间，单位为毫秒")
                        Integer timeout) {
            assertSafe(ToolNameConstants.EXECUTE_JS, code, securityPolicyService);
            return super.execute(code, timeout);
        }
    }

    static void assertSafe(
            String toolName, String code, SecurityPolicyService securityPolicyService) {
        if (securityPolicyService != null) {
            SecurityPolicyService.FileVerdict fileVerdict =
                    securityPolicyService.checkCommandPaths(code);
            if (!fileVerdict.isAllowed()) {
                throw new IllegalArgumentException(blockedFileMessage(toolName, fileVerdict));
            }
            SecurityPolicyService.UrlVerdict urlVerdict =
                    securityPolicyService.checkCommandUrls(code);
            if (!urlVerdict.isAllowed()) {
                throw new IllegalArgumentException(blockedUrlMessage(urlVerdict));
            }
        }

        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(null, securityPolicyService);
        DangerousCommandApprovalService.DetectionResult hardline =
                approvalService.detectHardline(toolName, code);
        if (hardline != null) {
            throw new IllegalArgumentException(blockedHardlineMessage(toolName, hardline));
        }
        String foregroundGuidance = approvalService.foregroundBackgroundGuidance(toolName, code);
        if (foregroundGuidance != null) {
            throw new IllegalArgumentException(foregroundGuidance);
        }
        DangerousCommandApprovalService.DetectionResult dangerous =
                approvalService.detect(toolName, code);
        if (dangerous != null) {
            throw new IllegalArgumentException(blockedDangerousMessage(toolName, dangerous));
        }
    }

    private static String blockedFileMessage(
            String toolName, SecurityPolicyService.FileVerdict verdict) {
        return "BLOCKED: 文件安全策略阻止访问："
                + verdict.getMessage()
                + "\n工具："
                + toolName
                + "\n路径："
                + StrUtil.nullToEmpty(verdict.getPath())
                + "\n请改用工作区内的普通项目文件，敏感凭据文件不能通过 Agent 工具读取、写入或删除。";
    }

    private static String blockedUrlMessage(SecurityPolicyService.UrlVerdict verdict) {
        return "BLOCKED: URL 安全策略阻止访问："
                + verdict.getMessage()
                + "\nURL: "
                + SecretRedactor.maskUrl(verdict.getUrl())
                + "\n请换用公开、可信且符合网站访问策略的地址。";
    }

    private static String blockedHardlineMessage(
            String toolName, DangerousCommandApprovalService.DetectionResult detection) {
        return "BLOCKED: 该 "
                + toolName
                + " 调用命中硬阻断安全规则："
                + StrUtil.blankToDefault(detection.getDescription(), detection.getPatternKey())
                + "。请改用更小、更可审计的安全操作。";
    }

    private static String blockedDangerousMessage(
            String toolName, DangerousCommandApprovalService.DetectionResult detection) {
        return "BLOCKED: 该 "
                + toolName
                + " 调用命中危险命令安全规则："
                + StrUtil.blankToDefault(detection.getDescription(), detection.getPatternKey())
                + "。直接执行入口没有审批上下文，请改用可审批的 Agent 工具调用流程或拆成更安全的操作。";
    }
}
