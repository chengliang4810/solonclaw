package com.jimuqu.solon.claw.tool.runtime;

import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.LONG_LIVED_FOREGROUND_PATTERNS;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.RULES;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.hardlineBlockedCategories;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.hardlineCoveredTools;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.hardlineRuleCount;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.hardlineRuleSamples;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.preferredRuleSamples;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.ruleSamples;
import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.terminalGuardrailKeys;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 生成危险命令审批相关策略摘要，避免审批服务主类承载大量只读格式化逻辑。 */
final class DangerousCommandApprovalPolicySummaries {
    /** 审批服务，用于读取公共策略状态。 */
    private final DangerousCommandApprovalService service;

    /** 应用配置，用于读取审批、终端和安全策略配置。 */
    private final AppConfig appConfig;

    /** 是否已配置 Tirith 扫描服务。 */
    private final boolean tirithConfigured;

    /** 审批观察者数量。 */
    private final int approvalObserverCount;

    /**
     * 创建策略摘要生成器。
     *
     * @param service 审批服务。
     * @param appConfig 应用配置。
     * @param tirithConfigured 是否已配置 Tirith 扫描服务。
     * @param approvalObserverCount 审批观察者数量。
     */
    DangerousCommandApprovalPolicySummaries(
            DangerousCommandApprovalService service,
            AppConfig appConfig,
            boolean tirithConfigured,
            int approvalObserverCount) {
        this.service = service;
        this.appConfig = appConfig;
        this.tirithConfigured = tirithConfigured;
        this.approvalObserverCount = approvalObserverCount;
    }

