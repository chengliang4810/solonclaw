package com.jimuqu.solon.claw.profile.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.ProfileTaskRecord;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteProfileTaskRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证协作任务回复提交和通知的竞态边界。 */
class ProfileTaskCoordinatorTest {
    /** 测试数据库。 */
    private SqliteDatabase database;

    /** 任务仓储。 */
    private SqliteProfileTaskRepository repository;

    /** 仅记录通知的协调器。 */
    private RecordingCoordinator coordinator;

    /** 创建隔离控制面。 */
    @BeforeEach
    void setUp() throws Exception {
        Path home = Files.createTempDirectory("profile-task-coordinator-test");
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.toString());
        config.getRuntime().setStateDb(home.resolve("data/state.db").toString());
        database = new SqliteDatabase(config);
        repository = new SqliteProfileTaskRepository(database);
        coordinator = new RecordingCoordinator(repository, config);
    }

    /** 释放线程池和数据库。 */
    @AfterEach
    void tearDown() {
        coordinator.close();
        database.shutdown();
    }

    /** 错误回复按失败提交，耗尽次数时通知真实 BLOCKED 状态。 */
    @Test
    void shouldPersistErrorReplyAsBlocked() throws Exception {
        ProfileTaskRecord task = task("error");
        task.setMaxAttempts(1);
        repository.save(task);
        ProfileTaskRecord claimed = repository.claimNext(5);

        coordinator.finishReply(claimed, GatewayReply.error("model failed"));

        assertThat(repository.findById("error").getStatus()).isEqualTo("BLOCKED");
        assertThat(coordinator.statuses).containsExactly("BLOCKED");
    }

    /** 取消后的迟到成功结果被 CAS 拒绝且不发送虚假完成通知。 */
    @Test
    void shouldDropLateSuccessAfterCancellation() throws Exception {
        repository.save(task("cancelled"));
        ProfileTaskRecord claimed = repository.claimNext(5);
        repository.cancel("cancelled");

        coordinator.finishReply(claimed, GatewayReply.ok("late result"));

        assertThat(repository.findById("cancelled").getStatus()).isEqualTo("CANCELLED");
        assertThat(coordinator.statuses).isEmpty();
    }

    /** 构造最小任务。 */
    private static ProfileTaskRecord task(String id) {
        ProfileTaskRecord task = new ProfileTaskRecord();
        task.setTaskId(id);
        task.setSourceProfile("default");
        task.setTargetProfile("researcher");
        task.setSourceKey("MEMORY:room:user");
        task.setTitle(id);
        task.setPrompt("execute " + id);
        return task;
    }

    /** 替换外部通知，只记录提交状态。 */
    private static final class RecordingCoordinator extends ProfileTaskCoordinator {
        /** 已发送状态。 */
        private final List<String> statuses = new ArrayList<String>();

        /** 创建记录型协调器。 */
        RecordingCoordinator(SqliteProfileTaskRepository repository, AppConfig config) {
            super(repository, null, null, config);
        }

        /** 记录通知状态。 */
        @Override
        void notifySource(ProfileTaskRecord task, String status, String result, String error) {
            statuses.add(status);
        }
    }
}
