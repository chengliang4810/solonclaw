package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.QueuedRunMessage;
import com.jimuqu.solon.claw.core.model.RunControlCommand;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.scheduler.DefaultCronScheduler;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class CommandEnhancementTest {
    @Test
    void shouldSupportResetAndPersonalityCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FakeLlmGateway fake = (FakeLlmGateway) env.llmGateway;
        bootstrapAdmin(env);

        GatewayReply listReply = env.send("admin-chat", "admin-user", "/personality");
        assertThat(listReply.getContent()).contains("helpful").contains("concise");

        GatewayReply setReply = env.send("admin-chat", "admin-user", "/personality helpful");
        assertThat(setReply.getContent()).contains("helpful");

        GatewayReply statusReply = env.send("admin-chat", "admin-user", "/status");
        assertThat(statusReply.getContent()).contains("personality=helpful");

        GatewayReply conversationReply = env.send("admin-chat", "admin-user", "人格测试");
        assertThat(conversationReply.getContent()).contains("echo:人格测试");
        assertThat(fake.lastSystemPrompt).contains("[Personality: helpful]");
        assertThat(fake.lastSystemPrompt).contains("You are a helpful assistant.");

        SessionRecord beforeReset =
                env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user");
        GatewayReply resetReply = env.send("admin-chat", "admin-user", "/reset");
        SessionRecord afterReset =
                env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user");

        assertThat(resetReply.getSessionId()).isNotEqualTo(beforeReset.getSessionId());
        assertThat(afterReset.getSessionId()).isEqualTo(resetReply.getSessionId());
    }

    @Test
    void shouldStopTrackedProcessesAndSupportRollbackListingAndIndexRestore() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        Process process = newSleepProcess();
        env.processRegistry.add(process);
        GatewayReply stopReply = env.send("admin-chat", "admin-user", "/stop");
        assertThat(stopReply.getContent()).contains("1");
        assertThat(env.processRegistry.runningCount()).isZero();

        String sourceKey = "MEMORY:admin-chat:admin-user";
        File file = FileUtil.file(env.appConfig.getRuntime().getCacheDir(), "rollback-command.txt");
        SessionRecord session = env.sessionRepository.bindNewSession(sourceKey);
        FileUtil.writeUtf8String("v1", file);
        env.checkpointService.createCheckpoint(
                sourceKey, session.getSessionId(), Collections.singletonList(file));
        FileUtil.writeUtf8String("v2", file);

        GatewayReply listReply = env.send("admin-chat", "admin-user", "/rollback");
        assertThat(listReply.getContent()).contains("1.").contains("created=");

        GatewayReply statusReply = env.send("admin-chat", "admin-user", "/rollback status");
        assertThat(statusReply.getContent()).contains("checkpoint_count=1").contains("total_size=");

        GatewayReply rollbackReply = env.send("admin-chat", "admin-user", "/rollback 1");
        assertThat(rollbackReply.getContent()).contains("checkpoint");
        assertThat(FileUtil.readUtf8String(file)).isEqualTo("v1");

        GatewayReply pruneReply = env.send("admin-chat", "admin-user", "/rollback prune");
        assertThat(pruneReply.getContent()).contains("deleted_missing=0").contains("remaining=1");

        GatewayReply clearWithoutConfirm = env.send("admin-chat", "admin-user", "/rollback clear");
        assertThat(clearWithoutConfirm.isError()).isTrue();
        assertThat(clearWithoutConfirm.getContent()).contains("/rollback clear --confirm");

        GatewayReply clearReply = env.send("admin-chat", "admin-user", "/rollback clear --confirm");
        assertThat(clearReply.getContent()).contains("deleted=1").contains("remaining=0");

        GatewayReply afterClear = env.send("admin-chat", "admin-user", "/rollback status");
        assertThat(afterClear.getContent()).contains("checkpoint_count=0");
    }

    @Test
    void shouldSupportBusyPolicyCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        GatewayReply status = env.send("admin-chat", "admin-user", "/busy");
        assertThat(status.getContent()).contains("busy_policy=interrupt").contains("source_running=false");

        GatewayReply steer = env.send("admin-chat", "admin-user", "/busy steer");
        assertThat(steer.getContent()).contains("已切换运行中输入策略为 steer");
        assertThat(env.appConfig.getTask().getBusyPolicy()).isEqualTo("steer");
        assertThat(RuntimeConfigResolver.initialize(env.appConfig.getRuntime().getHome())
                        .get("solonclaw.task.busyPolicy"))
                .isEqualTo("steer");

        GatewayReply after = env.send("admin-chat", "admin-user", "/busy status");
        assertThat(after.getContent()).contains("busy_policy=steer").contains("steer：");

        GatewayReply invalid = env.send("admin-chat", "admin-user", "/busy drop");
        assertThat(invalid.isError()).isTrue();
        assertThat(invalid.getContent()).contains("/busy [status|queue|steer|interrupt|reject]");

        GatewayReply help = env.send("admin-chat", "admin-user", "/help");
        assertThat(help.getContent()).contains("/busy [status|queue|steer|interrupt|reject]");
    }

    @Test
    void shouldSupportExplicitQueueAndSteerCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        String sourceKey = "MEMORY:admin-chat:admin-user";
        SessionRecord session = env.sessionRepository.getBoundSession(sourceKey);
        if (session == null) {
            session = env.sessionRepository.bindNewSession(sourceKey);
        }

        GatewayReply queued = env.send("admin-chat", "admin-user", "/queue run tests next");

        assertThat(queued.getContent()).contains("队列");
        assertThat(queued.getRuntimeMetadata()).containsKey("queue_id");
        QueuedRunMessage queuedMessage =
                env.agentRunRepository.findNextQueuedMessage(sourceKey, session.getSessionId());
        assertThat(queuedMessage).isNotNull();
        assertThat(queuedMessage.getMessageText()).isEqualTo("run tests next");
        assertThat(env.sessionRepository.getBoundSession(sourceKey).getNdjson())
                .doesNotContain("run tests next");

        GatewayReply idleSteer = env.send("admin-chat", "admin-user", "/steer summarize README");

        assertThat(idleSteer.getContent()).contains("echo:summarize README");
        assertThat(env.sessionRepository.getBoundSession(sourceKey).getNdjson())
                .contains("summarize README");
        GatewayReply help = env.send("admin-chat", "admin-user", "/help");
        assertThat(help.getContent()).contains("/queue <prompt>").contains("/steer <prompt>");
    }

    @Test
    void shouldInjectExplicitSteerIntoRunningAgentWithoutInterrupting() throws Exception {
        SteerAwareSlowLlmGateway slowLlmGateway = new SteerAwareSlowLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(slowLlmGateway);
        bootstrapAdmin(env);

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<GatewayReply> running =
                executorService.submit(() -> env.send("admin-chat", "admin-user", "执行一个长任务"));

        assertThat(slowLlmGateway.started.await(2, TimeUnit.SECONDS)).isTrue();
        GatewayReply steer = env.send("admin-chat", "admin-user", "/steer prefer simpler fix");

        assertThat(steer.getContent()).contains("steer").contains("注入");
        assertThat(steer.getRuntimeMetadata()).containsEntry("busy_status", "steered");
        String runId = String.valueOf(steer.getRuntimeMetadata().get("run_id"));
        RunControlCommand pending =
                env.agentRunRepository.findLatestPendingCommand(runId, "steer");
        assertThat(pending).isNotNull();
        assertThat(pending.getPayloadJson()).contains("prefer simpler fix");
        assertThat(slowLlmGateway.interrupted).isFalse();

        env.send("admin-chat", "admin-user", "/stop");
        running.get(3, TimeUnit.SECONDS);
        executorService.shutdownNow();
    }

    @Test
    void shouldShowResumeRecapUnlessDisplayIsMinimal() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        env.send("admin-chat", "admin-user", "第一轮问题");
        SessionRecord original =
                env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user");
        original.setTitle("恢复测试");
        env.sessionRepository.save(original);
        env.send("admin-chat", "admin-user", "/new");

        GatewayReply full = env.send("admin-chat", "admin-user", "/resume " + original.getSessionId());
        assertThat(full.getContent())
                .contains("已恢复会话")
                .contains("恢复测试")
                .contains("历史摘要")
                .contains("第一轮问题")
                .contains("echo:第一轮问题");

        env.appConfig.getDisplay().setResumeDisplay("minimal");
        env.send("admin-chat", "admin-user", "/new");
        GatewayReply minimal =
                env.send("admin-chat", "admin-user", "/resume " + original.getSessionId());
        assertThat(minimal.getContent())
                .contains("已恢复会话")
                .doesNotContain("历史摘要")
                .doesNotContain("echo:第一轮问题");
    }

    @Test
    void shouldSupportGoalCommandLifecycle() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        GatewayMessage message =
                new GatewayMessage(PlatformType.MEMORY, "goal-chat", "goal-user", "/goal 完成一次端到端验证 --max 4");

        GatewayReply set = env.commandService.handle(message, "/goal 完成一次端到端验证 --max 4");
        assertThat(set.getContent()).contains("Goal set").contains("完成一次端到端验证");

        GatewayReply status = env.commandService.handle(message, "/goal status");
        assertThat(status.getContent()).contains("active").contains("0/4");

        GatewayReply pause = env.commandService.handle(message, "/goal pause");
        assertThat(pause.getContent()).contains("Goal paused");

        GatewayReply resume = env.commandService.handle(message, "/goal resume");
        assertThat(resume.getContent()).contains("Goal resumed").contains("Continuing toward your standing goal");

        GatewayReply clear = env.commandService.handle(message, "/goal clear");
        assertThat(clear.getContent()).contains("Goal cleared");
    }

    @Test
    void shouldSupportHermesCronFlagSyntaxAndSkillEditing() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        GatewayReply created =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron add \"every 2h\" \"Check server status\" --skill blogwatcher --skill maps --model gpt-test-cron --provider default --base-url https://api.example.com/ --no-wrap-response");
        assertThat(created.getContent()).contains("已创建定时任务");
        String jobId = env.cronJobRepository.listBySource("MEMORY:admin-chat:admin-user")
                .get(0)
                .getJobId();
        assertThat(cronJobView(env, jobId))
                .contains("blogwatcher")
                .contains("maps")
                .contains("model=gpt-test-cron")
                .contains("provider=default")
                .contains("base_url=https://api.example.com")
                .contains("wrap_response=false");

        GatewayReply edited =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron edit "
                                + jobId
                                + " --schedule \"every 4h\" --prompt \"New task\" --remove-skill maps --add-skill reports");
        assertThat(edited.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId))
                .contains("every 4h")
                .contains("New task")
                .contains("blogwatcher")
                .contains("reports")
                .doesNotContain("maps");

        GatewayReply cleared = env.send("admin-chat", "admin-user", "/cron edit " + jobId + " --clear-skills");
        assertThat(cleared.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId)).contains("skills=[]");

        String runtimeHome = env.appConfig.getRuntime().getHome().replace('\\', '/');
        GatewayReply tuned =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron edit "
                                + jobId
                                + " --script collect.py --workdir \""
                                + runtimeHome
                                + "\" --context-from "
                                + jobId
                                + " --toolsets web,terminal");
        assertThat(tuned.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId))
                .contains("script=collect.py")
                .contains("workdir=" + runtimeHome)
                .contains("context_from=[" + jobId + "]")
                .contains("enabled_toolsets=[web, terminal]");

        GatewayReply noAgent = env.send("admin-chat", "admin-user", "/cron edit " + jobId + " --no-agent --wrap-response");
        assertThat(noAgent.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId)).contains("no_agent=true").contains("wrap_response=true");

        GatewayReply agent = env.send("admin-chat", "admin-user", "/cron edit " + jobId + " --agent");
        assertThat(agent.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId)).contains("no_agent=false");

        GatewayReply clearedRuntime =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron edit "
                                + jobId
                                + " --clear-script --clear-workdir --clear-context-from --clear-toolsets");
        assertThat(clearedRuntime.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId))
                .contains("script=null")
                .contains("workdir=null")
                .contains("context_from=[]")
                .contains("enabled_toolsets=[]");

        GatewayReply invalidNoAgent = env.send("admin-chat", "admin-user", "/cron edit " + jobId + " --no-agent");
        assertThat(invalidNoAgent.getContent()).contains("no_agent requires script");

        GatewayReply help = env.send("admin-chat", "admin-user", "/help");
        assertThat(help.getContent()).contains("/cron [list [--all]|add|edit|pause|resume|remove|run|history]");
    }

    @Test
    void shouldMatchHermesCronListAllSemantics() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        env.send("admin-chat", "admin-user", "/cron add \"every 2h\" \"Check server status\" --skill blogwatcher");
        String jobId = env.cronJobRepository.listBySource("MEMORY:admin-chat:admin-user")
                .get(0)
                .getJobId();
        GatewayReply activeList = env.send("admin-chat", "admin-user", "/cron list");
        assertThat(activeList.getContent())
                .contains("Scheduled Jobs:")
                .contains("ID: " + jobId)
                .contains("State: scheduled")
                .contains("Skills: blogwatcher")
                .contains("Prompt: Check server status");

        GatewayReply paused = env.send("admin-chat", "admin-user", "/cron pause " + jobId);
        assertThat(paused.getContent()).contains("已暂停定时任务");

        GatewayReply defaultList = env.send("admin-chat", "admin-user", "/cron list");
        assertThat(defaultList.getContent()).contains("当前没有定时任务。").doesNotContain(jobId);

        GatewayReply allList = env.send("admin-chat", "admin-user", "/cron list --all");
        assertThat(allList.getContent())
                .contains("ID: " + jobId)
                .contains("State: paused")
                .contains("Schedule: every 2h");

        GatewayReply overview = env.send("admin-chat", "admin-user", "/cron");
        assertThat(overview.getContent())
                .contains("Cron 定时任务")
                .contains("/cron list --all")
                .contains("/cron history <job-id>")
                .contains("当前没有定时任务。");
    }

    @Test
    void shouldShowCronHistoryCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        env.send("admin-chat", "admin-user", "/cron add \"30m\" \"History check\"");
        String jobId =
                env.cronJobRepository
                        .listBySource("MEMORY:admin-chat:admin-user")
                        .get(0)
                        .getJobId();
        com.jimuqu.solon.claw.core.model.CronJobRecord job = env.cronJobRepository.findById(jobId);
        job.setNextRunAt(System.currentTimeMillis() - 1000L);
        env.cronJobRepository.update(job);
        new DefaultCronScheduler(
                        env.appConfig,
                        env.cronJobRepository,
                        new CronJobService(env.appConfig, env.cronJobRepository),
                        env.conversationOrchestrator,
                        env.deliveryService,
                        env.gatewayPolicyRepository)
                .tick();

        GatewayReply history =
                env.send("admin-chat", "admin-user", "/cron history " + jobId + " --limit 5");
        assertThat(history.getContent())
                .contains("Cron 执行历史：" + jobId)
                .contains("Status: ok")
                .contains("trigger=scheduled")
                .contains("Output: echo:History check");
    }

    @Test
    void shouldSupportReloadMcpCommandWithConfirmationTextAndToolBaseline() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        DashboardMcpService mcpService = new DashboardMcpService(env.appConfig, env.sqliteDatabase);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("serverId", "local-docs");
        body.put("name", "Local Docs");
        body.put("transport", "stdio");
        body.put("command", "docs-mcp");
        body.put("tools", Collections.singletonList(Collections.singletonMap("name", "docs_search")));
        mcpService.save(body);

        GatewayReply prompt = env.send("admin-chat", "admin-user", "/reload-mcp");
        assertThat(prompt.getContent())
                .contains("/approve")
                .contains("/always")
                .contains("/cancel")
                .contains("工具 schema");

        GatewayReply reloaded = env.send("admin-chat", "admin-user", "/reload-mcp now");
        assertThat(reloaded.getContent())
                .contains("MCP reload completed")
                .contains("tools=1")
                .contains("changed_servers=[]")
                .contains("unchanged_servers=[local-docs]");

        assertThat(mcpService.check("local-docs").get("tool_changed_notification")).isEqualTo(false);

        Map<String, Object> updated = new LinkedHashMap<String, Object>(body);
        updated.put(
                "tools",
                java.util.Arrays.asList(
                        Collections.singletonMap("name", "docs_search"),
                        Collections.singletonMap("name", "docs_fetch")));
        mcpService.save(updated);
        assertThat(mcpService.check("local-docs").get("tool_changed_notification")).isEqualTo(true);

        GatewayReply help = env.send("admin-chat", "admin-user", "/help");
        assertThat(help.getContent()).contains("/reload-mcp [now|always]");
    }

    @Test
    void shouldResolveReloadMcpWithSlashConfirmFallbacks() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        DashboardMcpService mcpService = new DashboardMcpService(env.appConfig, env.sqliteDatabase);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("serverId", "slash-confirm-docs");
        body.put("name", "Slash Confirm Docs");
        body.put("transport", "stdio");
        body.put("command", "docs-mcp");
        body.put("tools", Collections.singletonList(Collections.singletonMap("name", "docs_search")));
        mcpService.save(body);

        GatewayReply prompt = env.send("admin-chat", "admin-user", "/reload-mcp");
        assertThat(prompt.getContent()).contains("确认编号");

        GatewayReply cancelled = env.send("admin-chat", "admin-user", "/cancel");
        assertThat(cancelled.getContent()).contains("已取消 /reload-mcp");

        GatewayReply promptAgain = env.send("admin-chat", "admin-user", "/reload-mcp");
        assertThat(promptAgain.getContent()).contains("/approve");

        GatewayReply approvedWithAlias = env.send("admin-chat", "admin-user", "/approve yes");
        assertThat(approvedWithAlias.getContent())
                .contains("MCP reload completed")
                .contains("tools=1");

        GatewayReply okPrompt = env.send("admin-chat", "admin-user", "/reload-mcp");
        assertThat(okPrompt.getContent()).contains("确认编号");
        GatewayReply approvedWithOk = env.send("admin-chat", "admin-user", "/approve ok");
        assertThat(approvedWithOk.getContent()).contains("MCP reload completed");

        GatewayReply confirmPrompt = env.send("admin-chat", "admin-user", "/reload-mcp");
        assertThat(confirmPrompt.getContent()).contains("确认编号");
        GatewayReply approvedWithConfirm = env.send("admin-chat", "admin-user", "/approve confirm");
        assertThat(approvedWithConfirm.getContent()).contains("MCP reload completed");

        GatewayReply alwaysPrompt = env.send("admin-chat", "admin-user", "/reload-mcp");
        assertThat(alwaysPrompt.getContent()).contains("/always");

        GatewayReply always = env.send("admin-chat", "admin-user", "/always");
        assertThat(always.getContent()).contains("已永久确认 /reload-mcp");
        assertThat(env.appConfig.getApprovals().isMcpReloadConfirm()).isFalse();
        assertThat(RuntimeConfigResolver.initialize(env.appConfig.getRuntime().getHome())
                        .get("solonclaw.approvals.mcpReloadConfirm"))
                .isEqualTo("false");

        GatewayReply direct = env.send("admin-chat", "admin-user", "/reload-mcp");
        assertThat(direct.getContent())
                .contains("MCP reload completed")
                .doesNotContain("确认编号");
    }

    @Test
    void shouldSupersedePendingReloadMcpSlashConfirm() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        DashboardMcpService mcpService = new DashboardMcpService(env.appConfig, env.sqliteDatabase);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("serverId", "slash-confirm-supersede");
        body.put("name", "Slash Confirm Supersede");
        body.put("transport", "stdio");
        body.put("command", "docs-mcp");
        body.put("tools", Collections.singletonList(Collections.singletonMap("name", "docs_search")));
        mcpService.save(body);

        GatewayReply first = env.send("admin-chat", "admin-user", "/reload-mcp");
        GatewayReply second = env.send("admin-chat", "admin-user", "/reload-mcp");

        assertThat(first.getContent()).contains("确认编号");
        assertThat(second.getContent()).contains("确认编号");
        assertThat(second.getContent()).isNotEqualTo(first.getContent());

        GatewayReply cancelled = env.send("admin-chat", "admin-user", "/cancel");
        assertThat(cancelled.getContent()).contains("已取消 /reload-mcp");

        GatewayReply stale = env.send("admin-chat", "admin-user", "/cancel");
        assertThat(stale.getContent()).contains("当前没有待确认的 slash 命令");
    }

    @Test
    void shouldPrioritizeDangerousApprovalOverSlashConfirmFallback() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        DashboardMcpService mcpService = new DashboardMcpService(env.appConfig, env.sqliteDatabase);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("serverId", "slash-confirm-priority");
        body.put("name", "Slash Confirm Priority");
        body.put("transport", "stdio");
        body.put("command", "docs-mcp");
        body.put("tools", Collections.singletonList(Collections.singletonMap("name", "docs_search")));
        mcpService.save(body);

        GatewayReply prompt = env.send("admin-chat", "admin-user", "/reload-mcp");
        assertThat(prompt.getContent()).contains("确认编号");

        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:admin-chat:admin-user");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "git_reset_hard",
                "git reset --hard (destroys uncommitted changes)",
                "git reset --hard origin/main");

        GatewayReply approved = env.send("admin-chat", "admin-user", "/approve session");
        SessionRecord updated =
                env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user");
        SqliteAgentSession updatedAgentSession =
                new SqliteAgentSession(updated, env.sessionRepository);

        assertThat(approved.getContent()).isEqualTo("echo:resume");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(updatedAgentSession))
                .isNull();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession, "git_reset_hard"))
                .isTrue();

        GatewayReply stillPendingSlashConfirm = env.send("admin-chat", "admin-user", "/cancel");
        assertThat(stillPendingSlashConfirm.getContent()).contains("已取消 /reload-mcp");
    }

    @Test
    void shouldSkipReloadMcpPromptWhenConfigDisablesConfirm() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        DashboardMcpService mcpService = new DashboardMcpService(env.appConfig, env.sqliteDatabase);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("serverId", "no-confirm-docs");
        body.put("name", "No Confirm Docs");
        body.put("transport", "stdio");
        body.put("command", "docs-mcp");
        body.put("tools", Collections.singletonList(Collections.singletonMap("name", "docs_search")));
        mcpService.save(body);
        env.appConfig.getApprovals().setMcpReloadConfirm(false);

        GatewayReply direct = env.send("admin-chat", "admin-user", "/reload-mcp");

        assertThat(direct.getContent())
                .contains("MCP reload completed")
                .contains("tools=1")
                .doesNotContain("确认编号");
    }

    private void bootstrapAdmin(TestEnvironment env) throws Exception {
        env.send("admin-chat", "admin-user", "hello");
        env.send("admin-chat", "admin-user", "/pairing claim-admin");
    }

    private static class SteerAwareSlowLlmGateway implements LlmGateway {
        private final CountDownLatch started = new CountDownLatch(1);
        private volatile boolean interrupted;

        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects)
                throws Exception {
            started.countDown();
            try {
                while (true) {
                    Thread.sleep(1000L);
                }
            } catch (InterruptedException e) {
                interrupted = true;
                throw e;
            }
        }

        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects)
                throws Exception {
            return chat(session, systemPrompt, null, toolObjects);
        }
    }

    private String cronJobView(TestEnvironment env, String jobId) throws Exception {
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        return service.toView(env.cronJobRepository.findById(jobId)).toString();
    }

    private Process newSleepProcess() throws Exception {
        return new ProcessBuilder(
                        System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                        "-cp",
                        System.getProperty("java.class.path"),
                        SleepProcess.class.getName())
                .start();
    }

    public static class SleepProcess {
        public static void main(String[] args) throws Exception {
            Thread.sleep(30000L);
        }
    }
}
