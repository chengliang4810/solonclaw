package com.jimuqu.solon.claw.proactive;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 仓库状态探测服务，只允许执行不会修改仓库或远程状态的读取操作。 */
public interface RepositoryProbeService {
    /**
     * 探测单个仓库引用的当前状态。
     *
     * @param reference 已规范化的仓库引用。
     * @return 成功读取到状态时返回状态快照；探测失败或引用不可用时返回 null。
     */
    RepositoryState probe(RepositoryReferenceExtractor.RepositoryReference reference);

    /** 仓库当前可比较状态，用于主动协作快照去重和变更观测。 */
    @Getter
    @Setter
    @NoArgsConstructor
    class RepositoryState {
        /** 被探测的仓库引用，通常是本地路径或远程 HTTPS URL。 */
        private String ref;

        /** 面向用户和诊断展示的仓库名称。 */
        private String displayName;

        /** 当前分支、默认分支或远程 HEAD 名称。 */
        private String branch;

        /** 当前提交哈希或远程 HEAD 哈希。 */
        private String commitHash;

        /** 显式 release 或标签标识，默认实现可为空。 */
        private String releaseId;

        /** 用于比较仓库状态是否变化的稳定哈希。 */
        private String stateHash;
    }
}
