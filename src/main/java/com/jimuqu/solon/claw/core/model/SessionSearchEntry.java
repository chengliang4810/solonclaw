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

    /** 会话创建时间，read 模式用于返回 session_meta.when。 */
    private long createdAt;

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

    /** 条目所属 Profile；当前 Profile 内普通查询可为空。 */
    private String profile;

    /** 命中消息角色。 */
    private String role;

    /** scroll 锚点之前仍存在的消息数量。 */
    private int messagesBefore;

    /** scroll 锚点之后仍存在的消息数量。 */
    private int messagesAfter;

    /** read 模式是否因会话过长而只返回首尾消息。 */
    private boolean truncated;

    /** read 模式原始消息总数。 */
    private int messageCount;

    /** 消息写入时间戳；历史快照未记录时为 0。 */
    private long messageTimestamp;

    /** 工具消息名称，或 assistant 工具调用的首个工具名称。 */
    private String messageToolName;

    /** 工具消息关联的调用 ID。 */
    private String toolCallId;

    /** assistant 消息携带的结构化工具调用列表 JSON。 */
    private String toolCallsJson;

    /** read 模式会话实际使用或覆盖的模型名称。 */
    private String sessionModel;
}
