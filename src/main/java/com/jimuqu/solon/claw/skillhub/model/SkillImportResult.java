package com.jimuqu.solon.claw.skillhub.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 自动导入结果。 */
@Getter
@Setter
@NoArgsConstructor
public class SkillImportResult {
    /** 记录技能导入中的installed次数。 */
    private int installedCount;

    /** 记录技能导入中的阻断次数。 */
    private int blockedCount;

    /** 记录技能导入中的archived次数。 */
    private int archivedCount;

    /** 保存messages集合，维持调用顺序或去重语义。 */
    private List<String> messages = new ArrayList<String>();
}