    /**
     * 执行审批策略摘要相关逻辑。
     *
     * @return 返回审批策略Summary结果。
     */
    Map<String, Object> approvalPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        String guardrailMode = service.guardrailMode();
        summary.put("guardrailMode", guardrailMode);
        summary.put("guardrailCronMode", service.guardrailCronMode());
        summary.put("cronApprovalPolicy", cronApprovalPolicySummary());
        summary.put("subagentApprovalPolicy", subagentApprovalPolicySummary());
        summary.put("smartJudgeConfigured", Boolean.valueOf(service.hasSmartApprovalJudge()));
        summary.put("smartApprovalPolicy", smartApprovalPolicySummary());
        summary.put("tirithApprovalPolicy", tirithApprovalPolicySummary());
        summary.put("dangerousRuleCount", Integer.valueOf(RULES.size()));
        summary.put("hardlineRuleCount", Integer.valueOf(hardlineRuleCount()));
        summary.put("dangerousRuleSamples", ruleSamples(RULES, 8));
        summary.put(
                "domesticCloudRuleSamples",
                preferredRuleSamples(
                        RULES,
                        3,
                        "domestic_cloud_cli_credential_config_change",
                        "domestic_object_storage_recursive_remove",
                        "object_storage_exposure_change"));
        summary.put(
                "cloudStorageRuleSamples",
                preferredRuleSamples(
                        RULES,
                        4,
                        "aws_s3_recursive_remove",
                        "domestic_object_storage_recursive_remove",
                        "remote_credential_file_transfer",
                        "object_storage_exposure_change"));
        summary.put(
                "credentialHandlingRuleSamples",
                preferredRuleSamples(
                        RULES,
                        5,
                        "sensitive_environment_read",
                        "sensitive_clipboard_export",
                        "sensitive_file_clipboard_export",
                        "network_credential_file_send",
                        "remote_credential_file_transfer"));
        summary.put(
                "secretStoreRuleSamples",
                preferredRuleSamples(
                        RULES,
                        4,
                        "secret_store_read",
                        "secret_store_write",
                        "secret_store_destroy",
                        "encrypted_secret_file_decrypt"));
        summary.put("networkCredentialFieldAliasDetection", Boolean.TRUE);
        summary.put("sensitiveHttpHeaderAliasDetection", Boolean.TRUE);
        summary.put("rawCredentialFileUploadDetection", Boolean.TRUE);
        summary.put("sensitiveClipboardExportDetection", Boolean.TRUE);
        summary.put("credentialFileClipboardExportDetection", Boolean.TRUE);
        summary.put("pythonCredentialFileClipboardExportDetection", Boolean.TRUE);
        summary.put("javascriptCredentialFileClipboardExportDetection", Boolean.TRUE);
        summary.put("codeCredentialFileStdoutDetection", Boolean.TRUE);
        summary.put("pythonCredentialFileStdoutDetection", Boolean.TRUE);
        summary.put("pythonCredentialFileVariableStdoutDetection", Boolean.TRUE);
        summary.put("pythonCredentialFileLogWriteDetection", Boolean.TRUE);
        summary.put("javascriptCredentialFileStdoutDetection", Boolean.TRUE);
        summary.put("javascriptCredentialFileVariableStdoutDetection", Boolean.TRUE);
        summary.put("javascriptCredentialFileLogWriteDetection", Boolean.TRUE);
        summary.put("codeCredentialFileVariableStdoutDetection", Boolean.TRUE);
        summary.put("codeHttpCredentialDisclosureDetection", Boolean.TRUE);
        summary.put("codeHttpCredentialFileDisclosureDetection", Boolean.TRUE);
        summary.put("codeHttpCredentialFileVariableDisclosureDetection", Boolean.TRUE);
        summary.put("powershellCredentialFileHttpDisclosureDetection", Boolean.TRUE);
        summary.put("configuredCredentialCommandPathDetection", Boolean.TRUE);
        summary.put("recursiveStructuredToolArgsDetection", Boolean.TRUE);
        summary.put("nestedArrayCommandArgumentDetection", Boolean.TRUE);
        summary.put("urlPolicyPrechecked", Boolean.TRUE);
        summary.put("privateUrlPolicyPrechecked", Boolean.TRUE);
        summary.put("credentialUrlPolicyPrechecked", Boolean.TRUE);
        summary.put("websitePolicyPrechecked", Boolean.TRUE);
        summary.put("unsafeUrlBlockedBeforeApproval", Boolean.TRUE);
        summary.put("unsafeUrlApprovalBypassAllowed", Boolean.FALSE);
        summary.put("hardlineRuleSamples", hardlineRuleSamples(8));
        summary.put("hardlinePolicy", hardlinePolicySummary());
        summary.put("terminalGuardrailCount", Integer.valueOf(terminalGuardrailKeys().size()));
        summary.put("terminalGuardrails", terminalGuardrailKeys());
        summary.put("sudoRewriteConfigured", Boolean.valueOf(isSudoPasswordConfigured()));
        summary.put("backgroundProcessGuard", Boolean.TRUE);
        summary.put("terminalGuardrailPolicy", terminalGuardrailPolicySummary());
        summary.put("approvalTimeoutSeconds", Integer.valueOf(service.approvalTimeoutSeconds()));
        summary.put(
                "gatewayTimeoutSeconds", Integer.valueOf(service.approvalGatewayTimeoutSeconds()));
        summary.put("alwaysApprovalCount", Integer.valueOf(service.listAlwaysApprovals().size()));
        summary.put("slashConfirmPolicy", slashConfirmPolicySummary());
        summary.put("approvalCardPolicy", approvalCardPolicySummary());
        summary.put("auditLogPolicy", approvalAuditPolicySummary());
        summary.put("mcpReloadPolicy", mcpReloadPolicySummary());
        summary.put("approvalLifecyclePolicy", approvalLifecyclePolicySummary());
        summary.put(
                "description",
                "Dangerous commands require approval, hardline commands are blocked, and foreground terminal commands are guarded against unmanaged long-running background work.");
        return summary;
    }

    /**
     * 执行hardline策略摘要相关逻辑。
     *
     * @return 返回hardline策略Summary结果。
     */
    Map<String, Object> hardlinePolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("ruleCount", Integer.valueOf(hardlineRuleCount()));
        summary.put("ruleSamples", hardlineRuleSamples(12));
        summary.put("coveredTools", hardlineCoveredTools());
        summary.put("blockedCategories", hardlineBlockedCategories());
        summary.put("approvalBypassAllowed", Boolean.FALSE);
        summary.put("slashApproveBypassAllowed", Boolean.FALSE);
        summary.put("sessionApprovalBypassAllowed", Boolean.FALSE);
        summary.put("alwaysApprovalBypassAllowed", Boolean.FALSE);
        summary.put("sessionAutoApprovalBypassAllowed", Boolean.FALSE);
        summary.put("smartApprovalBypassAllowed", Boolean.FALSE);
        summary.put("blockingDecision", "block");
        summary.put("approvalRequired", Boolean.FALSE);
        summary.put("commandPreviewRedacted", Boolean.TRUE);
        summary.put(
                "description",
                "Hardline commands are blocked before approval handling; slash approvals, session approvals, always approvals, smart approval, and session auto approval cannot bypass them.");
        return summary;
    }

    /**
     * 执行smart审批策略摘要相关逻辑。
     *
     * @return 返回smart审批策略Summary结果。
     */
    Map<String, Object> smartApprovalPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        String guardrailMode = service.guardrailMode();
        boolean smartMode = "smart".equals(guardrailMode);
        boolean judgeConfigured = service.hasSmartApprovalJudge();
        summary.put("guardrailMode", guardrailMode);
        summary.put("smartMode", Boolean.valueOf(smartMode));
        summary.put("judgeConfigured", Boolean.valueOf(judgeConfigured));
        summary.put("active", Boolean.valueOf(smartMode && judgeConfigured));
        summary.put("decisionTypes", Arrays.asList("approve", "escalate", "deny"));
        summary.put("approveWritesSessionApproval", Boolean.TRUE);
        summary.put("approveMarksCurrentThread", Boolean.TRUE);
        summary.put("escalateFallsBackToHumanApproval", Boolean.TRUE);
        summary.put("denyBlocksExecution", Boolean.TRUE);
        summary.put("judgeFailureFallsBackToHumanApproval", Boolean.TRUE);
        summary.put("hardlinePrechecked", Boolean.TRUE);
        summary.put("filePolicyPrechecked", Boolean.TRUE);
        summary.put("urlPolicyPrechecked", Boolean.TRUE);
        summary.put("terminalGuardrailPrechecked", Boolean.TRUE);
        summary.put("tirithFindingsIncluded", Boolean.TRUE);
        summary.put("subagentPolicyRunsAfterSmartApproval", Boolean.TRUE);
        summary.put("approvalCardFallback", Boolean.TRUE);
        summary.put("reasonStoredInBlockMessage", Boolean.TRUE);
        summary.put("commandPreviewRedacted", Boolean.TRUE);
        summary.put(
                "description",
                "Smart approval only evaluates commands that remain approvable after hardline, file, URL, and terminal guardrail checks; approvals become session-scoped while escalations fall back to human confirmation.");
        return summary;
    }

    /**
     * 执行tirith审批策略摘要相关逻辑。
     *
     * @return 返回tirith审批策略Summary结果。
     */
    Map<String, Object> tirithApprovalPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("scannerConfigured", Boolean.valueOf(tirithConfigured));
        summary.put(
                "scanRunsInApprovalMode",
                Boolean.valueOf(!"bypass".equals(service.guardrailMode())));
        summary.put("patternKeyPrefix", "tirith:");
        summary.put("emptyFindingsPatternKey", "tirith:security_scan");
        summary.put("findingsBecomePatternKeys", Boolean.TRUE);
        summary.put("combinedWithLocalDangerRules", Boolean.TRUE);
        summary.put("permanentApprovalAllowed", Boolean.FALSE);
        summary.put("alwaysScopeDowngradedToSession", Boolean.TRUE);
        summary.put("approvalCardAlwaysHidden", Boolean.TRUE);
        summary.put("smartApprovalCanApproveSessionOnly", Boolean.TRUE);
        summary.put("smartApprovalCanDeny", Boolean.TRUE);
        summary.put("pendingMessageBlocksAlwaysScope", Boolean.TRUE);
        summary.put("descriptionRedacted", Boolean.TRUE);
        summary.put(
                "description",
                "Tirith findings are converted into tirith:* approval patterns, can be combined with local dangerous rules, and never create permanent approvals.");
        return summary;
    }

    /**
     * 执行定时任务审批策略摘要相关逻辑。
     *
     * @return 返回定时任务审批策略Summary结果。
     */
    Map<String, Object> cronApprovalPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        String mode = service.guardrailCronMode();
        summary.put("guardrailCronMode", mode);
        summary.put("autoApproveDangerousCommands", Boolean.valueOf("approve".equals(mode)));
        summary.put("defaultDecision", cronDefaultDecision(mode));
        summary.put(
                "configKeys",
                Arrays.asList("security.guardrailCronMode", "security.guardrailCronScope"));
        summary.put("supportedModes", Arrays.asList("strict", "bypass", "approval", "approve"));
        summary.put("approvalScope", cronApprovalScope());
        summary.put("guardrailApprovalCanPauseCron", Boolean.TRUE);
        summary.put("jobScopeIncludesScriptFingerprint", Boolean.TRUE);
        summary.put("hardlineAlwaysBlocked", Boolean.TRUE);
        summary.put("filePolicyPrechecked", Boolean.TRUE);
        summary.put("urlPolicyPrechecked", Boolean.TRUE);
        summary.put("terminalGuardrailPrechecked", Boolean.TRUE);
        summary.put("dangerousPatternCheckedBeforeRun", Boolean.TRUE);
        summary.put("scriptContentChecked", Boolean.TRUE);
        summary.put(
                "description",
                "Cron uses guardrailCronMode for approvable dangerous commands: approval pauses the job for channel approval, strict blocks, bypass skips soft guardrails, and approve auto-approves approvable commands; hardline commands remain blocked.");
        return summary;
    }

    /**
     * 执行定时任务默认决策相关逻辑。
     *
     * @param mode 模式参数。
     * @return 返回定时任务默认Decision结果。
     */
    private String cronDefaultDecision(String mode) {
        if ("approve".equals(mode) || "bypass".equals(mode)) {
            return mode;
        }
        if ("approval".equals(mode)) {
            return "request_approval";
        }
        return "deny";
    }

    /**
     * 执行定时任务审批范围相关逻辑。
     *
     * @return 返回定时任务审批范围结果。
     */
    private String cronApprovalScope() {
        String scope =
                appConfig == null || appConfig.getSecurity() == null
                        ? ""
                        : appConfig.getSecurity().getGuardrailCronScope();
        scope = StrUtil.blankToDefault(scope, "job").trim().toLowerCase(Locale.ROOT);
        return "global".equals(scope) || "session".equals(scope) ? scope : "job";
    }

    /**
     * 执行子Agent审批策略摘要相关逻辑。
     *
     * @return 返回subagent审批策略Summary结果。
     */
    Map<String, Object> subagentApprovalPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("defaultDecision", "human_approval");
        summary.put("runKind", "subagent");
        summary.put("hardlinePrechecked", Boolean.TRUE);
        summary.put("terminalGuardrailPrechecked", Boolean.TRUE);
        summary.put("smartApprovalRunsBeforeSubagentPolicy", Boolean.TRUE);
        summary.put("humanApprovalPromptSuppressed", Boolean.FALSE);
        summary.put(
                "description",
                "Subagent dangerous commands use the same human approval flow as other Agent runs.");
        return summary;
    }

    /**
     * 执行斜杠命令Confirm策略摘要相关逻辑。
     *
     * @return 返回slash Confirm策略Summary结果。
     */
    Map<String, Object> slashConfirmPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("commands", Arrays.asList("/approve", "/deny"));
        summary.put("selectorSupported", Boolean.TRUE);
        summary.put("listSupported", Boolean.TRUE);
        summary.put("approveAllSupported", Boolean.TRUE);
        summary.put("denyAllSupported", Boolean.TRUE);
        summary.put("clearSessionSupported", Boolean.TRUE);
        summary.put("clearAlwaysSupported", Boolean.TRUE);
        summary.put("clearAllSupported", Boolean.TRUE);
        summary.put("scopes", Arrays.asList("once", "session", "always"));
        summary.put("defaultScope", "once");
        summary.put(
                "managementCommands",
                Arrays.asList(
                        "/approve list",
                        "/approve status",
                        "/approve clear session",
                        "/approve clear always",
                        "/approve clear all",
                        "/deny list",
                        "/deny status",
                        "/deny all"));
        summary.put("pendingQueueSupported", Boolean.TRUE);
        summary.put(
                "pendingQueueContextKey",
                DangerousCommandApprovalService.CONTEXT_PENDING_APPROVAL_QUEUE);
        summary.put("pendingListHidesApprovalKey", Boolean.TRUE);
        summary.put("approvalKeySelectorHidden", Boolean.TRUE);
        summary.put("pendingListUsesSafeSelector", Boolean.TRUE);
        summary.put("pendingListShowsPatternKey", Boolean.TRUE);
        summary.put("sessionApprovalListShowsCountOnly", Boolean.TRUE);
        summary.put("alwaysApprovalListShowsCountOnly", Boolean.TRUE);
        summary.put(
                "approvalCardDeliveryMode",
                DangerousCommandApprovalService.DELIVERY_MODE_APPROVAL_CARD);
        summary.put(
                "approvalCardPlatforms",
                Arrays.asList(PlatformType.FEISHU.name(), PlatformType.QQBOT.name()));
        summary.put("approvalCardActionKey", DangerousCommandApprovalService.CARD_ACTION_KEY);
        summary.put(
                "approvalCardApproveAction", DangerousCommandApprovalService.CARD_ACTION_APPROVE);
        summary.put("approvalCardDenyAction", DangerousCommandApprovalService.CARD_ACTION_DENY);
        summary.put("approvalCardScopeKey", DangerousCommandApprovalService.CARD_SCOPE_KEY);
        summary.put(
                "approvalCardApprovalIdKey", DangerousCommandApprovalService.CARD_APPROVAL_ID_KEY);
        summary.put("permanentApprovalAllowedExceptTirith", Boolean.TRUE);
        summary.put("tirithAlwaysDowngradedToSession", Boolean.TRUE);
        summary.put(
                "selectorTokenPattern",
                DangerousCommandApprovalService.APPROVAL_SELECTOR_TOKEN.pattern());
        summary.put(
                "selectorPrefixMinLength",
                Integer.valueOf(
                        DangerousCommandApprovalService.APPROVAL_SELECTOR_PREFIX_MIN_LENGTH));
        summary.put("unsafeSelectorRejected", Boolean.TRUE);
        summary.put("approverRedacted", Boolean.TRUE);
        summary.put("commandPreviewRedacted", Boolean.TRUE);
        summary.put("encodedUrlParameterRedacted", Boolean.TRUE);
        summary.put("approvalMetadataRedacted", Boolean.TRUE);
        summary.put("observerEventsRedacted", Boolean.TRUE);
        summary.put("approvalTimeoutSeconds", Integer.valueOf(service.approvalTimeoutSeconds()));
        summary.put(
                "gatewayTimeoutSeconds", Integer.valueOf(service.approvalGatewayTimeoutSeconds()));
        summary.put(
                "description",
                "Slash approval commands can approve or deny one pending item, all pending items, or an id selector, with once/session/always scopes, hidden approval keys in list output, and redacted approval metadata.");
        return summary;
    }

    /**
     * 执行审批卡片策略摘要相关逻辑。
     *
     * @return 返回审批Card策略Summary结果。
     */
    Map<String, Object> approvalCardPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("deliveryMode", DangerousCommandApprovalService.DELIVERY_MODE_APPROVAL_CARD);
        summary.put(
                "supportedPlatforms",
                Arrays.asList(PlatformType.FEISHU.name(), PlatformType.QQBOT.name()));
        summary.put("unsupportedPlatformsReturnEmptyExtras", Boolean.TRUE);
        summary.put("actionKey", DangerousCommandApprovalService.CARD_ACTION_KEY);
        summary.put("approveAction", DangerousCommandApprovalService.CARD_ACTION_APPROVE);
        summary.put("denyAction", DangerousCommandApprovalService.CARD_ACTION_DENY);
        summary.put("scopeKey", DangerousCommandApprovalService.CARD_SCOPE_KEY);
        summary.put("approvalIdKey", DangerousCommandApprovalService.CARD_APPROVAL_ID_KEY);
        summary.put("scopeOptions", Arrays.asList("once", "session", "always"));
        summary.put("defaultScope", "once");
        summary.put("approvalIdSelectorSupported", Boolean.TRUE);
        summary.put(
                "selectorTokenPattern",
                DangerousCommandApprovalService.APPROVAL_SELECTOR_TOKEN.pattern());
        summary.put("unsafeSelectorRejected", Boolean.TRUE);
        summary.put("outboundApprovalIdSanitized", Boolean.TRUE);
        summary.put("unsafeApprovalIdFallsBackToKeySelector", Boolean.TRUE);
        summary.put("secretLikeApprovalIdFallsBackToKeySelector", Boolean.TRUE);
        summary.put("secretLikeInboundApprovalIdRejected", Boolean.TRUE);
        summary.put("approveCommandGenerated", Boolean.TRUE);
        summary.put("denyCommandGenerated", Boolean.TRUE);
        summary.put("alwaysScopeCommandGenerated", Boolean.TRUE);
        summary.put("sessionScopeCommandGenerated", Boolean.TRUE);
        summary.put("domesticCardLabelsLocalized", Boolean.TRUE);
        summary.put("feishuChineseCardLabels", Boolean.TRUE);
        summary.put("qqbotSessionActionSupported", Boolean.TRUE);
        summary.put("tirithPermanentApprovalHidden", Boolean.TRUE);
        summary.put("commandPreviewRedacted", Boolean.TRUE);
        summary.put("descriptionPreviewRedacted", Boolean.TRUE);
        summary.put("toolNameRedacted", Boolean.TRUE);
        summary.put("commandPreviewRedactedInExtras", Boolean.TRUE);
        summary.put("rawCommandRedactedInExtras", Boolean.TRUE);
        summary.put("encodedUrlParameterRedacted", Boolean.TRUE);
        summary.put("encodedUrlParameterRedactedInExtras", Boolean.TRUE);
        summary.put("semicolonUrlParameterRedacted", Boolean.TRUE);
        summary.put("fragmentUrlParameterRedacted", Boolean.TRUE);
        summary.put(
                "description",
                "Approval card extras are only emitted for supported domestic card platforms, use safe approval selectors in outbound card payloads, map card actions back to /approve or /deny commands with redacted previews, and expose localized card labels plus session-scope channel actions.");
        return summary;
    }

    /**
     * 执行审批审计策略摘要相关逻辑。
     *
     * @return 返回审批审计策略Summary结果。
     */
    Map<String, Object> approvalAuditPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("observerCount", Integer.valueOf(approvalObserverCount));
        summary.put("requestEvents", Boolean.TRUE);
        summary.put("responseEvents", Boolean.TRUE);
        summary.put("eventTypes", Arrays.asList("request", "response"));
        summary.put(
                "responseOutcomes",
                Arrays.asList(
                        DangerousCommandApprovalService.ApprovalResponseEvent.OUTCOME_APPROVED,
                        DangerousCommandApprovalService.ApprovalResponseEvent.OUTCOME_DENIED,
                        DangerousCommandApprovalService.ApprovalResponseEvent.OUTCOME_TIMED_OUT));
        summary.put("responseStatusDistinct", Boolean.TRUE);
        summary.put("repositoryBackedWhenConfigured", Boolean.TRUE);
        summary.put("observerFailureIsolated", Boolean.TRUE);
        summary.put("approverRedacted", Boolean.TRUE);
        summary.put("commandPreviewRedacted", Boolean.TRUE);
        summary.put("descriptionRedacted", Boolean.TRUE);
        summary.put("approvalKeyRedacted", Boolean.TRUE);
        summary.put("encodedUrlParameterRedacted", Boolean.TRUE);
        summary.put("commandHashStored", Boolean.TRUE);
        summary.put("patternKeysStored", Boolean.TRUE);
        summary.put("timestampsStored", Boolean.TRUE);
        summary.put("recentDashboardViewSupported", Boolean.TRUE);
        summary.put("manualRevocationAudited", Boolean.TRUE);
        summary.put(
                "description",
                "Approval request and response events can be persisted with redacted command previews, approvers, descriptions, pattern keys, command hashes, and approval timestamps.");
        return summary;
    }

    /**
     * 执行MCPReload策略摘要相关逻辑。
     *
     * @return 返回MCP Reload策略Summary结果。
     */
    Map<String, Object> mcpReloadPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        boolean confirmRequired =
                appConfig == null
                        || appConfig.getApprovals() == null
                        || appConfig.getApprovals().isMcpReloadConfirm();
        summary.put("command", "/reload-mcp");
        summary.put("confirmRequired", Boolean.valueOf(confirmRequired));
        summary.put("configKey", "approvals.mcpReloadConfirm");
        summary.put("slashConfirmBacked", Boolean.TRUE);
        summary.put("directRunArgument", "now");
        summary.put("alwaysConfirmArgument", "always");
        summary.put("persistentDisableSupported", Boolean.TRUE);
        summary.put("runtimeConfigPersisted", Boolean.TRUE);
        summary.put("toolChangeNoticeInjected", Boolean.TRUE);
        summary.put("changedServerSummary", Boolean.TRUE);
        summary.put("toolCountSummary", Boolean.TRUE);
        summary.put("oauthUrlSafetyCovered", Boolean.TRUE);
        summary.put("encodedUrlParameterRedacted", Boolean.TRUE);
        summary.put("reloadHistoryNoticeRedacted", Boolean.TRUE);
        summary.put(
                "description",
                "MCP reload can require slash confirmation, supports now/always arguments, persists the confirmation flag, and records tool-change notices for the next model turn.");
        return summary;
    }

    /**
     * 执行审批生命周期策略摘要相关逻辑。
     *
     * @return 返回审批生命周期策略Summary结果。
     */
    Map<String, Object> approvalLifecyclePolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put(
                "pendingQueueContextKey",
                DangerousCommandApprovalService.CONTEXT_PENDING_APPROVAL_QUEUE);
        summary.put("pendingListPrunedBeforeRead", Boolean.TRUE);
        summary.put("selectorSupported", Boolean.TRUE);
        summary.put("listSupported", Boolean.TRUE);
        summary.put("approveAllSupported", Boolean.TRUE);
        summary.put("rejectAllSupported", Boolean.TRUE);
        summary.put("clearSessionSupported", Boolean.TRUE);
        summary.put("clearAlwaysSupported", Boolean.TRUE);
        summary.put("clearAllSupported", Boolean.TRUE);
        summary.put("scopes", Arrays.asList("once", "session", "always"));
        summary.put(
                "onceScopeStoresContextKey",
                DangerousCommandApprovalService.CONTEXT_ONCE_APPROVALS);
        summary.put(
                "sessionScopeStoresContextKey",
                DangerousCommandApprovalService.CONTEXT_SESSION_APPROVALS);
        summary.put("alwaysScopeUsesGlobalSettings", Boolean.TRUE);
        summary.put("tirithAlwaysScopeDowngradedToSession", Boolean.TRUE);
        summary.put(
                "currentThreadApprovalTtlMillis",
                Long.valueOf(DangerousCommandApprovalService.CURRENT_THREAD_APPROVAL_TTL_MILLIS));
        summary.put("currentThreadApprovalEnabled", Boolean.TRUE);
        summary.put(
                "selectorTokenPattern",
                DangerousCommandApprovalService.APPROVAL_SELECTOR_TOKEN.pattern());
        summary.put("unsafeSelectorRejected", Boolean.TRUE);
        summary.put("bulkRejectUsesSafeSelector", Boolean.TRUE);
        summary.put("approveRemovesPendingApproval", Boolean.TRUE);
        summary.put("rejectRemovesPendingApproval", Boolean.TRUE);
        summary.put("sessionSnapshotUpdated", Boolean.TRUE);
        summary.put("approvalRequestObserved", Boolean.TRUE);
        summary.put("approvalResponseObserved", Boolean.TRUE);
        summary.put("approverRedacted", Boolean.TRUE);
        summary.put("approvalKeyRedacted", Boolean.TRUE);
        summary.put("commandPreviewRedacted", Boolean.TRUE);
        summary.put("encodedUrlParameterRedacted", Boolean.TRUE);
        summary.put(
                "description",
                "Approval lifecycle stores queued approvals in session context, supports once/session/always scopes, downgrades scanner findings to session scope, updates snapshots, and emits redacted request/response events.");
        return summary;
    }

    /**
     * 执行终端防护策略摘要相关逻辑。
     *
     * @return 返回终端防护策略Summary结果。
     */
    Map<String, Object> terminalGuardrailPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("backgroundShellWrappersBlocked", Arrays.asList("nohup", "disown", "setsid"));
        summary.put(
                "detachedSessionLaunchersBlocked",
                Arrays.asList(
                        "tmux new-session -d", "screen -dmS", "systemd-run", "cmd /c start /B"));
        summary.put(
                "powershellBackgroundCommandsBlocked",
                Arrays.asList("Start-Process", "Start-Job", "Start-ThreadJob"));
        summary.put("powershellStartProcessRequiresWait", Boolean.TRUE);
        summary.put("powershellStartProcessNoNewWindowNotEnough", Boolean.TRUE);
        summary.put("powershellStartProcessPassThruNotEnough", Boolean.TRUE);
        summary.put("inlineAmpersandBlocked", Boolean.TRUE);
        summary.put("trailingAmpersandBlocked", Boolean.TRUE);
        summary.put("longLivedForegroundBlocked", Boolean.TRUE);
        summary.put(
                "longLivedForegroundPatternCount",
                Integer.valueOf(LONG_LIVED_FOREGROUND_PATTERNS.size()));
        summary.put(
                "longLivedForegroundSamples",
                Arrays.asList("npm run dev", "docker compose up", "vite", "python -m http.server"));
        summary.put("commandPathPrechecked", Boolean.TRUE);
        summary.put("credentialPathPrechecked", Boolean.TRUE);
        summary.put("downloadOutputPathPrechecked", Boolean.TRUE);
        summary.put("downloadOutputDetachedOptionPrechecked", Boolean.TRUE);
        summary.put("networkUploadSourcePathPrechecked", Boolean.TRUE);
        summary.put("proxyUrlPrechecked", Boolean.TRUE);
        summary.put("preproxyUrlPrechecked", Boolean.TRUE);
        summary.put("systemDnsCommandPrechecked", Boolean.TRUE);
        summary.put("systemProxyCommandPrechecked", Boolean.TRUE);
        summary.put("windowsRegistryProxyCommandPrechecked", Boolean.TRUE);
        summary.put("hostsAndResolverPathPrechecked", Boolean.TRUE);
        summary.put("managedBackgroundProcessRequired", Boolean.TRUE);
        summary.put("processRegistryBacked", Boolean.TRUE);
        summary.put("sudoRewriteConfigured", Boolean.valueOf(isSudoPasswordConfigured()));
        summary.put("sudoPasswordRedacted", Boolean.TRUE);
        summary.put("foregroundMaxTimeoutSeconds", Integer.valueOf(maxForegroundTimeoutSeconds()));
        summary.put("foregroundMaxRetries", Integer.valueOf(foregroundMaxRetries()));
        summary.put(
                "foregroundRetryBaseDelaySeconds",
                Integer.valueOf(foregroundRetryBaseDelaySeconds()));
        summary.put(
                "description",
                "Foreground terminal guardrails block unmanaged background wrappers, inline background operators, credential path access, unsafe proxy/preproxy URLs, system DNS/proxy changes, hosts/resolver writes, download output or network upload source credential paths, and common long-running dev/server commands, with managed background process guidance and redacted sudo support.");
        return summary;
    }

    /** 判断是否配置了 sudo 密码。 */
    private boolean isSudoPasswordConfigured() {
        return appConfig != null
                && appConfig.getTerminal() != null
                && StrUtil.isNotBlank(appConfig.getTerminal().getSudoPassword());
    }

    /** 返回前台命令最大超时时间。 */
    private int maxForegroundTimeoutSeconds() {
        return appConfig == null || appConfig.getTerminal() == null
                ? 0
                : appConfig.getTerminal().getMaxForegroundTimeoutSeconds();
    }

    /** 返回前台命令最大重试次数。 */
    private int foregroundMaxRetries() {
        return appConfig == null || appConfig.getTerminal() == null
                ? 0
                : appConfig.getTerminal().getForegroundMaxRetries();
    }

    /** 返回前台命令重试基础延迟。 */
    private int foregroundRetryBaseDelaySeconds() {
        return appConfig == null || appConfig.getTerminal() == null
                ? 0
                : appConfig.getTerminal().getForegroundRetryBaseDelaySeconds();
    }
}
