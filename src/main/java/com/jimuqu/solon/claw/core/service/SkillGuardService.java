package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.skillhub.model.InstallDecision;
import com.jimuqu.solon.claw.skillhub.model.ScanResult;
import java.io.File;

/** 技能安全扫描服务。 */
public interface SkillGuardService {
    /**
     * 执行scan技能相关逻辑。
     *
     * @param skillPath 文件或目录路径参数。
     * @param source 来源参数。
     * @return 返回scan技能结果。
     */
    ScanResult scanSkill(File skillPath, String source) throws Exception;

    /**
     * 判断是否需要Allow Install。
     *
     * @param result 结果响应或执行结果。
     * @param force force 参数。
     * @return 如果Allow Install满足条件则返回 true，否则返回 false。
     */
    InstallDecision shouldAllowInstall(ScanResult result, boolean force);

    /**
     * 格式化Report。
     *
     * @param result 结果响应或执行结果。
     * @return 返回Report结果。
     */
    String formatReport(ScanResult result);
}
