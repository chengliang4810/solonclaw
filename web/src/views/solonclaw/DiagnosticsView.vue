<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Button, SpaceCompact, Input, Select, Spin, Switch, Tag, TextArea, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import ApprovalEventsPanel from '@/components/solonclaw/diagnostics/ApprovalEventsPanel.vue'
import {
  auditSecurity,
  fetchApprovalEvents,
  fetchApprovalStats,
  fetchAlwaysApprovals,
  fetchApprovalHistory,
  fetchDiagnosticsDoctor,
  fetchPendingApprovals,
  fetchPendingSlashConfirms,
  fetchDiagnostics,
  fetchPluginStatus,
  fetchPlatformToolsets,
  probeSubprocessEnvironment,
  resolveApproval,
  resolveSlashConfirm,
  retryProactiveDelivery,
  updatePlatformToolsets,
  revokeAlwaysApproval,
  type AlwaysApproval,
  type AlwaysApprovalsResult,
  type ApprovalEventsResult,
  type ApprovalStats,
  type ApprovalAuditEvent,
  type ApprovalHistoryResult,
  type Diagnostics,
  type DiagnosticsDoctor,
  type PendingApproval,
  type PendingApprovalsResult,
  type PendingSlashConfirm,
  type PendingSlashConfirmsResult,
  type PlatformDoctor,
  type PlatformToolsetConfig,
  type PlatformToolsetsOverview,
  type ProactiveUnknownCandidate,
  type PluginDiagnosticItem,
  type PluginStatusOverview,
  type SecurityPolicyProbe,
  type SecurityAuditFinding,
  type SecurityAuditResult,
  type SubprocessEnvironmentProbeResult,
} from '@/api/solonclaw/diagnostics'
import {
  fetchInsightsOverview,
  fetchSkillInsights,
  type InsightsOverview,
  type SkillInsights,
} from '@/api/solonclaw/insights'
import { fetchRuntimeStatus, type RuntimeStatusResponse } from '@/api/solonclaw/system'
import { formatTimestampText } from '@/shared/timeFormat'

