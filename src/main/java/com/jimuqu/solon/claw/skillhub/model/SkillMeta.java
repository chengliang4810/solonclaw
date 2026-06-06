package com.jimuqu.solon.claw.skillhub.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Skills Hub 统一元数据。 */
@Getter
@Setter
@NoArgsConstructor
public class SkillMeta {
    /** 记录技能Meta中的名称。 */
    private String name;

    /** 记录技能Meta中的描述。 */
    private String description;

    /** 记录技能Meta中的来源。 */
    private String source;

    /** 记录技能Meta中的identifier。 */
    private String identifier;

    /** 记录技能Meta中的trust级别。 */
    private String trustLevel;

    /** 记录技能Meta中的repo。 */
    private String repo;

    /** 记录技能Meta中的路径。 */
    private String path;

    /** 保存tags集合，维持调用顺序或去重语义。 */
    private List<String> tags = new ArrayList<String>();

    /** 保存extra映射，便于按键快速查询。 */
    private Map<String, Object> extra = new LinkedHashMap<String, Object>();
}
