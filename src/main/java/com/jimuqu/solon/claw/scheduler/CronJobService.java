package com.jimuqu.solon.claw.scheduler;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.support.CronSupport;
import com.jimuqu.solon.claw.support.FilePathSupport;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Solon Claw Cron 任务管理服务。 */
public class CronJobService {
    /** 记录定时任务管理服务的低敏运行诊断日志。 */
    private static final Logger log = LoggerFactory.getLogger(CronJobService.class);

    /** 定时任务受保护的禁用工具集，禁止外部代码改写安全边界。 */
    public static final List<String> PROTECTED_CRON_DISABLED_TOOLSETS =
            Collections.unmodifiableList(Arrays.asList("cronjob", "messaging", "clarify"));

    /** 状态ACTIVE的统一常量值。 */
    private static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态PAUSED的统一常量值。 */
    private static final String STATUS_PAUSED = "PAUSED";

    /** 状态COMPLETED的统一常量值。 */
    private static final String STATUS_COMPLETED = "COMPLETED";

    /** 默认来源的统一常量值。 */
    private static final String DEFAULT_SOURCE = "MEMORY:dashboard:cron";

    /** 定时任务密钥VAR的统一常量值。 */
    private static final String CRON_SECRET_VAR =
            "\\$\\{?\\w*(?:KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)\\w*\\}?";

    /** 定时任务GitHub认证HEADER的统一常量值。 */
    private static final Pattern CRON_GITHUB_AUTH_HEADER =
            Pattern.compile(
                    "curl\\s+[^\\n]*(?:-H|--header)\\s+[\"']Authorization:\\s*token\\s+"
                            + CRON_SECRET_VAR
                            + "[\"']\\s+[\"']?https://api\\.github\\.com(?:/|\\b)",
                    Pattern.CASE_INSENSITIVE);

    /** VARIATION选择器CP的统一常量值。 */
    private static final int VARIATION_SELECTOR_CP = 0xFE0F;

    /** EMOJINEIGHBOURCPRANGES的统一常量值。 */
    private static final int[][] EMOJI_NEIGHBOUR_CP_RANGES =
            new int[][] {
                new int[] {0x1F000, 0x1FFFF},
                new int[] {0x2600, 0x27BF},
                new int[] {0x2300, 0x23FF},
                new int[] {0x1F1E6, 0x1F1FF},
                new int[] {0x20E3, 0x20E3}
            };

    /** 原始用户定时任务提示词的严格威胁规则。 */
    private static final CronPromptThreat[] CRON_PROMPT_THREATS =
            new CronPromptThreat[] {
                threat(
                        "prompt_injection",
                        "ignore\\s+(?:\\w+\\s+)*(?:previous|all|above|prior)\\s+(?:\\w+\\s+)*instructions"),
                threat("deception_hide", "do\\s+not\\s+tell\\s+the\\s+user"),
                threat("sys_prompt_override", "system\\s+prompt\\s+override"),
                threat(
                        "disregard_rules",
                        "disregard\\s+(your|all|any)\\s+(instructions|rules|guidelines)"),
                threat(
                        "exfil_curl_data",
                        "curl\\s+[^\\n]*(?:--data(?:-raw|-binary|-urlencode)?|-d|--form|-F)\\s+[^\\n]*"
                                + CRON_SECRET_VAR),
                threat(
                        "exfil_wget_post",
                        "wget\\s+[^\\n]*--post-(?:data|file)=[^\\n]*" + CRON_SECRET_VAR),
                threat(
                        "exfil_curl_auth_header",
                        "curl\\s+[^\\n]*(?:-H|--header)\\s+[\"']Authorization:\\s*(?:Bearer|token)\\s+"
                                + CRON_SECRET_VAR
                                + "[\"']"),
                threat("exfil_curl", "curl\\s+[^\\n]*https?://[^\\s\"'`]*" + CRON_SECRET_VAR),
                threat("exfil_wget", "wget\\s+[^\\n]*https?://[^\\s\"'`]*" + CRON_SECRET_VAR),
                threat("read_secrets", "cat\\s+[^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass)"),
                threat("ssh_backdoor", "authorized_keys"),
                threat("sudoers_mod", "/etc/sudoers|visudo"),
                threat("destructive_root_rm", "rm\\s+-rf\\s+/"),
                threat(
                        "gateway_lifecycle",
                        "(?:\\b(?:solonclaw)\\s+gateway\\s+(?:restart|stop|start)\\b)"
                                + "|(?:\\blaunchctl\\s+(?:kickstart|unload|load|stop|restart)\\b[^\\n]*solon-?claw)"
                                + "|(?:\\bsystemctl\\s+(?:restart|stop|start)\\b[^\\n]*solon-?claw)"
                                + "|(?:\\b(?:pkill|killall)\\b[^\\n]*(?:solonclaw|gateway))"
                                + "|(?:\\bkill\\b[^\\n]*(?:\\$\\(\\s*(?:pgrep|pidof)\\b|`\\s*(?:pgrep|pidof)\\b))")
            };

    /** 可信运行期内容的宽松威胁规则，仅拦截脱离正常资料文本的明确注入指令。 */
    private static final CronPromptThreat[] CRON_TRUSTED_ASSEMBLED_PROMPT_THREATS =
            new CronPromptThreat[] {
                threat(
                        "prompt_injection",
                        "ignore\\s+(?:\\w+\\s+)*(?:previous|all|above|prior)\\s+(?:\\w+\\s+)*instructions"),
                threat("deception_hide", "do\\s+not\\s+tell\\s+the\\s+user"),
                threat("sys_prompt_override", "system\\s+prompt\\s+override"),
                threat(
                        "disregard_rules",
                        "disregard\\s+(your|all|any)\\s+(instructions|rules|guidelines)")
            };

    /** 注入应用配置，用于定时任务任务。 */
    private final AppConfig appConfig;

    /** 保存定时任务任务仓储依赖，用于访问持久化数据。 */
    private final CronJobRepository cronJobRepository;

    /** 创建或修改 Cron 脚本时使用的版本授权服务；测试和无审批环境可不注入。 */
    private CronScriptApprovalService cronScriptApprovalService;

    /**
     * 创建定时任务任务服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param cronJobRepository 定时任务Job仓储依赖。
     */
    public CronJobService(AppConfig appConfig, CronJobRepository cronJobRepository) {
        this.appConfig = appConfig;
        this.cronJobRepository = cronJobRepository;
    }

    /**
     * 注入创建或修改阶段的 Cron 脚本版本授权服务。
     *
     * @param cronScriptApprovalService Cron 脚本版本授权服务。
     */
    public void setCronScriptApprovalService(CronScriptApprovalService cronScriptApprovalService) {
        this.cronScriptApprovalService = cronScriptApprovalService;
    }

    /**
     * 执行create，服务于定时任务任务主流程相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param body 请求体或消息正文内容。
     * @return 返回create结果。
     */
    public CronJobRecord create(String sourceKey, Map<String, Object> body) throws Exception {
        String schedule = scheduleValue(body.get("schedule"), body.get("cronExpr"), null);
        String prompt = string(body.get("prompt"), "");
        List<String> skills = canonicalSkills(body);
        String script = string(body.get("script"), null);
        boolean noAgent = bool(body.get("no_agent"), bool(body.get("noAgent"), false));
        ModelOverride modelOverride =
                modelOverride(
                        body.get("model"),
                        body.get("provider"),
                        body.get("base_url"),
                        body.get("baseUrl"),
                        null,
                        null,
                        null);
        if (StrUtil.isBlank(schedule)) {
            throw new IllegalStateException("schedule is required");
        }
        if (noAgent && StrUtil.isBlank(script)) {
            throw new IllegalStateException("no_agent requires script");
        }
        if (!noAgent && StrUtil.isBlank(prompt) && CollUtil.isEmpty(skills)) {
            throw new IllegalStateException("prompt or skills are required");
        }
        scanPrompt(prompt);
        validateScript(script);
        String workdir = normalizeWorkdir(string(body.get("workdir"), null));
        List<String> dependencyRefs = dependencyRefs(body);
        validateContextFrom(dependencyRefs);

        long now = System.currentTimeMillis();
        CronJobRecord record = new CronJobRecord();
        record.setJobId(IdSupport.newId());
        record.setName(defaultJobName(body, prompt, skills, script, noAgent));
        record.setCronExpr(schedule);
        record.setPrompt(prompt);
        record.setSourceKey(StrUtil.blankToDefault(sourceKey, DEFAULT_SOURCE));
        String deliver = deliverValue(body.get("deliver"), defaultDeliver(body));
        validateDeliverTargets(deliver);
        record.setDeliverPlatform(deliver);
        record.setDeliverChatId(
                string(body.get("deliver_chat_id"), string(body.get("deliverChatId"), null)));
        record.setDeliverThreadId(
                string(body.get("deliver_thread_id"), string(body.get("deliverThreadId"), null)));
        record.setOriginJson(json(body.get("origin")));
        record.setSkillsJson(json(skills));
        record.setRepeatTimes(intValue(body.get("repeat"), 0));
        record.setRepeatCompleted(0);
        record.setScript(script);
        record.setApprovedScriptFingerprint(null);
        record.setWorkdir(workdir);
        record.setNoAgent(noAgent);
        record.setContextFromJson(json(dependencyRefs));
        record.setEnabledToolsetsJson(json(cronEnabledToolsets(body)));
        applyModelPin(record, modelOverride.model, modelOverride.provider, modelOverride.baseUrl);
        record.setWrapResponse(
                bool(
                        body.get("wrap_response"),
                        bool(body.get("wrapResponse"), appConfig.getScheduler().isWrapResponse())));
        record.setStatus(STATUS_ACTIVE);
        record.setNextRunAt(CronSupport.nextRunAt(schedule, now));
        record.setLastRunAt(0L);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        prepareScriptApproval(record);
        return cronJobRepository.save(record);
    }

    /**
     * 查找同一来源下仍未完成的等价定时任务，供会产生副作用的入口实现幂等复用。
     *
     * @param sourceKey 渠道来源键。
     * @param body 即将创建的任务参数。
     * @return 找到可复用任务时返回记录，否则返回 null。
     */
    public CronJobRecord findDuplicateCreateJob(String sourceKey, Map<String, Object> body)
            throws Exception {
        String normalizedSource = StrUtil.blankToDefault(sourceKey, DEFAULT_SOURCE);
        for (CronJobRecord job : cronJobRepository.listBySource(normalizedSource)) {
            if (job == null || isCompleted(job) || !createMatches(job, body)) {
                continue;
            }
            return job;
        }
        return null;
    }