const { t } = useI18n()
const diagnostics = ref<Diagnostics | null>(null)
const doctor = ref<DiagnosticsDoctor | null>(null)
const runtimeStatus = ref<RuntimeStatusResponse | null>(null)
const insightsOverview = ref<InsightsOverview | null>(null)
const skillInsights = ref<SkillInsights>({})
const loading = ref(false)
const auditLoading = ref(false)
const approvalsLoading = ref(false)
const approvalEventsLoading = ref(false)
const historyLoading = ref(false)
const alwaysLoading = ref(false)
const confirmsLoading = ref(false)
const auditResult = ref<SecurityAuditResult | null>(null)
const policyAuditResult = ref<SecurityAuditResult | null>(null)
const subprocessEnvProbeResult = ref<SubprocessEnvironmentProbeResult | null>(null)
const subprocessEnvProbeLoading = ref(false)
const pluginStatus = ref<PluginStatusOverview | null>(null)
const platformToolsets = ref<PlatformToolsetsOverview | null>(null)
const platformToolsetForms = ref<Record<string, {
  enabledToolsetsText: string
  disabledToolsetsText: string
  approvalRequired: boolean
}>>({})
const savingPlatformToolsets = ref('')
const pendingApprovals = ref<PendingApproval[]>([])
const approvalEvents = ref<ApprovalEventsResult | null>(null)
const approvalStats = ref<ApprovalStats | null>(null)
const pendingApprovalMeta = ref<PendingApprovalsResult | null>(null)
const approvalHistory = ref<ApprovalAuditEvent[]>([])
const approvalHistoryMeta = ref<ApprovalHistoryResult | null>(null)
const alwaysApprovals = ref<AlwaysApproval[]>([])
const alwaysApprovalMeta = ref<AlwaysApprovalsResult | null>(null)
const pendingSlashConfirms = ref<PendingSlashConfirm[]>([])
const slashConfirmMeta = ref<PendingSlashConfirmsResult | null>(null)
function d(key: string, params?: Record<string, unknown>) {
  return params ? t(`diagnostics.${key}`, params) : t(`diagnostics.${key}`)
}
const auditForm = ref({
  action: 'command',
  toolName: 'execute_shell',
  command: '',
  url: '',
  path: '',
  writeLike: false,
  argsJson: '',
})
const subprocessEnvProbeNames = ref('OPENAI_API_KEY, PATH, SOLONCLAW_HOME')
const resolvingKey = ref('')
const revokingAlwaysKey = ref('')
const resolvingConfirmKey = ref('')
const retryingProactiveCandidate = ref('')
const securityApprovals = computed(() => diagnostics.value?.security?.approvals || {})
const securityPolicy = computed(() => diagnostics.value?.security?.policy || {})
const securityTerminal = computed(() => diagnostics.value?.security?.terminal || {})
const securityAuditPolicy = computed<Record<string, unknown>>(() => diagnostics.value?.security?.audit_policy || {})
const securityProbes = computed<SecurityPolicyProbe[]>(() => diagnostics.value?.security?.probes?.items || [])
const securityProbePassed = computed(() => diagnostics.value?.security?.probes?.passed)
const securityCoverage = computed<Record<string, unknown>>(() => {
  const policy = policyAuditResult.value?.policy as Record<string, unknown> | undefined
  const coverage = objectValue(policy?.coverage)
  if (Object.keys(coverage).length > 0) return coverage
  return objectValue(securityAuditPolicy.value.coverage)
})
const securitySurfaces = computed<string[]>(() => {
  const policy = policyAuditResult.value?.policy as Record<string, unknown> | undefined
  const surfaces = policy?.activeSurfaces || securityAuditPolicy.value.activeSurfaces
  return Array.isArray(surfaces) ? surfaces.map((item) => String(item)) : []
})
const platformToolsetRows = computed(() => Object.values(platformToolsets.value?.platforms || {}))
type SecurityMetric = {
  label: string
  value: unknown
  goodWhenTrue?: boolean
  countWarning?: boolean
}
type SecurityDetailGroup = {
  title: string
  items: SecurityMetric[]
}
type RuntimeCapabilityRow = {
  key: string
  label: string
  highlights: SecurityMetric[]
}
const securityDetailGroups = computed<SecurityDetailGroup[]>(() => {
  const policy = securityPolicy.value
  const terminal = securityTerminal.value
  const urlPolicy = objectValue(policy.url_policy)
  const privateUrlPolicy = objectValue(policy.private_url_policy)
  const websitePolicy = objectValue(policy.website_policy)
  const pathPolicy = objectValue(policy.path_policy)
  const credentialPolicy = objectValue(policy.credential_policy)
  const toolArgsPolicy = objectValue(policy.tool_args_policy)
  const readOnlyAuditPolicy = objectValue(securityCoverage.value.readOnlyAuditPolicy)
  const schemaSanitizerPolicy = objectValue(securityCoverage.value.schemaSanitizerPolicy)
  const patchParserPolicy = objectValue(securityCoverage.value.patchParserPolicy)
  const mcpRuntimePolicy = objectValue(securityCoverage.value.mcpRuntimePolicy)
  const mcpOAuthPolicy = objectValue(securityCoverage.value.mcpOAuthPolicy)
  const mcpPackageSecurityPolicy = objectValue(securityCoverage.value.mcpPackageSecurityPolicy)
  const attachmentPolicy = objectValue(securityCoverage.value.attachmentPolicy)
  const attachmentDownloadPolicy = objectValue(attachmentPolicy.downloadIo)
  const attachmentMediaCachePolicy = objectValue(attachmentPolicy.mediaCache)
  const attachmentTerminalPastePolicy = objectValue(attachmentPolicy.terminalPaste)
  const approvalPolicy = objectValue(securityApprovals.value.approval_policy)
  const hardlinePolicy = objectValue(securityApprovals.value.hardline_policy)
  const cronApprovalPolicy = objectValue(securityApprovals.value.cron_approval_policy)
  const subagentApprovalPolicy = objectValue(securityApprovals.value.subagent_approval_policy)
  const smartApprovalPolicy = objectValue(securityApprovals.value.smart_approval_policy)
  const tirithApprovalPolicy = objectValue(securityApprovals.value.tirith_approval_policy)
  const approvalLifecyclePolicy = objectValue(securityApprovals.value.approval_lifecycle_policy)
  const slashConfirmPolicy = objectValue(securityApprovals.value.slash_confirm_policy)
  const approvalCardPolicy = objectValue(securityApprovals.value.approval_card_policy)
  const approvalAuditPolicy = objectValue(securityApprovals.value.approval_audit_policy)
  const mcpReloadPolicy = objectValue(securityApprovals.value.mcp_reload_policy)
  const terminalCredentialFilePolicy = objectValue(terminal.credential_file_policy)
  const terminalGuardrailPolicy = objectValue(terminal.terminal_guardrail_policy)
  const terminalOutputPolicy = objectValue(terminal.terminal_output_policy)
  const toolResultStoragePolicy = objectValue(terminal.tool_result_storage_policy)
  const sudoRewritePolicy = objectValue(terminal.sudo_rewrite_policy)
  const backgroundProcessPolicy = objectValue(terminal.background_process_policy)
  return [
    {
      title: d('detailGroups.approvalRules'),
      items: [
        metric(
          'commandApprovalMode',
          firstDefined(approvalPolicy.guardrailMode, securityApprovals.value.guardrail_mode),
        ),
        metric(
          'scheduledJobMode',
          firstDefined(approvalPolicy.guardrailCronMode, securityApprovals.value.guardrail_cron_mode),
        ),
        metric('smartJudgeConfigured', approvalPolicy.smartJudgeConfigured),
        metric('dangerousRuleCount', approvalPolicy.dangerousRuleCount),
        metric('hardlineRuleCount', approvalPolicy.hardlineRuleCount),
        metric('terminalGuardrailCount', approvalPolicy.terminalGuardrailCount),
        metric('urlPolicyPrechecked', approvalPolicy.urlPolicyPrechecked),
        metric('privateUrlPolicyPrechecked', approvalPolicy.privateUrlPolicyPrechecked),
        metric('credentialUrlPolicyPrechecked', approvalPolicy.credentialUrlPolicyPrechecked),
        metric('websitePolicyPrechecked', approvalPolicy.websitePolicyPrechecked),
        metric('unsafeUrlApprovalBypassAllowed', approvalPolicy.unsafeUrlApprovalBypassAllowed, false),
      ],
    },
    {
      title: d('detailGroups.hardlineCommands'),
      items: [
        metric('ruleCount', hardlinePolicy.ruleCount),
        metric('coveredTools', hardlinePolicy.coveredTools),
        metric('blockedCategories', hardlinePolicy.blockedCategories),
        metric('approvalBypassAllowed', hardlinePolicy.approvalBypassAllowed, false),
        metric('slashApproveBypassAllowed', hardlinePolicy.slashApproveBypassAllowed, false),
        metric('sessionApprovalBypassAllowed', hardlinePolicy.sessionApprovalBypassAllowed, false),
        metric('alwaysApprovalBypassAllowed', hardlinePolicy.alwaysApprovalBypassAllowed, false),
        metric('yoloBypassAllowed', hardlinePolicy.yoloBypassAllowed, false),
        metric('smartApprovalBypassAllowed', hardlinePolicy.smartApprovalBypassAllowed, false),
        metric('commandPreviewRedacted', hardlinePolicy.commandPreviewRedacted),
      ],
    },
    {
      title: d('detailGroups.automationAndSubagents'),
      items: [
        metric('cronDefaultDecision', cronApprovalPolicy.defaultDecision),
        metric('cronHardlineAlwaysBlocked', cronApprovalPolicy.hardlineAlwaysBlocked),
        metric('dangerousPatternCheckedBeforeRun', cronApprovalPolicy.dangerousPatternCheckedBeforeRun),
        metric('scriptContentChecked', cronApprovalPolicy.scriptContentChecked),
        metric('subagentDefaultDecision', subagentApprovalPolicy.defaultDecision),
        metric('subagentHardlinePrechecked', subagentApprovalPolicy.hardlinePrechecked),
        metric('subagentFilePolicyPrechecked', subagentApprovalPolicy.filePolicyPrechecked),
        metric('subagentUrlPolicyPrechecked', subagentApprovalPolicy.urlPolicyPrechecked),
        metric('subagentTerminalGuardrailPrechecked', subagentApprovalPolicy.terminalGuardrailPrechecked),
        metric('pendingApprovalCreatedWhenDenied', subagentApprovalPolicy.pendingApprovalCreatedWhenDenied),
      ],
    },
    {
      title: d('detailGroups.smartAndContentScan'),
      items: [
        metric('smartMode', smartApprovalPolicy.smartMode),
        metric('smartApprovalActive', smartApprovalPolicy.active),
        metric('judgeConfigured', smartApprovalPolicy.judgeConfigured),
        metric('escalateFallsBackToHumanApproval', smartApprovalPolicy.escalateFallsBackToHumanApproval),
        metric('judgeFailureFallsBackToHumanApproval', smartApprovalPolicy.judgeFailureFallsBackToHumanApproval),
        metric('tirithFindingsIncluded', smartApprovalPolicy.tirithFindingsIncluded),
        metric('scannerConfigured', tirithApprovalPolicy.scannerConfigured),
        metric('scanRunsInApprovalMode', tirithApprovalPolicy.scanRunsInApprovalMode),
        metric('combinedWithLocalDangerRules', tirithApprovalPolicy.combinedWithLocalDangerRules),
        metric('permanentApprovalAllowed', tirithApprovalPolicy.permanentApprovalAllowed),
      ],
    },
    {
      title: d('detailGroups.approvalLifecycle'),
      items: [
        metric('pendingListPrunedBeforeRead', approvalLifecyclePolicy.pendingListPrunedBeforeRead),
        metric('selectorSupported', approvalLifecyclePolicy.selectorSupported),
        metric('unsafeSelectorRejected', approvalLifecyclePolicy.unsafeSelectorRejected),
        metric('bulkRejectUsesSafeSelector', approvalLifecyclePolicy.bulkRejectUsesSafeSelector),
        metric('approveRemovesPendingApproval', approvalLifecyclePolicy.approveRemovesPendingApproval),
        metric('rejectRemovesPendingApproval', approvalLifecyclePolicy.rejectRemovesPendingApproval),
        metric('sessionSnapshotUpdated', approvalLifecyclePolicy.sessionSnapshotUpdated),
        metric('approvalKeyRedacted', approvalLifecyclePolicy.approvalKeyRedacted),
      ],
    },
    {
      title: d('detailGroups.slashAndApprovalCards'),
      items: [
        metric('pendingQueueSupported', slashConfirmPolicy.pendingQueueSupported),
        metric('pendingListUsesSafeSelector', slashConfirmPolicy.pendingListUsesSafeSelector),
        metric('pendingListHidesApprovalKey', slashConfirmPolicy.pendingListHidesApprovalKey),
        metric('approvalMetadataRedacted', slashConfirmPolicy.approvalMetadataRedacted),
        metric('approvalIdSelectorSupported', approvalCardPolicy.approvalIdSelectorSupported),
        metric('approvalCardUnsafeSelectorRejected', approvalCardPolicy.unsafeSelectorRejected),
        metric('approvalCardCommandPreviewRedacted', approvalCardPolicy.commandPreviewRedacted),
        metric('rawCommandRedactedInExtras', approvalCardPolicy.rawCommandRedactedInExtras),
      ],
    },
    {
      title: d('detailGroups.approvalAuditAndMcp'),
      items: [
        metric('requestEvents', approvalAuditPolicy.requestEvents),
        metric('responseEvents', approvalAuditPolicy.responseEvents),
        metric('observerFailureIsolated', approvalAuditPolicy.observerFailureIsolated),
        metric('auditApprovalKeyRedacted', approvalAuditPolicy.approvalKeyRedacted),
        metric('manualRevocationAudited', approvalAuditPolicy.manualRevocationAudited),
        metric('confirmRequired', mcpReloadPolicy.confirmRequired),
        metric('slashConfirmBacked', mcpReloadPolicy.slashConfirmBacked),
        metric('oauthUrlSafetyCovered', mcpReloadPolicy.oauthUrlSafetyCovered),
      ],
    },
    {
      title: d('detailGroups.readOnlyAuditTool'),
      items: [
        metric('toolName', readOnlyAuditPolicy.toolName),
        metric('executesCommand', readOnlyAuditPolicy.executesCommand, false),
        metric('opensNetworkConnection', readOnlyAuditPolicy.opensNetworkConnection, false),
        metric('readsTargetUrl', readOnlyAuditPolicy.readsTargetUrl, false),
        metric('writesFile', readOnlyAuditPolicy.writesFile, false),
        metric('storesAuditInput', readOnlyAuditPolicy.storesAuditInput, false),
        metric('secretRedactionApplied', readOnlyAuditPolicy.secretRedactionApplied),
        metric('toolArgsCommandPolicyInherited', readOnlyAuditPolicy.toolArgsCommandPolicyInherited),
        metric('toolArgsUrlPolicyInherited', readOnlyAuditPolicy.toolArgsUrlPolicyInherited),
        metric('toolArgsPathPolicyInherited', readOnlyAuditPolicy.toolArgsPathPolicyInherited),
        metric('toolArgsJsonParseErrorsRedacted', readOnlyAuditPolicy.toolArgsJsonParseErrorsRedacted),
        metric('commandPreviewLimitChars', readOnlyAuditPolicy.commandPreviewLimitChars),
        metric('findingMessageLimitChars', readOnlyAuditPolicy.findingMessageLimitChars),
      ],
    },
    {
      title: d('detailGroups.schemaAndPatch'),
      items: [
        metric('schemaSanitizerEnabled', schemaSanitizerPolicy.enabled),
        metric('inputSchemaSanitized', schemaSanitizerPolicy.inputSchemaSanitized),
        metric('mcpInputSchemaSanitized', schemaSanitizerPolicy.mcpInputSchemaSanitized),
        metric('invalidSchemaDefaultsToObject', schemaSanitizerPolicy.invalidSchemaDefaultsToObject),
        metric('topLevelObjectRequired', schemaSanitizerPolicy.topLevelObjectRequired),
        metric('requiredPrunedToKnownProperties', schemaSanitizerPolicy.requiredPrunedToKnownProperties),
        metric('patternAndFormatStripped', schemaSanitizerPolicy.patternAndFormatStripped),
        metric('atomicValidationBeforeWrite', patchParserPolicy.atomicValidationBeforeWrite),
        metric('noPartialWritesOnValidationFailure', patchParserPolicy.noPartialWritesOnValidationFailure),
        metric('replaceAllRequiresExplicitFlag', patchParserPolicy.replaceAllRequiresExplicitFlag),
        metric('ambiguousHunksBlocked', patchParserPolicy.ambiguousHunksBlocked),
        metric('missingHunksBlocked', patchParserPolicy.missingHunksBlocked),
        metric('pathTraversalBlocked', patchParserPolicy.pathTraversalBlocked),
        metric('patchCredentialPolicyPrechecked', patchParserPolicy.credentialPolicyPrechecked),
      ],
    },
    {
      title: d('detailGroups.mcpSecurity'),
      items: [
        metric('remoteEndpointUrlSafety', mcpRuntimePolicy.remoteEndpointUrlSafety),
        metric('remoteToolArgumentsForwarded', mcpRuntimePolicy.remoteToolArgumentsForwarded),
        metric('resourceUrisForwarded', mcpRuntimePolicy.resourceUrisForwarded),
        metric('toolNamesPrefixed', mcpRuntimePolicy.toolNamesPrefixed),
        metric('toolsChangeNotificationPersisted', mcpRuntimePolicy.toolsChangeNotificationPersisted),
        metric('authorizationEndpointUrlSafety', mcpOAuthPolicy.authorizationEndpointUrlSafety),
        metric('stateValidationRequired', mcpOAuthPolicy.stateValidationRequired),
        metric('pkceS256Required', mcpOAuthPolicy.pkceS256Required),
        metric('accessTokenRedacted', mcpOAuthPolicy.accessTokenRedacted),
        metric('requestFailureFailsOpen', mcpPackageSecurityPolicy.requestFailureFailsOpen),
        metric('malwareBlocksSaveAndCheck', mcpPackageSecurityPolicy.malwareBlocksSaveAndCheck),
      ],
    },
    {
      title: d('detailGroups.attachmentSecurity'),
      items: [
        metric('hutoolDownloadGuarded', attachmentDownloadPolicy.hutoolDownloadGuarded),
        metric('okHttpDownloadGuarded', attachmentDownloadPolicy.okHttpDownloadGuarded),
        metric('initialUrlChecked', attachmentDownloadPolicy.initialUrlChecked),
        metric('redirectUrlCheckedBeforeFollow', attachmentDownloadPolicy.redirectUrlCheckedBeforeFollow),
        metric('crossHostHeaderForwardingBlocked', attachmentDownloadPolicy.crossHostHeaderForwardingBlocked),
        metric('blockedUrlMasked', attachmentDownloadPolicy.blockedUrlMasked),
        metric('contentLengthChecked', attachmentDownloadPolicy.contentLengthChecked),
        metric('streamReadBounded', attachmentDownloadPolicy.streamReadBounded),
        metric('cacheBytesSizeChecked', attachmentMediaCachePolicy.cacheBytesSizeChecked),
        metric('safeOriginalNameSecretRedacted', attachmentMediaCachePolicy.safeOriginalNameSecretRedacted),
        metric('mediaReferenceTraversalBlocked', attachmentMediaCachePolicy.mediaReferenceTraversalBlocked),
        metric('hostPathsNotReturnedInMediaReference', attachmentMediaCachePolicy.hostPathsNotReturnedInMediaReference),
        metric('pathPolicyCheckedBeforeCache', attachmentTerminalPastePolicy.pathPolicyCheckedBeforeCache),
        metric('credentialPathBlocked', attachmentTerminalPastePolicy.credentialPathBlocked),
        metric('blockedPreviewRedacted', attachmentTerminalPastePolicy.blockedPreviewRedacted),
        metric('rawPathHiddenInPrompt', attachmentTerminalPastePolicy.rawPathHiddenInPrompt),
      ],
    },
    {
      title: d('detailGroups.urlAndPrivateAddresses'),
      items: [
        metric('alwaysBlockedHostCount', urlPolicy.alwaysBlockedHostCount),
        metric('alwaysBlockedIpCount', urlPolicy.alwaysBlockedIpCount),
        metric('sensitiveQueryNameCount', urlPolicy.sensitiveQueryNameCount),
        metric('dnsResolutionRequired', firstDefined(privateUrlPolicy.dnsResolutionRequired, urlPolicy.dnsResolutionRequired)),
        metric('userinfoBlocked', urlPolicy.userinfoBlocked),
        metric('sensitiveQueryBlocked', urlPolicy.sensitiveQueryBlocked),
        metric('cloudMetadataAlwaysBlocked', firstDefined(privateUrlPolicy.cloudMetadataAlwaysBlocked, urlPolicy.cloudMetadataBlocked)),
        metric('loopbackBlocked', privateUrlPolicy.loopbackBlocked),
        metric('linkLocalBlocked', privateUrlPolicy.linkLocalBlocked),
        metric('siteLocalBlocked', privateUrlPolicy.siteLocalBlocked),
      ],
    },
    {
      title: d('detailGroups.websitePolicy'),
      items: [
        metric('websitePolicyEnabled', firstDefined(websitePolicy.enabled, policy.website_blocklist_enabled)),
        metric('configuredDomainCount', firstDefined(websitePolicy.configuredDomainCount, policy.website_blocklist_domain_count)),
        metric('sharedFileCount', firstDefined(websitePolicy.sharedFileCount, policy.website_blocklist_shared_file_count)),
        metric('loadedSharedFileCount', websitePolicy.loadedSharedFileCount),
        metric('skippedSharedFileCount', websitePolicy.skippedSharedFileCount, true, true),
        metric('sharedRuleCount', websitePolicy.sharedRuleCount),
        metric('wildcardSubdomainSupported', websitePolicy.wildcardSubdomainSupported),
        metric('sharedFilePathSafetyChecked', websitePolicy.sharedFilePathSafetyChecked),
      ],
    },
    {
      title: d('detailGroups.pathAndCredentials'),
      items: [
        metric('traversalBlocked', pathPolicy.traversalBlocked),
        metric('controlCharactersBlocked', pathPolicy.controlCharactersBlocked),
        metric('devicePathBlocked', pathPolicy.devicePathBlocked),
        metric('rawBlockDeviceWriteBlocked', pathPolicy.rawBlockDeviceWriteBlocked),
        metric('writeDeniedExactPathCount', pathPolicy.writeDeniedExactPathCount),
        metric('writeDeniedPrefixCount', pathPolicy.writeDeniedPrefixCount),
        metric('directorySegmentCount', credentialPolicy.directorySegmentCount),
        metric('fileNameCount', credentialPolicy.fileNameCount),
        metric('pathSuffixCount', credentialPolicy.pathSuffixCount),
        metric('keyFileExtensionCount', credentialPolicy.keyFileExtensionCount),
      ],
    },
    {
      title: d('detailGroups.toolArgsAndTerminal'),
      items: [
        metric('recursiveUrlExtraction', toolArgsPolicy.recursiveUrlExtraction),
        metric('returnedContentUrlExtraction', toolArgsPolicy.returnedContentUrlExtraction),
        metric('recursivePathExtraction', toolArgsPolicy.recursivePathExtraction),
        metric('writeIntentDetection', toolArgsPolicy.writeIntentDetection),
        metric('networkUploadCredentialOnlyBlocked', toolArgsPolicy.networkUploadCredentialOnlyBlocked),
        metric('terminalSecretRedactionApplied', terminalOutputPolicy.secretRedactionApplied),
        metric('truncationNoticeIncluded', terminalOutputPolicy.truncationNoticeIncluded),
        metric('maxInlineChars', terminalOutputPolicy.maxInlineChars),
        metric('processRegistryBacked', backgroundProcessPolicy.processRegistryBacked),
        metric('managedBackgroundRequiredForLongRunningCommands', backgroundProcessPolicy.managedBackgroundRequiredForLongRunningCommands),
      ],
    },
    {
      title: d('detailGroups.terminalHardGuardrails'),
      items: [
        metric('backgroundShellWrappersBlocked', terminalGuardrailPolicy.backgroundShellWrappersBlocked),
        metric('detachedSessionLaunchersBlocked', terminalGuardrailPolicy.detachedSessionLaunchersBlocked),
        metric('powershellBackgroundCommandsBlocked', terminalGuardrailPolicy.powershellBackgroundCommandsBlocked),
        metric('inlineAmpersandBlocked', terminalGuardrailPolicy.inlineAmpersandBlocked),
        metric('trailingAmpersandBlocked', terminalGuardrailPolicy.trailingAmpersandBlocked),
        metric('longLivedForegroundBlocked', terminalGuardrailPolicy.longLivedForegroundBlocked),
        metric('commandPathPrechecked', terminalGuardrailPolicy.commandPathPrechecked),
        metric('credentialPathGuardrailPrechecked', terminalGuardrailPolicy.credentialPathPrechecked),
        metric('downloadOutputPathPrechecked', terminalGuardrailPolicy.downloadOutputPathPrechecked),
        metric('proxyUrlPrechecked', terminalGuardrailPolicy.proxyUrlPrechecked),
        metric('systemDnsCommandPrechecked', terminalGuardrailPolicy.systemDnsCommandPrechecked),
        metric('systemProxyCommandPrechecked', terminalGuardrailPolicy.systemProxyCommandPrechecked),
        metric('hostsAndResolverPathPrechecked', terminalGuardrailPolicy.hostsAndResolverPathPrechecked),
        metric('managedBackgroundProcessRequired', terminalGuardrailPolicy.managedBackgroundProcessRequired),
        metric('guardrailProcessRegistryBacked', terminalGuardrailPolicy.processRegistryBacked),
        metric('guardrailSudoPasswordRedacted', terminalGuardrailPolicy.sudoPasswordRedacted),
      ],
    },
    {
      title: d('detailGroups.credentialFilesAndResultStorage'),
      items: [
        metric('configCredentialFileCount', terminalCredentialFilePolicy.configCredentialFileCount),
        metric('configuredMountCount', terminalCredentialFilePolicy.configuredMountCount),
        metric('missingFilesNotMounted', terminalCredentialFilePolicy.missingFilesNotMounted),
        metric('hostPathsOmittedFromMetadata', terminalCredentialFilePolicy.hostPathsOmittedFromMetadata),
        metric('rejectedPathsRedacted', terminalCredentialFilePolicy.rejectedPathsRedacted),
        metric('sudoRewriteConfigured', sudoRewritePolicy.configured),
        metric('sudoRewritePasswordRedacted', sudoRewritePolicy.passwordRedacted),
        metric('stdinPasswordInjection', sudoRewritePolicy.stdinPasswordInjection),
        metric('resultStorageEnabled', toolResultStoragePolicy.enabled),
        metric('oversizedResultsPersisted', toolResultStoragePolicy.oversizedResultsPersisted),
        metric('resultRefReturned', toolResultStoragePolicy.resultRefReturned),
        metric('previewRedacted', toolResultStoragePolicy.previewRedacted),
        metric('persistedOutputRedacted', toolResultStoragePolicy.persistedOutputRedacted),
        metric('inlineLimitBytes', toolResultStoragePolicy.inlineLimitBytes),
      ],
    },
  ]
})
const coverageItems = [
  { key: 'dangerousCommandApproval', label: d('coverageItems.dangerousCommandApproval') },
  { key: 'slashApprovalConfirm', label: d('coverageItems.slashApprovalConfirm') },
  { key: 'smartApproval', label: d('coverageItems.smartApproval') },
  { key: 'tirithSmartApproval', label: d('coverageItems.tirithSmartApproval') },
  { key: 'cronApprovalPolicy', label: d('coverageItems.cronApprovalPolicy') },
  { key: 'subagentApprovalPolicy', label: d('coverageItems.subagentApprovalPolicy') },
  { key: 'approvalAuditLog', label: d('coverageItems.approvalAuditLog') },
  { key: 'hardlineCommandBlocks', label: d('coverageItems.hardlineCommandBlocks') },
  { key: 'terminalGuardrails', label: d('coverageItems.terminalGuardrails') },
  { key: 'sudoRewrite', label: d('coverageItems.sudoRewrite') },
  { key: 'backgroundProcessGuard', label: d('coverageItems.backgroundProcessGuard') },
  { key: 'urlSafety', label: d('coverageItems.urlSafety') },
  { key: 'privateUrlPolicy', label: d('coverageItems.privateUrlPolicy') },
  { key: 'websitePolicy', label: d('coverageItems.websitePolicy') },
  { key: 'credentialFilePolicy', label: d('coverageItems.credentialFilePolicy') },
  { key: 'credentialMountPolicy', label: d('coverageItems.credentialMountPolicy') },
  { key: 'pathSecurity', label: d('coverageItems.pathSecurity') },
  { key: 'toolArgsSecurity', label: d('coverageItems.toolArgsSecurity') },
  { key: 'toolReturnedContentUrlSafety', label: d('coverageItems.toolReturnedContentUrlSafety') },
  { key: 'schemaSanitizer', label: d('coverageItems.schemaSanitizer') },
  { key: 'patchParser', label: d('coverageItems.patchParser') },
  { key: 'subprocessEnvironmentSanitizer', label: d('coverageItems.subprocessEnvironmentSanitizer') },
  { key: 'toolResultStorage', label: d('coverageItems.toolResultStorage') },
  { key: 'codeExecutionGuardrails', label: d('coverageItems.codeExecutionGuardrails') },
  { key: 'codeExecutionPolicyAuditable', label: d('coverageItems.codeExecutionPolicyAuditable') },
  { key: 'mcpUrlSafety', label: d('coverageItems.mcpUrlSafety') },
  { key: 'mcpReloadConfirmation', label: d('coverageItems.mcpReloadConfirmation') },
  { key: 'mcpToolChangeNotice', label: d('coverageItems.mcpToolChangeNotice') },
  { key: 'mcpRuntimePolicyAuditable', label: d('coverageItems.mcpRuntimePolicyAuditable') },
  { key: 'mcpPackageSecurity', label: d('coverageItems.mcpPackageSecurity') },
  { key: 'attachmentUrlSafety', label: d('coverageItems.attachmentUrlSafety') },
  { key: 'attachmentCachePathSafety', label: d('coverageItems.attachmentCachePathSafety') },
  { key: 'attachmentDisplayNameRedaction', label: d('coverageItems.attachmentDisplayNameRedaction') },
  { key: 'terminalAttachmentPathSafety', label: d('coverageItems.terminalAttachmentPathSafety') },
  { key: 'terminalAttachmentPreviewRedaction', label: d('coverageItems.terminalAttachmentPreviewRedaction') },
  { key: 'terminalAttachmentResolvedNameRedaction', label: d('coverageItems.terminalAttachmentResolvedNameRedaction') },
  { key: 'tirithSecurity', label: d('coverageItems.tirithSecurity') },
  { key: 'readOnlyAuditTool', label: d('coverageItems.readOnlyAuditTool') },
]
const auditActionOptions = [
  { label: d('auditActions.command'), value: 'command' },
  { label: d('auditActions.url'), value: 'url' },
  { label: d('auditActions.path'), value: 'path' },
  { label: d('auditActions.toolArgs'), value: 'tool_args' },
  { label: d('auditActions.policy'), value: 'policy' },
  { label: d('auditActions.status'), value: 'status' },
]
const auditFindings = computed<SecurityAuditFinding[]>(() => auditResult.value?.findings || [])
const pendingCount = computed(() => pendingApprovals.value.length)
const pendingApprovalScanText = computed(() => {
  const meta = pendingApprovalMeta.value
  if (!meta) return d('pendingScanEmpty')
  const scanned = meta.scanned_sessions ?? 0
  const limit = meta.session_scan_limit ?? '-'
  return d('pendingScanText', { scanned, limit })
})
const historyCount = computed(() => approvalHistory.value.length)
const alwaysCount = computed(() => alwaysApprovals.value.length)
const slashConfirmCount = computed(() => pendingSlashConfirms.value.length)
const pluginLoadedCount = computed(() => pluginStatus.value?.loaded_count ?? 0)
const pluginSkippedCount = computed(() => pluginStatus.value?.skipped_count ?? 0)
const pluginFailedCount = computed(() => pluginStatus.value?.failed_count ?? 0)
const pluginRows = computed(() => pluginStatus.value?.plugins || [])
const pluginDiagnostics = computed<PluginDiagnosticItem[]>(() => pluginStatus.value?.diagnostics || [])
const proactiveDiagnostics = computed<Record<string, unknown>>(() => objectValue(diagnostics.value?.proactive))
const proactiveUnknownCandidates = computed<ProactiveUnknownCandidate[]>(() => {
  const value = proactiveDiagnostics.value.delivery_unknown_candidates
  return Array.isArray(value) ? value as ProactiveUnknownCandidate[] : []
})
const hasProactiveDiagnostics = computed(() => Object.keys(proactiveDiagnostics.value).length > 0)
const proactiveBlocked = computed(() =>
  Boolean(
    proactiveDiagnostics.value.missing_home_channel ||
      proactiveDiagnostics.value.quiet_hours_blocked ||
      proactiveDiagnostics.value.cooldown_blocked ||
      proactiveDiagnostics.value.daily_cap_blocked ||
      proactiveDiagnostics.value.delivery_failed ||
      proactiveDiagnostics.value.delivery_unknown,
  ),
)
const proactiveStatusItems = computed<SecurityMetric[]>(() => [
  { label: d('proactiveEnabled'), value: proactiveDiagnostics.value.enabled },
  { label: d('proactiveSchedulerRan'), value: proactiveDiagnostics.value.scheduler_ran },
  { label: d('proactiveCandidates'), value: proactiveDiagnostics.value.pending_candidate_count },
  { label: d('proactiveHomeChannels'), value: proactiveDiagnostics.value.home_channels },
  { label: d('proactiveMissingHomeChannel'), value: proactiveDiagnostics.value.missing_home_channel, goodWhenTrue: false },
  { label: d('proactiveQuietHours'), value: proactiveDiagnostics.value.quiet_hours_blocked, goodWhenTrue: false },
  { label: d('proactiveCooldown'), value: proactiveDiagnostics.value.cooldown_blocked, goodWhenTrue: false },
  { label: d('proactiveDailyCap'), value: proactiveDiagnostics.value.daily_cap_blocked, goodWhenTrue: false },
  { label: d('proactiveDeliveryFailed'), value: proactiveDiagnostics.value.delivery_failed, goodWhenTrue: false },
  { label: d('proactiveDeliveryUnknown'), value: proactiveDiagnostics.value.delivery_unknown_count, countWarning: true },
])
const approvalStatItems = computed(() => [
  { label: d('approvalStatTotal'), value: approvalStats.value?.totalEvents ?? 0 },
  { label: d('approvalStatApproved'), value: approvalStats.value?.approved ?? 0 },
  { label: d('approvalStatBlocked'), value: approvalStats.value?.blocked ?? 0 },
  { label: d('approvalStatPending'), value: approvalStats.value?.pending ?? 0 },
])
const doctorSummary = computed(() => doctor.value?.summary || {})
const doctorIssues = computed(() => doctor.value?.summary?.issues || [])
const doctorNextActions = computed(() => doctor.value?.summary?.nextActions || [])
const doctorPlatforms = computed<PlatformDoctor[]>(() => doctor.value?.platforms || [])
const insightRuntime = computed(() => insightsOverview.value?.runtime || {})
const insightSessions = computed(() => insightsOverview.value?.sessions || {})
const insightSkills = computed(() => insightsOverview.value?.skills || {})
const skillInsightRows = computed<Array<{ key: string } & Record<string, unknown>>>(() =>
  Object.entries(skillInsights.value)
    .map(([key, value]) => ({ key, ...value }))
    .sort((left, right) => Number(right.count || 0) - Number(left.count || 0))
    .slice(0, 6),
)
const runtimeCapabilityRows = computed<RuntimeCapabilityRow[]>(() => {
  const runtime_status = runtimeStatus.value?.runtime_status
  const multimodal = objectValue(runtime_status?.multimodal)
  const modelInput = objectValue(multimodal.model_input)
  const pricing = objectValue(runtime_status?.pricing)
  const gateway = objectValue(runtime_status?.gateway)
  return [
    {
      key: 'multimodal',
      label: d('runtimeMultimodal'),
      highlights: [
        metric('runtimeProvider', multimodal.provider),
        metric('runtimeModel', multimodal.model),
        metric('runtimeDialect', multimodal.dialect),
        metric('runtimeVisionInput', modelInput.vision),
        metric('runtimeAudioInput', modelInput.audio),
        metric('runtimeAttachmentInput', modelInput.attachments),
        metric('runtimePdfInput', modelInput.pdf),
        metric('runtimeImageGeneration', multimodal.image_generation),
        metric('runtimeTts', multimodal.tts),
        metric('runtimeTranscription', multimodal.transcription),
      ],
    },
    {
      key: 'pricing',
      label: d('runtimePricing'),
      highlights: [
        metric('runtimeBuiltinPriceCount', pricing.builtin_price_count),
        metric('runtimeConfiguredPriceCount', pricing.configured_price_count),
        metric('runtimeEffectivePriceCount', pricing.effective_price_count),
        metric('runtimeUsageCostCalculation', pricing.usage_cost_calculation),
        metric('runtimePricingAvailable', pricing.pricing_available),
        metric('runtimeCurrencyDefault', pricing.currency_default),
      ],
    },
    {
      key: 'gateway',
      label: d('runtimeGateway'),
      highlights: [
        metric('runtimeGatewayState', gateway.state),
        metric('runtimeGatewayRunning', gateway.running),
        metric('runtimeSupportedChannels', gateway.supported_channels),
        metric('runtimeActiveAgents', gateway.active_agents),
        metric('runtimeRecentActiveSessions', gateway.recent_active_sessions),
        metric('runtimeExitReason', gateway.exit_reason),
      ],
    },
  ].filter((row) => row.highlights.some((item) => item.value !== undefined && item.value !== null && item.value !== ''))
})

