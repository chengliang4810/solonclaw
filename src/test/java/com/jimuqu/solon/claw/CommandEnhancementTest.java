package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.QueuedRunMessage;
import com.jimuqu.solon.claw.core.model.RunControlCommand;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.engine.AgentRunSupervisor;
import com.jimuqu.solon.claw.goal.GoalService;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.scheduler.DefaultCronScheduler;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.BlockingLlmGateway;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import com.jimuqu.solon.claw.web.DashboardRunService;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;

public class CommandEnhancementTest {
    private static final Pattern SLASH_CONFIRM_ID = Pattern.compile("确认编号：([0-9a-fA-F]{32})");

    @Test
    void shouldSupportResetCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        env.sessionRepository.bindNewSession("MEMORY:admin-chat:admin-user");
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
        session.setSessionId("session-ghp_commandrollbacksecret");
        FileUtil.writeUtf8String("v1", file);
        env.checkpointService.createCheckpoint(
                sourceKey, session.getSessionId(), Collections.singletonList(file));
        FileUtil.writeUtf8String("v2", file);

        GatewayReply listReply = env.send("admin-chat", "admin-user", "/rollback");
        assertThat(listReply.getContent()).contains("1.").contains("created=");
        assertThat(listReply.getContent())
                .contains("session=session-ghp_***")
                .doesNotContain("ghp_commandrollbacksecret");

        GatewayReply explicitListReply = env.send("admin-chat", "admin-user", "/rollback list");
        assertThat(explicitListReply.isError()).isFalse();
        assertThat(explicitListReply.getContent())
                .contains("1.")
                .contains("created=")
                .doesNotContain("ghp_commandrollbacksecret");

        GatewayReply statusReply = env.send("admin-chat", "admin-user", "/rollback status");
        assertThat(statusReply.getContent()).contains("checkpoint_count=1").contains("total_size=");

