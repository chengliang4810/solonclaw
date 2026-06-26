<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NButtonGroup, NInput, NSelect, NSpin, NSwitch, NTag, useMessage } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import {
  auditSecurity,
  fetchAlwaysApprovals,
  fetchApprovalHistory,
  fetchApprovalRuntimeEvents,
  fetchApprovalRuntimeStats,
  fetchPendingApprovals,
  fetchPendingSlashConfirms,
  fetchDiagnostics,
  resolveApproval,
  resolveSlashConfirm,
  revokeAlwaysApproval,
  type AlwaysApproval,
  type AlwaysApprovalsResult,
  type ApprovalAuditEvent,
  type ApprovalHistoryResult,
  type ApprovalRuntimeEvent,
  type ApprovalRuntimeEventsResult,
  type ApprovalRuntimeStats,
  type Diagnostics,
  type PendingApproval,
  type PendingApprovalsResult,
  type PendingSlashConfirm,
  type PendingSlashConfirmsResult,
  type SecurityPolicyProbe,
  type SecurityAuditFinding,
  type SecurityAuditResult,
} from '@/api/solonclaw/diagnostics'

const message = useMessage()
const { t } = useI18n()
const diagnostics = ref<Diagnostics | null>(null)
const loading = ref(false)
const auditLoading = ref(false)
const approvalsLoading = ref(false)
const historyLoading = ref(false)
const runtimeEventsLoading = ref(false)
const alwaysLoading = ref(false)
const confirmsLoading = ref(false)
const auditResult = ref<SecurityAuditResult | null>(null)
const policyAuditResult = ref<SecurityAuditResult | null>(null)
const pendingApprovals = ref<PendingApproval[]>([])
const pendingApprovalMeta = ref<PendingApprovalsResult | null>(null)
const approvalHistory = ref<ApprovalAuditEvent[]>([])
const approvalHistoryMeta = ref<ApprovalHistoryResult | null>(null)
const approvalRuntimeEvents = ref<ApprovalRuntimeEvent[]>([])
const approvalRuntimeEventsMeta = ref<ApprovalRuntimeEventsResult | null>(null)
const approvalRuntimeStats = ref<ApprovalRuntimeStats | null>(null)
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
const resolvingKey = ref('')
const revokingAlwaysKey = ref('')
const resolvingConfirmKey = ref('')
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
        metric('subagentAutoApprove', approvalPolicy.subagentAutoApprove),
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
        metric('metadataUrlBlocked', hardlinePolicy.metadataUrlBlocked),
        metric('codeToolShellExtractionCovered', hardlinePolicy.codeToolShellExtractionCovered),
        metric('pythonShellExtractionCovered', hardlinePolicy.pythonShellExtractionCovered),
        metric('javascriptChildProcessExtractionCovered', hardlinePolicy.javascriptChildProcessExtractionCovered),
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
        metric('remoteToolArgumentUrlSafety', mcpRuntimePolicy.remoteToolArgumentUrlSafety),
        metric('remoteToolArgumentPathSafety', mcpRuntimePolicy.remoteToolArgumentPathSafety),
        metric('resourceUriUrlSafety', mcpRuntimePolicy.resourceUriUrlSafety),
        metric('resourceUriPathSafety', mcpRuntimePolicy.resourceUriPathSafety),
        metric('nestedUrlExtraction', mcpRuntimePolicy.nestedUrlExtraction),
        metric('blockedUrlsMasked', mcpRuntimePolicy.blockedUrlsMasked),
        metric('blockedPathsRedacted', mcpRuntimePolicy.blockedPathsRedacted),
        metric('toolNamesPrefixed', mcpRuntimePolicy.toolNamesPrefixed),
        metric('toolsChangeNotificationPersisted', mcpRuntimePolicy.toolsChangeNotificationPersisted),
        metric('authorizationEndpointUrlSafety', mcpOAuthPolicy.authorizationEndpointUrlSafety),
        metric('stateValidationRequired', mcpOAuthPolicy.stateValidationRequired),
        metric('pkceS256Required', mcpOAuthPolicy.pkceS256Required),
        metric('accessTokenRedacted', mcpOAuthPolicy.accessTokenRedacted),
        metric('endpointUrlSafetyChecked', mcpPackageSecurityPolicy.endpointUrlSafetyChecked),
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
        metric('allowPrivateUrls', firstDefined(privateUrlPolicy.allowPrivateUrls, urlPolicy.allowPrivateUrls, policy.allow_private_urls), true),
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
const runtimeEventCount = computed(() => approvalRuntimeEvents.value.length)
const alwaysCount = computed(() => alwaysApprovals.value.length)
const slashConfirmCount = computed(() => pendingSlashConfirms.value.length)

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

