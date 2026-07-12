package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

/** 验证微信用户可以审批自己创建的定时任务独立会话。 */
public class WeixinCronApprovalCommandTest {
    /** 微信审批命令应回查同一来源的定时任务会话，并恢复实际挂起来源。 */
    @Test
    void shouldApproveLatestCronPendingCommandFromOwningWeixinSource() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "WEIXIN:wx-room:wx-user";
        GatewayMessage message =
                new GatewayMessage(PlatformType.WEIXIN, "wx-room", "wx-user", "/approve always");
        env.sessionRepository.bindNewSession(sourceKey);

        CronJobRecord job = new CronJobRecord();
        job.setJobId("weixin-cron-approval");
        job.setName("微信审批测试任务");
        job.setCronExpr("0 0 * * *");
        job.setPrompt("检查版本");
        job.setSourceKey(sourceKey);
        job.setStatus("ACTIVE");
        env.cronJobRepository.save(job);

        SessionRecord cronRecord = env.sessionRepository.bindNewSession("CRON:" + job.getJobId());
        SqliteAgentSession cronSession =
                new SqliteAgentSession(cronRecord, env.sessionRepository);
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
        assertThat(
                        env.dangerousCommandApprovalService.isAlwaysApproved(
                                "execute_code",
                                "network_external_operation",
                                "request releases"))
                .isTrue();
        assertThat(reply.getRuntimeMetadata()).containsEntry("resumed_pending_run", Boolean.TRUE);
    }
}
