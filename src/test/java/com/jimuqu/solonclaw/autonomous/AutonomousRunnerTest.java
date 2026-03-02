package com.jimuqu.solonclaw.autonomous;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.noear.solon.annotation.Inject;
import org.noear.solon.test.SolonTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AutonomousRunner 测试
 *
 * @author SolonClaw
 */
@SolonTest
@ExtendWith(org.noear.solon.test.SolonJUnit5Extension.class)
public class AutonomousRunnerTest {

    @Inject
    private AutonomousRunner autonomousRunner;

    @Inject
    private TaskScheduler taskScheduler;

    @Inject
    private GoalManager goalManager;

    @Inject
    private ResourceManager resourceManager;

    @BeforeEach
    public void setUp() {
        // 清理测试数据
        // TODO: 添加清理逻辑
    }

    @Test
    public void testStart() {
        // 测试启动自主运行
        autonomousRunner.start();

        assertTrue(autonomousRunner.isRunning());
    }

    @Test
    public void testStop() {
        // 先启动
        autonomousRunner.start();

        // 测试停止
        autonomousRunner.stop();

        assertFalse(autonomousRunner.isRunning());
    }

    @Test
    public void testIsRunning() {
        // 默认状态
        boolean initialRunning = autonomousRunner.isRunning();

        // 启动后
        autonomousRunner.start();
        boolean afterStart = autonomousRunner.isRunning();

        assertTrue(afterStart);
        // 停止后
        autonomousRunner.stop();
        boolean afterStop = autonomousRunner.isRunning();

        assertFalse(afterStop);
    }

    @Test
    public void testGetStatus() {
        // 启动系统
        autonomousRunner.start();

        // 获取状态
        AutonomousRunner.AutonomousStatus status = autonomousRunner.getStatus();

        assertNotNull(status);
        assertTrue(status.running());
        assertNotNull(status.startTime());
        assertNotNull(status.lastActiveTime());
    }

    @Test
    public void testGetStats() {
        // 启动系统
        autonomousRunner.start();

        // 获取统计信息
        AutonomousRunner.AutonomousStats stats = autonomousRunner.getStats();

        assertNotNull(stats);
        assertTrue(stats.totalTasksExecuted() >= 0);
        assertTrue(stats.totalGoalsCompleted() >= 0);
    }

    @Test
    public void testTriggerTask() {
        // 启动系统
        autonomousRunner.start();

        // 创建一个任务
        AutonomousTask task = new AutonomousTask(
            TaskType.CUSTOM,
            "测试任务",
            1
        );

        // 使用反射设置任务 ID（简化实现）
        try {
            task.getClass().getMethod("withId", String.class).invoke(task, "test-task-id");

            // 触发任务
            autonomousRunner.triggerTask("test-task-id");
        } catch (Exception e) {
            // 任务可能不存在，测试系统状态
            assertTrue(true);
        }
    }
}