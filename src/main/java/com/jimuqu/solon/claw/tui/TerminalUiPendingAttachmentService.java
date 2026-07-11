package com.jimuqu.solon.claw.tui;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 保存终端 UI 各会话尚未提交的附件，并提供按轮次原子消费能力。 */
public class TerminalUiPendingAttachmentService {
    /** 会话到待提交附件快照的映射；同一键的 compute 操作定义提交轮次边界。 */
    private final Map<String, List<MessageAttachment>> pending =
            new ConcurrentHashMap<String, List<MessageAttachment>>();

    /**
     * 将附件加入指定会话的下一次提交。
     *
     * @param sessionId 目标会话标识。
     * @param attachment 已完成安全校验和缓存的附件。
     * @return 当前会话待提交附件数量。
     */
    public int add(String sessionId, MessageAttachment attachment) {
        String normalizedSessionId = requireSessionId(sessionId);
        if (attachment == null) {
            throw new IllegalArgumentException("attachment is required");
        }
        AtomicInteger count = new AtomicInteger();
        pending.compute(
                normalizedSessionId,
                (key, current) -> {
                    List<MessageAttachment> next =
                            current == null
                                    ? new ArrayList<MessageAttachment>()
                                    : new ArrayList<MessageAttachment>(current);
                    next.add(attachment);
                    count.set(next.size());
                    return Collections.unmodifiableList(next);
                });
        return count.get();
    }

    /**
     * 原子取出并清空指定会话的附件。
     *
     * <p>与 add 的同键 compute 串行化：drain 之前完成的附件属于本轮，之后完成的附件保留到下一轮。
     *
     * @param sessionId 提交所属会话。
     * @return 本轮附件副本；没有附件时返回空列表。
     */
    public List<MessageAttachment> drain(String sessionId) {
        String normalizedSessionId = requireSessionId(sessionId);
        AtomicReference<List<MessageAttachment>> drained =
                new AtomicReference<List<MessageAttachment>>();
        pending.computeIfPresent(
                normalizedSessionId,
                (key, current) -> {
                    drained.set(new ArrayList<MessageAttachment>(current));
                    return null;
                });
        List<MessageAttachment> result = drained.get();
        return result == null
                ? Collections.<MessageAttachment>emptyList()
                : Collections.unmodifiableList(result);
    }

    /** 清理指定会话尚未提交的附件，用于关闭、中断和测试隔离。 */
    public void clear(String sessionId) {
        String normalizedSessionId = StrUtil.nullToEmpty(sessionId).trim();
        if (StrUtil.isNotBlank(normalizedSessionId)) {
            pending.remove(normalizedSessionId);
        }
    }

    /** 返回指定会话当前待提交附件数。 */
    public int size(String sessionId) {
        String normalizedSessionId = StrUtil.nullToEmpty(sessionId).trim();
        List<MessageAttachment> attachments = pending.get(normalizedSessionId);
        return attachments == null ? 0 : attachments.size();
    }

    /** 校验所有状态操作必须绑定明确会话，避免附件落入共享默认会话。 */
    private String requireSessionId(String sessionId) {
        String normalizedSessionId = StrUtil.nullToEmpty(sessionId).trim();
        if (StrUtil.isBlank(normalizedSessionId)) {
            throw new IllegalArgumentException("session_id is required");
        }
        return normalizedSessionId;
    }
}
