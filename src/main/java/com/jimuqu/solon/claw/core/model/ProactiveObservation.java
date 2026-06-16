package com.jimuqu.solon.claw.core.model;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 主动协作采集阶段产出的原始观测模型。 */
@Getter
@Setter
@NoArgsConstructor
public class ProactiveObservation {
    /** 观测记录 ID；为空时由服务层补齐。 */
    private String observationId;

    /** 采集器名称；为空时回退到执行该观测的采集器名称。 */
    private String collector;

    /** 观测关联的来源键，用于后续去重和频率控制。 */
    private String sourceKey;

    /** 面向决策层的简短摘要。 */
    private String summary;

    /** 结构化载荷，会在落库前做脱敏与小尺寸裁剪。 */
    private Map<String, Object> payload = new LinkedHashMap<String, Object>();

    /** 当前观测状态，例如 NEW、OPEN、FAILED。 */
    private String status;

    /** 观测自身声明的错误摘要。 */
    private String error;
}
