<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NButtonGroup, NInput, NSelect, NSpin, NSwitch, NTag, useMessage } from 'naive-ui'
import {
  auditSecurity,
  fetchAlwaysApprovals,
  fetchApprovalHistory,
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
  type Diagnostics,
  type PendingApproval,
  type PendingApprovalsResult,
  type PendingSlashConfirm,
  type PendingSlashConfirmsResult,
  type SecurityPolicyProbe,
  type SecurityAuditFinding,
  type SecurityAuditResult,
} from '@/api/jimuqu/diagnostics'

const message = useMessage()
const diagnostics = ref<Diagnostics | null>(null)
const loading = ref(false)
const auditLoading = ref(false)
const approvalsLoading = ref(false)
const historyLoading = ref(false)
const alwaysLoading = ref(false)
const confirmsLoading = ref(false)
const auditResult = ref<SecurityAuditResult | null>(null)
const policyAuditResult = ref<SecurityAuditResult | null>(null)
const pendingApprovals = ref<PendingApproval[]>([])
const pendingApprovalMeta = ref<PendingApprovalsResult | null>(null)
const approvalHistory = ref<ApprovalAuditEvent[]>([])
const approvalHistoryMeta = ref<ApprovalHistoryResult | null>(null)
const alwaysApprovals = ref<AlwaysApproval[]>([])
const alwaysApprovalMeta = ref<AlwaysApprovalsResult | null>(null)
const pendingSlashConfirms = ref<PendingSlashConfirm[]>([])
const slashConfirmMeta = ref<PendingSlashConfirmsResult | null>(null)
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
      title: '审批规则',
      items: [
        metric('命令审批模式', firstDefined(approvalPolicy.mode, securityApprovals.value.mode)),
        metric('定时任务模式', firstDefined(approvalPolicy.cronMode, securityApprovals.value.cron_mode)),
        metric('子代理自动审批', approvalPolicy.subagentAutoApprove),
        metric('智能裁决已配置', approvalPolicy.smartJudgeConfigured),
        metric('危险规则数', approvalPolicy.dangerousRuleCount),
        metric('硬阻断规则数', approvalPolicy.hardlineRuleCount),
        metric('终端守卫规则数', approvalPolicy.terminalGuardrailCount),
        metric('URL 预检', approvalPolicy.urlPolicyPrechecked),
        metric('私有地址预检', approvalPolicy.privateUrlPolicyPrechecked),
        metric('凭据 URL 预检', approvalPolicy.credentialUrlPolicyPrechecked),
        metric('网站策略预检', approvalPolicy.websitePolicyPrechecked),
        metric('不安全 URL 绕过允许', approvalPolicy.unsafeUrlApprovalBypassAllowed, false),
      ],
    },
    {
      title: '硬阻断命令',
      items: [
        metric('规则数', hardlinePolicy.ruleCount),
        metric('覆盖工具', hardlinePolicy.coveredTools),
        metric('阻断类别', hardlinePolicy.blockedCategories),
        metric('元数据地址阻断', hardlinePolicy.metadataUrlBlocked),
        metric('代码工具 Shell 提取', hardlinePolicy.codeToolShellExtractionCovered),
        metric('Python Shell 提取', hardlinePolicy.pythonShellExtractionCovered),
        metric('JavaScript 子进程提取', hardlinePolicy.javascriptChildProcessExtractionCovered),
        metric('审批绕过允许', hardlinePolicy.approvalBypassAllowed, false),
        metric('Slash 绕过允许', hardlinePolicy.slashApproveBypassAllowed, false),
        metric('会话授权绕过允许', hardlinePolicy.sessionApprovalBypassAllowed, false),
        metric('长期授权绕过允许', hardlinePolicy.alwaysApprovalBypassAllowed, false),
        metric('自动模式绕过允许', hardlinePolicy.yoloBypassAllowed, false),
        metric('智能审批绕过允许', hardlinePolicy.smartApprovalBypassAllowed, false),
        metric('命令预览脱敏', hardlinePolicy.commandPreviewRedacted),
      ],
    },
    {
      title: '自动化与子代理审批',
      items: [
        metric('定时任务默认决策', cronApprovalPolicy.defaultDecision),
        metric('定时任务硬阻断', cronApprovalPolicy.hardlineAlwaysBlocked),
        metric('运行前检查危险模式', cronApprovalPolicy.dangerousPatternCheckedBeforeRun),
        metric('脚本内容检查', cronApprovalPolicy.scriptContentChecked),
        metric('子代理默认决策', subagentApprovalPolicy.defaultDecision),
        metric('子代理硬阻断预检', subagentApprovalPolicy.hardlinePrechecked),
        metric('子代理文件预检', subagentApprovalPolicy.filePolicyPrechecked),
        metric('子代理 URL 预检', subagentApprovalPolicy.urlPolicyPrechecked),
        metric('子代理终端预检', subagentApprovalPolicy.terminalGuardrailPrechecked),
        metric('被拒绝时创建待审批', subagentApprovalPolicy.pendingApprovalCreatedWhenDenied),
      ],
    },
    {
      title: '智能与内容扫描审批',
      items: [
        metric('智能模式', smartApprovalPolicy.smartMode),
        metric('智能审批生效', smartApprovalPolicy.active),
        metric('裁决器已配置', smartApprovalPolicy.judgeConfigured),
        metric('升级到人工审批', smartApprovalPolicy.escalateFallsBackToHumanApproval),
        metric('裁决失败回退人工', smartApprovalPolicy.judgeFailureFallsBackToHumanApproval),
        metric('内容扫描纳入裁决', smartApprovalPolicy.tirithFindingsIncluded),
        metric('内容扫描器已配置', tirithApprovalPolicy.scannerConfigured),
        metric('审批模式执行扫描', tirithApprovalPolicy.scanRunsInApprovalMode),
        metric('扫描结果合并本地规则', tirithApprovalPolicy.combinedWithLocalDangerRules),
        metric('长期授权允许', tirithApprovalPolicy.permanentApprovalAllowed),
      ],
    },
    {
      title: '审批生命周期',
      items: [
        metric('列表前清理过期项', approvalLifecyclePolicy.pendingListPrunedBeforeRead),
        metric('选择器支持', approvalLifecyclePolicy.selectorSupported),
        metric('拒绝不安全选择器', approvalLifecyclePolicy.unsafeSelectorRejected),
        metric('批量拒绝安全选择器', approvalLifecyclePolicy.bulkRejectUsesSafeSelector),
        metric('批准移除待审批', approvalLifecyclePolicy.approveRemovesPendingApproval),
        metric('拒绝移除待审批', approvalLifecyclePolicy.rejectRemovesPendingApproval),
        metric('会话快照更新', approvalLifecyclePolicy.sessionSnapshotUpdated),
        metric('审批键脱敏', approvalLifecyclePolicy.approvalKeyRedacted),
      ],
    },
    {
      title: 'Slash 与审批卡',
      items: [
        metric('Slash 队列', slashConfirmPolicy.pendingQueueSupported),
        metric('待确认安全选择器', slashConfirmPolicy.pendingListUsesSafeSelector),
        metric('待确认隐藏审批键', slashConfirmPolicy.pendingListHidesApprovalKey),
        metric('确认元数据脱敏', slashConfirmPolicy.approvalMetadataRedacted),
        metric('审批卡选择器', approvalCardPolicy.approvalIdSelectorSupported),
        metric('审批卡阻断不安全选择器', approvalCardPolicy.unsafeSelectorRejected),
        metric('审批卡命令脱敏', approvalCardPolicy.commandPreviewRedacted),
        metric('原始命令不进扩展', approvalCardPolicy.rawCommandRedactedInExtras),
      ],
    },
    {
      title: '审批审计与 MCP',
      items: [
        metric('请求事件', approvalAuditPolicy.requestEvents),
        metric('响应事件', approvalAuditPolicy.responseEvents),
        metric('观察者失败隔离', approvalAuditPolicy.observerFailureIsolated),
        metric('审计审批键脱敏', approvalAuditPolicy.approvalKeyRedacted),
        metric('手动撤销审计', approvalAuditPolicy.manualRevocationAudited),
        metric('MCP 重载需确认', mcpReloadPolicy.confirmRequired),
        metric('确认由 Slash 承载', mcpReloadPolicy.slashConfirmBacked),
        metric('OAuth URL 安全覆盖', mcpReloadPolicy.oauthUrlSafetyCovered),
      ],
    },
    {
      title: '只读审计工具',
      items: [
        metric('工具名', readOnlyAuditPolicy.toolName),
        metric('不执行命令', readOnlyAuditPolicy.executesCommand, false),
        metric('不打开网络连接', readOnlyAuditPolicy.opensNetworkConnection, false),
        metric('不读取目标 URL', readOnlyAuditPolicy.readsTargetUrl, false),
        metric('不写文件', readOnlyAuditPolicy.writesFile, false),
        metric('不保存审计输入', readOnlyAuditPolicy.storesAuditInput, false),
        metric('密文脱敏', readOnlyAuditPolicy.secretRedactionApplied),
        metric('继承命令策略', readOnlyAuditPolicy.toolArgsCommandPolicyInherited),
        metric('继承 URL 策略', readOnlyAuditPolicy.toolArgsUrlPolicyInherited),
        metric('继承路径策略', readOnlyAuditPolicy.toolArgsPathPolicyInherited),
        metric('JSON 错误脱敏', readOnlyAuditPolicy.toolArgsJsonParseErrorsRedacted),
        metric('命令预览上限', readOnlyAuditPolicy.commandPreviewLimitChars),
        metric('发现消息上限', readOnlyAuditPolicy.findingMessageLimitChars),
      ],
    },
    {
      title: 'Schema 与补丁解析',
      items: [
        metric('Schema 清洗启用', schemaSanitizerPolicy.enabled),
        metric('输入 Schema 清洗', schemaSanitizerPolicy.inputSchemaSanitized),
        metric('MCP 输入 Schema 清洗', schemaSanitizerPolicy.mcpInputSchemaSanitized),
        metric('非法 Schema 默认对象', schemaSanitizerPolicy.invalidSchemaDefaultsToObject),
        metric('顶层对象必需', schemaSanitizerPolicy.topLevelObjectRequired),
        metric('required 裁剪', schemaSanitizerPolicy.requiredPrunedToKnownProperties),
        metric('pattern/format 移除', schemaSanitizerPolicy.patternAndFormatStripped),
        metric('补丁原子校验', patchParserPolicy.atomicValidationBeforeWrite),
        metric('校验失败不部分写入', patchParserPolicy.noPartialWritesOnValidationFailure),
        metric('重复匹配需显式 all', patchParserPolicy.replaceAllRequiresExplicitFlag),
        metric('歧义块阻断', patchParserPolicy.ambiguousHunksBlocked),
        metric('缺失块阻断', patchParserPolicy.missingHunksBlocked),
        metric('补丁路径穿越阻断', patchParserPolicy.pathTraversalBlocked),
        metric('补丁凭据预检', patchParserPolicy.credentialPolicyPrechecked),
      ],
    },
    {
      title: 'MCP 安全',
      items: [
        metric('远程端点 URL 安全', mcpRuntimePolicy.remoteEndpointUrlSafety),
        metric('远程工具 URL 参数安全', mcpRuntimePolicy.remoteToolArgumentUrlSafety),
        metric('远程工具路径参数安全', mcpRuntimePolicy.remoteToolArgumentPathSafety),
        metric('资源 URI URL 安全', mcpRuntimePolicy.resourceUriUrlSafety),
        metric('资源 URI 路径安全', mcpRuntimePolicy.resourceUriPathSafety),
        metric('嵌套 URL 提取', mcpRuntimePolicy.nestedUrlExtraction),
        metric('阻断 URL 脱敏', mcpRuntimePolicy.blockedUrlsMasked),
        metric('阻断路径脱敏', mcpRuntimePolicy.blockedPathsRedacted),
        metric('工具名加前缀', mcpRuntimePolicy.toolNamesPrefixed),
        metric('工具变更持久通知', mcpRuntimePolicy.toolsChangeNotificationPersisted),
        metric('OAuth 授权 URL 安全', mcpOAuthPolicy.authorizationEndpointUrlSafety),
        metric('OAuth state 校验', mcpOAuthPolicy.stateValidationRequired),
        metric('OAuth PKCE S256', mcpOAuthPolicy.pkceS256Required),
        metric('访问令牌脱敏', mcpOAuthPolicy.accessTokenRedacted),
        metric('包端点预检', mcpPackageSecurityPolicy.endpointUrlSafetyChecked),
        metric('恶意包阻断保存', mcpPackageSecurityPolicy.malwareBlocksSaveAndCheck),
      ],
    },
    {
      title: '附件安全',
      items: [
        metric('Hutool 下载守卫', attachmentDownloadPolicy.hutoolDownloadGuarded),
        metric('OkHttp 下载守卫', attachmentDownloadPolicy.okHttpDownloadGuarded),
        metric('初始 URL 检查', attachmentDownloadPolicy.initialUrlChecked),
        metric('重定向 URL 预检', attachmentDownloadPolicy.redirectUrlCheckedBeforeFollow),
        metric('跨站请求头阻断', attachmentDownloadPolicy.crossHostHeaderForwardingBlocked),
        metric('阻断 URL 脱敏', attachmentDownloadPolicy.blockedUrlMasked),
        metric('内容长度检查', attachmentDownloadPolicy.contentLengthChecked),
        metric('流读取限额', attachmentDownloadPolicy.streamReadBounded),
        metric('缓存字节限额', attachmentMediaCachePolicy.cacheBytesSizeChecked),
        metric('原始文件名脱敏', attachmentMediaCachePolicy.safeOriginalNameSecretRedacted),
        metric('媒体引用防穿越', attachmentMediaCachePolicy.mediaReferenceTraversalBlocked),
        metric('宿主路径不返回', attachmentMediaCachePolicy.hostPathsNotReturnedInMediaReference),
        metric('粘贴路径预检', attachmentTerminalPastePolicy.pathPolicyCheckedBeforeCache),
        metric('凭据路径阻断', attachmentTerminalPastePolicy.credentialPathBlocked),
        metric('阻断预览脱敏', attachmentTerminalPastePolicy.blockedPreviewRedacted),
        metric('原始路径不进提示', attachmentTerminalPastePolicy.rawPathHiddenInPrompt),
      ],
    },
    {
      title: 'URL 与私有地址',
      items: [
        metric('允许私有地址', firstDefined(privateUrlPolicy.allowPrivateUrls, urlPolicy.allowPrivateUrls, policy.allow_private_urls), false),
        metric('固定阻断主机', urlPolicy.alwaysBlockedHostCount),
        metric('固定阻断地址', urlPolicy.alwaysBlockedIpCount),
        metric('敏感参数名', urlPolicy.sensitiveQueryNameCount),
        metric('DNS 必检', firstDefined(privateUrlPolicy.dnsResolutionRequired, urlPolicy.dnsResolutionRequired)),
        metric('Userinfo 阻断', urlPolicy.userinfoBlocked),
        metric('敏感参数阻断', urlPolicy.sensitiveQueryBlocked),
        metric('云元数据阻断', firstDefined(privateUrlPolicy.cloudMetadataAlwaysBlocked, urlPolicy.cloudMetadataBlocked)),
        metric('Loopback 阻断', privateUrlPolicy.loopbackBlocked),
        metric('链路本地阻断', privateUrlPolicy.linkLocalBlocked),
        metric('站点本地阻断', privateUrlPolicy.siteLocalBlocked),
      ],
    },
    {
      title: '网站策略',
      items: [
        metric('策略启用', firstDefined(websitePolicy.enabled, policy.website_blocklist_enabled)),
        metric('内置域名规则', firstDefined(websitePolicy.configuredDomainCount, policy.website_blocklist_domain_count)),
        metric('共享规则文件', firstDefined(websitePolicy.sharedFileCount, policy.website_blocklist_shared_file_count)),
        metric('已加载文件', websitePolicy.loadedSharedFileCount),
        metric('跳过文件', websitePolicy.skippedSharedFileCount, true, true),
        metric('共享规则数', websitePolicy.sharedRuleCount),
        metric('通配子域名', websitePolicy.wildcardSubdomainSupported),
        metric('共享文件路径检查', websitePolicy.sharedFilePathSafetyChecked),
      ],
    },
    {
      title: '路径与凭据',
      items: [
        metric('路径穿越阻断', pathPolicy.traversalBlocked),
        metric('控制字符阻断', pathPolicy.controlCharactersBlocked),
        metric('设备路径阻断', pathPolicy.devicePathBlocked),
        metric('原始块设备写入阻断', pathPolicy.rawBlockDeviceWriteBlocked),
        metric('写入精确拒绝', pathPolicy.writeDeniedExactPathCount),
        metric('写入前缀拒绝', pathPolicy.writeDeniedPrefixCount),
        metric('凭据目录段', credentialPolicy.directorySegmentCount),
        metric('凭据文件名', credentialPolicy.fileNameCount),
        metric('凭据后缀', credentialPolicy.pathSuffixCount),
        metric('密钥扩展名', credentialPolicy.keyFileExtensionCount),
      ],
    },
    {
      title: '工具参数与终端',
      items: [
        metric('递归 URL 提取', toolArgsPolicy.recursiveUrlExtraction),
        metric('返回内容 URL 检查', toolArgsPolicy.returnedContentUrlExtraction),
        metric('递归路径提取', toolArgsPolicy.recursivePathExtraction),
        metric('写入意图识别', toolArgsPolicy.writeIntentDetection),
        metric('上传凭据阻断', toolArgsPolicy.networkUploadCredentialOnlyBlocked),
        metric('终端密文脱敏', terminalOutputPolicy.secretRedactionApplied),
        metric('输出截断提示', terminalOutputPolicy.truncationNoticeIncluded),
        metric('最大内联字符', terminalOutputPolicy.maxInlineChars),
        metric('进程登记表', backgroundProcessPolicy.processRegistryBacked),
        metric('后台任务强制托管', backgroundProcessPolicy.managedBackgroundRequiredForLongRunningCommands),
      ],
    },
    {
      title: '终端硬守卫',
      items: [
        metric('后台 Shell 包装阻断', terminalGuardrailPolicy.backgroundShellWrappersBlocked),
        metric('脱离会话启动阻断', terminalGuardrailPolicy.detachedSessionLaunchersBlocked),
        metric('PowerShell 后台命令阻断', terminalGuardrailPolicy.powershellBackgroundCommandsBlocked),
        metric('行内 & 阻断', terminalGuardrailPolicy.inlineAmpersandBlocked),
        metric('末尾 & 阻断', terminalGuardrailPolicy.trailingAmpersandBlocked),
        metric('长驻前台阻断', terminalGuardrailPolicy.longLivedForegroundBlocked),
        metric('命令路径预检', terminalGuardrailPolicy.commandPathPrechecked),
        metric('凭据路径预检', terminalGuardrailPolicy.credentialPathPrechecked),
        metric('下载输出路径预检', terminalGuardrailPolicy.downloadOutputPathPrechecked),
        metric('代理 URL 预检', terminalGuardrailPolicy.proxyUrlPrechecked),
        metric('系统 DNS 命令预检', terminalGuardrailPolicy.systemDnsCommandPrechecked),
        metric('系统代理命令预检', terminalGuardrailPolicy.systemProxyCommandPrechecked),
        metric('Hosts/解析器路径预检', terminalGuardrailPolicy.hostsAndResolverPathPrechecked),
        metric('受管后台进程必需', terminalGuardrailPolicy.managedBackgroundProcessRequired),
        metric('进程登记表支撑', terminalGuardrailPolicy.processRegistryBacked),
        metric('sudo 密码脱敏', terminalGuardrailPolicy.sudoPasswordRedacted),
      ],
    },
    {
      title: '凭据文件与结果存储',
      items: [
        metric('配置凭据文件数', terminalCredentialFilePolicy.configCredentialFileCount),
        metric('已配置挂载数', terminalCredentialFilePolicy.configuredMountCount),
        metric('缺失文件未挂载', terminalCredentialFilePolicy.missingFilesNotMounted),
        metric('宿主路径不进元数据', terminalCredentialFilePolicy.hostPathsOmittedFromMetadata),
        metric('拒绝路径脱敏', terminalCredentialFilePolicy.rejectedPathsRedacted),
        metric('sudo 改写已配置', sudoRewritePolicy.configured),
        metric('sudo 密码脱敏', sudoRewritePolicy.passwordRedacted),
        metric('sudo 密码走 stdin', sudoRewritePolicy.stdinPasswordInjection),
        metric('结果存储已启用', toolResultStoragePolicy.enabled),
        metric('超大结果持久化', toolResultStoragePolicy.oversizedResultsPersisted),
        metric('结果引用返回', toolResultStoragePolicy.resultRefReturned),
        metric('预览脱敏', toolResultStoragePolicy.previewRedacted),
        metric('持久输出提示脱敏', toolResultStoragePolicy.persistedOutputRedacted),
        metric('内联字节上限', toolResultStoragePolicy.inlineLimitBytes),
      ],
    },
  ]
})
const coverageItems = [
  { key: 'dangerousCommandApproval', label: '危险命令审批' },
  { key: 'slashApprovalConfirm', label: 'Slash 确认' },
  { key: 'smartApproval', label: '智能审批' },
  { key: 'tirithSmartApproval', label: '内容扫描审批' },
  { key: 'cronApprovalPolicy', label: '定时任务审批' },
  { key: 'subagentApprovalPolicy', label: '子代理审批' },
  { key: 'approvalAuditLog', label: '审批审计日志' },
  { key: 'hardlineCommandBlocks', label: '硬阻断命令' },
  { key: 'terminalGuardrails', label: '终端守卫' },
  { key: 'sudoRewrite', label: 'sudo 改写' },
  { key: 'backgroundProcessGuard', label: '后台进程保护' },
  { key: 'urlSafety', label: 'URL 安全' },
  { key: 'privateUrlPolicy', label: '私有地址策略' },
  { key: 'websitePolicy', label: '网站策略' },
  { key: 'credentialFilePolicy', label: '凭据文件' },
  { key: 'credentialMountPolicy', label: '凭据挂载' },
  { key: 'pathSecurity', label: '路径安全' },
  { key: 'toolArgsSecurity', label: '工具参数安全' },
  { key: 'toolReturnedContentUrlSafety', label: '工具返回 URL' },
  { key: 'schemaSanitizer', label: '工具 Schema 清洗' },
  { key: 'patchParser', label: '补丁解析保护' },
  { key: 'subprocessEnvironmentSanitizer', label: '子进程环境清洗' },
  { key: 'toolResultStorage', label: '工具结果存储' },
  { key: 'codeExecutionGuardrails', label: '代码执行保护' },
  { key: 'codeExecutionPolicyAuditable', label: '代码执行审计' },
  { key: 'mcpUrlSafety', label: 'MCP URL 安全' },
  { key: 'mcpReloadConfirmation', label: 'MCP 重载确认' },
  { key: 'mcpToolChangeNotice', label: 'MCP 工具变更通知' },
  { key: 'mcpRuntimePolicyAuditable', label: 'MCP 运行策略' },
  { key: 'mcpPackageSecurity', label: 'MCP 包安全' },
  { key: 'attachmentUrlSafety', label: '附件 URL 安全' },
  { key: 'attachmentCachePathSafety', label: '附件缓存路径' },
  { key: 'attachmentDisplayNameRedaction', label: '附件名称脱敏' },
  { key: 'terminalAttachmentPathSafety', label: '终端附件路径' },
  { key: 'terminalAttachmentPreviewRedaction', label: '终端附件预览脱敏' },
  { key: 'terminalAttachmentResolvedNameRedaction', label: '终端附件名称脱敏' },
  { key: 'tirithSecurity', label: '内容扫描' },
  { key: 'readOnlyAuditTool', label: '只读审计工具' },
]
const surfaceLabels: Record<string, string> = {
  approval: '审批',
  approvalLifecycle: '审批生命周期',
  approvalAuditLog: '审批审计日志',
  slashConfirm: 'Slash 确认',
  smartApproval: '智能审批',
  tirithSmartApproval: '内容扫描审批',
  cronApprovalPolicy: '定时任务审批',
  subagentApprovalPolicy: '子代理审批',
  hardlineCommand: '硬阻断',
  terminalGuardrails: '终端守卫',
  sudoRewrite: 'sudo 改写',
  backgroundProcess: '后台进程',
  urlSafety: 'URL 安全',
  privateUrlPolicy: '私有地址策略',
  websitePolicy: '网站策略',
  credentialFilePolicy: '凭据文件',
  credentialMountPolicy: '凭据挂载',
  pathSecurity: '路径安全',
  toolArgsSecurity: '工具参数',
  toolReturnedContentUrlSafety: '工具返回 URL',
  schemaSanitizer: 'Schema 清洗',
  patchParser: '补丁解析',
  subprocessEnvironmentSanitizer: '子进程环境',
  toolResultStorage: '工具结果存储',
  codeExecution: '代码执行',
  mcpRuntimePolicy: 'MCP 运行策略',
  mcpOauthUrlSafety: 'MCP OAuth URL',
  mcpOauthPolicy: 'MCP OAuth 策略',
  mcpPackageSecurity: 'MCP 包安全',
  mcpReloadConfirmation: 'MCP 重载确认',
  mcpToolChangeNotice: 'MCP 工具变更',
  attachmentPolicy: '附件策略',
  terminalAttachmentPathSafety: '终端附件路径',
  tirithSecurity: '内容扫描',
  readOnlyAuditTool: '只读审计',
  url: 'URL',
  path_read: '路径读取',
  path_write: '路径写入',
  tool_args: '工具参数',
  hardline_command: '硬阻断',
  terminal_guardrail: '终端守卫',
  approval_detection: '审批检测',
  approval_selector: '审批选择器',
  slash_confirm_selector: 'Slash 确认编号',
}
const auditActionOptions = [
  { label: '命令', value: 'command' },
  { label: 'URL', value: 'url' },
  { label: '路径', value: 'path' },
  { label: '工具参数', value: 'tool_args' },
  { label: '策略状态', value: 'status' },
]
const auditFindings = computed<SecurityAuditFinding[]>(() => auditResult.value?.findings || [])
const pendingCount = computed(() => pendingApprovals.value.length)
const pendingApprovalScanText = computed(() => {
  const meta = pendingApprovalMeta.value
  if (!meta) return '扫描：-'
  const scanned = meta.scanned_sessions ?? 0
  const limit = meta.session_scan_limit ?? '-'
  return `扫描：${scanned}/${limit} 个会话`
})
const historyCount = computed(() => approvalHistory.value.length)
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

