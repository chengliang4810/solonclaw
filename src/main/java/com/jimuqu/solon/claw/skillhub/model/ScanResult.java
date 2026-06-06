package com.jimuqu.solon.claw.skillhub.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 技能扫描结果。 */
@Getter
@Setter
@NoArgsConstructor
public class ScanResult {
    /** 记录Scan中的技能名称。 */
    private String skillName;

    /** 记录Scan中的来源。 */
    private String source;

    /** 记录Scan中的trust级别。 */
    private String trustLevel;

    /** 记录Scan中的判定。 */
    private String verdict;

    /** 保存findings集合，维持调用顺序或去重语义。 */
    private List<Finding> findings = new ArrayList<Finding>();

    /** 记录Scan中的scanned时间。 */
    private String scannedAt;

    /** 记录Scan中的摘要。 */
    private String summary;
}
