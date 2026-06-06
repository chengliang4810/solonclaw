package com.jimuqu.solon.claw.skillhub.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Hub 已安装技能记录。 */
@Getter
@Setter
@NoArgsConstructor
public class HubInstallRecord {
    /** 记录中心Install中的名称。 */
    private String name;

    /** 记录中心Install中的来源。 */
    private String source;

    /** 记录中心Install中的identifier。 */
    private String identifier;

    /** 记录中心Install中的trust级别。 */
    private String trustLevel;

    /** 记录中心Install中的scan判定。 */
    private String scanVerdict;

    /** 记录中心Install中的content哈希。 */
    private String contentHash;

    /** 记录中心Install中的install路径。 */
    private String installPath;

    /** 保存files集合，维持调用顺序或去重语义。 */
    private List<String> files = new ArrayList<String>();

    /** 保存元数据映射，便于按键快速查询。 */
    private Map<String, Object> metadata = new LinkedHashMap<String, Object>();
}
