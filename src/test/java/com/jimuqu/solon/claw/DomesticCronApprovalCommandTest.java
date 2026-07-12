package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 验证国内渠道只能查看和处理自己来源创建的定时任务审批会话。 */
public class DomesticCronApprovalCommandTest {
    /** 已支持的非微信国内渠道都应回查同一来源的定时任务待审批项。 */
    @ParameterizedTest
    @MethodSource("nonWeixinDomesticPlatforms")
    void shouldApproveLatestCronPendingCommandFromOwningDomesticSource(PlatformType platform)
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String channel = platform.name().toLowerCase();
        String sourceKey = platform + ":" + channel + "-room:" + channel + "-user";
        GatewayMessage message =
                new GatewayMessage(
                        platform, channel + "-room", channel + "-user", "/approve always");
        env.sessionRepository.bindNewSession(sourceKey);

        CronJobRecord job = new CronJobRecord();
        job.setJobId(channel + "-cron-approval");
        job.setName("国内渠道审批测试任务");
        job.setCronExpr("0 0 * * *");
        job.setPrompt("检查版本");
        job.setSourceKey(sourceKey);
        job.setStatus("ACTIVE");
        env.cronJobRepository.save(job);

        SessionRecord cronRecord = env.sessionRepository.bindNewSession("CRON:" + job.getJobId());
        SqliteAgentSession cronSession = new SqliteAgentSession(cronRecord, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                cronSession,
                "execute_code",
                "network_external_operation",
                "定时任务访问外部接口",
                "request releases");

        GatewayReply reply = env.commandService.handle(message, "/approve always");

        SessionRecord updated = env.sessionRepository.getBoundSession("CRON:" + job.getJobId());
        assertThat(
                        env.dangerousCommandApprovalService.getPendingApproval(
                                new SqliteAgentSession(updated, env.sessionRepository)))
                .isNull();
        assertThat(reply.getContent()).contains("定时任务审批已通过");
        assertThat(reply.getRuntimeMetadata())
                .containsEntry("cron_approval_processed", Boolean.TRUE)
                .doesNotContainKey("resumed_pending_run");
    }

    /** 国内渠道应能查看并拒绝自己来源的定时任务待审批项。 */
    @ParameterizedTest
    @MethodSource("domesticPlatforms")
    void shouldListAndRejectCronPendingCommandFromOwningDomesticSource(PlatformType platform)
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String channel = platform.name().toLowerCase();
        String sourceKey = platform + ":" + channel + "-deny-room:" + channel + "-deny-user";
        GatewayMessage message =
                new GatewayMessage(platform, channel + "-deny-room", channel + "-deny-user", "");
        env.sessionRepository.bindNewSession(sourceKey);

        CronJobRecord job = new CronJobRecord();
        job.setJobId(channel + "-cron-deny");
        job.setName("国内渠道拒绝测试任务");
        job.setCronExpr("0 0 * * *");
        job.setPrompt("执行危险操作");
        job.setSourceKey(sourceKey);
        job.setStatus("ACTIVE");
        env.cronJobRepository.save(job);

        SessionRecord cronRecord = env.sessionRepository.bindNewSession("CRON:" + job.getJobId());
        SqliteAgentSession cronSession = new SqliteAgentSession(cronRecord, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                cronSession,
                "execute_code",
                "network_external_operation",
                "定时任务访问外部接口",
                "request releases");
        String selector =
                DangerousCommandApprovalService.approvalSelector(
                        env.dangerousCommandApprovalService.getPendingApproval(cronSession));

        GatewayReply list = env.commandService.handle(message, "/deny list");
        GatewayReply status = env.commandService.handle(message, "/approve status");
        GatewayReply denied = env.commandService.handle(message, "/deny " + selector);

        assertThat(list.getContent())
                .contains("pending=1")
                .contains(selector)
                .contains("network_external_operation");
        assertThat(status.getContent())
                .contains("pending=1")
                .contains(selector)
                .contains("network_external_operation");
        SqliteAgentSession reloadedCronSession =
                new SqliteAgentSession(
                        env.sessionRepository.getBoundSession("CRON:" + job.getJobId()),
                        env.sessionRepository);
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(reloadedCronSession))
                .isNull();
        assertThat(denied.getContent()).contains("任务保持暂停");
        assertThat(denied.getRuntimeMetadata())
                .containsEntry("cron_approval_processed", Boolean.TRUE)
                .doesNotContainKey("resumed_pending_run");
    }

    /** 统一从国内渠道白名单派生测试参数，新增受支持渠道时自动纳入回归。 */
    private static Stream<PlatformType> nonWeixinDomesticPlatforms() {
        return PlatformType.DOMESTIC_PLATFORMS.stream()
                .filter(platform -> platform != PlatformType.WEIXIN);
    }

    /** 返回全部已确认支持的国内渠道，确保审批拒绝链路按白名单统一回归。 */
    private static Stream<PlatformType> domesticPlatforms() {
        return PlatformType.DOMESTIC_PLATFORMS.stream();
    }
}