    /**
     * 执行更新相关逻辑。
     *
     * @param jobId job标识。
     * @param body 请求体或消息正文内容。
     * @return 返回更新结果。
     */
    public CronJobRecord update(String jobId, Map<String, Object> body) throws Exception {
        CronJobRecord record = require(jobId);
        boolean scriptSecurityChanged = false;
        if (body.containsKey("name")) {
            record.setName(string(body.get("name"), record.getName()));
        }
        if (body.containsKey("prompt")) {
            String prompt = string(body.get("prompt"), "");
            scanPrompt(prompt);
            record.setPrompt(prompt);
        }
        if (body.containsKey("schedule") || body.containsKey("cronExpr")) {
            String schedule =
                    scheduleValue(body.get("schedule"), body.get("cronExpr"), record.getCronExpr());
            CronSupport.validate(schedule);
            record.setCronExpr(schedule);
            record.setNextRunAt(CronSupport.nextRunAt(schedule, System.currentTimeMillis()));
            if (!STATUS_PAUSED.equalsIgnoreCase(record.getStatus())) {
                record.setStatus(STATUS_ACTIVE);
            }
        }
        if (body.containsKey("deliver")) {
            String deliver = deliverValue(body.get("deliver"), "local");
            validateDeliverTargets(deliver);
            record.setDeliverPlatform(deliver);
        }
        if (body.containsKey("deliver_chat_id") || body.containsKey("deliverChatId")) {
            record.setDeliverChatId(
                    string(body.get("deliver_chat_id"), string(body.get("deliverChatId"), null)));
        }
        if (body.containsKey("deliver_thread_id") || body.containsKey("deliverThreadId")) {
            record.setDeliverThreadId(
                    string(
                            body.get("deliver_thread_id"),
                            string(body.get("deliverThreadId"), null)));
        }
        if (clearSkills(body)) {
            record.setSkillsJson(json(new ArrayList<String>()));
        } else if (body.containsKey("skills") || body.containsKey("skill")) {
            record.setSkillsJson(json(canonicalSkills(body)));
        } else if (hasSkillsDelta(body)) {
            record.setSkillsJson(json(applySkillsDelta(parseList(record.getSkillsJson()), body)));
        }
        if (body.containsKey("repeat")) {
            int repeat = intValue(body.get("repeat"), 0);
            record.setRepeatTimes(Math.max(0, repeat));
        }
        if (body.containsKey("script")) {
            String script = string(body.get("script"), null);
            validateScript(script);
            if (!sameText(record.getScript(), script)) {
                record.setScript(script);
                record.setApprovedScriptFingerprint(null);
                scriptSecurityChanged = true;
            }
        }
        if (body.containsKey("workdir")) {
            String workdir = normalizeWorkdir(string(body.get("workdir"), null));
            if (!sameText(record.getWorkdir(), workdir)) {
                record.setWorkdir(workdir);
                record.setApprovedScriptFingerprint(null);
                scriptSecurityChanged = true;
            }
        }
        if (body.containsKey("no_agent") || body.containsKey("noAgent")) {
            boolean noAgent = bool(body.get("no_agent"), bool(body.get("noAgent"), false));
            if (noAgent && StrUtil.isBlank(record.getScript())) {
                throw new IllegalStateException("no_agent requires script");
            }
            if (record.isNoAgent() != noAgent) {
                record.setNoAgent(noAgent);
                record.setApprovedScriptFingerprint(null);
                scriptSecurityChanged = true;
            }
        }
        if (containsDependencyRefs(body)) {
            List<String> refs = dependencyRefs(body);
            validateContextFrom(refs);
            record.setContextFromJson(json(refs));
        }
        if (body.containsKey("enabled_toolsets") || body.containsKey("enabledToolsets")) {
            record.setEnabledToolsetsJson(json(cronEnabledToolsets(body)));
        }
        if (body.containsKey("model")
                || body.containsKey("provider")
                || body.containsKey("base_url")
                || body.containsKey("baseUrl")) {
            ModelOverride modelOverride =
                    modelOverride(
                            body.get("model"),
                            body.get("provider"),
                            body.get("base_url"),
                            body.get("baseUrl"),
                            defaultModelValue(body, record),
                            defaultProviderValue(body, record),
                            defaultBaseUrlValue(body, record));
            applyModelPin(
                    record, modelOverride.model, modelOverride.provider, modelOverride.baseUrl);
        }
        if (body.containsKey("wrap_response") || body.containsKey("wrapResponse")) {
            record.setWrapResponse(
                    bool(body.get("wrap_response"), bool(body.get("wrapResponse"), true)));
        }
        if (body.containsKey("enabled")) {
            boolean enabled = bool(body.get("enabled"), true);
            record.setStatus(enabled ? STATUS_ACTIVE : STATUS_PAUSED);
            record.setPausedAt(enabled ? 0L : System.currentTimeMillis());
            if (enabled) {
                record.setPausedReason(null);
            }
        }
        if (body.containsKey("status") || body.containsKey("state")) {
            applyEditableStatus(
                    record, string(body.get("status"), string(body.get("state"), null)));
        }
        if (body.containsKey("paused_reason") || body.containsKey("pausedReason")) {
            String reason =
                    string(body.get("paused_reason"), string(body.get("pausedReason"), null));
            if (STATUS_PAUSED.equalsIgnoreCase(record.getStatus())) {
                record.setPausedReason(StrUtil.blankToDefault(reason, "paused from edit"));
                if (record.getPausedAt() <= 0L) {
                    record.setPausedAt(System.currentTimeMillis());
                }
            }
        }
        if (record.isNoAgent() && StrUtil.isBlank(record.getScript())) {
            throw new IllegalStateException("no_agent requires script");
        }
        if (scriptSecurityChanged) {
            prepareScriptApproval(record);
        }
        return cronJobRepository.update(record);
    }

    /**
     * 应用Editable状态。
     *
     * @param record 记录参数。
     * @param rawStatus 原始状态参数。
     */
    private void applyEditableStatus(CronJobRecord record, String rawStatus) {
        String status = StrUtil.nullToEmpty(rawStatus).trim().toLowerCase(Locale.ROOT);
        if (StrUtil.isBlank(status)) {
            return;
        }
        if ("active".equals(status)
                || "enabled".equals(status)
                || "enable".equals(status)
                || "running".equals(status)
                || "resume".equals(status)
                || "resumed".equals(status)) {
            record.setStatus(STATUS_ACTIVE);
            record.setPausedAt(0L);
            record.setPausedReason(null);
            if (record.getNextRunAt() <= System.currentTimeMillis()) {
                record.setNextRunAt(
                        CronSupport.nextRunAt(record.getCronExpr(), System.currentTimeMillis()));
            }
            return;
        }
        if ("paused".equals(status)
                || "pause".equals(status)
                || "disabled".equals(status)
                || "disable".equals(status)
                || "stopped".equals(status)
                || "stop".equals(status)) {
            record.setStatus(STATUS_PAUSED);
            record.setPausedAt(System.currentTimeMillis());
            return;
        }
        if ("completed".equals(status) || "complete".equals(status)) {
            record.setStatus(STATUS_COMPLETED);
            record.setNextRunAt(0L);
            return;
        }
        throw new IllegalStateException("unsupported cron job status: " + rawStatus);
    }

    /**
     * 列出全部。
     *
     * @param includeDisabled includeDisabled 参数。
     * @return 返回全部列表。
     */
    public List<CronJobRecord> listAll(boolean includeDisabled) throws Exception {
        List<CronJobRecord> result = new ArrayList<CronJobRecord>();
        for (CronJobRecord job : cronJobRepository.listAll()) {
            if (includeDisabled || !STATUS_PAUSED.equalsIgnoreCase(job.getStatus())) {
                result.add(job);
            }
        }
        return result;
    }

    /**
     * 列出根据来源。
     *
     * @param sourceKey 渠道来源键。
     * @param includeDisabled includeDisabled 参数。
     * @return 返回根据来源列表。
     */
    public List<CronJobRecord> listBySource(String sourceKey, boolean includeDisabled)
            throws Exception {
        List<CronJobRecord> result = new ArrayList<CronJobRecord>();
        for (CronJobRecord job : cronJobRepository.listBySource(sourceKey)) {
            if (includeDisabled || !STATUS_PAUSED.equalsIgnoreCase(job.getStatus())) {
                result.add(job);
            }
        }
        return result;
    }

    /**
     * 判断已有任务是否与当前 create 参数等价。
     *
     * @param job 已持久化任务。
     * @param body 即将创建的任务参数。
     * @return 关键字段一致时返回 true。
     */
    private boolean createMatches(CronJobRecord job, Map<String, Object> body) {
        String schedule = scheduleValue(body.get("schedule"), body.get("cronExpr"), null);
        String prompt = string(body.get("prompt"), "");
        List<String> skills = canonicalSkills(body);
        String script = string(body.get("script"), null);
        boolean noAgent = bool(body.get("no_agent"), bool(body.get("noAgent"), false));
        ModelOverride modelOverride =
                modelOverride(
                        body.get("model"),
                        body.get("provider"),
                        body.get("base_url"),
                        body.get("baseUrl"),
                        null,
                        null,
                        null);
        String workdir = normalizeWorkdir(string(body.get("workdir"), null));
        List<String> dependencyRefs = dependencyRefs(body);
        String deliver = deliverValue(body.get("deliver"), defaultDeliver(body));
        boolean wrapResponse =
                bool(
                        body.get("wrap_response"),
                        bool(body.get("wrapResponse"), appConfig.getScheduler().isWrapResponse()));
        return sameText(job.getName(), defaultJobName(body, prompt, skills, script, noAgent))
                && sameText(job.getCronExpr(), schedule)
                && sameText(job.getPrompt(), prompt)
                && sameText(job.getDeliverPlatform(), deliver)
                && sameText(
                        job.getDeliverChatId(),
                        string(
                                body.get("deliver_chat_id"),
                                string(body.get("deliverChatId"), null)))
                && sameText(
                        job.getDeliverThreadId(),
                        string(
                                body.get("deliver_thread_id"),
                                string(body.get("deliverThreadId"), null)))
                && sameJson(job.getOriginJson(), body.get("origin"))
                && sameJson(job.getSkillsJson(), skills)
                && job.getRepeatTimes() == intValue(body.get("repeat"), 0)
                && sameText(job.getScript(), script)
                && sameText(job.getWorkdir(), workdir)
                && job.isNoAgent() == noAgent
                && sameJson(job.getContextFromJson(), dependencyRefs)
                && sameJson(job.getEnabledToolsetsJson(), cronEnabledToolsets(body))
                && sameText(job.getModel(), modelOverride.model)
                && sameText(job.getProvider(), modelOverride.provider)
                && sameText(job.getBaseUrl(), modelOverride.baseUrl)
                && job.isWrapResponse() == wrapResponse;
    }

    /**
     * 判断任务是否已经完成，完成态历史不参与幂等复用。
     *
     * @param job 已持久化任务。
     * @return 完成态返回 true。
     */
    private boolean isCompleted(CronJobRecord job) {
        return STATUS_COMPLETED.equalsIgnoreCase(StrUtil.nullToEmpty(job.getStatus()));
    }

    /**
     * 比较文本字段，null 与空白按同一缺省值处理。
     *
     * @param left 已持久化字段。
     * @param right 当前入参字段。
     * @return 一致时返回 true。
     */
    private boolean sameText(String left, String right) {
        return StrUtil.nullToEmpty(left).trim().equals(StrUtil.nullToEmpty(right).trim());
    }

    /**
     * 比较 JSON 字段，空数组、空对象与未配置值视为等价。
     *
     * @param stored 已持久化 JSON。
     * @param value 当前入参结构。
     * @return 结构等价时返回 true。
     */
    private boolean sameJson(String stored, Object value) {
        return canonicalJson(stored).equals(canonicalJson(value));
    }

    /**
     * 将 JSON 字符串或对象转为稳定结构字符串。
     *
     * @param value 待规范化对象。
     * @return 稳定 JSON 字符串。
     */
    private String canonicalJson(Object value) {
        if (value == null || StrUtil.isBlank(String.valueOf(value))) {
            return "";
        }
        if (emptyJsonValue(value)) {
            return "";
        }
        try {
            if (value instanceof String) {
                Object data = ONode.ofJson((String) value).toData();
                return emptyJsonValue(data) ? "" : ONode.serialize(data);
            }
            return ONode.serialize(value);
        } catch (Exception e) {
            logCronBestEffortFailure("unknown", "canonical_json", e);
            return String.valueOf(value).trim();
        }
    }

