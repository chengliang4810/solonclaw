package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 会话搜索结果条目。 */
@Getter
@Setter
@NoArgsConstructor
public class SessionSearchEntry {
    /** 会话 ID。 */
    private String sessionId;

    /** 分支名。 */
    private String branchName;

    /** 标题。 */
    private String title;

    /** 最近更新时间。 */
    private long updatedAt;

    /** 匹配预览。 */
    private String matchPreview;

    /** 聚焦总结。 */
    private String summary;

    /** 记录会话搜索Entry中的运行标识。 */
    private String runId;

    /** 记录会话搜索Entry中的工具名称。 */
    private String toolName;

    /** 记录会话搜索Entry中的渠道。 */
    private String channel;

    /** 记录会话搜索Entry中的score。 */
    private long score;

    /** 搜索模式：discovery / scroll / browse。 */
    private String mode;

    /** 命中或窗口内消息 ID。 */
    private String messageId;

    /** 平台侧会话/消息锚点 ID。 */
    private String platformMessageId;

    /** 上游兼容命名的片段字段，默认与 matchPreview 保持一致。 */
    private String snippet;

    /** scroll 模式下是否为 aroundMessageId 锚点。 */
    private boolean anchor;
}
