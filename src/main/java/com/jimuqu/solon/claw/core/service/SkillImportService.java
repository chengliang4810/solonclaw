package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.skillhub.model.HubInstallRecord;
import com.jimuqu.solon.claw.skillhub.model.SkillBundle;
import com.jimuqu.solon.claw.skillhub.model.SkillImportResult;
import java.io.File;

/** 技能导入服务接口。 */
public interface SkillImportService {
    /**
     * 执行待恢复Imports相关逻辑。
     *
     * @param force force 参数。
     * @return 返回Pending Imports结果。
     */
    SkillImportResult processPendingImports(boolean force) throws Exception;

    /**
     * 安装包。
     *
     * @param bundle bundle 参数。
     * @param category 分类参数。
     * @param force force 参数。
     * @param sourceArtifact 来源Artifact参数。
     * @return 返回install包结果。
     */
    HubInstallRecord installBundle(
            SkillBundle bundle, String category, boolean force, File sourceArtifact)
            throws Exception;
}