    /**
     * 判断 JSON 结构是否等价于未配置值。
     *
     * @param value 已解析或原始 JSON 结构。
     * @return 空数组、空对象和 null 返回 true。
     */
    private boolean emptyJsonValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Iterable && !((Iterable<?>) value).iterator().hasNext()) {
            return true;
        }
        return value instanceof Map && ((Map<?, ?>) value).isEmpty();
    }

    /**
     * 执行require相关逻辑。
     *
     * @param jobId job标识。
     * @return 返回require结果。
     */
    public CronJobRecord require(String jobId) throws Exception {
        CronJobRecord record = cronJobRepository.findById(jobId);
        if (record == null) {
            throw new IllegalStateException("Job not found: " + jobId);
        }
        return record;
    }

    /**
     * 执行pause相关逻辑。
     *
     * @param jobId job标识。
     * @param reason 原因参数。
     * @return 返回pause结果。
     */
    public CronJobRecord pause(String jobId, String reason) throws Exception {
        CronJobRecord record = require(jobId);
        record.setStatus(STATUS_PAUSED);
        record.setPausedAt(System.currentTimeMillis());
        record.setPausedReason(reason);
        return cronJobRepository.update(record);
    }

    /**
     * 执行resume相关逻辑。
     *
     * @param jobId job标识。
     * @return 返回resume结果。
     */
    public CronJobRecord resume(String jobId) throws Exception {
        CronJobRecord record = require(jobId);
        record.setStatus(STATUS_ACTIVE);
        record.setPausedAt(0L);
        record.setPausedReason(null);
        if (record.getNextRunAt() <= System.currentTimeMillis()) {
            record.setNextRunAt(
                    CronSupport.nextRunAt(record.getCronExpr(), System.currentTimeMillis()));
        }
        return cronJobRepository.update(record);
    }

    /**
     * 标记定时任务脚本版本已通过审批。
     *
     * @param jobId job标识。
     * @param fingerprint 脚本指纹。
     * @return 返回更新后的记录。
     */
    public CronJobRecord approveScriptVersion(String jobId, String fingerprint) throws Exception {
        CronJobRecord record = require(jobId);
        if (StrUtil.isBlank(fingerprint)) {
            return record;
        }
        record.setApprovedScriptFingerprint(fingerprint.trim());
        return cronJobRepository.update(record);
    }

    /**
     * 根据审批规则键持久化已批准脚本版本并恢复对应 Cron 任务。
     *
     * @param patternKeys 审批记录中的原始规则键。
     * @return 找到并恢复对应任务时返回 true。
     */
    public boolean approveAndResumeScriptVersion(List<String> patternKeys) throws Exception {
        if (patternKeys == null) {
            return false;
        }
        for (String patternKey : patternKeys) {
            String value = StrUtil.nullToEmpty(patternKey).trim();
            if (!value.startsWith("cron-job:")) {
                continue;
            }
            String[] parts = value.split(":", 4);
            if (parts.length < 4 || StrUtil.hasBlank(parts[1], parts[2])) {
                continue;
            }
            CronJobRecord job = require(parts[1]);
            if (STATUS_ACTIVE.equalsIgnoreCase(StrUtil.nullToEmpty(job.getStatus()))
                    && parts[2].equals(job.getApprovedScriptFingerprint())) {
                return true;
            }
            if (!STATUS_PAUSED.equalsIgnoreCase(StrUtil.nullToEmpty(job.getStatus()))
                    || !StrUtil.startWith(
                            StrUtil.nullToEmpty(job.getPausedReason()), "waiting for approval:")) {
                return false;
            }
            approveScriptVersion(parts[1], parts[2]);
            resume(parts[1]);
            return true;
        }
        return false;
    }

    /** 在任务落库前执行脚本版本安全检查和授权申请。 */
    private void prepareScriptApproval(CronJobRecord record) throws Exception {
        if (cronScriptApprovalService != null) {
            cronScriptApprovalService.prepareForSave(record);
        }
    }

    /**
     * 执行trigger相关逻辑。
     *
     * @param jobId job标识。
     * @return 返回trigger结果。
     */
    public CronJobRecord trigger(String jobId) throws Exception {
        return trigger(jobId, "manual");
    }

    /**
     * 执行trigger相关逻辑。
     *
     * @param jobId job标识。
     * @param triggerType trigger类型参数。
     * @return 返回trigger结果。
     */
    public CronJobRecord trigger(String jobId, String triggerType) throws Exception {
        CronJobRecord record = require(jobId);
        record.setStatus(STATUS_ACTIVE);
        record.setNextRunAt(System.currentTimeMillis());
        record.setPendingTriggerType(normalizeTriggerType(triggerType, "manual"));
        return cronJobRepository.update(record);
    }

    /**
     * 执行remove相关逻辑。
     *
     * @param jobId job标识。
     * @return 返回remove结果。
     */
    public CronJobRecord remove(String jobId) throws Exception {
        CronJobRecord record = require(jobId);
        cronJobRepository.delete(jobId);
        return record;
    }

    /**
     * 执行历史相关逻辑。
     *
     * @param jobId job标识。
     * @param limit 最大返回数量。
     * @return 返回历史结果。
     */
    public List<CronJobRunRecord> history(String jobId, int limit) throws Exception {
        require(jobId);
        return cronJobRepository.listRuns(jobId, limit);
    }

    /**
     * 执行rewrite技能Refs相关逻辑。
     *
     * @param consolidated consolidated标识或键值。
     * @param pruned pruned 参数。
     * @return 返回rewrite技能Refs结果。
     */
    public Map<String, Object> rewriteSkillRefs(
            Map<String, String> consolidated, List<String> pruned) throws Exception {
        Map<String, String> consolidatedMap = normalizedMap(consolidated);
        List<String> prunedList = normalizedList(pruned);
        for (String key : consolidatedMap.keySet()) {
            prunedList.remove(key);
        }
        if (consolidatedMap.isEmpty() && prunedList.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("rewrites", new ArrayList<Map<String, Object>>());
            empty.put("jobs_updated", Integer.valueOf(0));
            empty.put("jobs_scanned", Integer.valueOf(0));
            return empty;
        }

        List<CronJobRecord> jobs = cronJobRepository.listAll();
        List<Map<String, Object>> rewrites = new ArrayList<Map<String, Object>>();
        for (CronJobRecord job : jobs) {
            List<String> before = parseList(job.getSkillsJson());
            if (before.isEmpty()) {
                continue;
            }
            List<String> after = new ArrayList<String>();
            Map<String, String> mapped = new LinkedHashMap<String, String>();
            List<String> dropped = new ArrayList<String>();
            for (String skill : before) {
                String target = consolidatedMap.get(skill);
                if (StrUtil.isNotBlank(target)) {
                    mapped.put(skill, target);
                    if (!after.contains(target)) {
                        after.add(target);
                    }
                } else if (prunedList.contains(skill)) {
                    dropped.add(skill);
                } else if (!after.contains(skill)) {
                    after.add(skill);
                }
            }
            if (mapped.isEmpty() && dropped.isEmpty()) {
                continue;
            }
            job.setSkillsJson(json(after));
            cronJobRepository.update(job);

            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("job_id", job.getJobId());
            entry.put("job_name", StrUtil.blankToDefault(job.getName(), job.getJobId()));
            entry.put("before", before);
            entry.put("after", after);
            entry.put("mapped", mapped);
            entry.put("dropped", dropped);
            rewrites.add(entry);
        }

        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("rewrites", rewrites);
        report.put("jobs_updated", Integer.valueOf(rewrites.size()));
        report.put("jobs_scanned", Integer.valueOf(jobs.size()));
        return report;
    }

    /**
     * 运行To视图。
     *
     * @param record 记录参数。
     * @return 返回To视图。
     */
    public Map<String, Object> runToView(CronJobRunRecord record) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("run_id", record.getRunId());
        result.put("job_id", record.getJobId());
        result.put("source_key", record.getSourceKey());
        result.put("trigger", StrUtil.blankToDefault(record.getTriggerType(), "scheduled"));
        result.put("attempt", Integer.valueOf(record.getAttempt()));
        result.put(
                "started_at",
                record.getStartedAt() <= 0 ? null : Long.valueOf(record.getStartedAt()));
        result.put(
                "finished_at",
                record.getFinishedAt() <= 0 ? null : Long.valueOf(record.getFinishedAt()));
        result.put("finished", Boolean.valueOf(record.getFinishedAt() > 0));
        result.put("duration_ms", durationMillis(record));
        result.put("status", record.getStatus());
        result.put("output", safeViewText(record.getOutput()));
        result.put("error", safeViewText(record.getError()));
        result.put("delivery_error", safeViewText(record.getDeliveryError()));
        result.put("delivery_result", safeViewValue(parse(record.getDeliveryResultJson())));
        result.put("summary", safeViewText(record.getSummary()));
        return result;
    }

    /**
     * 执行durationMillis相关逻辑。
     *
     * @param record 记录参数。
     * @return 返回duration Millis结果。
     */
    private Long durationMillis(CronJobRunRecord record) {
        if (record == null || record.getStartedAt() <= 0 || record.getFinishedAt() <= 0) {
            return null;
        }
        return Long.valueOf(Math.max(0L, record.getFinishedAt() - record.getStartedAt()));
    }

    /**
     * 转换为视图。
     *
     * @param record 记录参数。
     * @return 返回转换后的视图。
     */
    public Map<String, Object> toView(CronJobRecord record) {
        Map<String, Object> schedule = new LinkedHashMap<String, Object>();
        String scheduleKind = CronSupport.kind(record.getCronExpr());
        schedule.put("kind", scheduleKind);
        schedule.put("raw", record.getCronExpr());
        if ("interval".equals(scheduleKind)) {
            schedule.put("minutes", CronSupport.intervalMinutes(record.getCronExpr()));
        } else if ("once".equals(scheduleKind)) {
            Long absoluteRunAt = CronSupport.absoluteRunAt(record.getCronExpr());
            schedule.put("run_at", visibleOnceRunAt(record, absoluteRunAt));
        } else {
            schedule.put("expr", record.getCronExpr());
        }
        schedule.put("display", scheduleDisplay(record));

        Map<String, Object> repeat = new LinkedHashMap<String, Object>();
        repeat.put(
                "times",
                record.getRepeatTimes() <= 0 ? null : Integer.valueOf(record.getRepeatTimes()));
        repeat.put("completed", Integer.valueOf(record.getRepeatCompleted()));

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", record.getJobId());
        result.put("job_id", record.getJobId());
        result.put("name", record.getName());
        result.put("prompt", safeViewText(record.getPrompt()));
        result.put("prompt_preview", safeViewText(StrUtil.maxLength(record.getPrompt(), 120)));
        result.put("cron_expr", record.getCronExpr());
        result.put("schedule", schedule);
        result.put("schedule_display", schedule.get("display"));
        result.put("enabled", Boolean.valueOf(STATUS_ACTIVE.equalsIgnoreCase(record.getStatus())));
        result.put("state", state(record));
        result.put("actions", actions(record));
        result.put("deliver", StrUtil.blankToDefault(record.getDeliverPlatform(), "local"));
        result.put("deliver_chat_id", record.getDeliverChatId());
        result.put("deliver_thread_id", record.getDeliverThreadId());
        result.put("origin", parse(record.getOriginJson()));
        result.put("skills", parseList(record.getSkillsJson()));
        result.put("skill", first(parseList(record.getSkillsJson())));
        result.put("repeat", repeat);
        result.put("script", record.getScript());
        result.put("approved_script_fingerprint", record.getApprovedScriptFingerprint());
        result.put("workdir", workdirReference(record.getJobId(), record.getWorkdir()));
        result.put("no_agent", Boolean.valueOf(record.isNoAgent()));
        List<String> contextFrom = parseList(record.getContextFromJson());
        result.put("context_from", contextFrom);
        result.put("depends_on", contextFrom);
        result.put(
                "enabled_toolsets",
                filterProtectedCronToolsets(parseList(record.getEnabledToolsetsJson())));
        result.put("model", record.getModel());
        result.put("provider", record.getProvider());
        result.put("base_url", record.getBaseUrl());
        result.put("wrap_response", Boolean.valueOf(record.isWrapResponse()));
        result.put(
                "last_run_at",
                record.getLastRunAt() <= 0 ? null : Long.valueOf(record.getLastRunAt()));
        result.put("next_run_at", visibleNextRunAt(record));
        result.put("last_status", record.getLastStatus());
        result.put("last_error", safeViewText(record.getLastError()));
        result.put("last_delivery_error", safeViewText(record.getLastDeliveryError()));
        result.put("diagnostics", diagnostics(record));
        result.put("pending_trigger", safeViewText(record.getPendingTriggerType()));
        result.put("last_output", safeViewText(record.getLastOutput()));
        result.put(
                "paused_at", record.getPausedAt() <= 0 ? null : Long.valueOf(record.getPausedAt()));
        result.put("paused_reason", record.getPausedReason());
        result.put("created_at", Long.valueOf(record.getCreatedAt()));
        return result;
    }

    /**
     * 执行guide相关逻辑。
     *
     * @return 返回guide结果。
     */
    public Map<String, Object> guide() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("objective", "通过 Cron 自动化创建、编辑、暂停、恢复、立即运行和复核任务，并把结果投递到本地或指定国内渠道。");
        result.put("schedule_types", Arrays.asList("interval", "cron", "once"));
        result.put(
                "editable_fields",
                Arrays.asList(
                        "name",
                        "schedule",
                        "prompt",
                        "skills",
                        "deliver",
                        "deliver_chat_id",
                        "deliver_thread_id",
                        "repeat",
                        "script",
                        "workdir",
                        "no_agent",
                        "context_from",
                        "depends_on",
                        "enabled_toolsets",
                        "enabledToolsets",
                        "model",
                        "provider",
                        "base_url",
                        "wrap_response",
                        "enabled",
                        "status",
                        "state",
                        "paused_reason"));
        result.put("actions", cronGuideActions());
        result.put("action_syntax", cronGuideActionSyntax());
        result.put("aliases", cronGuideAliases());
        result.put("skill_binding", cronGuideSkillBinding());
        result.put("delivery", cronGuideDelivery());
        result.put("runtime_modes", cronGuideRuntimeModes());
        result.put("runtime_isolation", cronRuntimeIsolationPolicy());
        result.put("history_and_status", cronGuideHistoryAndStatus());
        result.put("security", cronGuideSecurity());
        result.put(
                "slash_examples",
                Arrays.asList(
                        "/cron add \"every 2h\" \"Check server status\" --skill blogwatcher",
                        "/cron edit <job-id> --schedule \"every 4h\" --prompt \"New task\"",
                        "/cron edit <job-id> --skill blogwatcher --skill maps",
                        "/cron edit <job-id> --remove-skill blogwatcher",
                        "/cron edit <job-id> --clear-skills",
                        "/cron edit <job-id> --clear-repeat",
                        "/cron add \"every 2h\" \"task\" --deliver feishu --deliver-chat-id chat --deliver-thread-id thread",
                        "/cron edit <job-id> --no-agent --script collect.py --workdir workspace/projects/demo",
                        "/cron edit <job-id> --context-from upstream-job --enabled-toolsets web,terminal",
                        "/cron edit <job-id> --clear-context-from --clear-enabled-toolsets",
                        "/cron run <job-id>",
                        "/cron history <job-id> --limit 20"));
        result.put(
                "api_routes",
                Arrays.asList(
                        "GET /api/cron/jobs/guide",
                        "GET /api/cron/jobs/policy",
                        "GET /api/cron/jobs/status",
                        "GET /api/cron/jobs/next",
                        "GET /api/cron/jobs",
                        "POST /api/cron/jobs",
                        "PUT /api/cron/jobs/{id}",
                        "DELETE /api/cron/jobs/{id}",
                        "GET /api/cron/jobs/{id}/inspect",
                        "POST /api/cron/jobs/{id}/pause",
                        "POST /api/cron/jobs/{id}/resume",
                        "POST /api/cron/jobs/{id}/trigger",
                        "POST /api/cron/jobs/{id}/retry",
                        "GET /api/cron/jobs/{id}/runs"));
        return result;
    }

    /**
     * 执行策略相关逻辑。
     *
     * @return 返回策略结果。
     */
    public Map<String, Object> policy() {
        Map<String, Object> policy = new LinkedHashMap<String, Object>();
        policy.put(
                "actions",
                Arrays.asList(
                        "create", "add", "update", "edit", "pause", "disable", "stop", "resume",
                        "enable", "start", "run", "run_now", "trigger", "retry", "rerun", "remove",
                        "delete", "history", "inspect", "list", "next"));
        policy.put("action_syntax", cronGuideActionSyntax());
        policy.put("sourceScopedList", Boolean.TRUE);
        policy.put("freshSessionRuns", Boolean.TRUE);
        policy.put("selfContainedPromptRequired", Boolean.TRUE);
        policy.put("recursiveCronCreationDiscouraged", Boolean.TRUE);
        policy.put("runtime_isolation", cronRuntimeIsolationPolicy());
        policy.put(
                "update_fields",
                Arrays.asList(
                        "name",
                        "schedule",
                        "prompt",
                        "deliver",
                        "deliver_chat_id",
                        "deliver_thread_id",
                        "skill",
                        "skills",
                        "repeat",
                        "wrap_response",
                        "script",
                        "workdir",
                        "no_agent",
                        "context_from",
                        "depends_on",
                        "enabled_toolsets",
                        "enabledToolsets",
                        "model",
                        "provider",
                        "base_url",
                        "enabled",
                        "status",
                        "paused_reason"));
        policy.put(
                "clear_fields",
                Arrays.asList(
                        "deliver_chat_id",
                        "deliver_thread_id",
                        "skills",
                        "repeat",
                        "script",
                        "workdir",
                        "context_from",
                        "depends_on",
                        "enabled_toolsets",
                        "enabledToolsets",
                        "model",
                        "provider",
                        "base_url"));
        policy.put(
                "status_fields",
                Arrays.asList(
                        "total",
                        "active",
                        "paused",
                        "completed",
                        "due",
                        "next",
                        "recent_failures"));
        policy.put(
                "history_fields",
                Arrays.asList(
                        "run_id",
                        "trigger",
                        "attempt",
                        "status",
                        "output",
                        "error",
                        "delivery_result",
                        "summary"));
        policy.put("trigger_type_fields", Arrays.asList("trigger_type", "triggerType", "reason"));
        policy.put("custom_manual_trigger_supported", Boolean.TRUE);
        policy.put("custom_retry_trigger_supported", Boolean.TRUE);
        policy.put("queued_trigger_type_persisted", Boolean.TRUE);

        Map<String, Object> schedule = new LinkedHashMap<String, Object>();
        schedule.put("cronExpressionSupported", Boolean.TRUE);
        schedule.put("intervalSupported", Boolean.TRUE);
        schedule.put("onceSupported", Boolean.TRUE);
        schedule.put("nextRunPreview", Boolean.TRUE);
        schedule.put("repeatLimitSupported", Boolean.TRUE);
        policy.put("schedule", schedule);

        Map<String, Object> delivery = new LinkedHashMap<String, Object>();
        delivery.put("originDefaultOnCreate", Boolean.TRUE);
        delivery.put("dashboardDefaultLocal", Boolean.TRUE);
        delivery.put("localDeliverySupported", Boolean.TRUE);
        delivery.put("originDeliverySupported", Boolean.TRUE);
        delivery.put("explicitPlatformTargetsSupported", Boolean.TRUE);
        delivery.put("explicitChatTargetSupported", Boolean.TRUE);
        delivery.put("multiTargetDeliverySupported", Boolean.TRUE);
        delivery.put("threadTargetSupported", Boolean.TRUE);
        delivery.put("wrapResponseSupported", Boolean.TRUE);
        delivery.put(
                "clearFlags",
                Arrays.asList("--clear-deliver-chat-id", "--clear-deliver-thread-id"));
        delivery.put(
                "wrapFlags",
                Arrays.asList(
                        "--wrap-response", "--no-wrap-response", "--wrap", "--raw", "--no-wrap"));
        delivery.put(
                "wrapResponsePolicy",
                "--wrap-response 会包装任务输出；--raw、--no-wrap 和 --no-wrap-response 会投递原始输出。");
        delivery.put(
                "supportedPlatforms",
                Arrays.asList(
                        "MEMORY", "FEISHU", "DINGTALK", "WECOM", "WEIXIN", "QQBOT", "YUANBAO"));
        delivery.put(
                "targetForms",
                Arrays.asList(
                        "origin",
                        "local",
                        "platform",
                        "platform:chat_id",
                        "platform:chat_id:thread_id",
                        "target1,target2"));
        delivery.put(
                "targetModes",
                Arrays.asList(
                        "origin 会回复到任务来源会话",
                        "local 只把结果写入运行历史",
                        "platform 使用该平台已配置的默认目标",
                        "platform:chat_id:thread_id 会投递到指定会话线程",
                        "target1,target2 会把同一次运行结果投递到多个目标"));
        policy.put("delivery", delivery);

        Map<String, Object> skillBinding = new LinkedHashMap<String, Object>();
        skillBinding.put("singleSkillSupported", Boolean.TRUE);
        skillBinding.put("multipleSkillsSupported", Boolean.TRUE);
        skillBinding.put("skillRewriteSupported", Boolean.TRUE);
        skillBinding.put("replaceFlags", Arrays.asList("--skill name", "--skills a,b"));
        skillBinding.put("appendFlags", Arrays.asList("--add-skill name", "--add-skills a,b"));
        skillBinding.put(
                "removeFlags", Arrays.asList("--remove-skill name", "--remove-skills a,b"));
        skillBinding.put("clearFlags", Arrays.asList("--clear-skills"));
        skillBinding.put("contextFromSupported", Boolean.TRUE);
        skillBinding.put("dependsOnAliasSupported", Boolean.TRUE);
        skillBinding.put(
                "dependencyFlags",
                Arrays.asList(
                        "--context-from job-id",
                        "--depends-on job-id",
                        "--clear-context-from",
                        "--clear-depends-on"));
        skillBinding.put("enabledToolsetsSupported", Boolean.TRUE);
        skillBinding.put("enabledToolsetsAliasSupported", Boolean.TRUE);
        skillBinding.put(
                "enabledToolsetsFields", Arrays.asList("enabled_toolsets", "enabledToolsets"));
        skillBinding.put("protectedDisabledToolsets", PROTECTED_CRON_DISABLED_TOOLSETS);
        skillBinding.put("protectedDisabledOverridesEnabledToolsets", Boolean.TRUE);
        skillBinding.put("dedupeApplied", Boolean.TRUE);
        policy.put("skill_binding", skillBinding);

        Map<String, Object> execution = new LinkedHashMap<String, Object>();
        execution.put("manualRunSupported", Boolean.TRUE);
        execution.put("retryAliasSupported", Boolean.TRUE);
        execution.put("customTriggerTypeSupported", Boolean.TRUE);
        execution.put("pauseResumeSupported", Boolean.TRUE);
        execution.put("stateEditSupported", Boolean.TRUE);
        execution.put("pausedReasonEditSupported", Boolean.TRUE);
        execution.put("historySupported", Boolean.TRUE);
        execution.put("statusOverviewSupported", Boolean.TRUE);
        execution.put("noAgentScriptSupported", Boolean.TRUE);
        execution.put("scriptMustStayInRuntimeScripts", Boolean.TRUE);
        execution.put("workdirSecurityChecked", Boolean.TRUE);
        execution.put("modelPinSupported", Boolean.TRUE);
        execution.put("providerPinSupported", Boolean.TRUE);
        execution.put("baseUrlPinSupported", Boolean.TRUE);
        execution.put("dangerousCommandApprovalApplied", Boolean.TRUE);
        execution.put("promptThreatScanApplied", Boolean.TRUE);
        execution.put("secretRedactionApplied", Boolean.TRUE);
        policy.put("execution", execution);
        return policy;
    }

    /**
     * 执行定时任务运行时Isolation策略相关逻辑。
     *
     * @return 返回定时任务运行时Isolation策略结果。
     */
    private Map<String, Object> cronRuntimeIsolationPolicy() {
        Map<String, Object> isolation = new LinkedHashMap<String, Object>();
        isolation.put("sourceBoundSessionRuns", Boolean.TRUE);
        isolation.put("sessionBinding", "source_key");
        isolation.put("sourceScopedLock", Boolean.TRUE);
        isolation.put("disabledToolsets", PROTECTED_CRON_DISABLED_TOOLSETS);
        isolation.put("protectedDisabledToolsets", PROTECTED_CRON_DISABLED_TOOLSETS);
        isolation.put("enabledToolsetsOverrideSupported", Boolean.TRUE);
        isolation.put("enabledToolsetsAliasSupported", Boolean.TRUE);
        isolation.put("protectedDisabledOverridesEnabledToolsets", Boolean.TRUE);
        isolation.put("autoDeliveryContext", Boolean.TRUE);
        isolation.put("selfDeliveryDiscouraged", Boolean.TRUE);
        isolation.put("localDeliveryHistoryOnly", Boolean.TRUE);
        isolation.put("tickLockFile", "workspace/jobs/cron.tick.lock");
        isolation.put("workdirJobsSerialized", Boolean.TRUE);
        isolation.put("parallelBySourceWithoutWorkdir", Boolean.TRUE);
        isolation.put("inactivityTimeoutSeconds", Integer.valueOf(cronInactivityTimeoutSeconds()));
        isolation.put("inactivityTimeoutEnv", "SOLONCLAW_CRON_TIMEOUT");
        isolation.put("missedRunCatchupWindow", "half_period_clamped_120s_to_2h");
        isolation.put("oneShotGraceWindowSeconds", Integer.valueOf(120));
        return isolation;
    }

    /**
     * 执行定时任务InactivityTimeoutSeconds相关逻辑。
     *
     * @return 返回定时任务Inactivity Timeout Seconds结果。
     */
    private int cronInactivityTimeoutSeconds() {
        if (appConfig == null || appConfig.getScheduler() == null) {
            return 600;
        }
        int timeout = appConfig.getScheduler().getInactivityTimeoutSeconds();
        return timeout >= 0 ? timeout : 600;
    }

    /**
     * 执行定时任务GuideActions相关逻辑。
     *
     * @return 返回定时任务Guide Actions结果。
     */
    private Map<String, Object> cronGuideActions() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("list", "查看当前会话或全部任务");
        result.put("inspect", "查看单个任务详情、动作标记和运行摘要");
        result.put("next", "按 next_run_at 查看即将运行的任务");
        result.put("status", "查看任务计数、到期任务、最近失败和下次运行");
        result.put("add", "创建自动化任务");
        result.put("edit", "编辑调度、提示词、技能、投递、脚本、模型和包装策略");
        result.put("pause", "暂停任务并保留暂停原因");
        result.put("resume", "恢复任务并重新计算下次运行时间");
        result.put("run", "立即触发任务");
        result.put("retry", "重跑最近失败或需要复核的任务");
        result.put("history", "查看执行历史、输出、错误和投递结果");
        result.put("remove", "删除任务");
        return result;
    }

    /**
     * 执行定时任务GuideActionSyntax相关逻辑。
     *
     * @return 返回定时任务Guide Action Syntax结果。
     */
    private Map<String, Object> cronGuideActionSyntax() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(
                "add",
                "/cron add \"every 2h\" \"task\" [--skill name] [--deliver target] [--wrap-response|--no-wrap-response]");
        result.put(
                "edit",
                "/cron edit <job-id> [--schedule expr] [--prompt text] [--add-skill name] [--remove-skill name]");
        result.put("pause", "/cron pause|disable|stop <job-id> [--reason reason]");
        result.put("resume", "/cron resume|enable|start <job-id>");
        result.put(
                "run",
                "/cron run|trigger|retry|rerun <job-id> [--trigger-type name|--reason name]");
        result.put("remove", "/cron remove|delete|rm <job-id>");
        result.put("history", "/cron history <job-id> [--limit 20]");
        result.put("status", "/cron status [--all]");
        result.put("next", "/cron next|upcoming [--all] [--limit 5]");
        result.put("inspect", "/cron inspect|show|detail <job-id>");
        return result;
    }

    /**
     * 执行定时任务GuideAliases相关逻辑。
     *
     * @return 返回定时任务Guide Aliases结果。
     */
    private Map<String, Object> cronGuideAliases() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("add", Arrays.asList("create"));
        result.put("edit", Arrays.asList("update"));
        result.put("pause", Arrays.asList("disable", "stop"));
        result.put("resume", Arrays.asList("enable", "start"));
        result.put("run", Arrays.asList("trigger", "retry", "rerun"));
        result.put("inspect", Arrays.asList("show", "detail"));
        result.put("remove", Arrays.asList("delete", "rm"));
        result.put("next", Arrays.asList("upcoming"));
        return result;
    }

    /**
     * 执行定时任务Guide技能Binding相关逻辑。
     *
     * @return 返回定时任务Guide技能Binding结果。
     */
    private Map<String, Object> cronGuideSkillBinding() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("fields", Arrays.asList("skill", "skills"));
        result.put("replace", Arrays.asList("--skill name", "--skills a,b"));
        result.put("append", Arrays.asList("--add-skill name", "--add-skills a,b"));
        result.put("remove", Arrays.asList("--remove-skill name", "--remove-skills a,b"));
        result.put("clear", Arrays.asList("--clear-skills"));
        result.put("dependency_fields", Arrays.asList("context_from", "depends_on"));
        result.put(
                "dependency_flags",
                Arrays.asList(
                        "--context-from job-id",
                        "--depends-on job-id",
                        "--clear-context-from",
                        "--clear-depends-on"));
        result.put("enabled_toolset_fields", Arrays.asList("enabled_toolsets", "enabledToolsets"));
        result.put("protected_disabled_toolsets", PROTECTED_CRON_DISABLED_TOOLSETS);
        result.put("protected_disabled_overrides_enabled_toolsets", Boolean.TRUE);
        result.put("dedupe", Boolean.TRUE);
        return result;
    }

    /**
     * 执行定时任务Guide投递相关逻辑。
     *
     * @return 返回定时任务Guide投递结果。
     */
    private Map<String, Object> cronGuideDelivery() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(
                "targets",
                Arrays.asList(
                        "origin",
                        "local",
                        "feishu",
                        "dingtalk",
                        "wecom",
                        "weixin",
                        "qqbot",
                        "yuanbao"));
        result.put(
                "fields",
                Arrays.asList("deliver", "deliver_chat_id", "deliver_thread_id", "wrap_response"));
        result.put(
                "modes",
                Arrays.asList(
                        "origin: 回复到创建任务的原始会话",
                        "local: 仅记录在本地任务历史",
                        "platform: 投递到指定平台的默认目标",
                        "platform:chat_id:thread_id: 投递到指定会话和线程",
                        "target1,target2: 同一次运行投递到多个目标"));
        result.put("default_from_slash", "origin");
        result.put("default_from_dashboard", "local");
        result.put(
                "clear_flags",
                Arrays.asList("--clear-deliver-chat-id", "--clear-deliver-thread-id"));
        result.put(
                "wrap_flags",
                Arrays.asList(
                        "--wrap-response", "--no-wrap-response", "--wrap", "--raw", "--no-wrap"));
        result.put(
                "wrap_response_policy",
                "--wrap-response 会包装任务输出，--raw/--no-wrap/--no-wrap-response 会投递原始输出。");
        result.put(
                "target_forms",
                Arrays.asList(
                        "origin",
                        "local",
                        "platform",
                        "platform:chat_id",
                        "platform:chat_id:thread_id",
                        "target1,target2"));
        result.put("multi_target", "deliver 支持逗号分隔或平台:目标形式，创建和编辑时会校验平台名称。");
        return result;
    }

    /**
     * 执行定时任务Guide运行时Modes相关逻辑。
     *
     * @return 返回定时任务Guide运行时Modes结果。
     */
    private Map<String, Object> cronGuideRuntimeModes() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("agent", "默认模式，使用 prompt 和 skills 进入 Agent 主循环。");
        result.put("no_agent", "脚本直投模式，必须提供 script，stdout 可按投递策略发送。");
        result.put("runtime_isolation", cronRuntimeIsolationPolicy());
        result.put("session_binding", "按 source_key 绑定运行会话；同一来源串行执行，不同来源可并行执行。");
        result.put("disabled_toolsets", PROTECTED_CRON_DISABLED_TOOLSETS);
        result.put("protected_disabled_toolsets", PROTECTED_CRON_DISABLED_TOOLSETS);
        result.put("protected_disabled_overrides_enabled_toolsets", Boolean.TRUE);
        result.put("local_delivery", "deliver=local 时只写入运行历史，不外投消息。");
        result.put(
                "inactivity_timeout",
                "Agent 无活动超时由 scheduler.inactivityTimeoutSeconds 或 SOLONCLAW_CRON_TIMEOUT 控制。");
        result.put("script_fields", Arrays.asList("script", "workdir", "enabled_toolsets"));
        result.put("dependency_fields", Arrays.asList("context_from", "depends_on"));
        result.put("model_pin_fields", Arrays.asList("model", "provider", "base_url"));
        result.put(
                "clear_flags",
                Arrays.asList(
                        "--clear-repeat",
                        "--clear-script",
                        "--clear-workdir",
                        "--clear-toolsets",
                        "--clear-enabled-toolsets",
                        "--clear-model",
                        "--clear-provider",
                        "--clear-base-url"));
        result.put("mode_flags", Arrays.asList("--no-agent", "--agent"));
        return result;
    }

    /**
     * 执行定时任务Guide历史And状态相关逻辑。
     *
     * @return 返回定时任务Guide历史And状态。
     */
    private Map<String, Object> cronGuideHistoryAndStatus() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(
                "status_fields",
                Arrays.asList(
                        "total",
                        "active",
                        "paused",
                        "completed",
                        "due",
                        "next",
                        "recent_failures"));
        result.put(
                "run_fields",
                Arrays.asList(
                        "run_id",
                        "trigger",
                        "attempt",
                        "status",
                        "output",
                        "error",
                        "delivery_result",
                        "summary"));
        result.put("trigger_type_fields", Arrays.asList("trigger_type", "triggerType", "reason"));
        result.put(
                "trigger_type_policy",
                "手动 run/retry 可记录短触发来源；空格会规范化为下划线，scheduled 会回退为 manual/retry。");
        result.put(
                "action_flags",
                Arrays.asList(
                        "can_inspect",
                        "can_edit",
                        "can_pause",
                        "can_resume",
                        "can_run",
                        "can_retry",
                        "can_history"));
        result.put("limits", "status、next、history 和 inspect 的 limit 会限制到安全范围。");
        return result;
    }

    /**
     * 执行定时任务Guide安全相关逻辑。
     *
     * @return 返回定时任务Guide安全结果。
     */
    private Map<String, Object> cronGuideSecurity() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(
                "prompt_scan",
                Arrays.asList(
                        "prompt_injection",
                        "deception_hide",
                        "sys_prompt_override",
                        "disregard_rules",
                        "exfil_curl",
                        "exfil_wget",
                        "read_secrets",
                        "ssh_backdoor",
                        "sudoers_mod",
                        "destructive_root_rm"));
        result.put("script_validation", "script 禁止绝对路径、父目录跳转、shell 片段、控制字符和 URL。");
        result.put("workdir_validation", "workdir 会规范化到 workspace 内部，禁止逃逸工作目录。");
        result.put("delivery_validation", "deliver 只允许本地、origin 或已支持平台。");
        result.put("protected_disabled_toolsets", PROTECTED_CRON_DISABLED_TOOLSETS);
        result.put("enabled_toolsets_policy", "受保护的禁用工具集会覆盖任务级和 scheduler enabled_toolsets 配置。");
        result.put("guardrail_mode", "触发后的命令和工具调用继续走运行时审批与危险命令策略。");
        return result;
    }

    /**
     * 执行状态相关逻辑。
     *
     * @param record 记录参数。
     * @return 返回状态。
     */
    private String state(CronJobRecord record) {
        if (STATUS_PAUSED.equalsIgnoreCase(record.getStatus())) {
            return "paused";
        }
        if (STATUS_COMPLETED.equalsIgnoreCase(record.getStatus())) {
            return "completed";
        }
        return "scheduled";
    }

    /**
     * 计算对外可见的下一次运行时间；完成态任务不再暴露历史残留时间，避免误判为待触发。
     *
     * @param record 定时任务记录。
     * @return 可展示的下一次运行时间，完成或无后续触发时返回 null。
     */
    private Long visibleNextRunAt(CronJobRecord record) {
        if (record == null
                || STATUS_COMPLETED.equalsIgnoreCase(record.getStatus())
                || record.getNextRunAt() <= 0L) {
            return null;
        }
        return Long.valueOf(record.getNextRunAt());
    }

    /**
     * 计算一次性任务的可见计划时间；完成态任务没有后续触发点，视图层统一隐藏。
     *
     * @param record 定时任务记录。
     * @param absoluteRunAt ISO 时间表达式解析出的绝对运行时间。
     * @return 可展示的一次性运行时间，完成态返回 null。
     */
    private Long visibleOnceRunAt(CronJobRecord record, Long absoluteRunAt) {
        if (record == null || STATUS_COMPLETED.equalsIgnoreCase(record.getStatus())) {
            return null;
        }
        if (absoluteRunAt != null) {
            return absoluteRunAt;
        }
        return record.getNextRunAt() <= 0L ? null : Long.valueOf(record.getNextRunAt());
    }

    /**
     * 执行actions相关逻辑。
     *
     * @param record 记录参数。
     * @return 返回actions结果。
     */
    private Map<String, Object> actions(CronJobRecord record) {
        String status = record == null || record.getStatus() == null ? "" : record.getStatus();
        boolean paused = STATUS_PAUSED.equalsIgnoreCase(status);
        boolean completed = STATUS_COMPLETED.equalsIgnoreCase(status);
        boolean failed =
                record != null
                        && ("error".equalsIgnoreCase(record.getLastStatus())
                                || StrUtil.isNotBlank(record.getLastError())
                                || StrUtil.isNotBlank(record.getLastDeliveryError()));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("can_inspect", Boolean.TRUE);
        result.put("can_edit", Boolean.TRUE);
        result.put("can_remove", Boolean.TRUE);
        result.put("can_history", Boolean.TRUE);
        result.put("can_pause", Boolean.valueOf(!paused && !completed));
        result.put("can_resume", Boolean.valueOf(paused || completed));
        result.put("can_run", Boolean.TRUE);
        result.put("can_retry", Boolean.valueOf(failed || completed));
        result.put("supports_enable_alias", Boolean.TRUE);
        result.put("supports_disable_alias", Boolean.TRUE);
        result.put("supports_start_alias", Boolean.TRUE);
        result.put("supports_stop_alias", Boolean.TRUE);
        result.put("supports_rerun_alias", Boolean.TRUE);
        return result;
    }

    /**
     * 执行调度展示相关逻辑。
     *
     * @param record 记录参数。
     * @return 返回调度展示结果。
     */
    private String scheduleDisplay(CronJobRecord record) {
        String expr = record.getCronExpr();
        String kind = CronSupport.kind(expr);
        if ("interval".equals(kind)) {
            Integer minutes = CronSupport.intervalMinutes(expr);
            return minutes == null ? expr : "every " + minutes + "m";
        }
        if ("once".equals(kind)) {
            Long absoluteRunAt = CronSupport.absoluteRunAt(expr);
            if (absoluteRunAt != null) {
                return "once at " + absoluteRunAt;
            }
            return "once in " + expr;
        }
        return expr;
    }

    /**
     * 校验上下文From。
     *
     * @param refs refs 参数。
     */
    private void validateContextFrom(List<String> refs) throws Exception {
        for (String ref : refs) {
            if (cronJobRepository.findById(ref) == null) {
                throw new IllegalStateException("context_from job not found: " + ref);
            }
        }
    }

    /**
     * 判断是否包含DependencyRefs。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回contains Dependency Refs结果。
     */
    private boolean containsDependencyRefs(Map<String, Object> body) {
        return body.containsKey("context_from") || body.containsKey("depends_on");
    }

    /**
     * 执行dependencyRefs相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回dependency Refs结果。
     */
    private List<String> dependencyRefs(Map<String, Object> body) {
        if (body.containsKey("context_from")) {
            return stringList(body.get("context_from"));
        }
        return stringList(body.get("depends_on"));
    }

    /**
     * 校验Script。
     *
     * @param script script 参数。
     */
    private void validateScript(String script) {
        if (StrUtil.isBlank(script)) {
            return;
        }
        String value = script.trim();
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalStateException("script path contains control character");
            }
        }
        if (value.startsWith("~")) {
            throw new IllegalStateException("script must stay within workspace/scripts");
        }
        try {
            File scriptsDir =
                    FileUtil.file(appConfig.getRuntime().getHome(), "scripts").getCanonicalFile();
            File requested = new File(value);
            File target =
                    (requested.isAbsolute() ? requested : new File(scriptsDir, value))
                            .getCanonicalFile();
            if (!CronJobSupport.isUnderDirectory(scriptsDir, target)) {
                throw new IllegalStateException("script must stay within workspace/scripts");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "script path could not be validated: " + CronJobSupport.safeError(e));
        }
    }

    /**
     * 生成定时任务诊断信息，帮助控制台把运行失败转换为可操作的修复提示。
     *
     * @param record 定时任务记录。
     * @return 返回诊断信息列表。
     */
    private List<Map<String, Object>> diagnostics(CronJobRecord record) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Map<String, Object> missingScript = missingScriptDiagnostic(record);
        if (missingScript != null) {
            result.add(missingScript);
        }
        return result;
    }

    /**
     * 识别脚本缺失或越界错误，给出保持在 workspace/scripts 下的恢复建议。
     *
     * @param record 定时任务记录。
     * @return 命中时返回诊断项，否则返回 null。
     */
    private Map<String, Object> missingScriptDiagnostic(CronJobRecord record) {
        if (record == null || !record.isNoAgent() || StrUtil.isBlank(record.getScript())) {
            return null;
        }
        String lastError = StrUtil.nullToEmpty(record.getLastError());
        if (!isMissingCronScriptError(lastError)) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        String script = safeViewText(record.getScript());
        item.put("code", "cron_script_missing");
        item.put("level", "error");
        item.put("message", "定时任务脚本缺失或不在 workspace/scripts 下");
        item.put("script", script);
        item.put("workspace_dir", "workspace://scripts");
        item.put("suggestion", "请恢复脚本到 workspace/scripts 后重试，或编辑任务选择新的脚本。");
        item.put("retryable_after_fix", Boolean.TRUE);
        return item;
    }

    /**
     * 判断错误是否属于定时任务脚本缺失或越界。
     *
     * @param error 错误文本。
     * @return 如果是脚本缺失或越界错误则返回 true。
     */
    private boolean isMissingCronScriptError(String error) {
        String value = StrUtil.nullToEmpty(error);
        return value.startsWith("定时任务脚本不在 workspace/scripts 下")
                || value.startsWith("Cron script not found under workspace/scripts");
    }

    /**
     * 规范化Workdir。
     *
     * @param workdir 命令执行工作目录。
     * @return 返回Workdir结果。
     */
    private String normalizeWorkdir(String workdir) {
        String value = StrUtil.nullToEmpty(workdir).trim();
        if (StrUtil.isBlank(value)) {
            return null;
        }
        SecurityPolicyService.FileVerdict textVerdict =
                SecurityPolicyService.checkWorkdirText(value);
        if (!textVerdict.isAllowed()) {
            throw new IllegalStateException(
                    "workdir blocked by security policy: "
                            + redactPath(textVerdict.getPath(), 400)
                            + " - "
                            + textVerdict.getMessage());
        }
        try {
            File file = FileUtil.file(FilePathSupport.expandUserHome(value));
            if (!file.isAbsolute() || !file.exists() || !file.isDirectory()) {
                throw new IllegalStateException("workdir must be an existing absolute directory");
            }
            File canonical = file.getCanonicalFile();
            SecurityPolicyService.FileVerdict verdict =
                    new SecurityPolicyService(appConfig)
                            .checkPath(canonical.getAbsolutePath(), false);
            if (!verdict.isAllowed()) {
                throw new IllegalStateException(
                        "workdir blocked by security policy: "
                                + redactPath(verdict.getPath(), 400)
                                + " - "
                                + verdict.getMessage());
            }
            String normalized =
                    file.getAbsoluteFile().toPath().normalize().toFile().getAbsolutePath();
            if (usesForwardSlash(value)) {
                return normalized.replace('\\', '/');
            }
            return normalized;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "workdir path could not be validated: " + CronJobSupport.safeError(e));
        }
    }

    /**
     * 脱敏定时任务工作目录安全拒绝消息中的敏感路径。
     *
     * @param path 原始路径。
     * @param maxLength 最大展示长度。
     * @return 返回脱敏后的路径。
     */
    private String redactPath(String path, int maxLength) {
        return SecretRedactor.redactSensitivePaths(SecretRedactor.redact(path, maxLength));
    }

    /**
     * 执行workdir引用相关逻辑。
     *
     * @param workdir 命令执行工作目录。
     * @return 返回workdir Reference结果。
     */
    private String workdirReference(String jobId, String workdir) {
        String value = StrUtil.nullToEmpty(workdir).trim();
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            File workspaceHome = FileUtil.file(appConfig.getRuntime().getHome()).getCanonicalFile();
            File file = FileUtil.file(value).getCanonicalFile();
            String homePath = normalizedPath(workspaceHome);
            String filePath = normalizedPath(file);
            if (filePath.equals(homePath)) {
                return "workspace://";
            }
            if (filePath.startsWith(homePath + File.separator)) {
                return "workspace://"
                        + filePath.substring(homePath.length() + 1).replace('\\', '/');
            }
        } catch (Exception e) {
            logCronBestEffortFailure(jobId, "workdir_reference", e);
        }
        String name = FileUtil.file(value).getName();
        if (StrUtil.isBlank(name)) {
            name = "workdir";
        }
        return "path://" + SecretRedactor.redact(name, 200);
    }

    /**
     * 执行normalized路径相关逻辑。
     *
     * @param file 文件或目录路径参数。
     * @return 返回normalized路径。
     */
    private String normalizedPath(File file) {
        String path = file.getAbsolutePath();
        if (File.separatorChar == '\\') {
            return path.toLowerCase(java.util.Locale.ROOT);
        }
        return path;
    }

    /**
     * 执行usesForward斜杠命令相关逻辑。
     *
     * @param path 文件或目录路径。
     * @return 返回uses Forward Slash结果。
     */
    private boolean usesForwardSlash(String path) {
        return path != null && path.indexOf('/') >= 0 && path.indexOf('\\') < 0;
    }

    /**
     * 执行scan提示词相关逻辑。
     *
     * @param prompt 提示词参数。
     */
    public void scanPrompt(String prompt) {
        if (StrUtil.isBlank(prompt)) {
            return;
        }
        String promptToScan = stripCronSafeConstructs(prompt);
        for (int i = 0; i < promptToScan.length(); i++) {
            char ch = promptToScan.charAt(i);
            if (isInvisibleInjectionChar(ch, promptToScan, i)) {
                throw new IllegalStateException(
                        "Blocked invisible unicode U+"
                                + String.format(Locale.ROOT, "%04X", Integer.valueOf(ch))
                                + " in cron prompt");
            }
        }
        for (CronPromptThreat threat : CRON_PROMPT_THREATS) {
            if (threat.pattern.matcher(promptToScan).find()) {
                throw new IllegalStateException("Blocked unsafe cron prompt pattern: " + threat.id);
            }
        }
    }

    /**
     * 扫描技能、脚本输出或上游任务输出参与组装后的定时任务提示词。
     *
     * <p>这些运行期内容可以合法引用危险命令作为报告、排障记录或安全文档的一部分， 因此只阻断明确的提示词注入指令。不可见字符会在实际调用 Agent 前剥离，避免资料中的
     * 偶发零宽字符持续阻断同一任务。
     *
     * @param prompt 包含可信运行期内容的完整提示词。
     * @return 已清理且通过宽松扫描的提示词。
     */
    String scanTrustedAssembledPrompt(String prompt) {
        if (StrUtil.isBlank(prompt)) {
            return prompt;
        }
        String cleanedPrompt = stripInvisibleInjectionChars(prompt);
        String promptToScan = stripCronSafeConstructs(cleanedPrompt);
        for (CronPromptThreat threat : CRON_TRUSTED_ASSEMBLED_PROMPT_THREATS) {
            if (threat.pattern.matcher(promptToScan).find()) {
                throw new IllegalStateException("Blocked unsafe cron prompt pattern: " + threat.id);
            }
        }
        return cleanedPrompt;
    }

    /**
     * 剥离定时任务安全Constructs。
     *
     * @param prompt 提示词参数。
     * @return 返回strip定时任务Safe Constructs结果。
     */
    private static String stripCronSafeConstructs(String prompt) {
        return CRON_GITHUB_AUTH_HEADER
                .matcher(prompt)
                .replaceAll("curl https://api.github.com/user");
    }

    /**
     * 剥离可信运行期内容中的不可见注入字符，同时保留合法 Emoji 连接符。
     *
     * @param prompt 待清理的完整提示词。
     * @return 供 Agent 实际执行的清理后提示词。
     */
    private static String stripInvisibleInjectionChars(String prompt) {
        StringBuilder cleaned = new StringBuilder(prompt.length());
        List<String> removed = new ArrayList<String>();
        for (int i = 0; i < prompt.length(); i++) {
            char ch = prompt.charAt(i);
            if (!isInvisibleInjectionChar(ch, prompt, i)) {
                cleaned.append(ch);
                continue;
            }
            String codePoint = String.format(Locale.ROOT, "U+%04X", Integer.valueOf(ch));
            if (!removed.contains(codePoint)) {
                removed.add(codePoint);
            }
        }
        if (!removed.isEmpty()) {
            log.warn(
                    "Cron trusted assembled prompt stripped {} invisible unicode character type(s): {}",
                    Integer.valueOf(removed.size()),
                    String.join(", ", removed));
        }
        return cleaned.toString();
    }

    /**
     * 判断是否Invisible Injection Char。
     *
     * @param ch ch 参数。
     * @param text 待处理文本。
     * @param index 索引参数。
     * @return 如果Invisible Injection Char满足条件则返回 true，否则返回 false。
     */
    private static boolean isInvisibleInjectionChar(char ch, String text, int index) {
        if (ch == '\u200d' && zwjHasEmojiNeighbour(text, index)) {
            return false;
        }
        return ch == '\u200b'
                || ch == '\u200c'
                || ch == '\u200d'
                || ch == '\u2060'
                || ch == '\u2062'
                || ch == '\u2063'
                || ch == '\u2064'
                || ch == '\ufeff'
                || ch == '\u202a'
                || ch == '\u202b'
                || ch == '\u202c'
                || ch == '\u202d'
                || ch == '\u202e'
                || ch == '\u2066'
                || ch == '\u2067'
                || ch == '\u2068'
                || ch == '\u2069';
    }

    /**
     * 执行zwjHasEmojiNeighbour相关逻辑。
     *
     * @param text 待处理文本。
     * @param index 索引参数。
     * @return 返回zwj Has Emoji Neighbour结果。
     */
    private static boolean zwjHasEmojiNeighbour(String text, int index) {
        int left = index - 1;
        while (left >= 0 && text.charAt(left) == VARIATION_SELECTOR_CP) {
            left--;
        }
        int right = index + 1;
        while (right < text.length() && text.charAt(right) == VARIATION_SELECTOR_CP) {
            right++;
        }
        return left >= 0
                && right < text.length()
                && isEmojiCodePoint(text.codePointBefore(left + 1))
                && isEmojiCodePoint(text.codePointAt(right));
    }

    /**
     * 判断是否Emoji Code Point。
     *
     * @param codePoint codePoint 参数。
     * @return 如果Emoji Code Point满足条件则返回 true，否则返回 false。
     */
    private static boolean isEmojiCodePoint(int codePoint) {
        for (int i = 0; i < EMOJI_NEIGHBOUR_CP_RANGES.length; i++) {
            int[] range = EMOJI_NEIGHBOUR_CP_RANGES[i];
            if (codePoint >= range[0] && codePoint <= range[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行threat相关逻辑。
     *
     * @param id 标识。
     * @param regex regex 参数。
     * @return 返回threat结果。
     */
    private static CronPromptThreat threat(String id, String regex) {
        return new CronPromptThreat(id, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    /** 承载定时任务提示词Threat相关状态和辅助逻辑。 */
    private static class CronPromptThreat {
        /** 记录定时任务提示词Threat中的标识。 */
        private final String id;

        /** 记录定时任务提示词Threat中的pattern。 */
        private final Pattern pattern;

        /**
         * 创建定时任务提示词Threat实例，并注入运行所需依赖。
         *
         * @param id 标识。
         * @param pattern pattern 参数。
         */
        private CronPromptThreat(String id, Pattern pattern) {
            this.id = id;
            this.pattern = pattern;
        }
    }

    /** 承载模型Override相关状态和辅助逻辑。 */
    private static class ModelOverride {
        /** 记录模型Override中的模型。 */
        private final String model;

        /** 记录模型Override中的提供方。 */
        private final String provider;

        /** 记录模型Override中的基础URL。 */
        private final String baseUrl;

        /**
         * 创建模型Override实例，并注入运行所需依赖。
         *
         * @param model 模型名称。
         * @param provider 模型或能力提供方。
         * @param baseUrl 待校验或访问的地址参数。
         */
        private ModelOverride(String model, String provider, String baseUrl) {
            this.model = model;
            this.provider = provider;
            this.baseUrl = baseUrl;
        }
    }

    /**
     * 执行JSON相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回JSON结果。
     */
    private String json(Object value) {
        if (value == null) {
            return null;
        }
        return ONode.serialize(value);
    }

    /**
     * 执行默认Deliver相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回默认Deliver结果。
     */
    private String defaultDeliver(Map<String, Object> body) {
        return body != null && body.get("origin") != null ? "origin" : "local";
    }

    /**
     * 投递Value。
     *
     * @param value 待规范化或校验的原始值。
     * @param defaultValue 默认值参数。
     * @return 返回Value结果。
     */
    private String deliverValue(Object value, String defaultValue) {
        List<String> targets = stringList(value);
        return targets.isEmpty() ? defaultValue : join(targets);
    }

    /**
     * 校验Deliver Targets。
     *
     * @param deliver deliver 参数。
     */
    private void validateDeliverTargets(String deliver) {
        if (StrUtil.isBlank(deliver)) {
            return;
        }
        for (String rawTarget : deliver.split(",")) {
            String target = StrUtil.trim(rawTarget);
            if (StrUtil.isBlank(target)) {
                continue;
            }
            if ("local".equalsIgnoreCase(target) || "origin".equalsIgnoreCase(target)) {
                continue;
            }
            String platformName = target;
            int colon = target.indexOf(':');
            if (colon >= 0) {
                platformName = target.substring(0, colon);
            }
            if (PlatformType.fromName(platformName) == null) {
                throw new IllegalStateException("unknown cron delivery platform: " + platformName);
            }
        }
    }

    /**
     * 执行join相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回join结果。
     */
    private String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (StrUtil.isBlank(value)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(value.trim());
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    /**
     * 执行解析相关逻辑。
     *
     * @param json JSON参数。
     * @return 返回parse结果。
     */
    private Object parse(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        return ONode.ofJson(json).toData();
    }

    /**
     * 生成安全展示用的视图文本。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe视图Text结果。
     */
    private String safeViewText(String value) {
        return SecretRedactor.redact(value);
    }

    /**
     * 生成安全展示用的视图值。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe视图Value结果。
     */
    private Object safeViewValue(Object value) {
        if (value instanceof String) {
            return safeViewText((String) value);
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), safeViewValue(entry.getValue()));
            }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) {
                result.add(safeViewValue(item));
            }
            return result;
        }
        return value;
    }

    /**
     * 解析List。
     *
     * @param json JSON参数。
     * @return 返回解析后的List。
     */
    private List<String> parseList(String json) {
        List<String> result = new ArrayList<String>();
        Object data = parse(json);
        if (data instanceof Iterable) {
            for (Object item : (Iterable<?>) data) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }

    /**
     * 执行first相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回first结果。
     */
    private Object first(List<String> values) {
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * 执行定时任务启用状态Toolsets相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回定时任务启用 Toolsets结果。
     */
    private List<String> cronEnabledToolsets(Map<String, Object> body) {
        if (body == null) {
            return new ArrayList<String>();
        }
        Object value =
                body.containsKey("enabled_toolsets")
                        ? body.get("enabled_toolsets")
                        : body.get("enabledToolsets");
        return filterProtectedCronToolsets(stringList(value));
    }

    /**
     * 执行过滤器Protected定时任务Toolsets相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回filter Protected定时任务Toolsets结果。
     */
    public static List<String> filterProtectedCronToolsets(List<String> values) {
        List<String> result = new ArrayList<String>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String normalized = normalizeToolsetName(value);
            if (StrUtil.isBlank(normalized)
                    || PROTECTED_CRON_DISABLED_TOOLSETS.contains(normalized)) {
                continue;
            }
            if (!result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    /**
     * 规范化Toolset名称。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回Toolset名称结果。
     */
    private static String normalizeToolsetName(String value) {
        String normalized = StrUtil.nullToEmpty(value).trim().toLowerCase(Locale.ROOT);
        if ("cron".equals(normalized)) {
            return "cronjob";
        }
        if ("message".equals(normalized) || "send".equals(normalized)) {
            return "messaging";
        }
        return normalized;
    }

    /**
     * 执行string列表相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回string List结果。
     */
    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (value == null) {
            return result;
        }
        if (value instanceof Map) {
            addString(result, structuredTarget((Map<?, ?>) value));
            return result;
        }
        if (value instanceof Iterable) {
            for (Object item : (Iterable<Object>) value) {
                if (item instanceof Map) {
                    addString(result, structuredTarget((Map<?, ?>) item));
                } else {
                    addString(result, item);
                }
            }
            return result;
        }
        String text = String.valueOf(value).trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            Object data = ONode.ofJson(text).toData();
            if (data instanceof Iterable) {
                for (Object item : (Iterable<Object>) data) {
                    if (item instanceof Map) {
                        addString(result, structuredTarget((Map<?, ?>) item));
                    } else {
                        addString(result, item);
                    }
                }
                return result;
            }
        }
        for (String part : text.split(",")) {
            addString(result, part);
        }
        return result;
    }

    /**
     * 执行调度值相关逻辑。
     *
     * @param scheduleValue schedule值参数。
     * @param cronExprValue 定时任务Expr值参数。
     * @param defaultValue 默认值参数。
     * @return 返回调度Value结果。
     */
    private String scheduleValue(Object scheduleValue, Object cronExprValue, String defaultValue) {
        String schedule = scheduleObjectValue(scheduleValue);
        if (StrUtil.isNotBlank(schedule)) {
            return schedule;
        }
        schedule = scheduleObjectValue(cronExprValue);
        if (StrUtil.isNotBlank(schedule)) {
            return schedule;
        }
        return defaultValue;
    }

    /**
     * 执行调度Object值相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回调度Object Value结果。
     */
    private String scheduleObjectValue(Object value) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            String text =
                    CronJobSupport.firstString(map, "raw", "expr", "cron", "value", "display");
            if (StrUtil.isNotBlank(text)) {
                return text;
            }
            Object runAt = map.get("run_at");
            if (runAt == null) {
                runAt = map.get("runAt");
            }
            if (runAt instanceof Number) {
                return Instant.ofEpochMilli(((Number) runAt).longValue()).toString();
            }
            return string(runAt, null);
        }
        return string(value, null);
    }

    /**
     * 执行structuredTarget相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回structured Target结果。
     */
    private String structuredTarget(Map<?, ?> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String platform = CronJobSupport.firstString(value, "platform", "type", "channel", "mode");
        String chatId =
                CronJobSupport.firstString(
                        value, "chat_id", "chatId", "target", "target_id", "targetId");
        String threadId =
                CronJobSupport.firstString(
                        value, "thread_id", "threadId", "message_id", "messageId");
        if (StrUtil.isBlank(platform)) {
            return null;
        }
        if ("local".equalsIgnoreCase(platform) || "origin".equalsIgnoreCase(platform)) {
            return platform.trim();
        }
        StringBuilder builder = new StringBuilder(platform.trim());
        if (StrUtil.isNotBlank(chatId)) {
            builder.append(':').append(chatId.trim());
        }
        if (StrUtil.isNotBlank(threadId)) {
            builder.append(':').append(threadId.trim());
        }
        return builder.toString();
    }

    /**
     * 应用模型Pin。
     *
     * @param record 记录参数。
     * @param model 模型名称。
     * @param provider 模型或能力提供方。
     * @param baseUrl 待校验或访问的地址参数。
     */
    private void applyModelPin(
            CronJobRecord record, String model, String provider, String baseUrl) {
        String normalizedModel = CronJobSupport.normalizeBlank(model);
        String normalizedProvider = CronJobSupport.normalizeBlank(provider);
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        if ("custom".equals(normalizedProvider)) {
            normalizedProvider = null;
        }
        if (StrUtil.isNotBlank(normalizedProvider)
                && !appConfig.getProviders().containsKey(normalizedProvider)) {
            throw new IllegalStateException("provider not found: " + normalizedProvider);
        }
        if (StrUtil.isNotBlank(normalizedModel) && StrUtil.isBlank(normalizedProvider)) {
            normalizedProvider = StrUtil.nullToEmpty(appConfig.getModel().getProviderKey()).trim();
        }
        if (StrUtil.isNotBlank(normalizedModel)
                && StrUtil.isBlank(normalizedProvider)
                && appConfig.getProviders().size() == 1) {
            normalizedProvider = appConfig.getProviders().keySet().iterator().next();
        }
        record.setModel(normalizedModel);
        record.setProvider(normalizedProvider);
        record.setBaseUrl(normalizedBaseUrl);
    }

    /**
     * 执行模型Override相关逻辑。
     *
     * @param modelValue 模型值参数。
     * @param providerValue 提供方值标识或键值。
     * @param baseUrlValue 待校验或访问的地址参数。
     * @param baseUrlAliasValue 待校验或访问的地址参数。
     * @param defaultModel 默认模型参数。
     * @param defaultProvider 默认提供方标识或键值。
     * @param defaultBaseUrl 待校验或访问的地址参数。
     * @return 返回模型Override结果。
     */
    private ModelOverride modelOverride(
            Object modelValue,
            Object providerValue,
            Object baseUrlValue,
            Object baseUrlAliasValue,
            String defaultModel,
            String defaultProvider,
            String defaultBaseUrl) {
        Map<?, ?> modelObject = objectMap(modelValue);
        String model =
                modelObject != null
                        ? CronJobSupport.firstString(modelObject, "model", "name", "id")
                        : string(modelValue, defaultModel);
        String provider =
                providerValue != null
                        ? string(providerValue, defaultProvider)
                        : modelObject != null
                                ? CronJobSupport.firstString(
                                        modelObject, "provider", "providerKey", "provider_key")
                                : defaultProvider;
        String baseUrl =
                baseUrlValue != null || baseUrlAliasValue != null
                        ? string(baseUrlValue, string(baseUrlAliasValue, defaultBaseUrl))
                        : modelObject != null
                                ? CronJobSupport.firstString(
                                        modelObject, "base_url", "baseUrl", "api_url", "apiUrl")
                                : defaultBaseUrl;
        return new ModelOverride(model, provider, baseUrl);
    }

    /**
     * 执行默认模型值相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @param record 记录参数。
     * @return 返回默认模型Value结果。
     */
    private String defaultModelValue(Map<String, Object> body, CronJobRecord record) {
        return body.containsKey("model") ? null : record.getModel();
    }

    /**
     * 执行默认提供方值相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @param record 记录参数。
     * @return 返回默认提供方Value结果。
     */
    private String defaultProviderValue(Map<String, Object> body, CronJobRecord record) {
        return body.containsKey("provider") ? null : record.getProvider();
    }

    /**
     * 执行默认基础URL值相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @param record 记录参数。
     * @return 返回默认Base URL Value结果。
     */
    private String defaultBaseUrlValue(Map<String, Object> body, CronJobRecord record) {
        return body.containsKey("base_url") || body.containsKey("baseUrl")
                ? null
                : record.getBaseUrl();
    }

    /**
     * 执行object映射相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回object Map结果。
     */
    private Map<?, ?> objectMap(Object value) {
        if (value instanceof Map) {
            return (Map<?, ?>) value;
        }
        if (!(value instanceof String)) {
            return null;
        }
        String text = ((String) value).trim();
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return null;
        }
        try {
            Object data = ONode.ofJson(text).toData();
            return data instanceof Map ? (Map<?, ?>) data : null;
        } catch (Exception e) {
            logCronBestEffortFailure("unknown", "object_map", e);
            return null;
        }
    }

    /**
     * 记录定时任务管理中的可恢复失败，日志仅包含任务标识、阶段和异常类型。
     *
     * @param jobId 定时任务标识。
     * @param phase 发生失败的内部阶段。
     * @param error 捕获到的异常。
     */
    private void logCronBestEffortFailure(String jobId, String phase, Exception error) {
        log.debug(
                "Cron job best-effort fallback: jobId={}, phase={}, error={}",
                CronJobSupport.safeLogJobId(jobId),
                phase,
                CronJobSupport.exceptionType(error));
    }

    /**
     * 规范化Base URL。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回Base URL结果。
     */
    private String normalizeBaseUrl(String value) {
        String normalized = CronJobSupport.normalizeBlank(value);
        while (StrUtil.isNotBlank(normalized) && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 规范化Trigger类型。
     *
     * @param value 待规范化或校验的原始值。
     * @param fallback 兜底参数。
     * @return 返回Trigger类型结果。
     */
    public String normalizeTriggerType(String value, String fallback) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (text.length() == 0) {
            return fallback;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length() && builder.length() < 40; i++) {
            char ch = text.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                builder.append(ch);
            } else if (ch == '_' || ch == '-' || ch == '.') {
                builder.append(ch);
            } else if (Character.isWhitespace(ch)) {
                builder.append('_');
            }
        }
        while (builder.length() > 0
                && (builder.charAt(0) == '_'
                        || builder.charAt(0) == '-'
                        || builder.charAt(0) == '.')) {
            builder.deleteCharAt(0);
        }
        while (builder.length() > 0) {
            char ch = builder.charAt(builder.length() - 1);
            if (ch != '_' && ch != '-' && ch != '.') {
                break;
            }
            builder.deleteCharAt(builder.length() - 1);
        }
        return builder.length() == 0 ? fallback : builder.toString();
    }

    /**
     * 执行规范技能相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回规范技能结果。
     */
    private List<String> canonicalSkills(Map<String, Object> body) {
        List<String> result = stringList(body.get("skills"));
        for (String item : stringList(body.get("skill"))) {
            addString(result, item);
        }
        return result;
    }

    /**
     * 应用技能Delta。
     *
     * @param current current 参数。
     * @param body 请求体或消息正文内容。
     * @return 返回apply技能Delta结果。
     */
    private List<String> applySkillsDelta(List<String> current, Map<String, Object> body) {
        List<String> result = normalizedList(current);
        Object raw =
                body.containsKey("skills_delta")
                        ? body.get("skills_delta")
                        : body.get("skillsDelta");
        Map<?, ?> delta = raw instanceof Map ? (Map<?, ?>) raw : body;
        List<String> remove =
                mergedStringList(
                        delta,
                        "remove",
                        "remove_skill",
                        "remove_skills",
                        "removeSkill",
                        "removeSkills");
        if (!remove.isEmpty()) {
            result.removeAll(remove);
        }
        for (String item :
                mergedStringList(
                        delta, "add", "add_skill", "add_skills", "addSkill", "addSkills")) {
            addString(result, item);
        }
        return result;
    }

    /**
     * 判断是否存在技能Delta。
     *
     * @param body 请求体或消息正文内容。
     * @return 如果技能Delta满足条件则返回 true，否则返回 false。
     */
    private boolean hasSkillsDelta(Map<String, Object> body) {
        return body.containsKey("skills_delta")
                || body.containsKey("skillsDelta")
                || body.containsKey("add_skill")
                || body.containsKey("addSkill")
                || body.containsKey("add_skills")
                || body.containsKey("addSkills")
                || body.containsKey("remove_skill")
                || body.containsKey("removeSkill")
                || body.containsKey("remove_skills")
                || body.containsKey("removeSkills");
    }

    /**
     * 清理技能。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回技能结果。
     */
    private boolean clearSkills(Map<String, Object> body) {
        return Boolean.TRUE.equals(body.get("clear_skills"))
                || Boolean.TRUE.equals(body.get("clearSkills"));
    }

    /**
     * 执行merged字符串列表相关逻辑。
     *
     * @param map 待读取的映射对象。
     * @param keys 候选键列表。
     * @return 返回merged String List结果。
     */
    private List<String> mergedStringList(Map<?, ?> map, String... keys) {
        List<String> result = new ArrayList<String>();
        if (map == null || keys == null) {
            return result;
        }
        for (String key : keys) {
            if (!map.containsKey(key)) {
                continue;
            }
            for (String item : stringList(map.get(key))) {
                addString(result, item);
            }
        }
        return result;
    }

    /**
     * 执行默认任务名称相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @param prompt 提示词参数。
     * @param skills 技能参数。
     * @param script script 参数。
     * @param noAgent noAgent 参数。
     * @return 返回默认任务名称结果。
     */
    private String defaultJobName(
            Map<String, Object> body,
            String prompt,
            List<String> skills,
            String script,
            boolean noAgent) {
        String explicit = string(body.get("name"), null);
        if (StrUtil.isNotBlank(explicit)) {
            return explicit;
        }
        String labelSource = CronJobSupport.normalizeBlank(prompt);
        if (StrUtil.isBlank(labelSource) && CollUtil.isNotEmpty(skills)) {
            labelSource = CronJobSupport.normalizeBlank(skills.get(0));
        }
        if (StrUtil.isBlank(labelSource) && noAgent) {
            labelSource = CronJobSupport.normalizeBlank(script);
        }
        if (StrUtil.isBlank(labelSource)) {
            labelSource = "cron job";
        }
        if (labelSource.length() > 50) {
            labelSource = labelSource.substring(0, 50);
        }
        return labelSource.trim();
    }

    /**
     * 执行normalized映射相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回normalized Map结果。
     */
    private Map<String, String> normalizedMap(Map<String, String> values) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (values == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = CronJobSupport.normalizeBlank(entry.getKey());
            String value = CronJobSupport.normalizeBlank(entry.getValue());
            if (StrUtil.isNotBlank(key) && StrUtil.isNotBlank(value)) {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * 执行normalized列表相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回normalized List结果。
     */
    private List<String> normalizedList(List<String> values) {
        List<String> result = new ArrayList<String>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            addString(result, value);
        }
        return result;
    }

    /**
     * 追加字符串。
     *
     * @param result 结果响应或执行结果。
     * @param value 待规范化或校验的原始值。
     */
    private void addString(List<String> result, Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (StrUtil.isNotBlank(text) && !result.contains(text)) {
            result.add(text);
        }
    }

    /**
     * 执行string相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param defaultValue 默认值参数。
     * @return 返回string结果。
     */
    private String string(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.length() == 0 ? defaultValue : text;
    }

    /**
     * 执行int值相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param defaultValue 默认值参数。
     * @return 返回int Value结果。
     */
    private int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    /**
     * 执行bool相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param defaultValue 默认值参数。
     * @return 返回bool结果。
     */
    private boolean bool(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