function valueOf(source: Record<string, unknown>, key: string, fallback: unknown = '-') {
  const value = source[key]
  if (value === undefined || value === null || value === '') return fallback
  return value
}

function objectValue(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  return value as Record<string, unknown>
}

function firstDefined(...values: unknown[]) {
  return values.find((value) => value !== undefined && value !== null && value !== '')
}

function metric(labelKey: string, value: unknown, goodWhenTrue = true, countWarning = false): SecurityMetric {
  return { label: d(`detailMetrics.${labelKey}`), value, goodWhenTrue, countWarning }
}

function booleanTagType(value: unknown, goodWhenTrue = true) {
  const enabled = value === true
  return enabled === goodWhenTrue ? 'success' : 'warning'
}

function booleanText(value: unknown) {
  return value === true ? d('booleanYes') : d('booleanNo')
}

function metricText(value: unknown) {
  if (value === true || value === false) return booleanText(value)
  if (value === undefined || value === null || value === '') return '-'
  if (Array.isArray(value)) return d('arrayCount', { count: value.length })
  if (typeof value === 'object') return d('configuredValue')
  return String(value)
}

function metricTagType(item: SecurityMetric) {
  if (item.value === true || item.value === false) return booleanTagType(item.value, item.goodWhenTrue !== false)
  if (typeof item.value === 'number') {
    if (item.countWarning && item.value > 0) return 'warning'
    return item.value > 0 ? 'success' : 'default'
  }
  return item.value === undefined || item.value === null || item.value === '' ? 'default' : 'success'
}

