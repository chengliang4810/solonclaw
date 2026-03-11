package com.jimuqu.solonclaw.context.components;

import com.jimuqu.solonclaw.memory.file.MemoryFileConfig;
import com.jimuqu.solonclaw.memory.file.MemoryFileManager;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 记忆上下文组件
 * <p>
 * 负责构建记忆上下文，注入到系统提示词
 *
 * @author SolonClaw
 */
@Component
public class MemoryContext {

    private static final Logger log = LoggerFactory.getLogger(MemoryContext.class);

    @Inject(required = false)
    private MemoryFileManager memoryFileManager;

    @Inject
    private MemoryFileConfig config;

    /**
     * 构建记忆上下文
     *
     * @param sessionId   会话ID
     * @param userMessage 用户消息
     * @param options     构建选项
     * @return 记忆上下文文本
     */
    public String build(String sessionId, String userMessage, Map<String, Object> options) {
        // 检查是否启用
        if (config == null || !config.isEnabled()) {
            log.debug("文件记忆已禁用，跳过构建");
            return "";
        }

        if (memoryFileManager == null) {
            log.warn("MemoryFileManager 未注入，跳过记忆上下文构建");
            return "";
        }

        try {
            StringBuilder context = new StringBuilder();
            context.append("## 记忆上下文\n\n");

            // 添加今日笔记
            String todayNote = memoryFileManager.readTodayNote();
            if (todayNote != null && !todayNote.isEmpty()) {
                context.append("### 今日笔记\n");
                context.append(todayNote);
                context.append("\n\n");
            }

            // 添加昨日笔记
            String yesterdayNote = memoryFileManager.readYesterdayNote();
            if (yesterdayNote != null && !yesterdayNote.isEmpty()) {
                context.append("### 昨日笔记\n");
                context.append(yesterdayNote);
                context.append("\n\n");
            }

            // 添加长期记忆
            String longTermMemory = memoryFileManager.readLongTermMemory();
            if (longTermMemory != null && !longTermMemory.isEmpty()) {
                context.append("### 长期记忆\n");
                context.append(longTermMemory);
                context.append("\n");
            }

            String result = context.toString();
            if (result.equals("## 记忆上下文\n\n")) {
                // 没有记忆内容
                return "";
            }

            log.debug("构建记忆上下文: {} 字符", result.length());
            return result;

        } catch (Exception e) {
            log.warn("构建记忆上下文失败: sessionId={}", sessionId, e);
            return "";
        }
    }
}
