package com.jimuqu.solon.claw.skillhub.model;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Skills Hub 统一技能包。 */
@Getter
@Setter
@NoArgsConstructor
public class SkillBundle {
    /** 记录技能包中的名称。 */
    private String name;

    /** 保存files映射，便于按键快速查询。 */
    private Map<String, String> files = new LinkedHashMap<String, String>();

    /** 记录技能包中的来源。 */
    private String source;

    /** 记录技能包中的identifier。 */
    private String identifier;

    /** 记录技能包中的trust级别。 */
    private String trustLevel;

    /** 保存元数据映射，便于按键快速查询。 */
    private Map<String, Object> metadata = new LinkedHashMap<String, Object>();
}