        SessionRecord bound = env.sessionRepository.getBoundSession(sourceKey);
        bound.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(ChatMessage.ofUser("change"), new AssistantMessage("done"))));
        env.sessionRepository.save(bound);

        GatewayReply rollbackReply = env.send("admin-chat", "admin-user", "/rollback 1");
        assertThat(rollbackReply.getContent()).contains("checkpoint");
        assertThat(rollbackReply.getRuntimeMetadata()).containsEntry("history_removed", 2);
        assertThat(FileUtil.readUtf8String(file)).isEqualTo("v1");
        assertThat(
                        MessageSupport.countMessages(
                                env.sessionRepository.findById(bound.getSessionId()).getNdjson()))
                .isZero();

        GatewayReply pruneReply = env.send("admin-chat", "admin-user", "/rollback prune");
        assertThat(pruneReply.getContent()).contains("deleted_missing=0").contains("remaining=2");

        GatewayReply clearWithoutConfirm = env.send("admin-chat", "admin-user", "/rollback clear");
        assertThat(clearWithoutConfirm.isError()).isFalse();
        assertThat(clearWithoutConfirm.getContent())
                .contains("/rollback clear")
                .contains("确认编号")
                .contains("/approve [确认编号] 执行一次")
                .doesNotContain("永久记住");

        GatewayReply clearDenied = env.send("admin-chat", "admin-user", "/deny");
        assertThat(clearDenied.getContent()).contains("已取消 /rollback");

        GatewayReply clearAlwaysPrompt = env.send("admin-chat", "admin-user", "/rollback clear");
        assertThat(clearAlwaysPrompt.getContent()).contains("确认编号");
        GatewayReply clearAlways = env.send("admin-chat", "admin-user", "/approve always");
        assertThat(clearAlways.isError()).isTrue();
        assertThat(clearAlways.getContent()).contains("不支持永久确认");

        GatewayReply staleId =
                env.send("admin-chat", "admin-user", "/approve 00000000000000000000000000000000");
        assertThat(staleId.isError()).isTrue();
        assertThat(staleId.getContent()).contains("确认编号不匹配");

        GatewayReply clearOnce =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/approve " + extractSlashConfirmId(clearAlwaysPrompt));
        assertThat(clearOnce.getContent()).contains("deleted=2").contains("remaining=0");

        env.checkpointService.createCheckpoint(
                sourceKey, session.getSessionId(), Collections.singletonList(file));
        GatewayReply confirmedClearReply =
                env.send("admin-chat", "admin-user", "/rollback clear --confirm");
        assertThat(confirmedClearReply.getContent()).contains("deleted=1").contains("remaining=0");

        GatewayReply afterClear = env.send("admin-chat", "admin-user", "/rollback status");
        assertThat(afterClear.getContent()).contains("checkpoint_count=0");
    }

    @Test
    void shouldSupportBusyPolicyCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        GatewayReply status = env.send("admin-chat", "admin-user", "/busy");
        assertThat(status.getContent())
                .contains("busy_policy=interrupt")
                .contains("source_running=false")
                .contains("active_run_id=-")
                .contains("queue_pending=0")
                .contains("current_policy=interrupt：")
                .contains("policy_options:")
                .contains("queue：")
                .contains("steer：")
                .contains("interrupt：")
                .contains("reject：");

        GatewayReply steer = env.send("admin-chat", "admin-user", "/busy steer");
        assertThat(steer.getContent()).contains("已切换运行中输入策略为 steer");
        assertThat(env.appConfig.getTask().getBusyPolicy()).isEqualTo("steer");
        assertThat(
                        RuntimeConfigResolver.initialize(env.appConfig.getRuntime().getHome())
                                .get("solonclaw.task.busyPolicy"))
                .isEqualTo("steer");

        GatewayReply after = env.send("admin-chat", "admin-user", "/busy status");
        assertThat(after.getContent())
                .contains("busy_policy=steer")
                .contains("current_policy=steer：")
                .contains("policy_options:")
                .contains("interrupt：")
                .contains("reject：");

        GatewayReply invalid = env.send("admin-chat", "admin-user", "/busy drop");
        assertThat(invalid.isError()).isTrue();
        assertThat(invalid.getContent()).contains("/busy [status|queue|steer|interrupt|reject]");

        GatewayReply help = env.send("admin-chat", "admin-user", "/help");
        assertThat(help.getContent()).contains("/busy [status|queue|steer|interrupt|reject]");
    }

    /** 运行中禁止 retry、undo、branch 与完整回滚，但只读 rollback 子命令仍可使用。 */
    @Test
    void shouldGuardDestructiveSessionCommandsWhileRunIsActive() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        String sourceKey = "MEMORY:admin-chat:admin-user";
        SessionRecord session = env.sessionRepository.bindNewSession(sourceKey);
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("before"), new AssistantMessage("reply"))));
        env.sessionRepository.save(session);
        File file = FileUtil.file(env.appConfig.getRuntime().getCacheDir(), "busy-rollback.txt");
        FileUtil.writeUtf8String("v1", file);
        env.checkpointService.createCheckpoint(
                sourceKey, session.getSessionId(), Collections.singletonList(file));
        FileUtil.writeUtf8String("v2", file);
        AgentRunSupervisor supervisor = (AgentRunSupervisor) env.agentRunControlService;
        supervisor.coordinateIncoming(
                sourceKey, session.getSessionId(), env.message("admin-chat", "admin-user", "run"));

        GatewayReply retry =
                env.commandService.handle(
                        env.message("admin-chat", "admin-user", "/retry"), "/retry");
        GatewayReply streamingRetry =
                env.commandService.handle(
                        env.message("admin-chat", "admin-user", "/retry"),
                        "/retry",
                        ConversationEventSink.noop());
        GatewayReply undo =
                env.commandService.handle(
                        env.message("admin-chat", "admin-user", "/undo"), "/undo");
        GatewayReply branch =
                env.commandService.handle(
                        env.message("admin-chat", "admin-user", "/branch busy"), "/branch busy");
        GatewayReply rollback =
                env.commandService.handle(
                        env.message("admin-chat", "admin-user", "/rollback latest"),
                        "/rollback latest");
        GatewayReply list =
                env.commandService.handle(
                        env.message("admin-chat", "admin-user", "/rollback list"),
                        "/rollback list");
        GatewayReply status =
                env.commandService.handle(
                        env.message("admin-chat", "admin-user", "/rollback status"),
                        "/rollback status");

        assertThat(retry.isError()).isTrue();
        assertThat(streamingRetry.isError()).isTrue();
        assertThat(undo.isError()).isTrue();
        assertThat(branch.isError()).isTrue();
        assertThat(rollback.isError()).isTrue();
        assertThat(retry.getRuntimeMetadata()).containsEntry("busy_status", "running");
        assertThat(streamingRetry.getRuntimeMetadata()).containsEntry("busy_status", "running");
        assertThat(undo.getRuntimeMetadata()).containsEntry("busy_status", "running");
        assertThat(env.sessionRepository.findById(session.getSessionId()).getNdjson())
                .isEqualTo(session.getNdjson());
        assertThat(FileUtil.readUtf8String(file)).isEqualTo("v2");
        assertThat(list.isError()).isFalse();
        assertThat(status.isError()).isFalse();
        supervisor.releaseIncomingReservation(sourceKey);
    }

    @Test
    void shouldExposeCuratorCommandForSkillMaintenanceStatusAndRun() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        GatewayReply status = env.send("admin-chat", "admin-user", "/curator");
        assertThat(status.isError()).isFalse();
        assertThat(status.getContent())
                .contains("curator_enabled=true")
                .contains("paused=false")
                .contains("reports=0")
                .contains("improvements=0")
                .contains("用法：/curator [status|list|improvements|run|pause|resume]");
        assertThat(status.getRuntimeMetadata())
                .containsEntry("command_status", "handled")
                .containsEntry("command", "curator");

        GatewayReply paused = env.send("admin-chat", "admin-user", "/curator pause");
        assertThat(paused.getContent()).contains("技能后台维护已暂停");

        GatewayReply pausedStatus = env.send("admin-chat", "admin-user", "/curator status");
        assertThat(pausedStatus.getContent()).contains("paused=true");

        GatewayReply resumed = env.send("admin-chat", "admin-user", "/curator resume");
        assertThat(resumed.getContent()).contains("技能后台维护已恢复");

        GatewayReply run = env.send("admin-chat", "admin-user", "/curator run");
        assertThat(run.isError()).isFalse();
        assertThat(run.getContent()).contains("技能维护运行 status=ok").contains("items=0");

        GatewayReply list = env.send("admin-chat", "admin-user", "/curator list");
        assertThat(list.getContent()).contains("技能维护报告：").contains("status=ok");
    }

    @Test
    void shouldExposeToolsetsCommandFromDashboardCatalog() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        GatewayReply reply = env.send("admin-chat", "admin-user", "/toolsets");

        assertThat(reply.isError()).isFalse();
        assertThat(reply.getContent())
                .contains("工具集：")
                .contains("total=8")
                .contains("code enabled=true tools=15")
                .contains("skills enabled=true tools=")
                .contains("gateway enabled=true tools=1");
        assertThat(reply.getRuntimeMetadata())
                .containsEntry("command_status", "handled")
                .containsEntry("command", "toolsets")
                .containsEntry("toolset_count", Integer.valueOf(8));
    }

    @Test
    void shouldExposeBrowserRuntimeCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        GatewayReply status = env.send("admin-chat", "admin-user", "/browser");

        assertThat(status.isError()).isFalse();
        assertThat(status.getContent())
                .contains("浏览器运行时：")
                .contains("active_sessions=0")
                .contains("用法：/browser [status|connect|disconnect <session-id>]");
        assertThat(status.getRuntimeMetadata())
                .containsEntry("command_status", "handled")
                .containsEntry("command", "browser")
                .containsEntry("browser_active_sessions", Integer.valueOf(0));
    }

    @Test
    void shouldExposeDebugDiagnosticsCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        GatewayReply reply = env.send("admin-chat", "admin-user", "/debug");

        assertThat(reply.isError()).isFalse();
        assertThat(reply.getContent())
                .contains("调试诊断：")
                .contains("workspace_home=workspace://")
                .contains("providers=")
                .contains("channels=")
                .contains("tools=")
                .contains("security_probes_passed=");
        assertThat(reply.getRuntimeMetadata())
                .containsEntry("command_status", "handled")
                .containsEntry("command", "debug")
                .containsKey("debug_provider_count")
                .containsKey("debug_channel_count")
                .containsKey("debug_tool_count");
    }

    @Test
    void shouldExposeApprovalManagementFormsInSlashHelp() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        GatewayReply help = env.send("admin-chat", "admin-user", "/help");

        assertThat(help.getContent())
                .contains("/approve [#序号|审批ID|all] [session|always]")
                .contains("/approve list|status|clear session|clear always|clear all")
                .contains("/deny [#序号|审批ID|all]")
                .contains("/deny list|status|all");
    }

    @Test
    void shouldTreatApprovalListsAsReadonlyWithoutBoundSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        GatewayMessage message = env.message("fresh-chat", "fresh-user", "/approve list");
        GatewayReply approveList = env.commandService.handle(message, "/approve list");
        GatewayReply denyList = env.commandService.handle(message, "/deny list");

        assertThat(approveList.isError()).isFalse();
        assertThat(approveList.getContent())
                .contains("审批状态：")
                .contains("待审批：无")
                .contains("当前会话已授权：0 项")
                .contains("永久授权：0 项");
        assertThat(denyList.isError()).isFalse();
        assertThat(denyList.getContent())
                .contains("审批状态：")
                .contains("待审批：无")
                .contains("当前会话已授权：0 项")
                .contains("永久授权：0 项");
    }

    @Test
    void shouldRedactTrackedSlashCommandEventArgsBeforeStorage() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        GatewayReply conversation = env.send("admin-chat", "admin-user", "记录一条运行");
        assertThat(conversation.getContent()).contains("echo:记录一条运行");
        SessionRecord session =
                env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user");
        List<AgentRunRecord> runs = env.agentRunRepository.listBySession(session.getSessionId(), 1);
        assertThat(runs).isNotEmpty();
        String runId = runs.get(0).getRunId();

        String secret = "ghp_slashmetadata12345";
        GatewayReply stop = env.send("admin-chat", "admin-user", "/stop token=" + secret);
        assertThat(stop.getContent()).doesNotContain(secret);

        List<AgentRunEventRecord> events = env.agentRunRepository.listEvents(runId);
        AgentRunEventRecord slashEvent = null;
        for (AgentRunEventRecord event : events) {
            if ("slash.command".equals(event.getEventType())) {
                slashEvent = event;
                break;
            }
        }
        assertThat(slashEvent).isNotNull();
        assertThat(slashEvent.getSessionId()).isEqualTo(session.getSessionId());
        assertThat(slashEvent.getSummary()).isEqualTo("/stop");
        assertThat(slashEvent.getMetadataJson()).contains("token=***").doesNotContain(secret);

        DashboardRunService runService = new DashboardRunService(env.agentRunRepository);
        String dashboardEvents = ONode.serialize(runService.events(runId));
        assertThat(dashboardEvents)
                .contains("token=***")
                .doesNotContain(secret)
                .doesNotContain("ghp_slashmetadata");
    }

    @Test
    void shouldRequestGracefulGatewayRestartDrain() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        env.appConfig.getTask().setRestartDrainTimeoutSeconds(180);

        GatewayReply restart = env.send("admin-chat", "admin-user", "/restart");

        assertThat(restart.getContent()).contains("网关将立即重启").contains("60 秒");
        assertThat(restart.getRuntimeMetadata())
                .containsEntry("restart_requested", Boolean.TRUE)
                .containsEntry("restart_first_request", Boolean.TRUE)
                .containsEntry("restart_active_runs", 0)
                .containsEntry("restart_drain_timeout_seconds", 180);
        assertThat(env.gatewayRestartCoordinator.isRestartRequested()).isTrue();
        assertThat(env.gatewayRestartCoordinator.getRequesterSourceKey())
                .isEqualTo("MEMORY:admin-chat:admin-user");

        GatewayReply repeated = env.send("admin-chat", "admin-user", "/restart");
        assertThat(repeated.getContent()).contains("网关重启已在进行中");
        assertThat(repeated.getRuntimeMetadata())
                .containsEntry("restart_first_request", Boolean.FALSE);

        GatewayReply help = env.send("admin-chat", "admin-user", "/help");
        assertThat(help.getContent()).contains("/restart").contains("drain");
    }

    @Test
    void shouldPreserveRestartRequesterRoutingMetadata() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        GatewayMessage message =
                new GatewayMessage(PlatformType.MEMORY, "admin-chat", "admin-user", "/restart");
        message.setChatType("group");
        message.setThreadId("topic-7");
        message.setChatName("Ops Topic");

        GatewayReply restart = env.gatewayService.handle(message);

        assertThat(restart.getRuntimeMetadata())
                .containsEntry("restart_requester_platform", "MEMORY")
                .containsEntry("restart_requester_chat_id", "admin-chat")
                .containsEntry("restart_requester_user_id", "admin-user")
                .containsEntry("restart_requester_chat_type", "group")
                .containsEntry("restart_requester_thread_id", "topic-7");
        assertThat(env.gatewayRestartCoordinator.getRequesterRouting().getPlatform())
                .isEqualTo(PlatformType.MEMORY);
        assertThat(env.gatewayRestartCoordinator.getRequesterRouting().getChatId())
                .isEqualTo("admin-chat");
        assertThat(env.gatewayRestartCoordinator.getRequesterRouting().getUserId())
                .isEqualTo("admin-user");
        assertThat(env.gatewayRestartCoordinator.getRequesterRouting().getChatType())
                .isEqualTo("group");
        assertThat(env.gatewayRestartCoordinator.getRequesterRouting().getThreadId())
                .isEqualTo("topic-7");
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

        GatewayReply queuedWithAlias =
                env.send("admin-chat", "admin-user", "/q inspect queue alias");
        assertThat(queuedWithAlias.getContent()).contains("队列");
        assertThat(queuedWithAlias.getRuntimeMetadata()).containsKey("queue_id");
        assertThat(
                        env.agentRunRepository
                                .findRun(
                                        String.valueOf(
                                                queuedWithAlias.getRuntimeMetadata().get("run_id")))
                                .getInputPreview())
                .isEqualTo("inspect queue alias");

        GatewayReply busyWithQueue = env.send("admin-chat", "admin-user", "/busy status");
        assertThat(busyWithQueue.getContent()).contains("queue_pending=2");

        GatewayReply idleSteer = env.send("admin-chat", "admin-user", "/steer summarize README");

        assertThat(idleSteer.getContent()).contains("echo:summarize README");
        assertThat(env.sessionRepository.getBoundSession(sourceKey).getNdjson())
                .contains("summarize README");
        GatewayReply help = env.send("admin-chat", "admin-user", "/help");
        assertThat(help.getContent()).contains("/queue <prompt>").contains("/steer <prompt>");
    }

    @Test
    void shouldInjectExplicitSteerIntoRunningAgentWithoutInterrupting() throws Exception {
        BlockingLlmGateway slowLlmGateway = new BlockingLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(slowLlmGateway);
        bootstrapAdmin(env);

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<GatewayReply> running =
                executorService.submit(() -> env.send("admin-chat", "admin-user", "执行一个长任务"));

        assertThat(slowLlmGateway.awaitStarted(2, TimeUnit.SECONDS)).isTrue();
        GatewayReply steer = env.send("admin-chat", "admin-user", "/steer prefer simpler fix");

        assertThat(steer.getContent()).contains("steer").contains("注入");
        assertThat(steer.getRuntimeMetadata()).containsEntry("busy_status", "steered");
        String runId = String.valueOf(steer.getRuntimeMetadata().get("run_id"));
        GatewayReply busy = env.send("admin-chat", "admin-user", "/busy status");
        assertThat(busy.getContent()).contains("active_run_id=" + runId);
        RunControlCommand pending = env.agentRunRepository.findLatestPendingCommand(runId, "steer");
        assertThat(pending).isNotNull();
        assertThat(pending.getPayloadJson()).contains("prefer simpler fix");
        assertThat(slowLlmGateway.isInterrupted()).isFalse();

        env.send("admin-chat", "admin-user", "/stop");
        running.get(3, TimeUnit.SECONDS);
        executorService.shutdownNow();
    }

    @Test
    void shouldRedactDashboardControlPayloadButKeepRawSteerForRuntime() throws Exception {
        BlockingLlmGateway slowLlmGateway = new BlockingLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(slowLlmGateway);
        bootstrapAdmin(env);

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<GatewayReply> running =
                executorService.submit(() -> env.send("admin-chat", "admin-user", "执行一个长任务"));

        assertThat(slowLlmGateway.awaitStarted(2, TimeUnit.SECONDS)).isTrue();
        GatewayReply steer =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/steer rotate token=ghp_dashcontrol12345 api_key=sk-dash-control-secret");

        String runId = String.valueOf(steer.getRuntimeMetadata().get("run_id"));
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("instruction", "rotate token=ghp_dashcontrol12345");
        payload.put("nested", Collections.singletonMap("api_key", "sk-dash-control-secret"));

        DashboardRunService dashboard =
                new DashboardRunService(env.agentRunRepository, env.agentRunControlService);
        dashboard.control(runId, "steer", payload);

        RunControlCommand pending = env.agentRunRepository.findLatestPendingCommand(runId, "steer");
        assertThat(pending).isNotNull();
        assertThat(pending.getPayloadJson())
                .contains("ghp_dashcontrol12345")
                .contains("sk-dash-control-secret");
        assertThat(env.agentRunRepository.listEvents(runId))
                .anySatisfy(
                        event -> {
                            assertThat(event.getEventType()).isEqualTo("run.steer");
                            assertThat(event.getMetadataJson())
                                    .contains("token=***")
                                    .contains("\"api_key\":\"***\"")
                                    .doesNotContain("ghp_dashcontrol12345")
                                    .doesNotContain("sk-dash-control-secret");
                        });

        String detail = ONode.serialize(dashboard.detail(runId));

        assertThat(detail)
                .contains("token=***")
                .contains("\"api_key\":\"***\"")
                .doesNotContain("ghp_dashcontrol12345")
                .doesNotContain("sk-dash-control-secret");

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

        GatewayReply full =
                env.send("admin-chat", "admin-user", "/resume " + original.getSessionId());
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
    void shouldResumeByTitleOrUniqueIdPrefixAndRejectAmbiguousTitle() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        env.send("admin-chat", "admin-user", "第一段历史");
        SessionRecord original =
                env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user");
        original.setTitle("客户周报");
        env.sessionRepository.save(original);
        env.send("admin-chat", "admin-user", "/new");

        GatewayReply byTitle = env.send("admin-chat", "admin-user", "/resume 客户周报");
        assertThat(byTitle.getSessionId()).isEqualTo(original.getSessionId());
        assertThat(byTitle.getContent()).contains("已恢复会话").contains("客户周报");

        env.send("admin-chat", "admin-user", "/new");
        GatewayReply byQuotedTitle = env.send("admin-chat", "admin-user", "/resume \"客户周报\"");
        assertThat(byQuotedTitle.getSessionId()).isEqualTo(original.getSessionId());

        env.send("admin-chat", "admin-user", "/new");
        GatewayReply byPrefix =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/resume " + original.getSessionId().substring(0, 8));
        assertThat(byPrefix.getSessionId()).isEqualTo(original.getSessionId());

        SessionRecord duplicate =
                env.sessionRepository.bindNewSession("MEMORY:other-chat:other-user");
        duplicate.setTitle("客户周报");
        env.sessionRepository.save(duplicate);

        env.send("admin-chat", "admin-user", "/new");
        GatewayReply ambiguous = env.send("admin-chat", "admin-user", "/resume 客户周报");
        assertThat(ambiguous.isError()).isTrue();
        assertThat(ambiguous.getContent()).contains("匹配到多个会话").contains(original.getSessionId());
    }

    @Test
    void shouldViewSetAndClearCurrentSessionTitle() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        env.send("admin-chat", "admin-user", "需要标题的会话");
        SessionRecord session =
                env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user");

        GatewayReply view = env.send("admin-chat", "admin-user", "/title");
        assertThat(view.getContent()).contains("当前会话标题").contains("需要标题的会话");

        GatewayReply set = env.send("admin-chat", "admin-user", "/title \"客户问题复盘\"");
        assertThat(set.getContent()).contains("已更新当前会话标题").contains("客户问题复盘");
        assertThat(set.getRuntimeMetadata()).containsEntry("title", "客户问题复盘");
        assertThat(env.sessionRepository.findById(session.getSessionId()).getTitle())
                .isEqualTo("客户问题复盘");

        GatewayReply resumed = env.send("admin-chat", "admin-user", "/resume 客户问题复盘");
        assertThat(resumed.getSessionId()).isEqualTo(session.getSessionId());

        GatewayReply clear = env.send("admin-chat", "admin-user", "/title clear");
        assertThat(clear.getContent()).contains("已清空当前会话标题");
        assertThat(env.sessionRepository.findById(session.getSessionId()).getTitle()).isEmpty();

        GatewayReply help = env.send("admin-chat", "admin-user", "/help");
        assertThat(help.getContent()).contains("/title [clear|新标题]");
    }

    @Test
    void shouldSupportGoalCommandLifecycle() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        GatewayMessage message =
                new GatewayMessage(
                        PlatformType.MEMORY, "goal-chat", "goal-user", "/goal 完成一次端到端验证 --max 4");

        GatewayReply set = env.commandService.handle(message, "/goal 完成一次端到端验证 --max 4");
        assertThat(set.getContent()).contains("Goal set").contains("完成一次端到端验证");

        GatewayReply status = env.commandService.handle(message, "/goal status");
        assertThat(status.getContent()).contains("active").contains("0/4");

        SessionRecord session = env.sessionRepository.getBoundSession(message.sourceKey());
        new GoalService(env.sessionRepository)
                .evaluateAfterTurn(session, "I took one concrete step and still need to continue.");

        GatewayReply judgedStatus = env.commandService.handle(message, "/goal status");
        assertThat(judgedStatus.getContent())
                .contains("1/4")
                .contains("judge=continue")
                .contains("response did not clearly complete");

        GatewayReply pause = env.commandService.handle(message, "/goal pause");
        assertThat(pause.getContent()).contains("Goal paused");

        GatewayReply resume = env.commandService.handle(message, "/goal resume");
        assertThat(resume.getContent())
                .contains("Goal resumed")
                .contains("Continuing toward your standing goal");

        GatewayReply clear = env.commandService.handle(message, "/goal clear");
        assertThat(clear.getContent()).contains("Goal cleared");

        GatewayReply help = env.commandService.handle(message, "/help");
        assertThat(help.getContent())
                .contains(
                        "/goal [status|show|pause|resume|clear|stop|done|wait <pid>|unwait|<目标> --max-turns N|--max N]")
                .contains("/subgoal [<text>|remove <n>|clear]");
    }

    @Test
    void shouldSupportSubgoalCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        GatewayMessage message =
                new GatewayMessage(PlatformType.MEMORY, "sg-chat", "sg-user", "/goal 完成测试 --max 5");
        env.commandService.handle(message, "/goal 完成测试 --max 5");

        GatewayReply add = env.commandService.handle(message, "/subgoal 覆盖 goal 包");
        assertThat(add.getContent()).contains("覆盖 goal 包");

        GatewayReply list = env.commandService.handle(message, "/subgoal");
        assertThat(list.getContent()).contains("覆盖 goal 包");

        GatewayReply remove = env.commandService.handle(message, "/subgoal remove 1");
        assertThat(remove.getContent()).containsAnyOf("removed", "cleared", "No subgoal");

        GatewayReply clear = env.commandService.handle(message, "/subgoal clear");
        assertThat(clear.getContent()).containsAnyOf("cleared", "No subgoal");
    }

    @Test
    void shouldSupportGoalShowAndStopDoneAliases() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        GatewayMessage message =
                new GatewayMessage(PlatformType.MEMORY, "g2-chat", "g2-user", "/goal 目标A --max 3");
        env.commandService.handle(message, "/goal 目标A --max 3");

        GatewayReply show = env.commandService.handle(message, "/goal show");
        assertThat(show.getContent()).contains("目标A");

        GatewayReply stop = env.commandService.handle(message, "/goal stop");
        assertThat(stop.getContent()).contains("cleared");

        GatewayReply doneAlias = env.commandService.handle(message, "/goal done");
        // 已 cleared 再 done 也应返回 No active goal，不报错
        assertThat(doneAlias.getContent()).containsAnyOf("No active goal", "cleared");
    }

    @Test
    void shouldSupportJimuquCronFlagSyntaxAndSkillEditing() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        createCronScript(env, "collect.py");
        bootstrapAdmin(env);

        GatewayReply created =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron add \"every 2h\" \"Check server status\" --skill blogwatcher --skill maps"
                                + " --model gpt-test-cron --provider default --base-url https://api.example.com/"
                                + " --deliver feishu --deliver-chat-id chat-create --deliver-thread-id thread-create"
                                + " --no-wrap-response");
        String jobId =
                env.cronJobRepository
                        .listBySource("MEMORY:admin-chat:admin-user")
                        .get(0)
                        .getJobId();
        assertThat(created.getContent()).contains("已创建定时任务").contains("job_id=" + jobId);
        assertThat(cronJobView(env, jobId))
                .contains("blogwatcher")
                .contains("maps")
                .contains("model=gpt-test-cron")
                .contains("provider=default")
                .contains("base_url=https://api.example.com")
                .contains("deliver=feishu")
                .contains("deliver_chat_id=chat-create")
                .contains("deliver_thread_id=thread-create")
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

        GatewayReply pluralSkillAliases =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron edit " + jobId + " --add-skills alerts,ops --remove-skills reports");
        assertThat(pluralSkillAliases.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId))
                .contains("blogwatcher")
                .contains("alerts")
                .contains("ops")
                .doesNotContain("reports");

        GatewayReply shortSkillAlias =
                env.send("admin-chat", "admin-user", "/cron edit " + jobId + " -s analyst");
        assertThat(shortSkillAlias.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId))
                .contains("analyst")
                .doesNotContain("blogwatcher")
                .doesNotContain("alerts")
                .doesNotContain("ops");

        GatewayReply cleared =
                env.send("admin-chat", "admin-user", "/cron edit " + jobId + " --clear-skills");
        assertThat(cleared.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId)).contains("skills=[]");

        GatewayReply repeated =
                env.send("admin-chat", "admin-user", "/cron edit " + jobId + " --repeat 3");
        assertThat(repeated.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId)).contains("repeat={times=3");

        GatewayReply clearedRepeat =
                env.send("admin-chat", "admin-user", "/cron edit " + jobId + " --clear-repeat");
        assertThat(clearedRepeat.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId)).contains("repeat={times=null");

        String workspaceHome = env.appConfig.getRuntime().getHome().replace('\\', '/');
        GatewayReply tuned =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron edit "
                                + jobId
                                + " --script collect.py --workdir \""
                                + workspaceHome
                                + "\" --context-from "
                                + jobId
                                + " --enabled-toolsets web,terminal");
        assertThat(tuned.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId))
                .contains("script=collect.py")
                .contains("workdir=workspace://")
                .contains("context_from=[" + jobId + "]")
                .contains("depends_on=[" + jobId + "]")
                .contains("enabled_toolsets=[web, terminal]");

        GatewayReply dependsOn =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron edit " + jobId + " --depends-on " + jobId);
        assertThat(dependsOn.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId))
                .contains("context_from=[" + jobId + "]")
                .contains("depends_on=[" + jobId + "]");

        GatewayReply clearedDependsOn =
                env.send("admin-chat", "admin-user", "/cron edit " + jobId + " --clear-depends-on");
        assertThat(clearedDependsOn.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId)).contains("context_from=[]").contains("depends_on=[]");

        GatewayReply noAgent =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron edit " + jobId + " --no-agent --wrap-response");
        assertThat(noAgent.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId))
                .contains("no_agent=true")
                .contains("wrap_response=true");

        GatewayReply agent =
                env.send("admin-chat", "admin-user", "/cron edit " + jobId + " --agent");
        assertThat(agent.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId)).contains("no_agent=false");

        GatewayReply clearedModelPinning =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron edit " + jobId + " --clear-model --clear-provider --clear-base-url");
        assertThat(clearedModelPinning.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId))
                .contains("model=null")
                .contains("provider=null")
                .contains("base_url=null");

        GatewayReply clearedRuntime =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron edit "
                                + jobId
                                + " --clear-script --clear-workdir --clear-context-from --clear-enabled-toolsets");
        assertThat(clearedRuntime.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId))
                .contains("script=null")
                .contains("workdir=null")
                .contains("context_from=[]")
                .contains("enabled_toolsets=[]");

        GatewayReply retuned =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron edit "
                                + jobId
                                + " --script collect.py --workdir \""
                                + workspaceHome
                                + "\" --deliver local --clear-deliver-chat-id --clear-deliver-thread-id");
        assertThat(retuned.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId))
                .contains("script=collect.py")
                .contains("workdir=workspace://")
                .contains("deliver=local")
                .contains("deliver_chat_id=null")
                .contains("deliver_thread_id=null");

        GatewayReply clearedWithJimuquEmptyValues =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron edit " + jobId + " --script \"\" --workdir \"\"");
        assertThat(clearedWithJimuquEmptyValues.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId)).contains("script=null").contains("workdir=null");

        GatewayReply pausedWithState =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron edit "
                                + jobId
                                + " --state paused --paused-reason \"maintenance window\"");
        assertThat(pausedWithState.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId))
                .contains("state=paused")
                .contains("paused_reason=maintenance window");

        GatewayReply resumedWithStatus =
                env.send("admin-chat", "admin-user", "/cron edit " + jobId + " --status active");
        assertThat(resumedWithStatus.getContent()).contains("已更新定时任务");
        assertThat(cronJobView(env, jobId))
                .contains("state=scheduled")
                .contains("paused_reason=null");

        GatewayReply invalidNoAgent =
                env.send("admin-chat", "admin-user", "/cron edit " + jobId + " --no-agent");
        assertThat(invalidNoAgent.getContent()).contains("no_agent requires script");

        GatewayReply help = env.send("admin-chat", "admin-user", "/help");
        assertThat(help.getContent())
                .contains(
                        "/cron [list [--all]|inspect|show|next|upcoming|guide|tutorial|capabilities|policy|add|edit|pause|disable|stop|resume|enable|start|remove|delete|run|trigger|retry|rerun|history|status|tick]");
    }

    @Test
    void shouldSupportJimuquCronDeleteAlias() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        env.send("admin-chat", "admin-user", "/cron add \"30m\" \"Delete alias check\"");
        String jobId =
                env.cronJobRepository
                        .listBySource("MEMORY:admin-chat:admin-user")
                        .get(0)
                        .getJobId();

        GatewayReply deleted = env.send("admin-chat", "admin-user", "/cron delete " + jobId);

        assertThat(deleted.getContent()).contains("已删除定时任务：" + jobId);
        assertThat(env.cronJobRepository.findById(jobId)).isNull();
    }

    @Test
    void shouldParseCronActionJobIdBeforeTrailingFlags() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        env.send("admin-chat", "admin-user", "/cron add \"30m\" \"Trailing flag check\"");
        String jobId =
                env.cronJobRepository
                        .listBySource("MEMORY:admin-chat:admin-user")
                        .get(0)
                        .getJobId();

        GatewayReply paused = env.send("admin-chat", "admin-user", "/cron pause " + jobId);
        assertThat(paused.getContent()).contains("已暂停定时任务：" + jobId);

        GatewayReply resumed =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron resume \"" + jobId + "\" --ignored-flag");
        assertThat(resumed.getContent()).contains("已恢复定时任务：" + jobId);
        assertThat(cronJobView(env, jobId)).contains("state=scheduled");

        GatewayReply stopped =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron stop \"" + jobId + "\" --reason manual-stop");
        assertThat(stopped.getContent()).contains("已暂停定时任务：" + jobId);
        assertThat(env.cronJobRepository.findById(jobId).getStatus()).isEqualTo("PAUSED");

        GatewayReply started =
                env.send(
                        "admin-chat", "admin-user", "/cron start \"" + jobId + "\" --ignored-flag");
        assertThat(started.getContent()).contains("已恢复定时任务：" + jobId);
        assertThat(env.cronJobRepository.findById(jobId).getStatus()).isEqualTo("ACTIVE");

        GatewayReply run =
                env.send("admin-chat", "admin-user", "/cron run " + jobId + " --accept-hooks");
        assertThat(run.getContent()).contains("已标记定时任务将在下一次 tick 执行：" + jobId);
        GatewayReply retry =
                env.send(
                        "admin-chat", "admin-user", "/cron retry \"" + jobId + "\" --accept-hooks");
        assertThat(retry.getContent()).contains("已标记定时任务将在下一次 tick 执行：" + jobId);
        GatewayReply trigger =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron trigger \"" + jobId + "\" --accept-hooks");
        assertThat(trigger.getContent()).contains("已标记定时任务将在下一次 tick 执行：" + jobId);

        GatewayReply removed =
                env.send("admin-chat", "admin-user", "/cron delete \"" + jobId + "\" --force");
        assertThat(removed.getContent()).contains("已删除定时任务：" + jobId);
        assertThat(env.cronJobRepository.findById(jobId)).isNull();
    }

    @Test
    void shouldRequireCronActionJobIds() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        GatewayReply resume = env.send("admin-chat", "admin-user", "/cron resume");
        GatewayReply enable = env.send("admin-chat", "admin-user", "/cron enable");
        GatewayReply stop = env.send("admin-chat", "admin-user", "/cron stop");
        GatewayReply run = env.send("admin-chat", "admin-user", "/cron run");
        GatewayReply retry = env.send("admin-chat", "admin-user", "/cron retry");
        GatewayReply trigger = env.send("admin-chat", "admin-user", "/cron trigger");
        GatewayReply rerun = env.send("admin-chat", "admin-user", "/cron rerun");
        GatewayReply remove = env.send("admin-chat", "admin-user", "/cron remove");

        assertThat(resume.isError()).isTrue();
        assertThat(resume.getContent()).contains("用法：/cron resume|enable|start <job-id>");
        assertThat(enable.isError()).isTrue();
        assertThat(enable.getContent()).contains("用法：/cron resume|enable|start <job-id>");
        assertThat(stop.isError()).isTrue();
        assertThat(stop.getContent()).contains("用法：/cron pause|disable|stop <job-id>");
        assertThat(run.isError()).isTrue();
        assertThat(run.getContent()).contains("用法：/cron run|trigger|retry|rerun <job-id>");
        assertThat(retry.isError()).isTrue();
        assertThat(retry.getContent()).contains("用法：/cron run|trigger|retry|rerun <job-id>");
        assertThat(trigger.getContent()).contains("用法：/cron run|trigger|retry|rerun <job-id>");
        assertThat(rerun.getContent()).contains("用法：/cron run|trigger|retry|rerun <job-id>");
        assertThat(remove.isError()).isTrue();
        assertThat(remove.getContent()).contains("用法：/cron remove <job-id>");
    }

    @Test
    void shouldMatchJimuquCronListAllSemantics() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        env.send(
                "admin-chat",
                "admin-user",
                "/cron add \"every 2h\" \"Check server status\" --skill blogwatcher");
        String jobId =
                env.cronJobRepository
                        .listBySource("MEMORY:admin-chat:admin-user")
                        .get(0)
                        .getJobId();
        GatewayReply activeList = env.send("admin-chat", "admin-user", "/cron list");
        assertThat(activeList.getContent())
                .contains("Scheduled Jobs:")
                .contains("ID: " + jobId)
                .contains("State: scheduled")
                .contains("Skills: blogwatcher")
                .contains("Prompt: Check server status");

        GatewayReply paused =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron pause " + jobId + " --reason \"maintenance window\"");
        assertThat(paused.getContent()).contains("已暂停定时任务");
        assertThat(cronJobView(env, jobId)).contains("paused_reason=maintenance window");

        GatewayReply defaultList = env.send("admin-chat", "admin-user", "/cron list");
        assertThat(defaultList.getContent()).contains("当前没有定时任务。").doesNotContain(jobId);

        GatewayReply allList = env.send("admin-chat", "admin-user", "/cron list --all");
        assertThat(allList.getContent())
                .contains("ID: " + jobId)
                .contains("State: paused")
                .contains("Paused reason: maintenance window")
                .contains("Repeat: ∞")
                .contains("Deliver: origin")
                .contains("Schedule: every 2h");

        GatewayReply status = env.send("admin-chat", "admin-user", "/cron status");
        assertThat(status.getContent())
                .contains("Cron 状态")
                .contains("范围：全部任务")
                .contains("总数：1")
                .contains("active=0")
                .contains("paused=1")
                .contains("已到期：0");

        GatewayReply overview = env.send("admin-chat", "admin-user", "/cron");
        assertThat(overview.getContent())
                .contains("Cron 定时任务")
                .contains("/cron list --all")
                .contains("/cron next [--all] [--limit 5]")
                .contains("/cron upcoming [--all] [--limit 5]")
                .contains("/cron status [--all]")
                .contains("/cron pause|disable|stop <job-id>")
                .contains("/cron resume|enable|start <job-id>")
                .contains("/cron trigger <job-id>")
                .contains("/cron retry <job-id>")
                .contains("/cron history <job-id>")
                .contains("--deliver-chat-id")
                .contains("--clear-deliver-chat-id")
                .contains("--clear-model")
                .contains("当前没有定时任务。");

        CronJobRecord failed = new CronJobRecord();
        failed.setJobId("cron-secret-status");
        failed.setName("secret status");
        failed.setSourceKey("MEMORY:admin-chat:admin-user");
        failed.setCronExpr("every 1h");
        failed.setPrompt("secret status prompt");
        failed.setStatus("ACTIVE");
        failed.setLastStatus("error");
        failed.setLastError("command failed token=ghp_croncommand12345\u202E");
        failed.setLastDeliveryError("delivery failed api_key=sk-croncommand-secret12345\u202E");
        failed.setLastRunAt(System.currentTimeMillis());
        failed.setCreatedAt(System.currentTimeMillis());
        env.cronJobRepository.save(failed);
        CronJobRunRecord failedRun = new CronJobRunRecord();
        failedRun.setRunId("cron-secret-run");
        failedRun.setJobId(failed.getJobId());
        failedRun.setSourceKey(failed.getSourceKey());
        failedRun.setTriggerType("manual");
        failedRun.setAttempt(1);
        failedRun.setStartedAt(System.currentTimeMillis() - 1000L);
        failedRun.setFinishedAt(System.currentTimeMillis());
        failedRun.setStatus("error");
        failedRun.setOutput("stdout token=ghp_cronrunoutput12345\u202E");
        failedRun.setError("stderr api_key=sk-cronrunerror-secret12345\u202E");
        failedRun.setDeliveryError("delivery bearer ghp_cronrundelivery12345\u202E");
        env.cronJobRepository.saveRun(failedRun);

        GatewayReply redactedStatus = env.send("admin-chat", "admin-user", "/cron status");
        assertThat(redactedStatus.getContent())
                .contains("cron-secret-status")
                .contains("token=***")
                .contains("api_key=***")
                .doesNotContain("ghp_croncommand12345")
                .doesNotContain("sk-croncommand-secret12345")
                .doesNotContain("\u202E");

        GatewayReply redactedHistory =
                env.send("admin-chat", "admin-user", "/cron history cron-secret-status");
        assertThat(redactedHistory.getContent())
                .contains("cron-secret-run")
                .contains("token=***")
                .contains("api_key=***")
                .contains("bearer ***")
                .doesNotContain("ghp_cronrunoutput12345")
                .doesNotContain("sk-cronrunerror-secret12345")
                .doesNotContain("ghp_cronrundelivery12345")
                .doesNotContain("\u202E");

        GatewayReply redactedOverview = env.send("admin-chat", "admin-user", "/cron");
        assertThat(redactedOverview.getContent())
                .contains("Cron 定时任务")
                .contains("cron-secret-status")
                .contains("api_key=***")
                .doesNotContain("sk-croncommand-secret12345")
                .doesNotContain("\u202E");
    }

    @Test
    void shouldShowUpcomingCronJobsInNextCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        env.send("admin-chat", "admin-user", "/cron add \"30m\" \"Later job\" --deliver feishu");
        env.send(
                "admin-chat",
                "admin-user",
                "/cron add \"30m\" \"Soon job\" --deliver local --repeat 2");
        env.send("admin-chat", "admin-user", "/cron add \"30m\" \"Paused job\"");
        List<CronJobRecord> records =
                env.cronJobRepository.listBySource("MEMORY:admin-chat:admin-user");
        CronJobRecord later = findCronByName(records, "Later job");
        CronJobRecord soon = findCronByName(records, "Soon job");
        CronJobRecord paused = findCronByName(records, "Paused job");
        long now = System.currentTimeMillis();
        later.setNextRunAt(now + 120000L);
        soon.setNextRunAt(now + 60000L);
        paused.setNextRunAt(now + 30000L);
        paused.setStatus("PAUSED");
        env.cronJobRepository.update(later);
        env.cronJobRepository.update(soon);
        env.cronJobRepository.update(paused);

        GatewayReply next = env.send("admin-chat", "admin-user", "/cron next --limit 1");

        assertThat(next.getContent())
                .contains("Cron 即将运行")
                .contains("范围：全部任务")
                .contains("1. ")
                .contains(soon.getJobId())
                .contains("Soon job")
                .contains("Deliver: local Repeat: 0/2")
                .contains("还有 1 个任务未显示。")
                .doesNotContain(later.getJobId())
                .doesNotContain(paused.getJobId());

        GatewayReply allNext = env.send("admin-chat", "admin-user", "/cron next --all --limit 5");
        assertThat(allNext.getContent())
                .contains("范围：全部任务")
                .contains(soon.getJobId())
                .contains(later.getJobId())
                .doesNotContain(paused.getJobId());
        assertThat(allNext.getContent().indexOf(soon.getJobId()))
                .isLessThan(allNext.getContent().indexOf(later.getJobId()));

        GatewayReply upcoming = env.send("admin-chat", "admin-user", "/cron upcoming --limit 1");
        assertThat(upcoming.getContent())
                .contains("Cron 即将运行")
                .contains(soon.getJobId())
                .doesNotContain(later.getJobId());
    }

    @Test
    void shouldShowCronAutomationGuideCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        GatewayReply guide = env.send("admin-chat", "admin-user", "/cron guide");

        assertThat(guide.getContent())
                .contains("Cron 自动化指南")
                .contains("可编辑字段：")
                .contains("deliver_chat_id")
                .contains("status")
                .contains("paused_reason")
                .contains("动作语法：")
                .contains("/cron pause|disable|stop <job-id> [--reason reason]")
                .contains("/cron run|trigger|retry|rerun <job-id>")
                .contains("/cron remove|delete|rm <job-id>")
                .contains("技能绑定：")
                .contains("--remove-skill name")
                .contains("--context-from job-id")
                .contains("--clear-context-from")
                .contains("投递策略：")
                .contains("feishu")
                .contains("platform:chat_id:thread_id")
                .contains("origin: 回复到创建任务的原始会话")
                .contains("target1,target2: 同一次运行投递到多个目标")
                .contains("--raw/--no-wrap/--no-wrap-response")
                .contains("--no-wrap-response")
                .contains("运行模式：")
                .contains("no_agent")
                .contains("--clear-enabled-toolsets")
                .contains("安全策略：")
                .contains("prompt_injection")
                .contains(
                        "/cron edit <job-id> --context-from upstream-job --enabled-toolsets web,terminal")
                .contains("/cron edit <job-id> --clear-repeat")
                .contains("/cron history <job-id> --limit 20");

        GatewayReply json = env.send("admin-chat", "admin-user", "/cron capabilities --json");

        ONode data = ONode.ofJson(json.getContent());
        assertThat(data.get("editable_fields").toJson())
                .contains("wrap_response")
                .contains("status")
                .contains("state")
                .contains("paused_reason");
        assertThat(data.get("action_syntax").get("edit").getString()).contains("--add-skill name");
        assertThat(data.get("action_syntax").get("run").getString()).contains("retry");
        assertThat(data.get("aliases").get("pause").toJson()).contains("disable").contains("stop");
        assertThat(data.get("aliases").get("run").toJson()).contains("trigger").contains("retry");
        assertThat(data.get("aliases").get("next").toJson()).contains("upcoming");
        assertThat(data.get("delivery").get("targets").toJson())
                .contains("feishu")
                .contains("yuanbao");
        assertThat(data.get("delivery").get("target_forms").toJson())
                .contains("platform:chat_id:thread_id");
        assertThat(data.get("delivery").get("wrap_flags").toJson()).contains("--no-wrap-response");
        assertThat(data.get("delivery").get("modes").toJson()).contains("target1,target2");
        assertThat(data.get("delivery").get("wrap_response_policy").getString()).contains("--raw");
        assertThat(data.get("runtime_modes").get("clear_flags").toJson())
                .contains("--clear-repeat")
                .contains("--clear-enabled-toolsets");
        assertThat(data.get("skill_binding").get("dependency_flags").toJson())
                .contains("--depends-on job-id");
        assertThat(data.get("security").get("script_validation").getString()).contains("script");

        GatewayReply policy = env.send("admin-chat", "admin-user", "/cron policy");
        assertThat(policy.getContent())
                .contains("Cron 自动化指南")
                .contains("安全策略：")
                .contains("投递策略：")
                .doesNotContain("用法：/cron");
    }

    @Test
    void shouldShowJimuquCronListRuntimeDetails() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        createCronScript(env, "collect.py");
        bootstrapAdmin(env);

        String workspaceHome = env.appConfig.getRuntime().getHome().replace('\\', '/');
        GatewayReply created =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron add \"30m\" \"Runtime detail check\" --script collect.py --workdir \""
                                + workspaceHome
                                + "\" --no-agent --repeat 3 --deliver feishu --no-wrap-response"
                                + " --deliver-chat-id chat-1 --deliver-thread-id topic-2"
                                + " --model gpt-cron --provider default --base-url https://api.cron.example/v1/"
                                + " --toolsets shell,file");
        assertThat(created.getContent()).contains("已创建定时任务");
        String jobId =
                env.cronJobRepository
                        .listBySource("MEMORY:admin-chat:admin-user")
                        .get(0)
                        .getJobId();
        GatewayReply createdDependency =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/cron add \"45m\" \"Dependency detail check\"");
        assertThat(createdDependency.getContent()).contains("已创建定时任务");
        String dependencyId =
                env.cronJobRepository.listBySource("MEMORY:admin-chat:admin-user").stream()
                        .filter(record -> !jobId.equals(record.getJobId()))
                        .findFirst()
                        .get()
                        .getJobId();
        CronJobRecord job = env.cronJobRepository.findById(jobId);
        job.setContextFromJson(org.noear.snack4.ONode.serialize(Arrays.asList(dependencyId)));
        job.setRepeatCompleted(1);
        job.setLastRunAt(System.currentTimeMillis());
        job.setLastStatus("error");
        job.setLastDeliveryError("send timeout");
        env.cronJobRepository.update(job);

        GatewayReply list = env.send("admin-chat", "admin-user", "/cron list");

        assertThat(list.getContent())
                .contains("ID: " + jobId)
                .contains("Repeat: 1/3")
                .contains("Deliver: feishu")
                .contains("Deliver chat: chat-1")
                .contains("Deliver thread: topic-2")
                .contains("Wrap response: false")
                .contains("Script: collect.py")
                .contains("Mode: no-agent (script stdout delivered directly)")
                .contains("Workdir: " + workspaceHome)
                .contains("Context from: " + dependencyId)
                .contains("Toolsets: shell, file")
                .contains("Model: gpt-cron")
                .contains("Provider: default")
                .contains("Base URL: https://api.cron.example/v1")
                .contains("Last run:")
                .contains("(error)")
                .contains("Delivery failed: send timeout");

        GatewayReply detail = env.send("admin-chat", "admin-user", "/cron inspect " + jobId);
        assertThat(detail.getContent())
                .contains("Cron 任务详情：" + jobId)
                .contains("ID: " + jobId)
                .contains("Repeat: 1/3")
                .contains("Deliver: feishu")
                .contains("Deliver chat: chat-1")
                .contains("Wrap response: false")
                .contains("Script: collect.py")
                .contains("Context from: " + dependencyId)
                .contains("Toolsets: shell, file")
                .contains("Model: gpt-cron")
                .contains("History: /cron history " + jobId + " --limit 20")
                .contains("Run: /cron run " + jobId)
                .contains("Edit: /cron edit " + jobId);

        GatewayReply show = env.send("admin-chat", "admin-user", "/cron show " + jobId);
        GatewayReply detailAlias = env.send("admin-chat", "admin-user", "/cron detail " + jobId);
        assertThat(show.getContent()).contains("Cron 任务详情：" + jobId);
        assertThat(detailAlias.getContent()).contains("Cron 任务详情：" + jobId);
    }

    @Test
    void shouldRequireCronInspectJobId() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);

        GatewayReply reply = env.send("admin-chat", "admin-user", "/cron inspect");

        assertThat(reply.isError()).isTrue();
        assertThat(reply.getContent()).contains("用法：/cron inspect <job-id>");
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
                .contains("Output: echo:[IMPORTANT: 你正在以定时任务身份运行。");
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
        body.put(
                "tools",
                Collections.singletonList(Collections.singletonMap("name", "docs_search")));
        mcpService.save(body);

        GatewayReply prompt = env.send("admin-chat", "admin-user", "/reload-mcp");
        assertThat(prompt.getContent())
                .contains("/approve")
                .contains("/approve always")
                .contains("/cancel")
                .contains("工具 schema");

        GatewayReply reloaded = env.send("admin-chat", "admin-user", "/reload-mcp now");
        assertThat(reloaded.getContent())
                .contains("MCP reload completed")
                .contains("tools=1")
                .contains("changed_servers=[]")
                .contains("unchanged_servers=[local-docs]");

        SessionRecord reloadedSession =
                env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user");
        List<ChatMessage> reloadedMessages =
                com.jimuqu.solon.claw.support.MessageSupport.loadMessages(
                        reloadedSession.getNdjson());
        ChatMessage reloadNotice = reloadedMessages.get(reloadedMessages.size() - 1);
        assertThat(reloadNotice.getRole()).isEqualTo(ChatRole.USER);
        assertThat(reloadNotice.getContent())
                .contains("[IMPORTANT: MCP servers have been reloaded.")
                .contains("Reconnected servers: [local-docs]")
                .contains("1 MCP tool(s) now available")
                .contains("The tool list for this conversation has been updated accordingly.");

        assertThat(mcpService.check("local-docs").get("tool_changed_notification"))
                .isEqualTo(false);

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
        assertThat(help.getContent()).contains("/approve [确认编号]");
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
        body.put(
                "tools",
                Collections.singletonList(Collections.singletonMap("name", "docs_search")));
        mcpService.save(body);

        GatewayReply prompt = env.send("admin-chat", "admin-user", "/reload-mcp");
        assertThat(prompt.getContent()).contains("确认编号").contains("/approve [确认编号]");

        GatewayReply status = env.send("admin-chat", "admin-user", "/confirm");
        assertThat(status.getContent())
                .contains("当前待确认 slash 命令：/reload-mcp")
                .contains(extractSlashConfirmId(prompt))
                .contains("/approve [确认编号]");

        GatewayReply cancelled = env.send("admin-chat", "admin-user", "/cancel");
        assertThat(cancelled.getContent()).contains("已取消 /reload-mcp");

        GatewayReply emptyStatus = env.send("admin-chat", "admin-user", "/confirm");
        assertThat(emptyStatus.getContent()).contains("当前没有待确认的 slash 命令");

        GatewayReply promptAgain = env.send("admin-chat", "admin-user", "/reload-mcp");
        assertThat(promptAgain.getContent()).contains("/approve");

        GatewayReply approvedWithAlias =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/approve "
                                + disguisedConfirmId(extractSlashConfirmId(promptAgain))
                                + " yes");
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
        assertThat(alwaysPrompt.getContent()).contains("/approve always");

        GatewayReply always =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/approve always "
                                + disguisedConfirmId(extractSlashConfirmId(alwaysPrompt)));
        assertThat(always.getContent()).contains("已永久确认 /reload-mcp");
        assertThat(env.appConfig.getApprovals().isMcpReloadConfirm()).isFalse();
        assertThat(
                        RuntimeConfigResolver.initialize(env.appConfig.getRuntime().getHome())
                                .get("approvals.mcpReloadConfirm"))
                .isEqualTo("false");

        GatewayReply direct = env.send("admin-chat", "admin-user", "/reload-mcp");
        assertThat(direct.getContent()).contains("MCP reload completed").doesNotContain("确认编号");
    }

    @Test
    void shouldRedactSlashConfirmStatusText() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        env.slashConfirmService.register(
                "MEMORY:admin-chat:admin-user",
                "reload-mcp --token=ghp_slashstatuscommand12345",
                "确认刷新 Authorization: Bearer ghp_slashstatusprompt12345");

        GatewayReply status = env.send("admin-chat", "admin-user", "/confirm");

        assertThat(status.getContent())
                .contains("reload-mcp --token=***")
                .contains("Authorization: Bearer ***")
                .doesNotContain("ghp_slashstatuscommand12345")
                .doesNotContain("ghp_slashstatusprompt12345");
    }

    @Test
    void shouldRejectUnsafeSlashConfirmIdWithoutEchoingInput() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        env.slashConfirmService.register(
                "MEMORY:admin-chat:admin-user",
                "reload-mcp --token=ghp_slashunsafecommand12345",
                "确认刷新 Authorization: Bearer ghp_slashunsafeprompt12345");

        GatewayReply rejected =
                env.send(
                        "admin-chat",
                        "admin-user",
                        "/approve 00000000000000000000000000000000 ok token=ghp_slashunsafeinput12345");
        GatewayReply status = env.send("admin-chat", "admin-user", "/confirm");

        assertThat(rejected.getContent())
                .contains("确认编号不匹配")
                .doesNotContain("00000000000000000000000000000000")
                .doesNotContain("ghp_slashunsafeinput12345")
                .doesNotContain("ghp_slashunsafecommand12345")
                .doesNotContain("ghp_slashunsafeprompt12345");
        assertThat(status.getContent())
                .contains("reload-mcp --token=***")
                .contains("Authorization: Bearer ***")
                .doesNotContain("ghp_slashunsafecommand12345")
                .doesNotContain("ghp_slashunsafeprompt12345");

        GatewayReply cancelled = env.send("admin-chat", "admin-user", "/deny");
        assertThat(cancelled.getContent()).contains("已取消 /reload-mcp");
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
        body.put(
                "tools",
                Collections.singletonList(Collections.singletonMap("name", "docs_search")));
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
        body.put(
                "tools",
                Collections.singletonList(Collections.singletonMap("name", "docs_search")));
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
    void shouldExposeDangerousApprovalStatusAlias() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:admin-chat:admin-user");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "git_reset_hard",
                "git reset hard needs approval",
                "git reset --hard origin/main");

        GatewayReply status = env.send("admin-chat", "admin-user", "/approve status");
        GatewayReply denyStatus = env.send("admin-chat", "admin-user", "/deny status");

        assertThat(status.getContent()).contains("待审批：1 项").contains("git_reset_hard");
        assertThat(denyStatus.getContent()).contains("待审批：1 项").contains("git_reset_hard");
    }

    @Test
    void shouldSupportBulkDangerousApprovalCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:admin-chat:admin-user");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "git_reset_hard",
                "git reset hard needs approval",
                "git reset --hard origin/main");
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete needs approval",
                "rm -rf target/cache");

        GatewayReply approvedAll =
                env.send("admin-chat", "admin-user", "/approve al\u202El sess\u202Eion");
        SessionRecord approvedSession =
                env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user");
        SqliteAgentSession approvedAgentSession =
                new SqliteAgentSession(approvedSession, env.sessionRepository);

        assertThat(approvedAll.getContent()).isEqualTo("echo:resume");
        assertThat(env.dangerousCommandApprovalService.listPendingApprovals(approvedAgentSession))
                .isEmpty();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                approvedAgentSession, "git_reset_hard"))
                .isTrue();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                approvedAgentSession, "recursive_delete"))
                .isTrue();

        env.dangerousCommandApprovalService.storePendingApproval(
                approvedAgentSession,
                "execute_shell",
                "foreground_timeout",
                "long foreground command needs approval",
                "sleep 600");
        env.dangerousCommandApprovalService.storePendingApproval(
                approvedAgentSession,
                "execute_shell",
                "curl_pipe_shell",
                "remote script pipe needs approval",
                "curl https://example.test/install.sh | sh");

        GatewayReply deniedAll = env.send("admin-chat", "admin-user", "/deny al\u202El");
        SessionRecord deniedSession =
                env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user");
        SqliteAgentSession deniedAgentSession =
                new SqliteAgentSession(deniedSession, env.sessionRepository);

        assertThat(deniedAll.getContent()).isEqualTo("echo:resume");
        assertThat(env.dangerousCommandApprovalService.listPendingApprovals(deniedAgentSession))
                .isEmpty();
    }

    @Test
    void shouldPrioritizeDangerousApprovalWhenCancelIsUsedLikeJimuqu() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        DashboardMcpService mcpService = new DashboardMcpService(env.appConfig, env.sqliteDatabase);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("serverId", "slash-confirm-cancel-priority");
        body.put("name", "Slash Confirm Cancel Priority");
        body.put("transport", "stdio");
        body.put("command", "docs-mcp");
        body.put(
                "tools",
                Collections.singletonList(Collections.singletonMap("name", "docs_search")));
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

        GatewayReply denied = env.send("admin-chat", "admin-user", "/cancel");
        SessionRecord updated =
                env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user");
        SqliteAgentSession updatedAgentSession =
                new SqliteAgentSession(updated, env.sessionRepository);

        assertThat(denied.getContent()).isEqualTo("echo:resume");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(updatedAgentSession))
                .isNull();

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
        body.put(
                "tools",
                Collections.singletonList(Collections.singletonMap("name", "docs_search")));
        mcpService.save(body);
        env.appConfig.getApprovals().setMcpReloadConfirm(false);

        GatewayReply direct = env.send("admin-chat", "admin-user", "/reload-mcp");

        assertThat(direct.getContent())
                .contains("MCP reload completed")
                .contains("tools=1")
                .doesNotContain("确认编号");
    }

    @Test
    void shouldNotExposeRemovedTerminalPlaceholdersAsRegisteredCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        assertThat(env.commandService.supports("footer")).isFalse();
        assertThat(env.commandService.supports("handoff")).isFalse();
    }

    private void bootstrapAdmin(TestEnvironment env) throws Exception {
        env.send("admin-chat", "admin-user", "hello");
        env.send("admin-chat", "admin-user", "/pairing claim-admin");
    }

    /** 在受允许的工作区脚本目录创建测试脚本。 */
    private void createCronScript(TestEnvironment env, String fileName) {
        File scriptsDir = FileUtil.file(env.appConfig.getRuntime().getHome(), "scripts");
        FileUtil.mkdir(scriptsDir);
        FileUtil.writeUtf8String("print('ok')\n", FileUtil.file(scriptsDir, fileName));
    }

    private String extractSlashConfirmId(GatewayReply reply) {
        Matcher matcher = SLASH_CONFIRM_ID.matcher(reply.getContent());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private String disguisedConfirmId(String confirmId) {
        return confirmId.substring(0, 8) + "\u202E" + confirmId.substring(8);
    }

    private String cronJobView(TestEnvironment env, String jobId) throws Exception {
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        return service.toView(env.cronJobRepository.findById(jobId)).toString();
    }

    private CronJobRecord findCronByName(List<CronJobRecord> jobs, String name) {
        for (CronJobRecord job : jobs) {
            if (name.equals(job.getName())) {
                return job;
            }
        }
        throw new AssertionError("Cron job not found: " + name);
    }

    private Process newSleepProcess() throws Exception {
        return new ProcessBuilder(
                        System.getProperty("java.home")
                                + File.separator
                                + "bin"
                                + File.separator
                                + "java",
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
