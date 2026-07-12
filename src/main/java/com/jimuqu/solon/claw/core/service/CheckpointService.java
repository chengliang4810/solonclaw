package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.core.model.CheckpointRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import java.io.File;
import java.util.List;
import java.util.Map;

/** 文件快照服务接口。 */
public interface CheckpointService {
    /** 为结构化写操作创建快照。 */
    CheckpointRecord createCheckpoint(String sourceKey, String sessionId, List<File> files)
            throws Exception;

    /** 回滚来源键最近一次快照。 */
    CheckpointRecord rollbackLatest(String sourceKey) throws Exception;

    /** 回滚指定快照。 */
    CheckpointRecord rollback(String checkpointId) throws Exception;

    /**
     * 完整恢复 checkpoint，并在成功后移除会话最后一轮；失败时使用反向 checkpoint 补偿工作区。
     *
     * @param checkpointId 待恢复的 checkpoint 标识。
     * @param session 待同步裁剪历史的会话。
     * @param sessionRepository 会话持久化仓储。
     * @return 实际从会话历史移除的消息条数。
     */
    int rollbackSession(
            String checkpointId, SessionRecord session, SessionRepository sessionRepository)
            throws Exception;

    /** 判断来源键最近是否发生过结构化文件修改。 */
    boolean hasRecentCheckpoint(String sourceKey, long sinceEpochMillis) throws Exception;

    /** 列出来源键最近的 checkpoints。 */
    List<CheckpointRecord> listRecent(String sourceKey, int limit) throws Exception;

    /** 预览指定 checkpoint 的文件清单。 */
    Map<String, Object> preview(String checkpointId) throws Exception;

    /** 查看 checkpoint 存储状态。 */
    Map<String, Object> status(String sourceKey) throws Exception;

    /** 清理当前来源键多余或失效的 checkpoint。 */
    Map<String, Object> prune(String sourceKey) throws Exception;

    /** 删除当前来源键的全部 checkpoint。 */
    Map<String, Object> clear(String sourceKey) throws Exception;
}
