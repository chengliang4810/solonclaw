package com.jimuqu.solon.claw.skillhub.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Browse/search 结果页。 */
@Getter
@Setter
@NoArgsConstructor
public class SkillBrowseResult {
    /** 保存items集合，维持调用顺序或去重语义。 */
    private List<SkillMeta> items = new ArrayList<SkillMeta>();

    /** 记录技能Browse中的total。 */
    private int total;

    /** 记录技能Browse中的页面。 */
    private int page;

    /** 记录技能Browse中的页面大小。 */
    private int pageSize;

    /** 保存timedOutSources集合，维持调用顺序或去重语义。 */
    private List<String> timedOutSources = new ArrayList<String>();
}
