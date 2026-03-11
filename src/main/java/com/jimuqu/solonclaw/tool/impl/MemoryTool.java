package com.jimuqu.solonclaw.tool.impl;

import com.jimuqu.solonclaw.memory.file.MemoryFileManager;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/**
 * 记忆工具
 * <p>
 * 提供记忆读写功能，让 Agent 能够访问和管理记忆文件
 *
 * @author SolonClaw
 */
@Component
public class MemoryTool {

    @Inject
    private MemoryFileManager memoryFileManager;

    /**
     * 读取今日笔记
     */
    @ToolMapping(description = "读取今日笔记内容")
    public String readTodayNote() {
        String content = memoryFileManager.readTodayNote();
        if (content == null || content.isEmpty()) {
            return "今日笔记为空";
        }
        return content;
    }

    /**
     * 读取昨日笔记
     */
    @ToolMapping(description = "读取昨日笔记内容")
    public String readYesterdayNote() {
        String content = memoryFileManager.readYesterdayNote();
        if (content == null || content.isEmpty()) {
            return "昨日笔记为空";
        }
        return content;
    }

    /**
     * 读取长期记忆
     */
    @ToolMapping(description = "读取长期记忆内容")
    public String readLongTermMemory() {
        String content = memoryFileManager.readLongTermMemory();
        if (content == null || content.isEmpty()) {
            return "长期记忆为空";
        }
        return content;
    }

    /**
     * 追加内容到今日笔记
     */
    @ToolMapping(description = "追加内容到今日笔记")
    public String appendToTodayNote(
            @Param(description = "要追加的内容，建议包含时间戳和事件描述") String content
    ) {
        return memoryFileManager.appendToTodayNote(content);
    }

    /**
     * 追加内容到长期记忆的指定章节
     */
    @ToolMapping(description = "追加内容到长期记忆的指定章节")
    public String appendToLongTermMemory(
            @Param(description = "章节名称，如：用户偏好、项目知识、学到的经验、工作记录") String section,
            @Param(description = "要追加的内容") String content
    ) {
        return memoryFileManager.appendToLongTermMemory(section, content);
    }

    /**
     * 更新长期记忆的全部内容
     */
    @ToolMapping(description = "更新长期记忆的全部内容（会覆盖原有内容）")
    public String updateLongTermMemory(
            @Param(description = "新的长期记忆完整内容") String content
    ) {
        return memoryFileManager.updateLongTermMemory(content);
    }
}
