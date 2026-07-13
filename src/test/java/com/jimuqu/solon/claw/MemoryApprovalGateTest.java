package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.context.FileMemoryService;
import com.jimuqu.solon.claw.core.model.MemoryApprovalRequest;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.support.constants.MemoryConstants;
import com.jimuqu.solon.claw.tool.runtime.MemoryTools;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 验证记忆写入审批的共享服务边界和持久化重放行为。 */
public class MemoryApprovalGateTest {
    /** 验证开关、队列和批准拒绝状态可跨服务实例持久化。 */
    @Test
    void shouldPersistGateAndApproveOrRejectStagedMutations() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileMemoryService service = new FileMemoryService(env.appConfig);

        assertThat(service.isApprovalEnabled()).isFalse();
        service.add("user", "用户偏好：审批前已存在条目");
        service.setApprovalEnabled(true);
        service.add("memory", "长期偏好：审批后使用中文 token=ghp_memoryapproval12345");
        service.replace("memory", "长期偏好", "长期偏好：替换后使用中文");
        service.remove("user", "审批前已存在条目");

        FileMemoryService restarted = new FileMemoryService(env.appConfig);
        List<MemoryApprovalRequest> pending = restarted.listPendingApprovals();
        assertThat(restarted.isApprovalEnabled()).isTrue();
        assertThat(restarted.read("memory")).isBlank();
        assertThat(pending).hasSize(3);
        assertThat(pending.get(0).getId()).matches("[0-9a-f]{8}");
        assertThat(pending.get(0).getSubsystem()).isEqualTo("memory");
        assertThat(pending.get(0).getOrigin()).isEqualTo("background_review");
        assertThat(pending.get(0).getCreatedAt()).isPositive();
        assertThat(pending.get(0).getSummary())
                .contains("token=***")
                .doesNotContain("ghp_memoryapproval12345");
        assertThat(pending.get(0).getPayload().get("content"))
                .contains("token=***")
                .doesNotContain("ghp_memoryapproval12345");

        restarted.approve(pending.get(0).getId());
        restarted.reject(pending.get(1).getId());
        restarted.approve("all");

        assertThat(restarted.read("memory")).contains("审批后使用中文").doesNotContain("替换后使用中文");
        assertThat(restarted.read("user")).doesNotContain("审批前已存在条目");
        assertThat(restarted.listPendingApprovals()).isEmpty();
    }

    /** 验证工具和后台调用共享同一门禁，批准重放不会再次暂存。 */
    @Test
    void shouldRouteEveryServiceWriterThroughSameGateWithoutRecursiveReplay() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileMemoryService service = new FileMemoryService(env.appConfig);
        service.setApprovalEnabled(true);

        MemoryTools tools = new MemoryTools(service);
        String staged = tools.memory("add", "today", "今日审批边界验证", null);
        String approved = service.approve("all");

        assertThat(staged)
                .contains("\"staged\":true")
                .containsPattern("\\\"pending_id\\\":\\\"[0-9a-f]{8}\\\"");
        assertThat(approved).contains("已批准并应用 1 条");
        assertThat(service.read("today")).contains("今日审批边界验证");
        assertThat(service.listPendingApprovals()).isEmpty();
    }

    /** 验证批量批准遇到失败时保留失败项，但不回滚已成功应用的前序项。 */
    @Test
    void shouldKeepFailedApprovalAndRemoveEarlierSuccessfulItems() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileMemoryService service = new FileMemoryService(env.appConfig);
        service.setApprovalEnabled(true);
        service.add("memory", "长期偏好：先应用成功项");
        service.add("user", "用户偏好：失败项保留");
        service.add("today", "今日审批失败后仍继续应用");
        File blockedUserFile =
                FileUtil.file(env.appConfig.getRuntime().getHome(), MemoryConstants.USER_FILE_NAME);
        FileUtil.del(blockedUserFile);
        FileUtil.mkdir(blockedUserFile);
        FileUtil.writeUtf8String("block", FileUtil.file(blockedUserFile, "child"));

        assertThatThrownBy(() -> service.approve("all")).isInstanceOf(Exception.class);
        assertThat(service.read("memory")).contains("先应用成功项");
        assertThat(service.read("today")).contains("审批失败后仍继续应用");
        assertThat(service.listPendingApprovals())
                .hasSize(1)
                .first()
                .extracting(MemoryApprovalRequest::getAction)
                .isEqualTo("add");
        assertThat(service.listPendingApprovals().get(0).getPayload().get("target"))
                .isEqualTo("user");
    }

    /** 验证同一 Profile 的多个服务实例并发暂存时不会互相覆盖队列。 */
    @Test
    void shouldPreserveConcurrentStagesAcrossServiceInstances() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileMemoryService first = new FileMemoryService(env.appConfig);
        FileMemoryService second = new FileMemoryService(env.appConfig);
        first.setApprovalEnabled(true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<Throwable>();

        Thread left = stageThread(first, "长期偏好：并发写入一", ready, start, failures);
        Thread right = stageThread(second, "长期偏好：并发写入二", ready, start, failures);
        left.start();
        right.start();
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        left.join(5000L);
        right.join(5000L);

        assertThat(left.isAlive()).isFalse();
        assertThat(right.isAlive()).isFalse();
        assertThat(failures).isEmpty();
        assertThat(first.listPendingApprovals()).hasSize(2);
    }

    /** 验证已存在但损坏的审批状态不会按默认关闭处理并绕过门禁。 */
    @Test
    void shouldFailClosedWhenApprovalStateIsCorrupted() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileMemoryService service = new FileMemoryService(env.appConfig);
        service.setApprovalEnabled(true);
        File state =
                FileUtil.file(
                        env.appConfig.getRuntime().getHome(),
                        MemoryConstants.APPROVAL_STATE_FILE_NAME);
        FileUtil.writeUtf8String("", state);

        assertThatThrownBy(() -> service.add("memory", "长期偏好：不得绕过损坏门禁"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("审批状态文件为空");
        assertThat(service.read("memory")).doesNotContain("不得绕过损坏门禁");
    }

    /** 创建等待同一闸门后并发暂存记忆的测试线程。 */
    private Thread stageThread(
            FileMemoryService service,
            String content,
            CountDownLatch ready,
            CountDownLatch start,
            List<Throwable> failures) {
        return new Thread(
                () -> {
                    ready.countDown();
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        service.add("memory", content);
                    } catch (Throwable e) {
                        synchronized (failures) {
                            failures.add(e);
                        }
                    }
                });
    }
}