function pluginStatusTagType(status?: string) {
  if (status === 'loaded') return 'success'
  if (status === 'failed') return 'error'
  if (status === 'skipped') return 'warning'
  return 'default'
}

function decisionType(decision: unknown) {
  if (decision === 'allow') return 'success'
  if (decision === 'warn') return 'warning'
  if (decision === 'block') return 'error'
  return 'default'
}

function findingActionText(action?: string) {
  if (action === 'request_approval') return d('findingActions.requestApproval')
  if (action === 'change_command') return d('findingActions.changeCommand')
  if (action === 'change_path') return d('findingActions.changePath')
  if (action === 'change_url_or_policy') return d('findingActions.changeUrlOrPolicy')
  if (action === 'use_managed_background_process') return d('findingActions.useManagedBackgroundProcess')
  return action || ''
}

function surfaceLabel(surface: string) {
  const key = `surfaceLabels.${surface}`
  const translated = d(key)
  return translated === key ? surface : translated
}

function toolsetsText(values?: readonly string[]) {
  return values?.length ? values.join(', ') : ''
}

function splitToolsetsText(value: string) {
  const result: string[] = []
  for (const item of value.split(/[\s,]+/)) {
    const normalized = item.trim()
    if (normalized && !result.includes(normalized)) {
      result.push(normalized)
    }
  }
  return result
}

function resetPlatformToolsetForms(overview: PlatformToolsetsOverview | null) {
  const forms: Record<string, {
    enabledToolsetsText: string
    disabledToolsetsText: string
    approvalRequired: boolean
  }> = {}
  for (const row of Object.values(overview?.platforms || {})) {
    forms[row.platform] = {
      enabledToolsetsText: toolsetsText(row.enabledToolsets),
      disabledToolsetsText: toolsetsText(row.disabledToolsets),
      approvalRequired: row.approvalRequired,
    }
  }
  platformToolsetForms.value = forms
}

async function loadPlatformToolsets() {
  platformToolsets.value = await fetchPlatformToolsets()
  resetPlatformToolsetForms(platformToolsets.value)
}

async function savePlatformToolsets(row: PlatformToolsetConfig) {
  const form = platformToolsetForms.value[row.platform]
  if (!form) return
  savingPlatformToolsets.value = row.platform
  try {
    const updated = await updatePlatformToolsets(row.platform, {
      enabledToolsets: splitToolsetsText(form.enabledToolsetsText),
      disabledToolsets: splitToolsetsText(form.disabledToolsetsText),
      approvalRequired: form.approvalRequired,
    })
    platformToolsets.value = {
      platforms: {
        ...(platformToolsets.value?.platforms || {}),
        [updated.platform]: updated,
      },
    }
    resetPlatformToolsetForms(platformToolsets.value)
    message.success(t('diagnostics.platformToolsetsSaved'))
  } catch (error: unknown) {
    message.error(error instanceof Error ? error.message : t('diagnostics.platformToolsetsSaveFailed'))
  } finally {
    savingPlatformToolsets.value = ''
  }
}

async function load() {
  loading.value = true
  try {
    const [diagnosticsData, runtimeStatusData, pluginStatusData, insightsData, skillInsightData] = await Promise.all([
      fetchDiagnostics(),
      fetchRuntimeStatus(),
      fetchPluginStatus(),
      fetchInsightsOverview(),
      fetchSkillInsights(),
      loadPolicyAudit(),
      loadApprovalEvents(),
      loadApprovals(),
      loadHistory(),
      loadAlwaysApprovals(),
      loadSlashConfirms(),
    ])
    diagnostics.value = diagnosticsData
    runtimeStatus.value = runtimeStatusData
    pluginStatus.value = pluginStatusData
    doctor.value = await fetchDiagnosticsDoctor()
    await loadPlatformToolsets()
    insightsOverview.value = insightsData
    skillInsights.value = skillInsightData
  } finally {
    loading.value = false
  }
}

async function retryUnknownProactive(candidate: ProactiveUnknownCandidate) {
  if (!window.confirm(t('diagnostics.proactiveRetryConfirm', { title: candidate.title || candidate.candidate_id }))) return
  retryingProactiveCandidate.value = candidate.candidate_id
  try {
    const result = await retryProactiveDelivery(candidate.candidate_id)
    if (result.delivery_status === 'SENT') {
      message.success(t('diagnostics.proactiveRetrySucceeded'))
    } else {
      message.warning(t('diagnostics.proactiveRetryStillUnknown'))
    }
    diagnostics.value = await fetchDiagnostics()
  } catch (error: unknown) {
    message.error(error instanceof Error ? error.message : t('diagnostics.proactiveRetryFailed'))
    diagnostics.value = await fetchDiagnostics()
  } finally {
    retryingProactiveCandidate.value = ''
  }
}

async function loadPolicyAudit() {
  policyAuditResult.value = await auditSecurity({ action: 'status' })
}

async function loadApprovalEvents() {
  approvalEventsLoading.value = true
  try {
    approvalEvents.value = await fetchApprovalEvents(50)
  } finally {
    approvalEventsLoading.value = false
  }
}

async function loadApprovals() {
  approvalsLoading.value = true
  try {
    approvalStats.value = await fetchApprovalStats()
    const result = await fetchPendingApprovals(100)
    pendingApprovalMeta.value = result
    pendingApprovals.value = result.items || []
  } finally {
    approvalsLoading.value = false
  }
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const result = await fetchApprovalHistory(100)
    approvalHistoryMeta.value = result
    approvalHistory.value = result.items || []
  } finally {
    historyLoading.value = false
  }
}

async function loadAlwaysApprovals() {
  alwaysLoading.value = true
  try {
    const result = await fetchAlwaysApprovals(100)
    alwaysApprovalMeta.value = result
    alwaysApprovals.value = result.items || []
  } finally {
    alwaysLoading.value = false
  }
}

async function loadSlashConfirms() {
  confirmsLoading.value = true
  try {
    const result = await fetchPendingSlashConfirms(100)
    slashConfirmMeta.value = result
    pendingSlashConfirms.value = result.items || []
  } finally {
    confirmsLoading.value = false
  }
}

async function runAudit() {
  auditLoading.value = true
  try {
    const result = await auditSecurity({
      action: auditForm.value.action,
      toolName: auditForm.value.toolName,
      command: auditForm.value.command,
      url: auditForm.value.url,
      path: auditForm.value.path,
      writeLike: auditForm.value.writeLike,
      argsJson: auditForm.value.argsJson,
    })
    auditResult.value = result
    if ((result.action === 'policy' || result.action === 'status') && result.policy) {
      policyAuditResult.value = result
    }
  } finally {
    auditLoading.value = false
  }
}

async function handleSubprocessEnvProbe() {
  const names = subprocessEnvProbeNames.value
    .split(/[\s,]+/)
    .map((name) => name.trim())
    .filter(Boolean)
  subprocessEnvProbeLoading.value = true
  try {
    subprocessEnvProbeResult.value = await probeSubprocessEnvironment(names)
  } catch (e: any) {
    message.error(e.message || t('diagnostics.subprocessEnvProbeFailed'))
  } finally {
    subprocessEnvProbeLoading.value = false
  }
}

async function handleApproval(item: PendingApproval, action: 'approve' | 'deny', scope: 'once' | 'session' | 'always' = 'once') {
  const approvalSelector = item.selector || item.approval_id || ''
  const key = `${item.session_id}:${approvalSelector}:${action}:${scope}`
  resolvingKey.value = key
  try {
    const result = await resolveApproval({
      sessionId: item.session_id,
      approvalId: approvalSelector,
      action,
      scope,
      resume: true,
    })
    if (result.success) {
      message.success(result.message || t('diagnostics.approvalStatusUpdated'))
      await loadApprovals()
      await loadHistory()
      return
    }
    message.error(result.message || t('diagnostics.approvalStatusUpdateFailed'))
  } finally {
    resolvingKey.value = ''
  }
}

function approvalBusy(item: PendingApproval, action: string, scope = 'once') {
  const approvalSelector = item.selector || item.approval_id || ''
  return resolvingKey.value === `${item.session_id}:${approvalSelector}:${action}:${scope}`
}

