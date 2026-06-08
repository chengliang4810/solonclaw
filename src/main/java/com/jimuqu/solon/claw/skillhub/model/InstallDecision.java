package com.jimuqu.solon.claw.skillhub.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 安装决策结果。 */
@Getter
@Setter
@NoArgsConstructor
public class InstallDecision {
    /** 是否启用allowed。 */
    private boolean allowed;

    /** 是否启用requiresConfirmation。 */
    private boolean requiresConfirmation;

    /** 记录Install中的原因。 */
    private String reason;
}