function metric(label: string, value: unknown, goodWhenTrue = true, countWarning = false): SecurityMetric {
  return { label, value, goodWhenTrue, countWarning }
}

function booleanTagType(value: unknown, goodWhenTrue = true) {
  const enabled = value === true
  return enabled === goodWhenTrue ? 'success' : 'warning'
}

function booleanText(value: unknown) {
  return value === true ? '是' : '否'
}

function metricText(value: unknown) {
  if (value === true || value === false) return booleanText(value)
  if (value === undefined || value === null || value === '') return '-'
  if (Array.isArray(value)) return `${value.length} 项`
  if (typeof value === 'object') return '已配置'
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
  if (action === 'request_approval') return '需要审批'
  if (action === 'change_command') return '修改命令'
  if (action === 'change_path') return '修改路径'
  if (action === 'change_url_or_policy') return '修改 URL 或策略'
  if (action === 'use_managed_background_process') return '使用受管后台进程'
  return action || ''
}

function surfaceLabel(surface: string) {
  return surfaceLabels[surface] || surface
}

async function load() {
  loading.value = true
  try {
    const [diagnosticsData] = await Promise.all([
      fetchDiagnostics(),
      loadPolicyAudit(),
      loadApprovals(),
      loadHistory(),
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
      message.success(result.message || '审批状态已更新')
      await loadApprovals()
      await loadHistory()
      return
    }
    message.error(result.message || '审批状态更新失败')
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
      message.success(result.message || '长期授权已撤销')
      const [diagnosticsData] = await Promise.all([fetchDiagnostics(), loadPolicyAudit(), loadAlwaysApprovals()])
      diagnostics.value = diagnosticsData
      return
    }
    message.error(result.message || '长期授权撤销失败')
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
      message.success(result.message || '确认状态已更新')
      await loadSlashConfirms()
      return
    }
    message.error(result.message || '确认状态更新失败')
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
  if (item.expired) return '已过期'
  if (typeof item.expires_in_seconds === 'number') {
    return `${item.expires_in_seconds} 秒`
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
  if (source === 'security_scan') return '安全扫描'
  if (source === 'local_policy') return '本地规则'
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
  if (item.event_type === 'request') return '请求审批'
  if (item.choice === 'deny') return '已拒绝'
  if (item.choice === 'revoke') return '已撤销'
  if (item.choice === 'timeout') return '已超时'
  if (item.choice === 'session') return '本会话批准'
  if (item.choice === 'always') return '长期批准'
  if (item.choice === 'once') return '批准本次'
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
      <h2 class="header-title">诊断</h2>
      <NButton size="small" :loading="loading" @click="load">刷新</NButton>
    </header>
    <NSpin :show="loading">
      <main class="diagnostics-grid">
        <section class="panel">
          <h3>运行目录</h3>
          <pre>{{ diagnostics?.runtime }}</pre>
        </section>
        <section class="panel">
          <h3>模型提供方</h3>
          <pre>{{ diagnostics?.providers }}</pre>
        </section>
        <section class="panel">
          <h3>渠道</h3>
          <pre>{{ diagnostics?.channels }}</pre>
        </section>
        <section class="panel">
          <h3>工具与 MCP</h3>
          <pre>{{ diagnostics?.tools }}&#10;{{ diagnostics?.mcp }}</pre>
        </section>
        <section class="panel security-panel">
          <h3>安全策略</h3>
          <div class="security-groups">
            <div class="security-group">
              <h4>审批</h4>
              <dl>
                <div>
                  <dt>命令审批</dt>
                  <dd>{{ valueOf(securityApprovals, 'mode') }}</dd>
                </div>
                <div>
                  <dt>定时任务审批</dt>
                  <dd>{{ valueOf(securityApprovals, 'cron_mode') }}</dd>
                </div>
                <div>
                  <dt>MCP 重载确认</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityApprovals.mcp_reload_confirm)">
                      {{ booleanText(securityApprovals.mcp_reload_confirm) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>长期授权</dt>
                  <dd>{{ valueOf(securityApprovals, 'always_approval_count', 0) }}</dd>
                </div>
              </dl>
            </div>
            <div class="security-group">
              <h4>URL 与网站策略</h4>
              <dl>
                <div>
                  <dt>允许私有地址</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityPolicy.allow_private_urls, false)">
                      {{ booleanText(securityPolicy.allow_private_urls) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>网站阻断表</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityPolicy.website_blocklist_enabled)">
                      {{ booleanText(securityPolicy.website_blocklist_enabled) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>域名规则</dt>
                  <dd>{{ valueOf(securityPolicy, 'website_blocklist_domain_count', 0) }}</dd>
                </div>
                <div>
                  <dt>共享规则文件</dt>
                  <dd>{{ valueOf(securityPolicy, 'website_blocklist_shared_file_count', 0) }}</dd>
                </div>
              </dl>
            </div>
            <div class="security-group">
              <h4>终端守卫</h4>
              <dl>
                <div>
                  <dt>凭据文件</dt>
                  <dd>{{ valueOf(securityTerminal, 'credential_file_count', 0) }}</dd>
                </div>
                <div>
                  <dt>环境透传</dt>
                  <dd>{{ valueOf(securityTerminal, 'env_passthrough_count', 0) }}</dd>
                </div>
                <div>
                  <dt>sudo 密码</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityTerminal.sudo_password_configured)">
                      {{ booleanText(securityTerminal.sudo_password_configured) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>写入安全根</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityTerminal.write_safe_root_configured)">
                      {{ booleanText(securityTerminal.write_safe_root_configured) }}
                    </NTag>
                  </dd>
                </div>
              </dl>
            </div>
            <div class="security-group">
              <h4>内容扫描</h4>
              <dl>
                <div>
                  <dt>扫描启用</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityPolicy.tirith_enabled)">
                      {{ booleanText(securityPolicy.tirith_enabled) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>扫描器配置</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityPolicy.tirith_configured)">
                      {{ booleanText(securityPolicy.tirith_configured) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>失败放行</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityPolicy.tirith_fail_open, false)">
                      {{ booleanText(securityPolicy.tirith_fail_open) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>超时</dt>
                  <dd>{{ valueOf(securityPolicy, 'tirith_timeout_seconds', 0) }} 秒</dd>
                </div>
              </dl>
            </div>
          </div>
          <div class="coverage-section">
            <div class="coverage-title">
              <h4>覆盖面快照</h4>
              <NTag size="small" :type="policyAuditResult?.success === false ? 'error' : 'success'" :bordered="false">
                {{ policyAuditResult?.success === false ? '异常' : '只读' }}
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
              <span v-if="!securitySurfaces.length" class="surface-empty">暂无覆盖面数据</span>
            </div>
          </div>
          <div class="policy-detail-section">
            <div class="coverage-title">
              <h4>策略明细</h4>
              <NTag size="small" :bordered="false">只读诊断</NTag>
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
              <h4>安全探针</h4>
              <NTag size="small" :type="securityProbePassed === false ? 'error' : 'success'" :bordered="false">
                {{ securityProbePassed === false ? '存在异常' : '全部通过' }}
              </NTag>
            </div>
            <p v-if="diagnostics?.security?.probes?.available === false" class="approval-note">
              {{ diagnostics?.security?.probes?.message || '安全策略服务尚未启用。' }}
            </p>
            <div v-else-if="securityProbes.length" class="probe-grid">
              <div v-for="probe in securityProbes" :key="probe.key || probe.label" class="probe-item">
                <div class="probe-head">
                  <strong>{{ probe.label || probe.key }}</strong>
                  <NTag size="small" :type="probe.passed ? 'success' : 'error'" :bordered="false">
                    {{ probe.skipped ? '跳过' : probe.passed ? '通过' : '异常' }}
                  </NTag>
                </div>
                <div class="probe-meta">
                  <span>{{ surfaceLabel(probe.surface || '') }}</span>
                  <span>{{ probe.skipped ? '未检查' : probe.blocked ? '已阻断' : '已放行' }}</span>
                </div>
                <p>{{ probe.message || '-' }}</p>
                <code>{{ probe.target || '-' }}</code>
              </div>
            </div>
            <div v-else class="surface-empty">暂无安全探针数据</div>
          </div>
        </section>
        <section class="panel audit-panel">
          <h3>安全审计</h3>
          <div class="audit-layout">
            <div class="audit-form">
              <label>
                <span>类型</span>
                <NSelect v-model:value="auditForm.action" :options="auditActionOptions" size="small" />
              </label>
              <label v-if="auditForm.action === 'command' || auditForm.action === 'tool_args'">
                <span>工具名</span>
                <NInput v-model:value="auditForm.toolName" size="small" placeholder="execute_shell" />
              </label>
              <label v-if="auditForm.action === 'command'">
                <span>命令</span>
                <NInput
                  v-model:value="auditForm.command"
                  type="textarea"
                  :autosize="{ minRows: 3, maxRows: 8 }"
                  placeholder="输入待审计命令"
                />
              </label>
              <label v-if="auditForm.action === 'url'">
                <span>URL</span>
                <NInput v-model:value="auditForm.url" size="small" placeholder="https://example.com" />
              </label>
              <label v-if="auditForm.action === 'path'">
                <span>路径</span>
                <NInput v-model:value="auditForm.path" size="small" placeholder="runtime/config.yml" />
              </label>
              <label v-if="auditForm.action === 'path'" class="switch-row">
                <span>按写入检查</span>
                <NSwitch v-model:value="auditForm.writeLike" size="small" />
              </label>
              <label v-if="auditForm.action === 'tool_args'">
                <span>参数 JSON</span>
                <NInput
                  v-model:value="auditForm.argsJson"
                  type="textarea"
                  :autosize="{ minRows: 3, maxRows: 8 }"
                  placeholder="{&quot;url&quot;:&quot;https://example.com&quot;}"
                />
              </label>
              <p v-if="auditForm.action === 'status'" class="approval-note">
                只读取当前安全策略摘要，不执行命令、不访问 URL、不读取文件。
              </p>
              <NButton size="small" type="primary" :loading="auditLoading" @click="runAudit">审计</NButton>
            </div>
            <div class="audit-result">
              <div class="audit-summary">
                <NTag size="small" :type="decisionType(auditResult?.decision)">
                  {{ auditResult?.decision || '未审计' }}
                </NTag>
                <NTag v-if="auditResult?.blocking" size="small" type="error" :bordered="false">已阻断</NTag>
                <NTag v-if="auditResult?.approval_required" size="small" type="warning" :bordered="false">需要审批</NTag>
                <span>{{ auditResult?.summary || '等待输入待审计内容' }}</span>
              </div>
              <div v-if="auditFindings.length" class="finding-list">
                <div v-for="(finding, index) in auditFindings" :key="index" class="finding-item">
                  <div class="finding-meta">
                    <NTag size="small" :bordered="false">{{ finding.source || 'policy' }}</NTag>
                    <NTag v-if="finding.blocking" size="small" type="error" :bordered="false">阻断</NTag>
                    <NTag v-else-if="finding.approval_required" size="small" type="warning" :bordered="false">
                      审批
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
            <h3>待审批命令</h3>
            <div class="panel-actions">
              <NTag size="small" :type="pendingCount ? 'warning' : 'success'">{{ pendingCount }}</NTag>
              <NTag size="small" :type="pendingApprovalMeta?.session_scan_truncated ? 'warning' : 'default'">
                {{ pendingApprovalScanText }}
              </NTag>
              <NButton size="small" :loading="approvalsLoading" @click="loadApprovals">刷新</NButton>
            </div>
          </div>
          <NSpin :show="approvalsLoading">
            <p v-if="pendingApprovalMeta?.available === false" class="approval-note">
              {{ pendingApprovalMeta.message || '审批服务尚未启用。' }}
            </p>
            <p v-else-if="pendingApprovalMeta?.session_scan_truncated" class="approval-note">
              仅扫描最近 {{ pendingApprovalMeta.scanned_sessions || 0 }} 个会话，窗口外可能仍有待审批项。
            </p>
            <p v-else-if="pendingApprovalMeta?.truncated" class="approval-note">
              当前只显示前 {{ pendingApprovalMeta.count || pendingCount }} 个待审批项。
            </p>
            <div v-if="pendingApprovals.length" class="approval-list">
              <article v-for="item in pendingApprovals" :key="`${item.session_id}:${item.selector || item.approval_id || item.command_hash}`" class="approval-item">
                <div class="approval-head">
                  <div>
                    <strong>{{ item.title || item.session_id }}</strong>
                    <span>{{ item.tool_name || '-' }} · {{ item.source_ref || '-' }}</span>
                  </div>
                  <NTag size="small" :type="item.permanent_allowed ? 'default' : 'warning'">
                    {{ item.permanent_allowed ? '可长期授权' : '仅本次/本会话' }}
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
                  <span>创建：{{ timeText(item.created_at) }}</span>
                  <span>过期：{{ timeText(item.expires_at) }}</span>
                  <span :class="{ 'approval-expired': item.expired }">剩余：{{ expiresText(item) }}</span>
                </div>
                <div v-if="item.scope_options?.length" class="approval-scopes">
                  <NTag v-for="scope in item.scope_options" :key="scope" size="small" :bordered="false">
                    {{ scope === 'once' ? '本次' : scope === 'session' ? '本会话' : '长期' }}
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
                      批准本次
                    </NButton>
                    <NButton
                      :disabled="!canApproveScope(item, 'session')"
                      :loading="approvalBusy(item, 'approve', 'session')"
                      @click="handleApproval(item, 'approve', 'session')"
                    >
                      本会话批准
                    </NButton>
                    <NButton
                      :disabled="!canApproveScope(item, 'always')"
                      :loading="approvalBusy(item, 'approve', 'always')"
                      @click="handleApproval(item, 'approve', 'always')"
                    >
                      长期批准
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
                    拒绝
                  </NButton>
                </div>
              </article>
            </div>
            <div v-else class="empty-state">暂无待审批命令</div>
          </NSpin>
        </section>
        <section class="panel approvals-panel">
          <div class="panel-title-row">
            <h3>审批历史</h3>
            <div class="panel-actions">
              <NTag size="small">{{ historyCount }}</NTag>
              <NButton size="small" :loading="historyLoading" @click="loadHistory">刷新</NButton>
            </div>
          </div>
          <NSpin :show="historyLoading">
            <p v-if="approvalHistoryMeta?.available === false" class="approval-note">
              {{ approvalHistoryMeta.message || '审批历史服务尚未启用。' }}
            </p>
            <p v-else-if="approvalHistoryMeta?.truncated" class="approval-note">
              当前只显示最近 {{ approvalHistoryMeta.count || historyCount }} 条审批历史。
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
                  <span v-if="item.approver">审批人：{{ item.approver }}</span>
                  <span>{{ item.command_hash || '-' }}</span>
                </div>
              </article>
            </div>
            <div v-else class="empty-state">暂无审批历史</div>
          </NSpin>
        </section>
        <section class="panel approvals-panel">
          <div class="panel-title-row">
            <h3>长期授权</h3>
            <div class="panel-actions">
              <NTag size="small" :type="alwaysCount ? 'warning' : 'success'">{{ alwaysCount }}</NTag>
              <NButton size="small" :loading="alwaysLoading" @click="loadAlwaysApprovals">刷新</NButton>
            </div>
          </div>
          <NSpin :show="alwaysLoading">
            <p v-if="alwaysApprovalMeta?.available === false" class="approval-note">
              {{ alwaysApprovalMeta.message || '审批服务尚未启用。' }}
            </p>
            <p v-else-if="alwaysApprovalMeta?.truncated" class="approval-note">
              当前只显示前 {{ alwaysApprovalMeta.count || alwaysCount }} 个长期授权。
            </p>
            <div v-if="alwaysApprovals.length" class="approval-list">
              <article v-for="item in alwaysApprovals" :key="item.approval_id || `${item.tool_name}:${item.pattern_key}`" class="approval-item">
                <div class="approval-head">
                  <div>
                    <strong>{{ item.pattern_key || '-' }}</strong>
                    <span>{{ item.tool_name || '-' }}</span>
                  </div>
                  <NTag size="small" type="warning">长期放行</NTag>
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
                    撤销
                  </NButton>
                </div>
              </article>
            </div>
            <div v-else class="empty-state">暂无长期授权</div>
          </NSpin>
        </section>
        <section class="panel approvals-panel">
          <div class="panel-title-row">
            <h3>待确认 Slash 命令</h3>
            <div class="panel-actions">
              <NTag size="small" :type="slashConfirmCount ? 'warning' : 'success'">{{ slashConfirmCount }}</NTag>
              <NButton size="small" :loading="confirmsLoading" @click="loadSlashConfirms">刷新</NButton>
            </div>
          </div>
          <NSpin :show="confirmsLoading">
            <p v-if="slashConfirmMeta?.available === false" class="approval-note">
              {{ slashConfirmMeta.message || 'Slash 确认服务尚未启用。' }}
            </p>
            <p v-else-if="slashConfirmMeta?.truncated" class="approval-note">
              当前只显示前 {{ slashConfirmMeta.count || slashConfirmCount }} 个待确认 Slash 命令。
            </p>
            <div v-if="pendingSlashConfirms.length" class="approval-list">
              <article v-for="item in pendingSlashConfirms" :key="item.confirm_id" class="approval-item">
                <div class="approval-head">
                  <div>
                    <strong>/{{ item.command_preview || '-' }}</strong>
                    <span>{{ item.source_ref || '-' }}</span>
                  </div>
                  <NTag size="small" :type="item.allow_always ? 'default' : 'warning'">
                    {{ item.allow_always ? '可永久确认' : '仅本次' }}
                  </NTag>
                </div>
                <p class="approval-desc">{{ item.prompt_preview || '-' }}</p>
                <div class="approval-meta">
                  <span>{{ item.confirm_ref || '-' }}</span>
                  <span>创建：{{ timeText(item.created_at) }}</span>
                  <span>过期：{{ timeText(item.expires_at) }}</span>
                  <span :class="{ 'approval-expired': item.expired }">剩余：{{ expiresText(item) }}</span>
                </div>
                <div class="approval-actions">
                  <NButtonGroup size="small">
                    <NButton
                      type="primary"
                      :disabled="!canConfirmAction(item, 'approve')"
                      :loading="slashConfirmBusy(item, 'approve')"
                      @click="handleSlashConfirm(item, 'approve')"
                    >
                      执行一次
                    </NButton>
                    <NButton
                      :disabled="!canConfirmAction(item, 'always')"
                      :loading="slashConfirmBusy(item, 'always')"
                      @click="handleSlashConfirm(item, 'always')"
                    >
                      永久确认
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
                    取消
                  </NButton>
                </div>
              </article>
            </div>
            <div v-else class="empty-state">暂无待确认 Slash 命令</div>
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