async function handleRevokeAlways(item: AlwaysApproval) {
  const approvalId = item.approval_id || ''
  revokingAlwaysKey.value = approvalId
  try {
    const result = await revokeAlwaysApproval(approvalId)
    if (result.success) {
      message.success(result.message || t('diagnostics.alwaysApprovalRevoked'))
      const [diagnosticsData] = await Promise.all([fetchDiagnostics(), loadPolicyAudit(), loadAlwaysApprovals()])
      diagnostics.value = diagnosticsData
      return
    }
    message.error(result.message || t('diagnostics.alwaysApprovalRevokeFailed'))
  } finally {
    revokingAlwaysKey.value = ''
  }
}

async function handleSlashConfirm(item: PendingSlashConfirm, action: 'approve' | 'always' | 'deny') {
  const key = `${item.confirm_id}:${action}`
  resolvingConfirmKey.value = key
  try {
    const result = await resolveSlashConfirm({
      confirmId: item.confirm_id,
      action,
    })
    if (result.success) {
      message.success(result.message || t('diagnostics.slashConfirmUpdatable'))
      await loadSlashConfirms()
      return
    }
    message.error(result.message || t('diagnostics.slashConfirmUpdateFailed'))
  } finally {
    resolvingConfirmKey.value = ''
  }
}

function slashConfirmBusy(item: PendingSlashConfirm, action: string) {
  return resolvingConfirmKey.value === `${item.confirm_id}:${action}`
}

function timeText(value?: number) {
  return formatTimestampText(value)
}

function expiresText(item: { expired?: boolean; expires_in_seconds?: number; expires_at?: number }) {
  if (item.expired) return t('diagnostics.expired')
  if (typeof item.expires_in_seconds === 'number') {
    return t('diagnostics.secondsRemaining', { count: item.expires_in_seconds })
  }
  return timeText(item.expires_at)
}

function canApproveScope(item: PendingApproval, scope: string) {
  if (item.expired) return false
  const scopes = item.scope_options || []
  if (scopes.length > 0) return scopes.includes(scope)
  if (scope === 'always') return item.permanent_allowed === true
  return true
}

function approvalSourceText(source: string) {
  if (source === 'security_scan') return t('diagnostics.sourceSecurityScan')
  if (source === 'local_policy') return t('diagnostics.sourceLocalPolicy')
  return source || '-'
}

function canConfirmAction(item: PendingSlashConfirm, action: string) {
  if (item.expired) return false
  const actions = item.action_options || []
  if (actions.length > 0) return actions.includes(action)
  if (action === 'always') return item.allow_always === true
  return true
}

function auditChoiceText(item: ApprovalAuditEvent) {
  if (item.event_type === 'request') return t('diagnostics.choiceRequested')
  if (item.choice === 'deny') return t('diagnostics.choiceDenied')
  if (item.choice === 'revoke') return t('diagnostics.choiceRevoked')
  if (item.choice === 'timeout') return t('diagnostics.choiceTimedOut')
  if (item.choice === 'session') return t('diagnostics.approveSession')
  if (item.choice === 'always') return t('diagnostics.approveAlways')
  if (item.choice === 'once') return t('diagnostics.approveOnce')
  return item.choice || item.event_type || '-'
}

function auditChoiceType(item: ApprovalAuditEvent) {
  if (item.event_type === 'request') return 'warning'
  if (item.choice === 'deny' || item.choice === 'timeout' || item.choice === 'revoke') return 'error'
  if (item.choice === 'once' || item.choice === 'session' || item.choice === 'always') return 'success'
  return 'default'
}

onMounted(load)
</script>

