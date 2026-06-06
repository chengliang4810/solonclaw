package com.jimuqu.solon.claw.skillhub.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 技能扫描单条发现。 */
@Getter
@Setter
@NoArgsConstructor
public class Finding {
    /** 记录Finding中的pattern标识。 */
    private String patternId;

    /** 记录Finding中的severity。 */
    private String severity;

    /** 记录Finding中的category。 */
    private String category;

    /** 记录Finding中的文件。 */
    private String file;

    /** 记录Finding中的行。 */
    private int line;

    /** 记录Finding中的match。 */
    private String match;

    /** 记录Finding中的描述。 */
    private String description;
}
