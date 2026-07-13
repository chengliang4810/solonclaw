package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.core.model.MemoryApprovalRequest;
import com.jimuqu.solon.claw.core.model.MemorySearchResult;
import com.jimuqu.solon.claw.core.model.MemorySnapshot;
import java.util.List;

/** 长期记忆服务接口。 */
public interface MemoryService {
    /** 读取冻结快照。 */
    MemorySnapshot loadSnapshot() throws Exception;

    /** 读取目标内容。 */
    String read(String target) throws Exception;

    /** 使用统一索引搜索长期、用户、每日和专题记忆。 */
    default List<MemorySearchResult> search(String query, int limit) throws Exception {
        throw new UnsupportedOperationException("当前记忆服务不支持统一检索。");
    }

    /** 按受控相对路径读取记忆文件。 */
    default String get(String path) throws Exception {
        throw new UnsupportedOperationException("当前记忆服务不支持按路径读取。");
    }

    /** 添加条目。 */
    String add(String target, String content) throws Exception;

    /** 添加条目并记录审批来源；默认实现保持现有服务兼容。 */
    default String add(String target, String content, String origin) throws Exception {
        return add(target, content);
    }

    /** 替换条目。 */
    String replace(String target, String oldText, String newContent) throws Exception;

    /** 替换条目并记录审批来源；默认实现保持现有服务兼容。 */
    default String replace(String target, String oldText, String newContent, String origin)
            throws Exception {
        return replace(target, oldText, newContent);
    }

    /** 删除条目。 */
    String remove(String target, String matchText) throws Exception;

    /** 删除条目并记录审批来源；默认实现保持现有服务兼容。 */
    default String remove(String target, String matchText, String origin) throws Exception {
        return remove(target, matchText);
    }

    /** 查询记忆写入是否需要审批。 */
    default boolean isApprovalEnabled() throws Exception {
        return false;
    }

    /** 持久化记忆写入审批开关。 */
    default String setApprovalEnabled(boolean enabled) throws Exception {
        throw new UnsupportedOperationException("当前记忆服务不支持写入审批控制。");
    }

    /** 返回脱敏且限长的待审批变更列表。 */
    default List<MemoryApprovalRequest> listPendingApprovals() throws Exception {
        throw new UnsupportedOperationException("当前记忆服务不支持待审批队列。");
    }

    /** 批准指定标识或全部待审批变更，并真正应用写入。 */
    default String approve(String idOrAll) throws Exception {
        throw new UnsupportedOperationException("当前记忆服务不支持写入审批。");
    }

    /** 拒绝指定标识或全部待审批变更，并丢弃暂存内容。 */
    default String reject(String idOrAll) throws Exception {
        throw new UnsupportedOperationException("当前记忆服务不支持写入审批。");
    }
}
