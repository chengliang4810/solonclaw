package com.jimuqu.solon.claw.core.model;

import cn.hutool.core.util.StrUtil;

/** 可独立参与系统提示词预算分配的记忆内容段。 */
public final class MemoryPromptSection {
    /** 记忆内容类型，用于确定稳定的预算优先级。 */
    public enum Type {
        /** 记忆使用和写入边界说明。 */
        GUIDANCE,
        /** 跨会话长期记忆。 */
        LONG_TERM,
        /** 当天产生的近期记忆。 */
        RECENT,
        /** 未声明细分类型的外部记忆。 */
        OTHER
    }

    /** 内容类型。 */
    private final Type type;

    /** 在系统提示词中展示的段标题。 */
    private final String label;

    /** 尚未添加安全边界的原始记忆正文。 */
    private final String content;

    /**
     * 创建记忆提示词段。
     *
     * @param type 内容类型。
     * @param label 展示标题。
     * @param content 原始正文。
     */
    public MemoryPromptSection(Type type, String label, String content) {
        this.type = type == null ? Type.OTHER : type;
        this.label = StrUtil.blankToDefault(label, "Memory");
        this.content = StrUtil.nullToEmpty(content);
    }

    /**
     * @return 内容类型。
     */
    public Type getType() {
        return type;
    }

    /**
     * @return 展示标题。
     */
    public String getLabel() {
        return label;
    }

    /**
     * @return 原始正文。
     */
    public String getContent() {
        return content;
    }
}
