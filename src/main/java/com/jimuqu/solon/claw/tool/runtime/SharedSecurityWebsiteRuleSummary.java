package com.jimuqu.solon.claw.tool.runtime;

import java.util.ArrayList;
import java.util.List;

/** 汇总共享网站访问规则的加载数量、样例和跳过文件数。 */
final class SharedSecurityWebsiteRuleSummary {
    /** 保存rules集合，维持调用顺序或去重语义。 */
    final List<String> rules = new ArrayList<String>();

    /** 保存ruleSamples集合，维持调用顺序或去重语义。 */
    final List<String> ruleSamples = new ArrayList<String>();

    /** 已识别的共享网站访问规则总数。 */
    int ruleCount;

    /** 成功加载并参与合并的规则文件数量。 */
    int loadedFileCount;

    /** 因不存在、无效或不在允许目录内而跳过的规则文件数量。 */
    int skippedFileCount;
}
