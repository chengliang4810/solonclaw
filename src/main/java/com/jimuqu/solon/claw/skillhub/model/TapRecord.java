package com.jimuqu.solon.claw.skillhub.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** GitHub tap 记录。 */
@Getter
@Setter
@NoArgsConstructor
public class TapRecord {
    /** 记录来源库中的repo。 */
    private String repo;

    /** 记录来源库中的路径。 */
    private String path;
}