<template>
  <div class="diagnostics-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t('diagnostics.title') }}</h2>
        <p class="header-subtitle">{{ t('diagnostics.description') }}</p>
      </div>
      <Button size="small" :loading="loading" @click="load">{{ t('diagnostics.refresh') }}</Button>
    </header>
    <Spin :spinning="loading">
      <main class="diagnostics-grid">
        <section class="panel doctor-panel">
          <div class="panel-title-row">
            <h3>{{ t('diagnostics.doctor') }}</h3>
            <Tag size="small" :color="doctorSummary.issueCount ? 'warning' : 'success'" :bordered="false">
              {{ doctorSummary.highestSeverity || t('diagnostics.allPassed') }}
            </Tag>
          </div>
          <div class="metric-grid">
            <div class="metric-item">
              <span>{{ t('diagnostics.doctorIssues') }}</span>
              <Tag size="small" :bordered="false">{{ doctorSummary.issueCount ?? 0 }}</Tag>
            </div>
            <div class="metric-item">
              <span>{{ t('diagnostics.doctorWarnings') }}</span>
              <Tag size="small" :bordered="false">{{ doctorSummary.warningCount ?? 0 }}</Tag>
            </div>
            <div class="metric-item">
              <span>{{ t('diagnostics.doctorPlatforms') }}</span>
              <Tag size="small" :bordered="false">{{ doctor?.platforms?.length || 0 }}</Tag>
            </div>
            <div class="metric-item">
              <span>{{ t('diagnostics.doctorGeneratedAt') }}</span>
              <span>{{ doctor?.generated_at || '-' }}</span>
            </div>
          </div>
          <div v-if="doctorIssues.length" class="doctor-list">
            <div v-for="(issue, index) in doctorIssues.slice(0, 4)" :key="index" class="doctor-item">
              <Tag size="small" :bordered="false">{{ issue.severity || '-' }}</Tag>
              <span>{{ issue.message || issue.code || '-' }}</span>
            </div>
          </div>
          <div v-if="doctorNextActions.length" class="doctor-list">
            <div v-for="action in doctorNextActions.slice(0, 3)" :key="action" class="doctor-item">
              <Tag size="small" :bordered="false">{{ t('diagnostics.doctorNextAction') }}</Tag>
              <span>{{ action }}</span>
            </div>
          </div>
          <div v-if="doctorPlatforms.length" class="doctor-platform-list">
            <article v-for="platform in doctorPlatforms" :key="platform.platform" class="doctor-platform-item">
              <div class="doctor-platform-head">
                <strong>{{ platform.platform || '-' }}</strong>
                <Tag size="small" :color="platform.enabled && platform.connected ? 'success' : 'warning'" :bordered="false">
                  {{ platform.enabled ? (platform.connected ? '已连接' : '未连接') : '未启用' }}
                </Tag>
              </div>
              <div class="doctor-platform-detail">
                <span>配置：{{ platform.setup_state || '-' }}</span>
                <span>连接：{{ platform.connection_mode || '-' }}</span>
                <span v-if="platform.missing_config?.length">缺失：{{ platform.missing_config.join(', ') }}</span>
                <span v-if="platform.last_error_message || platform.last_reconnect_error">
                  错误：{{ platform.last_error_message || platform.last_reconnect_error }}
                </span>
                <span>下一步：{{ platform.next_step || '-' }}</span>
              </div>
            </article>
          </div>
        </section>
        <section class="panel">
          <h3>{{ t('diagnostics.runtime') }}</h3>
          <pre>{{ diagnostics?.runtime }}</pre>
        </section>
        <section class="panel">
          <h3>{{ t('diagnostics.runtimeCapabilities') }}</h3>
          <div v-if="runtimeCapabilityRows.length" class="runtime-capability-list">
            <article v-for="row in runtimeCapabilityRows" :key="row.key" class="runtime-capability-row">
              <div class="runtime-capability-head">
                <strong>{{ row.label }}</strong>
                <Tag size="small" :bordered="false">{{ row.key }}</Tag>
              </div>
              <div class="metric-grid">
                <div v-for="item in row.highlights" :key="item.label" class="metric-item">
                  <span>{{ item.label }}</span>
                  <Tag size="small" :color="metricTagType(item)" :bordered="false">
                    {{ metricText(item.value) }}
                  </Tag>
                </div>
              </div>
            </article>
          </div>
          <div v-else class="empty-state">{{ t('diagnostics.noRuntimeCapabilities') }}</div>
        </section>
        <section class="panel">
          <h3>{{ t('diagnostics.providers') }}</h3>
          <pre>{{ diagnostics?.providers }}</pre>
        </section>
        <section class="panel">
          <h3>{{ t('diagnostics.channels') }}</h3>
          <pre>{{ diagnostics?.channels }}</pre>
        </section>
        <section class="panel">
          <h3>{{ t('diagnostics.toolsAndMcp') }}</h3>
          <pre>{{ diagnostics?.tools }}&#10;{{ diagnostics?.mcp }}</pre>
        </section>
        <section class="panel">
          <div class="panel-title-row">
            <h3>{{ t('diagnostics.proactiveDiagnostics') }}</h3>
            <Tag size="small" :color="proactiveBlocked ? 'warning' : 'success'" :bordered="false">
              {{ proactiveBlocked ? t('diagnostics.hasIssues') : t('diagnostics.allPassed') }}
            </Tag>
          </div>
          <div v-if="hasProactiveDiagnostics" class="metric-grid">
            <div v-for="item in proactiveStatusItems" :key="item.label" class="metric-item">
              <span>{{ item.label }}</span>
              <Tag size="small" :color="metricTagType(item)" :bordered="false">
                {{ metricText(item.value) }}
              </Tag>
            </div>
          </div>
          <div v-if="hasProactiveDiagnostics" class="approval-note">
            {{ t('diagnostics.proactiveWhyNoneSent') }}：{{ valueOf(proactiveDiagnostics, 'why_none_sent') }}
          </div>
          <div v-if="hasProactiveDiagnostics" class="approval-note">
            {{ t('diagnostics.proactiveLastSkipReason') }}：{{ valueOf(proactiveDiagnostics, 'last_skip_reason') }}
          </div>
          <div v-if="proactiveUnknownCandidates.length" class="approval-list">
            <article v-for="candidate in proactiveUnknownCandidates" :key="candidate.candidate_id" class="approval-item">
              <div>
                <strong>{{ candidate.title || candidate.candidate_id }}</strong>
                <div class="approval-note">{{ candidate.summary || candidate.candidate_id }}</div>
              </div>
              <Button
                danger
                size="small"
                :loading="retryingProactiveCandidate === candidate.candidate_id"
                :disabled="Boolean(retryingProactiveCandidate)"
                @click="retryUnknownProactive(candidate)"
              >
                {{ t('diagnostics.proactiveRetry') }}
              </Button>
            </article>
          </div>
          <div v-else-if="hasProactiveDiagnostics" class="empty-state">
            {{ t('diagnostics.noProactiveUnknownDeliveries') }}
          </div>
          <div v-if="!hasProactiveDiagnostics" class="empty-state">{{ t('diagnostics.noProactiveDiagnostics') }}</div>
        </section>
        <section class="panel">
          <div class="panel-title-row">
            <h3>{{ t('diagnostics.pluginStatus') }}</h3>
            <Tag size="small" :color="pluginFailedCount ? 'error' : 'success'" :bordered="false">
              {{ pluginLoadedCount }}
            </Tag>
          </div>
          <div class="metric-grid">
            <div class="metric-item">
              <span>{{ t('diagnostics.pluginLoaded') }}</span>
              <Tag size="small" color="success" :bordered="false">{{ pluginLoadedCount }}</Tag>
            </div>
            <div class="metric-item">
              <span>{{ t('diagnostics.pluginSkipped') }}</span>
              <Tag size="small" color="warning" :bordered="false">{{ pluginSkippedCount }}</Tag>
            </div>
            <div class="metric-item">
              <span>{{ t('diagnostics.pluginFailed') }}</span>
              <Tag size="small" :color="pluginFailedCount ? 'error' : 'default'" :bordered="false">{{ pluginFailedCount }}</Tag>
            </div>
            <div class="metric-item">
              <span>{{ t('diagnostics.pluginDiagnostics') }}</span>
              <Tag size="small" :bordered="false">{{ pluginStatus?.diagnostic_count ?? 0 }}</Tag>
            </div>
          </div>
          <div v-if="pluginRows.length" class="plugin-list">
            <article v-for="plugin in pluginRows.slice(0, 4)" :key="plugin.name" class="plugin-item">
              <div class="plugin-head">
                <strong>{{ plugin.name || '-' }}</strong>
                <Tag size="small" :bordered="false">{{ plugin.kind || '-' }}</Tag>
              </div>
              <p>{{ plugin.description || plugin.directory_ref || '-' }}</p>
              <div class="plugin-meta">
                <span>{{ t('diagnostics.pluginVersion') }}: {{ plugin.version || '-' }}</span>
                <span>{{ t('diagnostics.pluginAuthor') }}: {{ plugin.author || '-' }}</span>
                <span>{{ t('diagnostics.pluginSource') }}: {{ plugin.source || '-' }}</span>
                <span>{{ t('diagnostics.pluginEnabled') }}: {{ plugin.enabled ? t('common.enabled') : t('common.disabled') }}</span>
                <span>{{ t('diagnostics.pluginAutoLoad') }}: {{ plugin.auto_load ? t('common.enabled') : t('common.disabled') }}</span>
                <span>{{ t('diagnostics.pluginProvidesTools') }}: {{ plugin.provides_tools?.join(', ') || '-' }}</span>
              </div>
            </article>
          </div>
          <div v-else class="empty-state">{{ t('diagnostics.noPlugins') }}</div>
          <div v-if="pluginDiagnostics.length" class="plugin-diagnostics">
            <div v-for="item in pluginDiagnostics.slice(0, 5)" :key="`${item.plugin_name}:${item.reason}`" class="doctor-item">
              <Tag size="small" :color="pluginStatusTagType(item.status)" :bordered="false">{{ item.status || '-' }}</Tag>
              <span>{{ item.plugin_name || '-' }} · {{ item.reason || item.message || '-' }}</span>
            </div>
          </div>
        </section>
        <section class="panel">
          <h3>{{ t('diagnostics.platformToolsets') }}</h3>
          <div v-if="platformToolsetRows.length" class="toolset-list">
            <div v-for="row in platformToolsetRows" :key="row.platform" class="toolset-row">
              <div class="toolset-platform">
                <strong>{{ row.platform }}</strong>
                <Tag size="small" :color="row.approvalRequired ? 'warning' : 'default'" :bordered="false">
                  {{ row.approvalRequired ? t('diagnostics.approvalRequired') : t('diagnostics.approvalNotRequired') }}
                </Tag>
              </div>
              <label>
                <span>{{ t('diagnostics.enabledToolsets') }}</span>
                <Input
                  v-model:value="platformToolsetForms[row.platform].enabledToolsetsText"
                  size="small"
                  :placeholder="t('diagnostics.enabledToolsetsPlaceholder')"
                />
              </label>
              <label>
                <span>{{ t('diagnostics.disabledToolsets') }}</span>
                <Input
                  v-model:value="platformToolsetForms[row.platform].disabledToolsetsText"
                  size="small"
                  :placeholder="t('diagnostics.disabledToolsetsPlaceholder')"
                />
              </label>
              <label class="toolset-approval">
                <span>{{ t('diagnostics.approvalRequired') }}</span>
                <Switch v-model:value="platformToolsetForms[row.platform].approvalRequired" size="small" />
              </label>
              <Button
                size="small"
                type="primary"
                :loading="savingPlatformToolsets === row.platform"
                @click="savePlatformToolsets(row)"
              >
                {{ t('diagnostics.savePlatformToolsets') }}
              </Button>
            </div>
          </div>
          <div v-else class="empty-state">{{ t('diagnostics.noPlatformToolsets') }}</div>
        </section>
        <section class="panel insights-panel">
          <h3>{{ t('diagnostics.insights') }}</h3>
          <div class="insight-stats">
            <div>
              <span>{{ t('diagnostics.insightSessions') }}</span>
              <strong>{{ valueOf(insightSessions, 'total', 0) }}</strong>
            </div>
            <div>
              <span>{{ t('diagnostics.insightTrackedSkills') }}</span>
              <strong>{{ valueOf(insightSkills, 'tracked', 0) }}</strong>
            </div>
            <div>
              <span>{{ t('diagnostics.insightActiveSkills') }}</span>
              <strong>{{ valueOf(insightSkills, 'active', 0) }}</strong>
            </div>
            <div>
              <span>{{ t('diagnostics.insightMemory') }}</span>
              <strong>{{ valueOf(insightRuntime, 'usedMemoryMb', 0) }} / {{ valueOf(insightRuntime, 'maxMemoryMb', 0) }} MB</strong>
            </div>
            <div>
              <span>{{ t('diagnostics.insightProcessors') }}</span>
              <strong>{{ valueOf(insightRuntime, 'availableProcessors', 0) }}</strong>
            </div>
            <div>
              <span>{{ t('diagnostics.insightUptime') }}</span>
              <strong>{{ Math.round(Number(valueOf(insightRuntime, 'uptimeMs', 0)) / 1000) }}s</strong>
            </div>
          </div>
          <div class="skill-insight-list">
            <div v-for="row in skillInsightRows" :key="String(row.key)" class="skill-insight-row">
              <span>{{ row.key }}</span>
              <Tag size="small" :bordered="false">{{ row.state || 'active' }}</Tag>
              <strong>{{ row.count || 0 }}</strong>
            </div>
            <div v-if="!skillInsightRows.length" class="empty-state">{{ t('diagnostics.noSkillInsights') }}</div>
          </div>
        </section>
        <section class="panel security-panel">
          <h3>{{ t('diagnostics.securityPolicy') }}</h3>
          <div class="security-groups">
            <div class="security-group">
              <h4>{{ t('diagnostics.approvalsGroup') }}</h4>
              <dl>
                <div>
                  <dt>{{ t('diagnostics.commandApproval') }}</dt>
                  <dd>{{ valueOf(securityApprovals, 'mode') }}</dd>
                </div>
                <div>
                  <dt>{{ t('diagnostics.cronApproval') }}</dt>
                  <dd>{{ valueOf(securityApprovals, 'cron_mode') }}</dd>
                </div>
                <div>
                  <dt>{{ t('diagnostics.mcpReloadConfirm') }}</dt>
                  <dd>
                    <Tag size="small" :color="booleanTagType(securityApprovals.mcp_reload_confirm)">
                      {{ booleanText(securityApprovals.mcp_reload_confirm) }}
                    </Tag>
                  </dd>
                </div>
                <div>
                  <dt>{{ t('diagnostics.alwaysApprovalCount') }}</dt>
                  <dd>{{ valueOf(securityApprovals, 'always_approval_count', 0) }}</dd>
                </div>
              </dl>
            </div>
            <div class="security-group">
              <h4>{{ t('diagnostics.terminalGuardrails') }}</h4>
              <dl>
                <div>
                  <dt>{{ t('diagnostics.credentialFiles') }}</dt>
                  <dd>{{ valueOf(securityTerminal, 'credential_file_count', 0) }}</dd>
                </div>
                <div>
                  <dt>{{ t('diagnostics.envPassthrough') }}</dt>
                  <dd>{{ valueOf(securityTerminal, 'env_passthrough_count', 0) }}</dd>
                </div>
                <div>
                  <dt>{{ t('diagnostics.sudoPassword') }}</dt>
                  <dd>
                    <Tag size="small" :color="booleanTagType(securityTerminal.sudo_password_configured)">
                      {{ booleanText(securityTerminal.sudo_password_configured) }}
                    </Tag>
                  </dd>
                </div>
                <div>
                  <dt>{{ t('diagnostics.writeSafeRoot') }}</dt>
                  <dd>
                    <Tag size="small" :color="booleanTagType(securityTerminal.write_safe_root_configured)">
                      {{ booleanText(securityTerminal.write_safe_root_configured) }}
                    </Tag>
                  </dd>
                </div>
              </dl>
            </div>
            <div class="security-group">
              <h4>{{ t('diagnostics.contentScan') }}</h4>
              <dl>
                <div>
                  <dt>{{ t('diagnostics.scanEnabled') }}</dt>
                  <dd>
                    <Tag size="small" :color="booleanTagType(securityPolicy.tirith_enabled)">
                      {{ booleanText(securityPolicy.tirith_enabled) }}
                    </Tag>
                  </dd>
                </div>
                <div>
                  <dt>{{ t('diagnostics.scannerConfigured') }}</dt>
                  <dd>
                    <Tag size="small" :color="booleanTagType(securityPolicy.tirith_configured)">
                      {{ booleanText(securityPolicy.tirith_configured) }}
                    </Tag>
                  </dd>
                </div>
                <div>
                  <dt>{{ t('diagnostics.failOpen') }}</dt>
                  <dd>
                    <Tag size="small" :color="booleanTagType(securityPolicy.tirith_fail_open, false)">
                      {{ booleanText(securityPolicy.tirith_fail_open) }}
                    </Tag>
                  </dd>
                </div>
                <div>
                  <dt>{{ t('diagnostics.timeout') }}</dt>
                  <dd>{{ valueOf(securityPolicy, 'tirith_timeout_seconds', 0) }} {{ t('diagnostics.secondsUnit') }}</dd>
                </div>
              </dl>
            </div>
          </div>
          <div class="coverage-section">
            <div class="coverage-title">
              <h4>{{ t('diagnostics.coverageSnapshot') }}</h4>
              <Tag size="small" :color="policyAuditResult?.success === false ? 'error' : 'success'" :bordered="false">
                {{ policyAuditResult?.success === false ? t('diagnostics.abnormal') : t('diagnostics.readonly') }}
              </Tag>
            </div>
            <div class="coverage-grid">
              <div v-for="item in coverageItems" :key="item.key" class="coverage-item">
                <span>{{ item.label }}</span>
                <Tag size="small" :color="booleanTagType(securityCoverage[item.key])" :bordered="false">
                  {{ booleanText(securityCoverage[item.key]) }}
                </Tag>
              </div>
            </div>
            <div class="surface-list">
              <Tag v-for="surface in securitySurfaces" :key="surface" size="small" :bordered="false">
                {{ surfaceLabel(surface) }}
              </Tag>
              <span v-if="!securitySurfaces.length" class="surface-empty">{{ t('diagnostics.noCoverageData') }}</span>
            </div>
          </div>
          <div class="policy-detail-section">
            <div class="coverage-title">
              <h4>{{ t('diagnostics.policyDetails') }}</h4>
              <Tag size="small" :bordered="false">{{ t('diagnostics.readonlyDiagnostics') }}</Tag>
            </div>
            <div class="policy-detail-grid">
              <div v-for="group in securityDetailGroups" :key="group.title" class="policy-detail-group">
                <h5>{{ group.title }}</h5>
                <div class="metric-grid">
                  <div v-for="item in group.items" :key="`${group.title}:${item.label}`" class="metric-item">
                    <span>{{ item.label }}</span>
                    <Tag size="small" :color="metricTagType(item)" :bordered="false">
                      {{ metricText(item.value) }}
                    </Tag>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div class="probe-section">
            <div class="coverage-title">
              <h4>{{ t('diagnostics.securityProbe') }}</h4>
              <Tag size="small" :color="securityProbePassed === false ? 'error' : 'success'" :bordered="false">
                {{ securityProbePassed === false ? t('diagnostics.hasIssues') : t('diagnostics.allPassed') }}
              </Tag>
            </div>
            <p v-if="diagnostics?.security?.probes?.available === false" class="approval-note">
              {{ diagnostics?.security?.probes?.message || t('diagnostics.serviceUnavailable') }}
            </p>
            <div v-else-if="securityProbes.length" class="probe-grid">
              <div v-for="probe in securityProbes" :key="probe.key || probe.label" class="probe-item">
                <div class="probe-head">
                  <strong>{{ probe.label || probe.key }}</strong>
                  <Tag size="small" :color="probe.passed ? 'success' : 'error'" :bordered="false">
                    {{ probe.skipped ? t('diagnostics.skipped') : probe.passed ? t('diagnostics.passed') : t('diagnostics.abnormal') }}
                  </Tag>
                </div>
                <div class="probe-meta">
                  <span>{{ surfaceLabel(probe.surface || '') }}</span>
                  <span>{{ probe.skipped ? t('diagnostics.unchecked') : probe.blocked ? t('diagnostics.blocked') : t('diagnostics.allowed') }}</span>
                </div>
                <p>{{ probe.message || '-' }}</p>
                <code>{{ probe.target || '-' }}</code>
              </div>
            </div>
            <div v-else class="surface-empty">{{ t('diagnostics.noProbeData') }}</div>
          </div>
          <div class="probe-section">
            <div class="coverage-title">
              <h4>{{ t('diagnostics.subprocessEnvProbe') }}</h4>
              <Button size="small" :loading="subprocessEnvProbeLoading" @click="handleSubprocessEnvProbe">
                {{ t('diagnostics.runProbe') }}
              </Button>
            </div>
            <TextArea
              v-model:value="subprocessEnvProbeNames"
              class="probe-input"
              :rows="2"
              :placeholder="t('diagnostics.subprocessEnvProbePlaceholder')"
            />
            <p class="approval-note">{{ t('diagnostics.subprocessEnvProbeHint') }}</p>
            <div v-if="subprocessEnvProbeResult" class="probe-result">
              <p>{{ subprocessEnvProbeResult.summary || '-' }}</p>
              <div class="probe-grid">
                <div v-for="decision in subprocessEnvProbeResult.decisions || []" :key="String(decision.name || decision.key)" class="probe-item">
                  <div class="probe-head">
                    <strong>{{ decision.name || decision.key || '-' }}</strong>
                    <Tag size="small" :bordered="false">{{ decision.decision || '-' }}</Tag>
                  </div>
                  <div class="probe-meta">
                    <span>{{ decision.visibility || '-' }}</span>
                    <span>{{ decision.reason || '-' }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>
        <section class="panel audit-panel">
          <h3>{{ t('diagnostics.audit') }}</h3>
          <div class="audit-layout">
            <div class="audit-form">
              <label>
                <span>{{ t('diagnostics.auditType') }}</span>
                <Select v-model:value="auditForm.action" :options="auditActionOptions" size="small" />
              </label>
              <label v-if="auditForm.action === 'command' || auditForm.action === 'tool_args'">
                <span>{{ t('diagnostics.auditToolName') }}</span>
                <Input v-model:value="auditForm.toolName" size="small" :placeholder="t('diagnostics.auditToolPlaceholder')" />
              </label>
              <label v-if="auditForm.action === 'command'">
                <span>{{ t('diagnostics.auditCommand') }}</span>
                <TextArea
                  v-model:value="auditForm.command"
                  :autosize="{ minRows: 3, maxRows: 8 }"
                  :placeholder="t('diagnostics.auditCommandPlaceholder')"
                />
              </label>
              <label v-if="auditForm.action === 'url'">
                <span>{{ t('diagnostics.auditUrl') }}</span>
                <Input v-model:value="auditForm.url" size="small" :placeholder="t('diagnostics.auditUrlPlaceholder')" />
              </label>
              <label v-if="auditForm.action === 'path'">
                <span>{{ t('diagnostics.auditPath') }}</span>
                <Input v-model:value="auditForm.path" size="small" :placeholder="t('diagnostics.auditPathPlaceholder')" />
              </label>
              <label v-if="auditForm.action === 'path'" class="switch-row">
                <span>{{ t('diagnostics.auditWriteLike') }}</span>
                <Switch v-model:value="auditForm.writeLike" size="small" />
              </label>
              <label v-if="auditForm.action === 'tool_args'">
                <span>{{ t('diagnostics.auditArgsJson') }}</span>
                <TextArea
                  v-model:value="auditForm.argsJson"
                  :autosize="{ minRows: 3, maxRows: 8 }"
                  :placeholder="t('diagnostics.auditArgsPlaceholder')"
                />
              </label>
              <p v-if="auditForm.action === 'status'" class="approval-note">
                {{ t('diagnostics.auditStatusHint') }}
              </p>
              <p v-if="auditForm.action === 'policy'" class="approval-note">
                {{ t('diagnostics.auditPolicyHint') }}
              </p>
              <Button size="small" type="primary" :loading="auditLoading" @click="runAudit">{{ t('diagnostics.auditRun') }}</Button>
            </div>
            <div class="audit-result">
              <div class="audit-summary">
                <Tag size="small" :color="decisionType(auditResult?.decision)">
                  {{ auditResult?.decision || t('diagnostics.notAudited') }}
                </Tag>
                <Tag v-if="auditResult?.blocking" size="small" color="error" :bordered="false">{{ t('diagnostics.blocked') }}</Tag>
                <Tag v-if="auditResult?.approval_required" size="small" color="warning" :bordered="false">{{ t('diagnostics.approvalRequired') }}</Tag>
                <span>{{ auditResult?.summary || t('diagnostics.waitingAuditInput') }}</span>
              </div>
              <div v-if="auditFindings.length" class="finding-list">
                <div v-for="(finding, index) in auditFindings" :key="index" class="finding-item">
                  <div class="finding-meta">
                    <Tag size="small" :bordered="false">{{ finding.source || 'policy' }}</Tag>
                    <Tag v-if="finding.blocking" size="small" color="error" :bordered="false">{{ t('diagnostics.findingBlocked') }}</Tag>
                    <Tag v-else-if="finding.approval_required" size="small" color="warning" :bordered="false">
                      {{ t('diagnostics.findingApproval') }}
                    </Tag>
                    <span>{{ finding.ruleId || '-' }}</span>
                    <span>{{ finding.severity || '-' }}</span>
                    <span v-if="finding.suggested_action">{{ findingActionText(finding.suggested_action) }}</span>
                  </div>
                  <p>{{ finding.message }}</p>
                </div>
              </div>
              <pre v-else>{{ auditResult }}</pre>
            </div>
          </div>
        </section>
        <ApprovalEventsPanel
          :loading="approvalEventsLoading"
          :result="approvalEvents"
          @refresh="loadApprovalEvents"
        />
        <section class="panel approvals-panel">
          <div class="panel-title-row">
            <h3>{{ t('diagnostics.pendingApprovals') }}</h3>
            <div class="panel-actions">
              <Tag size="small" :color="pendingCount ? 'warning' : 'success'">{{ pendingCount }}</Tag>
              <Tag size="small" :color="pendingApprovalMeta?.session_scan_truncated ? 'warning' : 'default'">
                {{ pendingApprovalScanText }}
              </Tag>
              <Button size="small" :loading="approvalsLoading" @click="loadApprovals">{{ t('diagnostics.refresh') }}</Button>
            </div>
          </div>
          <Spin :spinning="approvalsLoading">
            <div class="approval-stats">
              <h4>{{ t('diagnostics.approvalStats') }}</h4>
              <div class="metric-grid">
                <div v-for="item in approvalStatItems" :key="item.label" class="metric-item">
                  <span>{{ item.label }}</span>
                  <Tag size="small" :bordered="false">{{ item.value }}</Tag>
                </div>
              </div>
            </div>
            <p v-if="pendingApprovalMeta?.available === false" class="approval-note">
              {{ pendingApprovalMeta.message || t('diagnostics.approvalServiceUnavailable') }}
            </p>
            <p v-else-if="pendingApprovalMeta?.session_scan_truncated" class="approval-note">
              {{ t('diagnostics.scanRecentSessions', { count: pendingApprovalMeta.scanned_sessions || 0 }) }}
            </p>
            <p v-else-if="pendingApprovalMeta?.truncated" class="approval-note">
              {{ t('diagnostics.showingPendingItems', { count: pendingApprovalMeta.count || pendingCount }) }}
            </p>
            <div v-if="pendingApprovals.length" class="approval-list">
              <article v-for="item in pendingApprovals" :key="`${item.session_id}:${item.selector || item.approval_id || item.command_hash}`" class="approval-item">
                <div class="approval-head">
                  <div>
                    <strong>{{ item.title || item.session_id }}</strong>
                    <span>{{ item.tool_name || '-' }} · {{ item.source_ref || '-' }}</span>
                  </div>
                  <Tag size="small" :color="item.permanent_allowed ? 'default' : 'warning'">
                    {{ item.permanent_allowed ? t('diagnostics.permanentAllowed') : t('diagnostics.onceOrSessionOnly') }}
                  </Tag>
                </div>
                <p class="approval-desc">{{ item.description || '-' }}</p>
                <pre class="approval-command">{{ item.command_preview || '-' }}</pre>
                <div v-if="item.rule_sources?.length" class="approval-scopes">
                  <Tag v-for="source in item.rule_sources" :key="source" size="small" :bordered="false">
                    {{ approvalSourceText(source) }}
                  </Tag>
                </div>
                <div class="approval-meta">
                  <span>{{ item.selector || item.approval_id || '-' }}</span>
                  <span>{{ t('diagnostics.createdAt', { time: timeText(item.created_at) }) }}</span>
                  <span>{{ t('diagnostics.expiresAt', { time: timeText(item.expires_at) }) }}</span>
                  <span :class="{ 'approval-expired': item.expired }">{{ t('diagnostics.remaining', { time: expiresText(item) }) }}</span>
                </div>
                <div v-if="item.scope_options?.length" class="approval-scopes">
                  <Tag v-for="scope in item.scope_options" :key="scope" size="small" :bordered="false">
                    {{ scope === 'once' ? t('diagnostics.scopeOnce') : scope === 'session' ? t('diagnostics.scopeSession') : t('diagnostics.scopeAlways') }}
                  </Tag>
                </div>
                <p v-if="item.permanent_disabled_reason" class="approval-note">
                  {{ item.permanent_disabled_reason }}
                </p>
                <div class="approval-actions">
                  <SpaceCompact size="small">
                    <Button
                      type="primary"
                      :disabled="!canApproveScope(item, 'once')"
                      :loading="approvalBusy(item, 'approve', 'once')"
                      @click="handleApproval(item, 'approve', 'once')"
                    >
                      {{ t('diagnostics.approveOnce') }}
                    </Button>
                    <Button
                      :disabled="!canApproveScope(item, 'session')"
                      :loading="approvalBusy(item, 'approve', 'session')"
                      @click="handleApproval(item, 'approve', 'session')"
                    >
                      {{ t('diagnostics.approveSession') }}
                    </Button>
                    <Button
                      :disabled="!canApproveScope(item, 'always')"
                      :loading="approvalBusy(item, 'approve', 'always')"
                      @click="handleApproval(item, 'approve', 'always')"
                    >
                      {{ t('diagnostics.approveAlways') }}
                    </Button>
                  </SpaceCompact>
                  <Button
                    size="small"
                    danger
                    ghost
                    :disabled="item.expired"
                    :loading="approvalBusy(item, 'deny')"
                    @click="handleApproval(item, 'deny')"
                  >
                    {{ t('diagnostics.deny') }}
                  </Button>
                </div>
              </article>
            </div>
            <div v-else class="empty-state">{{ t('diagnostics.pendingApprovalsEmpty') }}</div>
          </Spin>
        </section>
        <section class="panel approvals-panel">
          <div class="panel-title-row">
            <h3>{{ t('diagnostics.approvalHistory') }}</h3>
            <div class="panel-actions">
              <Tag size="small">{{ historyCount }}</Tag>
              <Button size="small" :loading="historyLoading" @click="loadHistory">{{ t('diagnostics.refresh') }}</Button>
            </div>
          </div>
          <Spin :spinning="historyLoading">
            <p v-if="approvalHistoryMeta?.available === false" class="approval-note">
              {{ approvalHistoryMeta.message || t('diagnostics.approvalHistoryUnavailable') }}
            </p>
            <p v-else-if="approvalHistoryMeta?.truncated" class="approval-note">
              {{ t('diagnostics.showingApprovalHistory', { count: approvalHistoryMeta.count || historyCount }) }}
            </p>
            <div v-if="approvalHistory.length" class="approval-list">
              <article v-for="item in approvalHistory" :key="item.event_id" class="approval-item">
                <div class="approval-head">
                  <div>
                    <strong>{{ item.description || item.command_hash || item.event_id }}</strong>
                    <span>{{ item.session_id || '-' }} · {{ item.tool_name || '-' }}</span>
                  </div>
                  <Tag size="small" :color="auditChoiceType(item)">
                    {{ auditChoiceText(item) }}
                  </Tag>
                </div>
                <pre class="approval-command">{{ item.command_preview || '-' }}</pre>
                <div class="approval-meta">
                  <span>{{ timeText(item.created_at) }}</span>
                  <span v-if="item.approver">{{ t('diagnostics.approver', { name: item.approver }) }}</span>
                  <span>{{ item.command_hash || '-' }}</span>
                </div>
              </article>
            </div>
            <div v-else class="empty-state">{{ t('diagnostics.noApprovalHistory') }}</div>
          </Spin>
        </section>
        <section class="panel approvals-panel">
          <div class="panel-title-row">
            <h3>{{ t('diagnostics.alwaysApprovals') }}</h3>
            <div class="panel-actions">
              <Tag size="small" :color="alwaysCount ? 'warning' : 'success'">{{ alwaysCount }}</Tag>
              <Button size="small" :loading="alwaysLoading" @click="loadAlwaysApprovals">{{ t('diagnostics.refresh') }}</Button>
            </div>
          </div>
          <Spin :spinning="alwaysLoading">
            <p v-if="alwaysApprovalMeta?.available === false" class="approval-note">
              {{ alwaysApprovalMeta.message || t('diagnostics.approvalServiceUnavailable') }}
            </p>
            <p v-else-if="alwaysApprovalMeta?.truncated" class="approval-note">
              {{ t('diagnostics.showingAlwaysApprovals', { count: alwaysApprovalMeta.count || alwaysCount }) }}
            </p>
            <div v-if="alwaysApprovals.length" class="approval-list">
              <article v-for="item in alwaysApprovals" :key="item.approval_id || `${item.tool_name}:${item.pattern_key}`" class="approval-item">
                <div class="approval-head">
                  <div>
                    <strong>{{ item.pattern_key || '-' }}</strong>
                    <span>{{ item.tool_name || '-' }}</span>
                  </div>
                  <Tag size="small" color="warning">{{ t('diagnostics.alwaysAllowed') }}</Tag>
                </div>
                <pre class="approval-command">{{ item.pattern_key || '-' }}</pre>
                <div class="approval-actions">
                  <Button
                    size="small"
                    danger
                    ghost
                    :loading="revokingAlwaysKey === item.approval_id"
                    :disabled="!item.approval_id"
                    @click="handleRevokeAlways(item)"
                  >
                    {{ t('diagnostics.revoke') }}
                  </Button>
                </div>
              </article>
            </div>
            <div v-else class="empty-state">{{ t('diagnostics.noAlwaysApprovals') }}</div>
          </Spin>
        </section>
        <section class="panel approvals-panel">
          <div class="panel-title-row">
            <h3>{{ t('diagnostics.pendingSlashCommands') }}</h3>
            <div class="panel-actions">
              <Tag size="small" :color="slashConfirmCount ? 'warning' : 'success'">{{ slashConfirmCount }}</Tag>
              <Button size="small" :loading="confirmsLoading" @click="loadSlashConfirms">{{ t('diagnostics.refresh') }}</Button>
            </div>
          </div>
          <Spin :spinning="confirmsLoading">
            <p v-if="slashConfirmMeta?.available === false" class="approval-note">
              {{ slashConfirmMeta.message || t('diagnostics.slashServiceUnavailable') }}
            </p>
            <p v-else-if="slashConfirmMeta?.truncated" class="approval-note">
              {{ t('diagnostics.showingPendingSlash', { count: slashConfirmMeta.count || slashConfirmCount }) }}
            </p>
            <div v-if="pendingSlashConfirms.length" class="approval-list">
              <article v-for="item in pendingSlashConfirms" :key="item.confirm_id" class="approval-item">
                <div class="approval-head">
                  <div>
                    <strong>/{{ item.command_preview || '-' }}</strong>
                    <span>{{ item.source_ref || '-' }}</span>
                  </div>
                  <Tag size="small" :color="item.allow_always ? 'default' : 'warning'">
                    {{ item.allow_always ? t('diagnostics.slashAllowAlways') : t('diagnostics.slashOnceOnly') }}
                  </Tag>
                </div>
                <p class="approval-desc">{{ item.prompt_preview || '-' }}</p>
                <div class="approval-meta">
                  <span>{{ item.confirm_ref || '-' }}</span>
                  <span>{{ t('diagnostics.createdAt', { time: timeText(item.created_at) }) }}</span>
                  <span>{{ t('diagnostics.expiresAt', { time: timeText(item.expires_at) }) }}</span>
                  <span :class="{ 'approval-expired': item.expired }">{{ t('diagnostics.remaining', { time: expiresText(item) }) }}</span>
                </div>
                <div class="approval-actions">
                  <SpaceCompact size="small">
                    <Button
                      type="primary"
                      :disabled="!canConfirmAction(item, 'approve')"
                      :loading="slashConfirmBusy(item, 'approve')"
                      @click="handleSlashConfirm(item, 'approve')"
                    >
                      {{ t('diagnostics.executeOnce') }}
                    </Button>
                    <Button
                      :disabled="!canConfirmAction(item, 'always')"
                      :loading="slashConfirmBusy(item, 'always')"
                      @click="handleSlashConfirm(item, 'always')"
                    >
                      {{ t('diagnostics.confirmAlways') }}
                    </Button>
                  </SpaceCompact>
                  <Button
                    size="small"
                    danger
                    ghost
                    :disabled="!canConfirmAction(item, 'deny')"
                    :loading="slashConfirmBusy(item, 'deny')"
                    @click="handleSlashConfirm(item, 'deny')"
                  >
                    {{ t('diagnostics.cancel') }}
                  </Button>
                </div>
              </article>
            </div>
            <div v-else class="empty-state">{{ t('diagnostics.noPendingSlash') }}</div>
          </Spin>
        </section>
      </main>
    </Spin>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.diagnostics-view {
  height: calc(100 * var(--vh));
  display: flex;
  flex-direction: column;
}

.diagnostics-grid {
  padding: 20px;
  display: grid;
  grid-template-columns: repeat(2, minmax(280px, 1fr));
  gap: 16px;
}

.panel {
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  background: $bg-primary;
  padding: 14px;
  min-height: 220px;
}

.security-panel {
  grid-column: 1 / -1;
}

.doctor-panel {
  grid-column: 1 / -1;
}

.doctor-platform-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 10px;
  margin-top: 12px;
}

