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
        FileUtil.writeUtf8String(
                "",
                FileUtil.file(
                        env.appConfig.getRuntime().getHome(), MemoryConstants.USER_FILE_NAME));
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

    /** 验证可直接复制 memory read 的 Markdown 单行执行替换。 */
    @Test
    void shouldReplaceEntryCopiedFromMemoryRead() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileMemoryService service = new FileMemoryService(env.appConfig);
        service.add("memory", "项目约定：版本为 v1");

        assertThat(service.replace("memory", "- 项目约定：版本为 v1", "项目约定：版本为 v2", "foreground"))
                .contains("已更新");
        assertThat(service.read("memory")).contains("版本为 v2").doesNotContain("版本为 v1");
    }

    /** 验证从 memory read 复制的连续多行可作为一个区间替换或删除。 */
    @Test
    void shouldReplaceAndRemoveMultipleEntriesCopiedFromMemoryRead() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileMemoryService service = new FileMemoryService(env.appConfig);
        service.add("memory", "项目约定：第一条");
        service.add("memory", "项目约定：第二条");
        service.add("memory", "项目约定：第三条");

        assertThat(service.replace("memory", "- 项目约定：第一条\n- 项目约定：第二条", "项目约定：已合并", "foreground"))
                .contains("已更新");
        assertThat(service.remove("memory", "- 项目约定：已合并\n- 项目约定：第三条", "foreground"))
                .contains("已删除");
        assertThat(service.read("memory"))
                .doesNotContain("项目约定：第一条")
                .doesNotContain("项目约定：第二条")
                .doesNotContain("项目约定：第三条")
                .doesNotContain("项目约定：已合并");
    }

    /** 验证后台新增记忆只在末尾追加，不重排人工维护的 USER.md 结构。 */
    @Test
    void shouldPreserveManualMarkdownWhenAddingBackgroundMemory() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileMemoryService service = new FileMemoryService(env.appConfig);
        File userFile =
                FileUtil.file(env.appConfig.getRuntime().getHome(), MemoryConstants.USER_FILE_NAME);
        String manual = FileUtil.readUtf8String(userFile);

        String result = service.add("user", "用户偏好：后台追加内容", "background_review");
        String updated = FileUtil.readUtf8String(userFile);

        assertThat(result).contains("已写入");
        assertThat(updated).startsWith(manual).endsWith("- 用户偏好：后台追加内容");
        assertThat(updated.substring(0, manual.length())).isEqualTo(manual);
    }

    /** 后台读取后若人工恰好保存新正文，追加只能落在人工新正文末尾，不能恢复旧快照。 */
    @Test
    void shouldNotOverwriteManualSaveRacingWithBackgroundAppend() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        File userFile =
                FileUtil.file(env.appConfig.getRuntime().getHome(), MemoryConstants.USER_FILE_NAME);
        String initial = "# 人工用户档案\n\n- 用户偏好：初始版本\n";
        String manualLatest = "# 人工用户档案\n\n- 用户偏好：人工并发保存的新版本";
        FileUtil.writeUtf8String(initial, userFile);
        FileMemoryService service =
                new FileMemoryService(env.appConfig) {
                    /** 在后台真正追加前模拟编辑器完成一次人工保存。 */
                    @Override
                    protected void appendUtf8(java.nio.file.Path target, String content)
                            throws java.io.IOException {
                        FileUtil.writeUtf8String(manualLatest, target.toFile());
                        super.appendUtf8(target, content);
                    }
                };

        String result = service.add("user", "用户偏好：后台候选", "background_review");
        String updated = FileUtil.readUtf8String(userFile);

        assertThat(result).contains("已写入");
        assertThat(updated)
                .startsWith(manualLatest)
                .contains("用户偏好：人工并发保存的新版本")
                .endsWith("- 用户偏好：后台候选")
                .doesNotContain("用户偏好：初始版本");
    }

    /** 目标缺失时人工若抢先创建文件，后台必须重读后分行追加。 */
    @Test
    void shouldRetryWhenManualSaveWinsMissingFileCreationRace() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        File userFile =
                FileUtil.file(env.appConfig.getRuntime().getHome(), MemoryConstants.USER_FILE_NAME);
        FileUtil.del(userFile);
        String manualLatest = "# 人工用户档案\n\n- 用户偏好：人工抢先创建";
        FileMemoryService service =
                new FileMemoryService(env.appConfig) {
                    /** 在后台 CREATE_NEW 前模拟人工编辑器抢先创建目标。 */
                    @Override
                    protected void createUtf8(java.nio.file.Path target, String content)
                            throws java.io.IOException {
                        FileUtil.writeUtf8String(manualLatest, target.toFile());
                        super.createUtf8(target, content);
                    }
                };

        String result = service.add("user", "用户偏好：后台候选", "background_review");
        String updated = FileUtil.readUtf8String(userFile);

        assertThat(result).contains("已写入");
        assertThat(updated)
                .startsWith(manualLatest + System.lineSeparator())
                .endsWith("- 用户偏好：后台候选")
                .doesNotContain(manualLatest + "- 用户偏好：后台候选");
    }

    /** 今日记忆已有人工正文时，后台追加必须始终从新行开始。 */
    @Test
    void shouldSeparateTodayAppendFromConcurrentManualContent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        File todayFile =
                FileUtil.file(
                        env.appConfig.getRuntime().getHome(),
                        MemoryConstants.DAILY_MEMORY_DIR_NAME,
                        java.time.LocalDate.now() + ".md");
        String manualLatest = "# 人工今日记录\n\n人工最后一行没有换行";
        FileUtil.writeUtf8String("", todayFile);
        FileMemoryService service =
                new FileMemoryService(env.appConfig) {
                    /** 在真正追加前模拟人工编辑器把空文件保存为非空正文。 */
                    @Override
                    protected void appendUtf8(java.nio.file.Path target, String content)
                            throws java.io.IOException {
                        FileUtil.writeUtf8String(manualLatest, target.toFile());
                        super.appendUtf8(target, content);
                    }
                };

        String result = service.add("today", "后台今日候选", "background_review");
        String updated = FileUtil.readUtf8String(todayFile);

        assertThat(result).contains("已写入 today");
        assertThat(updated)
                .startsWith(manualLatest + System.lineSeparator())
                .contains("- 后台今日候选")
                .doesNotContain("没有换行- 后台今日候选");
    }

    /** 验证人工 Markdown 无法无损往返时拒绝 replace/remove，并保留可恢复备份。 */
    @Test
    void shouldRejectDestructiveMutationOfManualMarkdownAndCreateBackups() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileMemoryService service = new FileMemoryService(env.appConfig);
        File memoryFile =
                FileUtil.file(
                        env.appConfig.getRuntime().getHome(), MemoryConstants.MEMORY_FILE_NAME);
        String manual = "# 人工记忆\n\n这里是人工维护的段落。\n\n- 项目约定：保留原文";
        FileUtil.writeUtf8String(manual, memoryFile);

        String replaced = service.replace("memory", "项目约定", "项目约定：前台改写", "foreground");
        String removed = service.remove("memory", "项目约定", "foreground");
        File[] backups =
                memoryFile
                        .getParentFile()
                        .listFiles(
                                file ->
                                        file.isFile()
                                                && file.getName()
                                                        .startsWith(
                                                                MemoryConstants.MEMORY_FILE_NAME
                                                                        + ".bak."));

        assertThat(replaced).contains("未执行").contains("人工或外部编辑").contains("备份位于");
        assertThat(removed).contains("未执行").contains("人工或外部编辑").contains("备份位于");
        assertThat(FileUtil.readUtf8String(memoryFile)).isEqualTo(manual);
        assertThat(backups).isNotNull().hasSize(2);
        assertThat(backups)
                .allSatisfy(
                        backup -> assertThat(FileUtil.readUtf8String(backup)).isEqualTo(manual));
    }

    /** 审批总开关关闭时，后台 replace/remove 仍必须只暂存，避免自动改写人工文件。 */
    @Test
    void shouldAlwaysStageDestructiveBackgroundMutations() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileMemoryService service = new FileMemoryService(env.appConfig);
        service.add("memory", "项目约定：保留人工条目", "foreground");
        String original = service.read("memory");

        String replaced =
                service.replace("memory", "项目约定：保留人工条目", "项目约定：后台替换", "background_review");
        String removed = service.remove("memory", "项目约定：保留人工条目", "background_review");

        assertThat(replaced).contains("已暂存待审批记忆变更");
        assertThat(removed).contains("已暂存待审批记忆变更");
        assertThat(service.read("memory")).isEqualTo(original);
        assertThat(service.listPendingApprovals())
                .hasSize(2)
                .allSatisfy(item -> assertThat(item.getOrigin()).isEqualTo("background_review"));
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
