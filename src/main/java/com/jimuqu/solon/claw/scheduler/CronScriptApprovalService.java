package com.jimuqu.solon.claw.scheduler;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 在 Cron 创建或修改阶段完成脚本版本安全检查和授权申请。 */
public class CronScriptApprovalService {
    /** 等待审批暂停原因前缀。 */
    private static final String APPROVAL_PAUSE_PREFIX = "waiting for approval:";

    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 危险命令审批服务。 */
    private final DangerousCommandApprovalService approvalService;

    /** 会话仓储，用于把审批记录绑定到可回复的持久化会话。 */
    private final SessionRepository sessionRepository;

    /** 创建 Cron 脚本授权服务。 */
    public CronScriptApprovalService(
            AppConfig appConfig,
            DangerousCommandApprovalService approvalService,
            SessionRepository sessionRepository) {
        this.appConfig = appConfig;
        this.approvalService = approvalService;
        this.sessionRepository = sessionRepository;
    }

    /**
     * 检查任务当前脚本版本；需要人工授权时直接把任务置为暂停并创建审批记录。
     *
     * @param job 待保存的 Cron 任务。
     */
    public void prepareForSave(CronJobRecord job) throws Exception {
        if (job == null || StrUtil.isBlank(job.getScript()) || approvalService == null) {
            return;
        }
        File script = resolveScript(job.getScript());
        String content = FileUtil.readString(script, StandardCharsets.UTF_8);
        String fingerprint = CronJobSupport.approvalFingerprint(job, content);
        if (fingerprint.equals(StrUtil.nullToEmpty(job.getApprovedScriptFingerprint()).trim())) {
            return;
        }
        String toolName = ruleToolName(script);
        String denyReason = approvalService.detectUserDenyReason(content);
        if (StrUtil.isNotBlank(denyReason)) {
            throw new IllegalStateException(
                    "BLOCKED (deny)：定时任务脚本 " + job.getScript() + " 命中 " + denyReason + "。");
        }
        DangerousCommandApprovalService.DetectionResult hardline =
                approvalService.detectHardline(toolName, content);
        if (hardline != null) {
            throw blocked("hardline", job, hardline);
        }
        DangerousCommandApprovalService.DetectionResult dangerous =
                approvalService.detect(toolName, content);
        if (dangerous == null) {
            job.setApprovedScriptFingerprint(fingerprint);
            return;
        }
        if (isLifecycleBlocked(dangerous)) {
            throw blocked("lifecycle", job, dangerous);
        }
        String mode = approvalService.guardrailCronMode();
        if ("strict".equals(mode)) {
            throw blocked("strict", job, dangerous);
        }
        if (!"approval".equals(mode)) {
            job.setApprovedScriptFingerprint(fingerprint);
            return;
        }
        requestApproval(job, content, fingerprint, toolName, dangerous);
    }

    /** 解析并校验 workspace/scripts 下的脚本文件。 */
    private File resolveScript(String value) throws Exception {
        File scriptsDir =
                FileUtil.file(appConfig.getRuntime().getHome(), "scripts").getCanonicalFile();
        File requested = new File(value);
        File script =
                (requested.isAbsolute() ? requested : new File(scriptsDir, value))
                        .getCanonicalFile();
        if (!CronJobSupport.isUnderDirectory(scriptsDir, script)
                || !script.exists()
                || !script.isFile()) {
            throw new IllegalStateException("定时任务脚本不在 workspace/scripts 下或文件不存在：" + value);
        }
        return script;
    }