.doctor-platform-item {
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  padding: 10px;
}

.doctor-platform-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  text-transform: capitalize;
}

.doctor-platform-detail {
  display: grid;
  gap: 4px;
  margin-top: 8px;
  color: $text-secondary;
  font-size: 12px;
  overflow-wrap: anywhere;
}

.audit-panel {
  grid-column: 1 / -1;
}

.insights-panel {
  grid-column: 1 / -1;
}

.approvals-panel {
  grid-column: 1 / -1;
}

.approval-stats {
  margin-bottom: 12px;
}

.panel-title-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}

.panel-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.security-groups {
  display: grid;
  grid-template-columns: repeat(4, minmax(180px, 1fr));
  gap: 12px;
}

.security-group {
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  padding: 12px;
  background: $bg-secondary;
}

.coverage-section {
  margin-top: 12px;
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  padding: 12px;
  background: $bg-secondary;
}

.policy-detail-section {
  margin-top: 12px;
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  padding: 12px;
  background: $bg-secondary;
}

.probe-section {
  margin-top: 12px;
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  padding: 12px;
  background: $bg-secondary;
}

.probe-input,
.probe-result {
  margin-top: 8px;
}

.probe-result > p {
  margin-bottom: 8px;
  color: $text-secondary;
  font-size: 13px;
}