async function load() {
  loading.value = true
  try {
    const [diagnosticsData] = await Promise.all([
      fetchDiagnostics(),
      loadPolicyAudit(),
      loadApprovals(),
      loadHistory(),
      loadRuntimeApprovalEvents(),
      loadAlwaysApprovals(),
      loadSlashConfirms(),
    ])
    diagnostics.value = diagnosticsData
  } finally {
    loading.value = false
  }
}

async function loadPolicyAudit() {
  policyAuditResult.value = await auditSecurity({ action: 'status' })
}

async function loadApprovals() {
  approvalsLoading.value = true
  try {
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

async function loadRuntimeApprovalEvents() {
  runtimeEventsLoading.value = true
  try {
    const [events, stats] = await Promise.all([
      fetchApprovalRuntimeEvents(50),
      fetchApprovalRuntimeStats(),
    ])
    approvalRuntimeEventsMeta.value = events
    approvalRuntimeEvents.value = events.events || []
    approvalRuntimeStats.value = stats
  } finally {
    runtimeEventsLoading.value = false
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
      await loadRuntimeApprovalEvents()
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
  if (!value) return '-'
  return new Date(value).toLocaleString()
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

function runtimeDecisionType(decision?: string) {
  if (decision === 'allow' || decision === 'approved') return 'success'
  if (decision === 'block' || decision === 'denied') return 'error'
  return 'warning'
}

function runtimeDecisionText(decision?: string) {
  if (decision === 'allow' || decision === 'approved') return t('diagnostics.runtimeApproved')
  if (decision === 'block' || decision === 'denied') return t('diagnostics.runtimeBlocked')
  return decision || t('diagnostics.runtimePending')
}

function eventDetailsText(details?: Record<string, unknown>) {
  if (!details || Object.keys(details).length === 0) return ''
  return JSON.stringify(details, null, 2)
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
      <NButton size="small" :loading="loading" @click="load">{{ t('diagnostics.refresh') }}</NButton>
    </header>
    <NSpin :show="loading">
      <main class="diagnostics-grid">
        <section class="panel">
          <h3>{{ t('diagnostics.runtime') }}</h3>
          <pre>{{ diagnostics?.runtime }}</pre>
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
                    <NTag size="small" :type="booleanTagType(securityApprovals.mcp_reload_confirm)">
                      {{ booleanText(securityApprovals.mcp_reload_confirm) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>{{ t('diagnostics.alwaysApprovalCount') }}</dt>
                  <dd>{{ valueOf(securityApprovals, 'always_approval_count', 0) }}</dd>
                </div>
              </dl>
            </div>
            <div class="security-group">
              <h4>{{ t('diagnostics.urlAndWebsitePolicy') }}</h4>
              <dl>
                <div>
                  <dt>{{ t('diagnostics.allowPrivateUrls') }}</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityPolicy.allow_private_urls, true)">
                      {{ booleanText(securityPolicy.allow_private_urls) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>{{ t('diagnostics.websiteBlocklist') }}</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityPolicy.website_blocklist_enabled)">
                      {{ booleanText(securityPolicy.website_blocklist_enabled) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>{{ t('diagnostics.domainRules') }}</dt>
                  <dd>{{ valueOf(securityPolicy, 'website_blocklist_domain_count', 0) }}</dd>
                </div>
                <div>
                  <dt>{{ t('diagnostics.sharedRuleFiles') }}</dt>
                  <dd>{{ valueOf(securityPolicy, 'website_blocklist_shared_file_count', 0) }}</dd>
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
                    <NTag size="small" :type="booleanTagType(securityTerminal.sudo_password_configured)">
                      {{ booleanText(securityTerminal.sudo_password_configured) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>{{ t('diagnostics.writeSafeRoot') }}</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityTerminal.write_safe_root_configured)">
                      {{ booleanText(securityTerminal.write_safe_root_configured) }}
                    </NTag>
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
                    <NTag size="small" :type="booleanTagType(securityPolicy.tirith_enabled)">
                      {{ booleanText(securityPolicy.tirith_enabled) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>{{ t('diagnostics.scannerConfigured') }}</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityPolicy.tirith_configured)">
                      {{ booleanText(securityPolicy.tirith_configured) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>{{ t('diagnostics.failOpen') }}</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityPolicy.tirith_fail_open, false)">
                      {{ booleanText(securityPolicy.tirith_fail_open) }}
                    </NTag>
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
              <NTag size="small" :type="policyAuditResult?.success === false ? 'error' : 'success'" :bordered="false">
                {{ policyAuditResult?.success === false ? t('diagnostics.abnormal') : t('diagnostics.readonly') }}
              </NTag>
            </div>
            <div class="coverage-grid">
              <div v-for="item in coverageItems" :key="item.key" class="coverage-item">
                <span>{{ item.label }}</span>
                <NTag size="small" :type="booleanTagType(securityCoverage[item.key])" :bordered="false">
                  {{ booleanText(securityCoverage[item.key]) }}
                </NTag>
              </div>
            </div>
            <div class="surface-list">
              <NTag v-for="surface in securitySurfaces" :key="surface" size="small" :bordered="false">
                {{ surfaceLabel(surface) }}
              </NTag>
              <span v-if="!securitySurfaces.length" class="surface-empty">{{ t('diagnostics.noCoverageData') }}</span>
            </div>
          </div>
          <div class="policy-detail-section">
            <div class="coverage-title">
              <h4>{{ t('diagnostics.policyDetails') }}</h4>
              <NTag size="small" :bordered="false">{{ t('diagnostics.readonlyDiagnostics') }}</NTag>
            </div>
            <div class="policy-detail-grid">
              <div v-for="group in securityDetailGroups" :key="group.title" class="policy-detail-group">
                <h5>{{ group.title }}</h5>
                <div class="metric-grid">
                  <div v-for="item in group.items" :key="`${group.title}:${item.label}`" class="metric-item">
                    <span>{{ item.label }}</span>
                    <NTag size="small" :type="metricTagType(item)" :bordered="false">
                      {{ metricText(item.value) }}
                    </NTag>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div class="probe-section">
            <div class="coverage-title">
              <h4>{{ t('diagnostics.securityProbe') }}</h4>
              <NTag size="small" :type="securityProbePassed === false ? 'error' : 'success'" :bordered="false">
                {{ securityProbePassed === false ? t('diagnostics.hasIssues') : t('diagnostics.allPassed') }}
              </NTag>
            </div>
            <p v-if="diagnostics?.security?.probes?.available === false" class="approval-note">
              {{ diagnostics?.security?.probes?.message || t('diagnostics.serviceUnavailable') }}
            </p>
            <div v-else-if="securityProbes.length" class="probe-grid">
              <div v-for="probe in securityProbes" :key="probe.key || probe.label" class="probe-item">
                <div class="probe-head">
                  <strong>{{ probe.label || probe.key }}</strong>
                  <NTag size="small" :type="probe.passed ? 'success' : 'error'" :bordered="false">
                    {{ probe.skipped ? t('diagnostics.skipped') : probe.passed ? t('diagnostics.passed') : t('diagnostics.abnormal') }}
                  </NTag>
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
        </section>
        <section class="panel audit-panel">
          <h3>{{ t('diagnostics.audit') }}</h3>
          <div class="audit-layout">
            <div class="audit-form">
              <label>
                <span>{{ t('diagnostics.auditType') }}</span>
                <NSelect v-model:value="auditForm.action" :options="auditActionOptions" size="small" />
              </label>
              <label v-if="auditForm.action === 'command' || auditForm.action === 'tool_args'">
                <span>{{ t('diagnostics.auditToolName') }}</span>
                <NInput v-model:value="auditForm.toolName" size="small" :placeholder="t('diagnostics.auditToolPlaceholder')" />
              </label>
              <label v-if="auditForm.action === 'command'">
                <span>{{ t('diagnostics.auditCommand') }}</span>
                <NInput
                  v-model:value="auditForm.command"
                  type="textarea"
                  :autosize="{ minRows: 3, maxRows: 8 }"
                  :placeholder="t('diagnostics.auditCommandPlaceholder')"
                />
              </label>
              <label v-if="auditForm.action === 'url'">
                <span>{{ t('diagnostics.auditUrl') }}</span>
                <NInput v-model:value="auditForm.url" size="small" :placeholder="t('diagnostics.auditUrlPlaceholder')" />
              </label>
              <label v-if="auditForm.action === 'path'">
                <span>{{ t('diagnostics.auditPath') }}</span>
                <NInput v-model:value="auditForm.path" size="small" :placeholder="t('diagnostics.auditPathPlaceholder')" />
              </label>
              <label v-if="auditForm.action === 'path'" class="switch-row">
                <span>{{ t('diagnostics.auditWriteLike') }}</span>
                <NSwitch v-model:value="auditForm.writeLike" size="small" />
              </label>
              <label v-if="auditForm.action === 'tool_args'">
                <span>{{ t('diagnostics.auditArgsJson') }}</span>
                <NInput
                  v-model:value="auditForm.argsJson"
                  type="textarea"
                  :autosize="{ minRows: 3, maxRows: 8 }"
                  :placeholder="t('diagnostics.auditArgsPlaceholder')"
                />
              </label>
              <p v-if="auditForm.action === 'status'" class="approval-note">
                {{ t('diagnostics.auditStatusHint') }}
              </p>
              <NButton size="small" type="primary" :loading="auditLoading" @click="runAudit">{{ t('diagnostics.auditRun') }}</NButton>
            </div>
            <div class="audit-result">
              <div class="audit-summary">
                <NTag size="small" :type="decisionType(auditResult?.decision)">
                  {{ auditResult?.decision || t('diagnostics.notAudited') }}
                </NTag>
                <NTag v-if="auditResult?.blocking" size="small" type="error" :bordered="false">{{ t('diagnostics.blocked') }}</NTag>
                <NTag v-if="auditResult?.approval_required" size="small" type="warning" :bordered="false">{{ t('diagnostics.approvalRequired') }}</NTag>
                <span>{{ auditResult?.summary || t('diagnostics.waitingAuditInput') }}</span>
              </div>
              <div v-if="auditFindings.length" class="finding-list">
                <div v-for="(finding, index) in auditFindings" :key="index" class="finding-item">
                  <div class="finding-meta">
                    <NTag size="small" :bordered="false">{{ finding.source || 'policy' }}</NTag>
                    <NTag v-if="finding.blocking" size="small" type="error" :bordered="false">{{ t('diagnostics.findingBlocked') }}</NTag>
                    <NTag v-else-if="finding.approval_required" size="small" type="warning" :bordered="false">
                      {{ t('diagnostics.findingApproval') }}
                    </NTag>
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
        <section class="panel approvals-panel">
          <div class="panel-title-row">
            <h3>{{ t('diagnostics.pendingApprovals') }}</h3>
            <div class="panel-actions">
              <NTag size="small" :type="pendingCount ? 'warning' : 'success'">{{ pendingCount }}</NTag>
              <NTag size="small" :type="pendingApprovalMeta?.session_scan_truncated ? 'warning' : 'default'">
                {{ pendingApprovalScanText }}
              </NTag>
              <NButton size="small" :loading="approvalsLoading" @click="loadApprovals">{{ t('diagnostics.refresh') }}</NButton>
            </div>
          </div>
          <NSpin :show="approvalsLoading">
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
                  <NTag size="small" :type="item.permanent_allowed ? 'default' : 'warning'">
                    {{ item.permanent_allowed ? t('diagnostics.permanentAllowed') : t('diagnostics.onceOrSessionOnly') }}
                  </NTag>
                </div>
                <p class="approval-desc">{{ item.description || '-' }}</p>
                <pre class="approval-command">{{ item.command_preview || '-' }}</pre>
                <div v-if="item.rule_sources?.length" class="approval-scopes">
                  <NTag v-for="source in item.rule_sources" :key="source" size="small" :bordered="false">
                    {{ approvalSourceText(source) }}
                  </NTag>
                </div>
                <div class="approval-meta">
                  <span>{{ item.selector || item.approval_id || '-' }}</span>
                  <span>{{ t('diagnostics.createdAt', { time: timeText(item.created_at) }) }}</span>
                  <span>{{ t('diagnostics.expiresAt', { time: timeText(item.expires_at) }) }}</span>
                  <span :class="{ 'approval-expired': item.expired }">{{ t('diagnostics.remaining', { time: expiresText(item) }) }}</span>
                </div>
                <div v-if="item.scope_options?.length" class="approval-scopes">
                  <NTag v-for="scope in item.scope_options" :key="scope" size="small" :bordered="false">
                    {{ scope === 'once' ? t('diagnostics.scopeOnce') : scope === 'session' ? t('diagnostics.scopeSession') : t('diagnostics.scopeAlways') }}
                  </NTag>
                </div>
                <p v-if="item.permanent_disabled_reason" class="approval-note">
                  {{ item.permanent_disabled_reason }}
                </p>
                <div class="approval-actions">
                  <NButtonGroup size="small">
                    <NButton
                      type="primary"
                      :disabled="!canApproveScope(item, 'once')"
                      :loading="approvalBusy(item, 'approve', 'once')"
                      @click="handleApproval(item, 'approve', 'once')"
                    >
                      {{ t('diagnostics.approveOnce') }}
                    </NButton>
                    <NButton
                      :disabled="!canApproveScope(item, 'session')"
                      :loading="approvalBusy(item, 'approve', 'session')"
                      @click="handleApproval(item, 'approve', 'session')"
                    >
                      {{ t('diagnostics.approveSession') }}
                    </NButton>
                    <NButton
                      :disabled="!canApproveScope(item, 'always')"
                      :loading="approvalBusy(item, 'approve', 'always')"
                      @click="handleApproval(item, 'approve', 'always')"
                    >
                      {{ t('diagnostics.approveAlways') }}
                    </NButton>
                  </NButtonGroup>
                  <NButton
                    size="small"
                    type="error"
                    ghost
                    :disabled="item.expired"
                    :loading="approvalBusy(item, 'deny')"
                    @click="handleApproval(item, 'deny')"
                  >
                    {{ t('diagnostics.deny') }}
                  </NButton>
                </div>
              </article>
            </div>
            <div v-else class="empty-state">{{ t('diagnostics.pendingApprovalsEmpty') }}</div>
          </NSpin>
        </section>
        <section class="panel approvals-panel">
          <div class="panel-title-row">
            <h3>{{ t('diagnostics.approvalHistory') }}</h3>
            <div class="panel-actions">
              <NTag size="small">{{ historyCount }}</NTag>
              <NButton size="small" :loading="historyLoading" @click="loadHistory">{{ t('diagnostics.refresh') }}</NButton>
            </div>
          </div>
          <NSpin :show="historyLoading">
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
                  <NTag size="small" :type="auditChoiceType(item)">
                    {{ auditChoiceText(item) }}
                  </NTag>
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
          </NSpin>
        </section>
        <section class="panel approvals-panel">
          <div class="panel-title-row">
            <h3>{{ t('diagnostics.approvalRuntimeEvents') }}</h3>
            <div class="panel-actions">
              <NTag size="small">{{ runtimeEventCount }}</NTag>
              <NTag size="small" type="success">
                {{ t('diagnostics.runtimeApprovedCount', { count: approvalRuntimeStats?.approved || 0 }) }}
              </NTag>
              <NTag size="small" :type="approvalRuntimeStats?.blocked ? 'error' : 'default'">
                {{ t('diagnostics.runtimeBlockedCount', { count: approvalRuntimeStats?.blocked || 0 }) }}
              </NTag>
              <NButton size="small" :loading="runtimeEventsLoading" @click="loadRuntimeApprovalEvents">{{ t('diagnostics.refresh') }}</NButton>
            </div>
          </div>
          <NSpin :show="runtimeEventsLoading">
            <p v-if="approvalRuntimeEventsMeta && approvalRuntimeEventsMeta.count > runtimeEventCount" class="approval-note">
              {{ t('diagnostics.showingRuntimeApprovalEvents', { count: approvalRuntimeEventsMeta.count }) }}
            </p>
            <div v-if="approvalRuntimeEvents.length" class="approval-list">
              <article v-for="(item, index) in approvalRuntimeEvents" :key="`${item.timestamp || index}:${item.toolName || '-'}`" class="approval-item">
                <div class="approval-head">
                  <div>
                    <strong>{{ item.summary || item.toolName || '-' }}</strong>
                    <span>{{ item.sourceKey || '-' }} · {{ timeText(item.timestamp) }}</span>
                  </div>
                  <NTag size="small" :type="runtimeDecisionType(item.decision)">
                    {{ runtimeDecisionText(item.decision) }}
                  </NTag>
                </div>
                <div class="approval-meta">
                  <span>{{ item.toolName || '-' }}</span>
                  <span>{{ item.decision || '-' }}</span>
                </div>
                <pre v-if="eventDetailsText(item.details)" class="approval-command">{{ eventDetailsText(item.details) }}</pre>
              </article>
            </div>
            <div v-else class="empty-state">{{ t('diagnostics.noRuntimeApprovalEvents') }}</div>
          </NSpin>
        </section>
        <section class="panel approvals-panel">
          <div class="panel-title-row">
            <h3>{{ t('diagnostics.alwaysApprovals') }}</h3>
            <div class="panel-actions">
              <NTag size="small" :type="alwaysCount ? 'warning' : 'success'">{{ alwaysCount }}</NTag>
              <NButton size="small" :loading="alwaysLoading" @click="loadAlwaysApprovals">{{ t('diagnostics.refresh') }}</NButton>
            </div>
          </div>
          <NSpin :show="alwaysLoading">
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
                  <NTag size="small" type="warning">{{ t('diagnostics.alwaysAllowed') }}</NTag>
                </div>
                <pre class="approval-command">{{ item.pattern_key || '-' }}</pre>
                <div class="approval-actions">
                  <NButton
                    size="small"
                    type="error"
                    ghost
                    :loading="revokingAlwaysKey === item.approval_id"
                    :disabled="!item.approval_id"
                    @click="handleRevokeAlways(item)"
                  >
                    {{ t('diagnostics.revoke') }}
                  </NButton>
                </div>
              </article>
            </div>
            <div v-else class="empty-state">{{ t('diagnostics.noAlwaysApprovals') }}</div>
          </NSpin>
        </section>
        <section class="panel approvals-panel">
          <div class="panel-title-row">
            <h3>{{ t('diagnostics.pendingSlashCommands') }}</h3>
            <div class="panel-actions">
              <NTag size="small" :type="slashConfirmCount ? 'warning' : 'success'">{{ slashConfirmCount }}</NTag>
              <NButton size="small" :loading="confirmsLoading" @click="loadSlashConfirms">{{ t('diagnostics.refresh') }}</NButton>
            </div>
          </div>
          <NSpin :show="confirmsLoading">
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
                  <NTag size="small" :type="item.allow_always ? 'default' : 'warning'">
                    {{ item.allow_always ? t('diagnostics.slashAllowAlways') : t('diagnostics.slashOnceOnly') }}
                  </NTag>
                </div>
                <p class="approval-desc">{{ item.prompt_preview || '-' }}</p>
                <div class="approval-meta">
                  <span>{{ item.confirm_ref || '-' }}</span>
                  <span>{{ t('diagnostics.createdAt', { time: timeText(item.created_at) }) }}</span>
                  <span>{{ t('diagnostics.expiresAt', { time: timeText(item.expires_at) }) }}</span>
                  <span :class="{ 'approval-expired': item.expired }">{{ t('diagnostics.remaining', { time: expiresText(item) }) }}</span>
                </div>
                <div class="approval-actions">
                  <NButtonGroup size="small">
                    <NButton
                      type="primary"
                      :disabled="!canConfirmAction(item, 'approve')"
                      :loading="slashConfirmBusy(item, 'approve')"
                      @click="handleSlashConfirm(item, 'approve')"
                    >
                      {{ t('diagnostics.executeOnce') }}
                    </NButton>
                    <NButton
                      :disabled="!canConfirmAction(item, 'always')"
                      :loading="slashConfirmBusy(item, 'always')"
                      @click="handleSlashConfirm(item, 'always')"
                    >
                      {{ t('diagnostics.confirmAlways') }}
                    </NButton>
                  </NButtonGroup>
                  <NButton
                    size="small"
                    type="error"
                    ghost
                    :disabled="!canConfirmAction(item, 'deny')"
                    :loading="slashConfirmBusy(item, 'deny')"
                    @click="handleSlashConfirm(item, 'deny')"
                  >
                    {{ t('diagnostics.cancel') }}
                  </NButton>
                </div>
              </article>
            </div>
            <div v-else class="empty-state">{{ t('diagnostics.noPendingSlash') }}</div>
          </NSpin>
        </section>
      </main>
    </NSpin>
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

.audit-panel {
  grid-column: 1 / -1;
}

.approvals-panel {
  grid-column: 1 / -1;
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