    /** 根据扩展名返回与运行期一致的安全规则工具名。 */
    private String ruleToolName(File script) {
        String name = script.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".sh") || name.endsWith(".bash")
                ? ToolNameConstants.EXECUTE_SHELL
                : ToolNameConstants.EXECUTE_PYTHON;
    }

    /** 创建待审批记录并暂停当前任务。 */
    private void requestApproval(
            CronJobRecord job,
            String content,
            String fingerprint,
            String toolName,
            DangerousCommandApprovalService.DetectionResult dangerous)
            throws Exception {
        SqliteAgentSession session = resolveSession(job);
        List<String> approvalKeys = approvalKeys(job, fingerprint, dangerous);
        String scope = guardrailScope();
        for (String key : approvalKeys) {
            boolean approved =
                    approvalService.isAlwaysApproved(toolName, key, content)
                            || (!"global".equals(scope)
                                    && approvalService.isSessionApproved(
                                            session, toolName, key, content));
            if (!approved) {
                List<String> eventKeys = new ArrayList<String>(approvalKeys);
                String metadataKey = metadataKey(job, fingerprint, dangerous);
                if (!eventKeys.contains(metadataKey)) {
                    eventKeys.add(metadataKey);
                }
                approvalService.storePendingApproval(
                        session,
                        toolName,
                        approvalKeys.get(0),
                        eventKeys,
                        "定时任务 "
                                + StrUtil.blankToDefault(job.getName(), job.getJobId())
                                + " 的脚本需要创建或修改授权（scope="
                                + scope
                                + "）："
                                + StrUtil.blankToDefault(
                                        dangerous.getDescription(), dangerous.getPatternKey()),
                        "定时任务脚本 "
                                + StrUtil.blankToDefault(job.getScript(), "<inline>")
                                + "\n\n"
                                + content);
                job.setApprovedScriptFingerprint(null);
                job.setStatus("PAUSED");
                job.setPausedAt(System.currentTimeMillis());
                job.setPausedReason(
                        APPROVAL_PAUSE_PREFIX
                                + " "
                                + StrUtil.blankToDefault(
                                        dangerous.getDescription(), dangerous.getPatternKey()));
                return;
            }
        }
        job.setApprovedScriptFingerprint(fingerprint);
    }

    /** 获取或创建任务专属审批会话。 */
    private SqliteAgentSession resolveSession(CronJobRecord job) throws Exception {
        if (sessionRepository == null || StrUtil.isBlank(job.getJobId())) {
            throw new IllegalStateException("定时任务审批会话不可用");
        }
        String sourceKey = "CRON:" + job.getJobId().trim();
        SessionRecord record = sessionRepository.getBoundSession(sourceKey);
        if (record == null) {
            record = sessionRepository.bindNewSession(sourceKey);
        }
        return new SqliteAgentSession(record, sessionRepository);
    }

    /** 返回当前 Cron 审批记忆范围。 */
    private String guardrailScope() {
        if (appConfig == null || appConfig.getSecurity() == null) {
            return "job";
        }
        String scope =
                StrUtil.nullToEmpty(appConfig.getSecurity().getGuardrailCronScope())
                        .trim()
                        .toLowerCase(Locale.ROOT);
        return "global".equals(scope) || "session".equals(scope) ? scope : "job";
    }

    /** 生成实际参与授权匹配的规则键。 */
    private List<String> approvalKeys(
            CronJobRecord job,
            String fingerprint,
            DangerousCommandApprovalService.DetectionResult dangerous) {
        List<String> result = new ArrayList<String>();
        String scope = guardrailScope();
        for (String patternKey : dangerous.effectivePatternKeys()) {
            if (StrUtil.isBlank(patternKey)) {
                continue;
            }
            result.add(
                    "job".equals(scope)
                            ? cronKey(job, fingerprint, patternKey.trim())
                            : patternKey.trim());
        }
        if (result.isEmpty()) {
            result.add(
                    "job".equals(scope)
                            ? cronKey(job, fingerprint, "dangerous_command")
                            : "dangerous_command");
        }
        return result;
    }

    /** 生成供审批响应观察器识别任务版本的元数据键。 */
    private String metadataKey(
            CronJobRecord job,
            String fingerprint,
            DangerousCommandApprovalService.DetectionResult dangerous) {
        return cronKey(
                job,
                fingerprint,
                StrUtil.blankToDefault(dangerous.getPatternKey(), "dangerous_command"));
    }

    /** 生成任务级审批键。 */
    private String cronKey(CronJobRecord job, String fingerprint, String patternKey) {
        return "cron-job:"
                + StrUtil.blankToDefault(job.getJobId(), "unknown")
                + ":"
                + fingerprint
                + ":"
                + patternKey;
    }

    /** 判断规则是否涉及禁止由 Cron 控制的网关生命周期。 */
    private boolean isLifecycleBlocked(DangerousCommandApprovalService.DetectionResult dangerous) {
        if (dangerous == null) {
            return false;
        }
        for (String key : dangerous.effectivePatternKeys()) {
            if (isLifecyclePattern(key)) {
                return true;
            }
        }
        return false;
    }

    /** 判断单条危险规则是否属于禁止由 Cron 控制的进程生命周期操作。 */
    private boolean isLifecyclePattern(String key) {
        return "gateway_stop_restart".equals(key)
                || "app_update_restart".equals(key)
                || "gateway_run_detached".equals(key)
                || "kill_agent_process".equals(key)
                || "kill_pgrep_expansion".equals(key);
    }

    /** 构造不允许保存的脚本安全异常。 */
    private IllegalStateException blocked(
            String category,
            CronJobRecord job,
            DangerousCommandApprovalService.DetectionResult detection) {
        return new IllegalStateException(
                "BLOCKED ("
                        + category
                        + ")：定时任务脚本 "
                        + job.getScript()
                        + " 命中 "
                        + StrUtil.blankToDefault(
                                detection.getDescription(), detection.getPatternKey())
                        + "。");
    }
}