.coverage-title {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  margin-bottom: 10px;
}

.coverage-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr));
  gap: 8px;
}

.coverage-item {
  min-height: 30px;
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  background: $bg-primary;
  padding: 6px 8px;
  font-size: 12px;
  color: $text-secondary;
}

.surface-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.surface-empty {
  font-size: 12px;
  color: $text-muted;
}

.policy-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(260px, 1fr));
  gap: 12px;
}

.policy-detail-group {
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  background: $bg-primary;
  padding: 10px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(120px, 1fr));
  gap: 8px;
}

.metric-item {
  min-height: 28px;
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
  font-size: 12px;
  color: $text-secondary;
}

.insight-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(140px, 1fr));
  gap: 10px;
}

.insight-stats div,
.skill-insight-row {
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  background: $bg-secondary;
  padding: 10px;
}

.insight-stats span {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  color: $text-muted;
}

.insight-stats strong {
  font-size: 18px;
  color: $text-primary;
}

.skill-insight-list {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.skill-insight-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 10px;
  align-items: center;
  font-size: 12px;
  color: $text-secondary;
}

.skill-insight-row span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.runtime-capability-list {
  display: grid;
  gap: 10px;
}

.runtime-capability-row {
  display: grid;
  gap: 8px;
}

.runtime-capability-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}

.runtime-capability-head strong {
  color: $text-primary;
  font-size: 13px;
}

.toolset-list {
  display: grid;
  gap: 8px;
}

.plugin-list {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.plugin-item {
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  background: $bg-secondary;
  padding: 10px;
}

.plugin-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}

.plugin-head strong {
  color: $text-primary;
  font-size: 13px;
}

.plugin-item p {
  margin: 6px 0 0;
  color: $text-secondary;
  font-size: 12px;
  word-break: break-word;
}

.plugin-meta {
  display: grid;
  gap: 4px;
  margin-top: 8px;
  color: $text-muted;
  font-size: 12px;
  overflow-wrap: anywhere;
}

.plugin-diagnostics {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.toolset-row {
  display: grid;
  grid-template-columns: 120px minmax(180px, 1fr) minmax(180px, 1fr) 120px auto;
  gap: 10px;
  align-items: end;
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  background: $bg-secondary;
  padding: 10px;
  font-size: 12px;
  color: $text-secondary;
}

.toolset-platform {
  display: grid;
  gap: 6px;
  align-self: center;
}

.toolset-row label {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.toolset-row label span {
  color: $text-muted;
}

.toolset-approval {
  align-items: start;
}

@media (max-width: $breakpoint-mobile) {
  .toolset-row {
    grid-template-columns: 1fr;
    align-items: stretch;
  }
}

.doctor-list {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.doctor-item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 8px;
  align-items: start;
  font-size: 12px;
  color: $text-secondary;
}

.doctor-item span {
  overflow-wrap: anywhere;
}

.probe-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(220px, 1fr));
  gap: 10px;
}

.probe-item {
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  background: $bg-primary;
  padding: 10px;
}

.probe-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}

.probe-head strong {
  font-size: 12px;
  color: $text-primary;
}

.probe-meta {
  display: flex;
  gap: 8px;
  margin-top: 6px;
  font-size: 12px;
  color: $text-muted;
}

.probe-item p {
  margin: 8px 0;
  font-size: 12px;
  color: $text-secondary;
  word-break: break-word;
}

.probe-item code {
  display: block;
  font-size: 12px;
  color: $text-muted;
  word-break: break-word;
}

h3 {
  margin: 0 0 12px;
  font-size: 14px;
}

h4 {
  margin: 0 0 10px;
  font-size: 13px;
  color: $text-primary;
}

h5 {
  margin: 0 0 10px;
  font-size: 12px;
  color: $text-primary;
}

dl {
  margin: 0;
  display: grid;
  gap: 8px;
}

dl > div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  min-height: 24px;
}

dt,
dd {
  margin: 0;
  font-size: 12px;
}

dt {
  color: $text-muted;
}

dd {
  color: $text-primary;
  text-align: right;
}

.audit-layout {
  display: grid;
  grid-template-columns: minmax(280px, 360px) 1fr;
  gap: 16px;
}

.audit-form {
  display: grid;
  gap: 12px;
}

.audit-form label {
  display: grid;
  gap: 6px;
  font-size: 12px;
  color: $text-muted;
}

.switch-row {
  grid-template-columns: 1fr auto;
  align-items: center;
}

.audit-result {
  min-height: 220px;
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  background: $bg-secondary;
  padding: 12px;
}

.audit-summary {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
  font-size: 12px;
  color: $text-secondary;
}

.finding-list {
  display: grid;
  gap: 10px;
}

.finding-item {
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  background: $bg-primary;
  padding: 10px;
}

.finding-meta {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 6px;
  font-size: 12px;
  color: $text-muted;
}

.finding-item p {
  margin: 0;
  font-size: 12px;
  color: $text-primary;
  word-break: break-word;
}

.approval-list {
  display: grid;
  gap: 12px;
}

.approval-item {
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  background: $bg-secondary;
  padding: 12px;
}

.approval-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}

.approval-head strong {
  display: block;
  font-size: 13px;
  color: $text-primary;
}

.approval-head span {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: $text-muted;
}

.approval-desc {
  margin: 10px 0 8px;
  font-size: 12px;
  color: $text-secondary;
}

.approval-command {
  background: $bg-primary;
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  padding: 10px;
}

.approval-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 8px;
  font-size: 12px;
  color: $text-muted;
}

.approval-scopes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.approval-note {
  margin: 8px 0 0;
  font-size: 12px;
  color: $warning;
}

.approval-expired {
  color: $error;
}

.approval-actions {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  margin-top: 12px;
}

.empty-state {
  min-height: 120px;
  display: grid;
  place-items: center;
  color: $text-muted;
  font-size: 13px;
}

pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: $font-code;
  font-size: 12px;
  color: $text-secondary;
}

@media (max-width: 900px) {
  .diagnostics-grid {
    grid-template-columns: 1fr;
  }

  .security-groups {
    grid-template-columns: 1fr;
  }

  .coverage-grid {
    grid-template-columns: 1fr;
  }

  .audit-layout {
    grid-template-columns: 1fr;
  }
}
</style>
