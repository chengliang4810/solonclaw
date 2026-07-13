package com.jimuqu.solon.claw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.MemorySnapshot;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.tool.runtime.MemoryApprovalCoordinator;
import com.jimuqu.solon.claw.tool.runtime.MemoryTools;
import com.jimuqu.solon.claw.tui.TerminalUiRpcService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 验证记忆工具只在前台终端会话内联消费持久化待审批项。 */
class MemoryToolsInlineApprovalTest {
    /** 清理线程级运行上下文，避免影响同一测试进程。 */
    @AfterEach
    void clearContext() {
        AgentRunContext.setCurrent(null);
    }

    /** 前台批准应应用暂存项，后台来源即使存在同会话绑定也只能保持暂存。 */
    @Test
    void shouldInlineApproveOnlyForegroundTerminalRun() throws Exception {
        RecordingMemoryService service = new RecordingMemoryService();
        MemoryApprovalCoordinator coordinator = new MemoryApprovalCoordinator(500L);
        Object owner = new Object();
        coordinator.bindSession(
                "s1",
                owner,
                request ->
                        coordinator.respondIfPending(
                                request.getSessionId(), request.getApprovalId(), "once", owner));
        MemoryTools tools = new MemoryTools(service, coordinator);

        AgentRunContext foreground =
                new AgentRunContext(
                        null, "r1", "s1", TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX + "s1");
        foreground.setRunKind("conversation");
        AgentRunContext.setCurrent(foreground);
        String approved = tools.memory("add", "memory", "value", null);
        assertTrue(approved.contains("applied"));
        assertEquals(1, service.approved.get());
        assertEquals("foreground", service.origin);

        foreground.setRunKind("subagent");
        String staged = tools.memory("add", "memory", "value", null);
        assertTrue(staged.contains("\"staged\":true"));
        assertEquals(1, service.approved.get());
        assertEquals("background_review", service.origin);
    }

    /** 工具边界拒绝伪目标和会进入审批展示的终端控制载荷。 */
    @Test
    void shouldRejectUnsafeTargetContentAndMatchText() throws Exception {
        MemoryTools tools =
                new MemoryTools(new RecordingMemoryService(), new MemoryApprovalCoordinator(50L));

        String invalidTarget = tools.memory("read", "memory\u001B[2J", null, null);
        String unsafeContent =
                tools.memory("add", "memory", "safe\u001B]52;c;c2VjcmV0\u0007value", null);
        String unsafeReplace = tools.memory("replace", "user", "replacement", "old\u001B[2Jtext");
        String unsafeRemove = tools.memory("remove", "today", null, "match\u0001text");
        String canonicalTarget = tools.memory("read", " USER ", null, null);

        assertTrue(invalidTarget.contains("Unsupported memory target"));
        assertTrue(unsafeContent.contains("terminal control sequences"));
        assertTrue(unsafeReplace.contains("terminal control sequences"));
        assertTrue(unsafeRemove.contains("terminal control sequences"));
        assertTrue(canonicalTarget.contains("\"target\":\"user\""));
        assertFalse(invalidTarget.contains("\u001B"));
        assertFalse(unsafeContent.contains("\u001B"));
    }

    /** 测试用服务只记录工具传入的来源与批准动作。 */
    private static final class RecordingMemoryService implements MemoryService {
        /** 已批准次数。 */
        private final AtomicInteger approved = new AtomicInteger();

        /** 最近一次写入来源。 */
        private String origin;

        /** 本测试不读取快照。 */
        @Override
        public MemorySnapshot loadSnapshot() {
            return null;
        }

        /** 本测试不读取目标内容。 */
        @Override
        public String read(String target) {
            return "";
        }

        /** 兼容旧接口并返回固定待审批标识。 */
        @Override
        public String add(String target, String content) {
            return "Staged. ID: 12345678";
        }

        /** 记录来源并返回固定待审批标识。 */
        @Override
        public String add(String target, String content, String origin) {
            this.origin = origin;
            return add(target, content);
        }

        /** 本测试不执行替换。 */
        @Override
        public String replace(String target, String oldText, String newContent) {
            return "";
        }

        /** 本测试不执行删除。 */
        @Override
        public String remove(String target, String matchText) {
            return "";
        }

        /** 记录批准调用并返回成功结果。 */
        @Override
        public String approve(String idOrAll) {
            approved.incrementAndGet();
            return "applied";
        }
    }
}
