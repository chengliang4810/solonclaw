package com.jimuqu.solon.claw.scheduler;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.support.CronSupport;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.noear.solon.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** DefaultCronScheduler 实现。 */
@RequiredArgsConstructor
public class DefaultCronScheduler {
    private static final Logger log = LoggerFactory.getLogger(DefaultCronScheduler.class);

    private final AppConfig appConfig;
    private final CronJobRepository cronJobRepository;
    private final CronJobService cronJobService;
    private final ConversationOrchestrator conversationOrchestrator;
    private final DeliveryService deliveryService;
    private final GatewayPolicyRepository gatewayPolicyRepository;
    private ScheduledExecutorService executorService;

    public void start() {
        if (!appConfig.getScheduler().isEnabled()) {
            return;
        }
        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleWithFixedDelay(
                this::tickSafe, 5, appConfig.getScheduler().getTickSeconds(), TimeUnit.SECONDS);
    }

    public void stop() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    public void shutdown() {
        stop();
    }

    public void tickSafe() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("Cron tick failed", e);
        }
    }

    public void tick() throws Exception {
        File lockFile = new File(appConfig.getRuntime().getHome(), "jobs/cron.tick.lock");
        FileUtil.mkParentDirs(lockFile);
        FileOutputStream outputStream = new FileOutputStream(lockFile, true);
        FileChannel channel = outputStream.getChannel();
        FileLock lock = channel.tryLock();
        if (lock == null) {
            channel.close();
            outputStream.close();
            return;
        }
        try {
            tickLocked();
        } finally {
            lock.release();
            channel.close();
            outputStream.close();
        }
    }

    private void tickLocked() throws Exception {
        long now = System.currentTimeMillis();
        List<CronJobRecord> jobs = cronJobRepository.listDue(now);
        Map<String, List<CronJobRecord>> grouped = groupBySource(jobs);
        if (grouped.isEmpty()) {
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, grouped.size()));
        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (final List<CronJobRecord> sourceJobs : grouped.values()) {
            futures.add(
                    executor.submit(
                            new Runnable() {
                                @Override
                                public void run() {
                                    for (CronJobRecord job : sourceJobs) {
                                        try {
                                            execute(job, System.currentTimeMillis());
                                        } catch (Exception e) {
                                            log.warn(
                                                    "Cron job failed: jobId={}, sourceKey={}",
                                                    job.getJobId(),
                                                    job.getSourceKey(),
                                                    e);
                                        }
                                    }
                                }
                            }));
        }
        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdown();
    }

    public void runNow(String jobId) throws Exception {
        CronJobRecord job = cronJobRepository.findById(jobId);
        if (job != null) {
            execute(job, System.currentTimeMillis());
        }
    }

    private void execute(CronJobRecord job, long now) throws Exception {
        long nextRunAt = CronSupport.nextRunAt(job.getCronExpr(), now);
        int completed = job.getRepeatCompleted() + 1;
        boolean done = job.getRepeatTimes() > 0 && completed >= job.getRepeatTimes();
        String nextStatus = done || CronSupport.isOneShot(job.getCronExpr()) ? "COMPLETED" : "ACTIVE";
        String output = "";
        String error = null;
        String runStatus = "ok";
        try {
            GatewayReply reply;
            if (job.isNoAgent()) {
                output = runScript(job);
                reply = GatewayReply.ok(output);
            } else {
                String prompt = buildPrompt(job);
                if (StrUtil.isNotBlank(job.getScript())) {
                    String scriptOutput = runScript(job);
                    prompt = prompt + "\n\n脚本输出：\n" + scriptOutput;
                }
                String[] parts = SourceKeySupport.split(job.getSourceKey());
                GatewayMessage synthetic =
                        new GatewayMessage(
                                PlatformType.fromName(parts[0]), parts[1], parts[2], prompt);
                reply = conversationOrchestrator.runScheduled(synthetic);
                output = reply == null ? "" : reply.getContent();
            }
            cronJobRepository.markRunResult(
                    job.getJobId(),
                    now,
                    nextRunAt,
                    runStatus,
                    null,
                    AgentRunPreview.safe(output),
                    completed,
                    nextStatus);
            deliver(job, reply);
        } catch (Exception e) {
            runStatus = "error";
            error = e.getMessage();
            cronJobRepository.markRunResult(
                    job.getJobId(),
                    now,
                    nextRunAt,
                    runStatus,
                    error,
                    AgentRunPreview.safe(output),
                    completed,
                    done ? "COMPLETED" : "ACTIVE");
            deliverError(job, error);
            throw e;
        }
    }

    private void deliver(CronJobRecord job, GatewayReply reply) throws Exception {
        if (reply == null || StrUtil.isBlank(reply.getContent())) {
            return;
        }
        String platformName =
                Utils.isNotEmpty(job.getDeliverPlatform()) ? job.getDeliverPlatform() : "local";
        String chatId = job.getDeliverChatId();
        if ("local".equalsIgnoreCase(platformName)) {
            String[] parts = SourceKeySupport.split(job.getSourceKey());
            platformName = parts[0];
            HomeChannelRecord home =
                    gatewayPolicyRepository.getHomeChannel(PlatformType.fromName(platformName));
            if (home == null || StrUtil.isBlank(home.getChatId())) {
                return;
            }
            chatId = home.getChatId();
        }

        PlatformType platform = PlatformType.fromName(platformName);
        if (platform == null) {
            log.warn(
                    "Cron deliver skipped because platform is unknown: jobId={}, platform={}",
                    job.getJobId(),
                    platformName);
            return;
        }
        if (StrUtil.isBlank(chatId)) {
            HomeChannelRecord home = gatewayPolicyRepository.getHomeChannel(platform);
            if (home == null || StrUtil.isBlank(home.getChatId())) {
                return;
            }
            chatId = home.getChatId();
        }
        DeliveryRequest request = new DeliveryRequest();
        request.setPlatform(platform);
        request.setChatId(chatId);
        request.setText(formatDelivery(job, reply.getContent()));
        try {
            deliveryService.deliver(request);
        } catch (Exception e) {
            cronJobRepository.markDeliveryError(job.getJobId(), e.getMessage());
            throw e;
        }
    }

    private String buildPrompt(CronJobRecord job) throws Exception {
        StringBuilder prompt = new StringBuilder(StrUtil.nullToEmpty(job.getPrompt()));
        Map<String, Object> view = cronJobService.toView(job);
        List<String> skills =
                view.containsKey("skills") ? (List<String>) view.get("skills") : new ArrayList<String>();
        if (!skills.isEmpty()) {
            prompt.insert(0, "请先加载并遵循这些技能：" + skills + "\n\n");
        }
        Object contextFrom = view.get("context_from");
        if (contextFrom instanceof Iterable) {
            for (Object ref : (Iterable<?>) contextFrom) {
                CronJobRecord upstream = cronJobRepository.findById(String.valueOf(ref));
                if (upstream != null && StrUtil.isNotBlank(upstream.getLastOutput())) {
                    prompt.append("\n\n上游任务 ")
                            .append(upstream.getJobId())
                            .append(" 最近输出：\n")
                            .append(upstream.getLastOutput());
                }
            }
        }
        return prompt.toString();
    }

    private String runScript(CronJobRecord job) throws Exception {
        File scriptsDir = FileUtil.file(appConfig.getRuntime().getHome(), "scripts");
        File script = FileUtil.file(scriptsDir, job.getScript());
        if (!FileUtil.isSub(scriptsDir, script) || !script.exists() || !script.isFile()) {
            throw new IllegalStateException("Cron script not found under runtime/scripts: " + job.getScript());
        }
        String name = script.getName().toLowerCase();
        List<String> command = new ArrayList<String>();
        if (name.endsWith(".sh") || name.endsWith(".bash")) {
            command.add("bash");
        } else {
            command.add(defaultPythonCommand());
        }
        command.add(script.getAbsolutePath());
        ProcessBuilder builder = new ProcessBuilder(command);
        if (StrUtil.isNotBlank(job.getWorkdir())) {
            builder.directory(new File(job.getWorkdir()));
        }
        builder.redirectErrorStream(true);
        Process process = builder.start();
        byte[] data = readAll(process.getInputStream());
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Cron script timed out");
        }
        String output = new String(data, StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Cron script exited " + process.exitValue() + ": " + output);
        }
        return output;
    }

    private void deliverError(CronJobRecord job, String error) {
        if (!job.isNoAgent()) {
            return;
        }
        try {
            deliver(job, GatewayReply.error("定时任务执行失败：" + StrUtil.blankToDefault(error, "unknown error")));
        } catch (Exception ignored) {
        }
    }

    private byte[] readAll(java.io.InputStream inputStream) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String defaultPythonCommand() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "python" : "python3";
    }

    private String formatDelivery(CronJobRecord job, String content) {
        if (!job.isWrapResponse()) {
            return content;
        }
        return "定时任务：" + StrUtil.blankToDefault(job.getName(), job.getJobId()) + "\n\n" + content;
    }

    private Map<String, List<CronJobRecord>> groupBySource(List<CronJobRecord> jobs) {
        Map<String, List<CronJobRecord>> grouped = new LinkedHashMap<String, List<CronJobRecord>>();
        for (CronJobRecord job : jobs) {
            String sourceKey = StrUtil.blankToDefault(job.getSourceKey(), "__default__");
            List<CronJobRecord> sourceJobs = grouped.get(sourceKey);
            if (sourceJobs == null) {
                sourceJobs = new ArrayList<CronJobRecord>();
                grouped.put(sourceKey, sourceJobs);
            }
            sourceJobs.add(job);
        }
        return grouped;
    }

    private static class AgentRunPreview {
        private static String safe(String value) {
            if (value == null) {
                return null;
            }
            return value.length() <= 4000 ? value : value.substring(0, 4000) + "...";
        }
    }
}
