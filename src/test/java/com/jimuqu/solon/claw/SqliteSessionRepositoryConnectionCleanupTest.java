package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteSessionRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SQLite 会话事务清理失败时的连接锁释放测试。 */
class SqliteSessionRepositoryConnectionCleanupTest {
    /** 自动提交复位故障信息。 */
    private static final String RESET_FAILURE = "forced auto-commit reset failure";

    /** 可注入自动提交复位故障的数据库。 */
    private ResetFailingSqliteDatabase database;

    /** 被测会话仓储。 */
    private SqliteSessionRepository repository;

    /** 创建隔离数据库与会话仓储。 */
    @BeforeEach
    void setUp() throws Exception {
        Path home = Files.createTempDirectory("session-connection-cleanup-test");
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.toString());
        config.getRuntime().setStateDb(home.resolve("data/state.db").toString());
        database = new ResetFailingSqliteDatabase(config);
        repository = new SqliteSessionRepository(database);
    }

    /** 关闭临时数据库持有的共享连接。 */
    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    /** 会话保存事务复位失败后必须释放连接锁。 */
    @Test
    void shouldReleaseConnectionAfterSaveResetFailure() throws Exception {
        SessionRecord session = session("save-reset-failure", "MEMORY:save-room:user");

        assertResetFailureReleasesConnection(() -> repository.save(session));
    }

    /** 外部消息追加事务复位失败后必须释放连接锁。 */
    @Test
    void shouldReleaseConnectionAfterAppendResetFailure() throws Exception {
        repository.bindNewSession("MEMORY:append-room:user");

        assertResetFailureReleasesConnection(
                () ->
                        repository.appendBoundOriginAssistantMessage(
                                PlatformType.MEMORY, "append-room", null, "user", "delivered"));
    }

    /** 会话删除事务复位失败后必须释放连接锁。 */
    @Test
    void shouldReleaseConnectionAfterDeleteResetFailure() throws Exception {
        SessionRecord session = repository.bindNewSession("MEMORY:delete-room:user");

        assertResetFailureReleasesConnection(() -> repository.delete(session.getSessionId()));
    }

    /** 注入自动提交复位故障，并从另一线程确认连接锁仍可获取。 */
    private void assertResetFailureReleasesConnection(ThrowingCallable operation) throws Exception {
        database.failNextReset();

        assertThatThrownBy(operation).isInstanceOf(SQLException.class).hasMessage(RESET_FAILURE);
        assertThat(database.getCloseCalls()).isEqualTo(1);

        ExecutorService executor =
                Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "sqlite-lock-probe");
                            thread.setDaemon(true);
                            return thread;
                        });
        try {
            Future<Void> probe =
                    executor.submit(
                            () -> {
                                try (Connection connection = database.openConnection()) {
                                    connection.setAutoCommit(true);
                                }
                                return null;
                            });
            assertThat(probe.get(2, TimeUnit.SECONDS)).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    /** 创建满足 SQLite 持久化约束的会话记录。 */
    private SessionRecord session(String sessionId, String sourceKey) {
        SessionRecord session = new SessionRecord();
        session.setSessionId(sessionId);
        session.setSourceKey(sourceKey);
        session.setBranchName("main");
        return session;
    }

    /** 在真实连接外注入一次自动提交复位故障。 */
    private static final class ResetFailingSqliteDatabase extends SqliteDatabase {
        /** 下一次自动提交复位是否失败。 */
        private AtomicBoolean failReset = new AtomicBoolean();

        /** 最近一次故障注入后的连接关闭次数。 */
        private AtomicInteger closeCalls = new AtomicInteger();

        /** 创建故障注入数据库。 */
        private ResetFailingSqliteDatabase(AppConfig config) throws SQLException {
            super(config);
        }

        /** 标记下一次恢复自动提交时抛出异常。 */
        private void failNextReset() {
            closeCalls.set(0);
            failReset.set(true);
        }

        /** 返回故障注入后的连接关闭次数。 */
        private int getCloseCalls() {
            return closeCalls.get();
        }

        /** 包装真实连接，在恢复自动提交时注入一次异常。 */
        @Override
        public Connection openConnection() throws SQLException {
            Connection delegate = super.openConnection();
            if (failReset == null) {
                return delegate;
            }
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, args) -> invoke(delegate, method, args));
        }

        /** 执行连接代理调用并记录关闭动作。 */
        private Object invoke(Connection delegate, Method method, Object[] args) throws Throwable {
            if ("setAutoCommit".equals(method.getName())
                    && args != null
                    && args.length == 1
                    && Boolean.TRUE.equals(args[0])
                    && failReset.compareAndSet(true, false)) {
                throw new SQLException(RESET_FAILURE);
            }
            if ("close".equals(method.getName()) && method.getParameterTypes().length == 0) {
                closeCalls.incrementAndGet();
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
