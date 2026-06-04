package com.jimuqu.solon.claw.context;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.MemorySnapshot;
import com.jimuqu.solon.claw.core.service.MemoryProvider;
import com.jimuqu.solon.claw.core.service.MemoryService;

/** 基于本地文件的内建长期记忆提供方。 */
public class BuiltinMemoryProvider implements MemoryProvider {
    private static final String MEMORY_GUIDANCE =
            "你具备跨会话长期记忆。请仅保存未来仍有价值的稳定事实：用户偏好、环境细节、项目约定、常见纠正、工具怪癖。"
                    + "\n不要把任务进度、一次性执行结果、临时 TODO、会话日志写入长期记忆；这些应依赖 session_search 回忆。"
                    + "\n用户偏好和 recurring corrections 的优先级高于程序性任务细节。"
                    + "\nToday Memory 只在你显式调用 memory 工具写入 today，或直接编辑 memory/YYYY-MM-DD.md 时更新。";

    private final MemoryService memoryService;

    public BuiltinMemoryProvider(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String name() {
        return "builtin";
    }

    @Override
    public String systemPromptBlock(String sourceKey) throws Exception {
        StringBuilder buffer = new StringBuilder();
        buffer.append("[Memory Guidance]\n").append(MEMORY_GUIDANCE);

        MemorySnapshot snapshot = memoryService.loadSnapshot();
        appendBlock(buffer, "Memory", snapshot.getMemoryText());
        appendBlock(buffer, "Today Memory", snapshot.getDailyMemoryText());
        return buffer.toString().trim();
    }

    @Override
    public String prefetch(String sourceKey, String userMessage) {
        return "";
    }

    @Override
    public void syncTurn(String sourceKey, String userMessage, String assistantMessage)
            throws Exception {}

    private void appendBlock(StringBuilder buffer, String label, String content) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        buffer.append("\n\n[").append(label).append("]\n").append(content.trim());
    }
}
