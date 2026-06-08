package com.jimuqu.solon.claw.core.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 承载模型元数据相关状态和辅助逻辑。 */
@Getter
@Setter
@NoArgsConstructor
public class ModelMetadata {
    /** 记录模型元数据中的提供方。 */
    private String provider;

    /** 记录模型元数据中的模型。 */
    private String model;

    /** 记录模型元数据中的协议方言。 */
    private String dialect;

    /** 保存aliases集合，维持调用顺序或去重语义。 */
    private List<String> aliases = new ArrayList<String>();

    /** 保存输入Modalities集合，维持调用顺序或去重语义。 */
    private List<String> inputModalities = new ArrayList<String>();

    /** 保存输出Modalities集合，维持调用顺序或去重语义。 */
    private List<String> outputModalities = new ArrayList<String>();

    /** 记录模型元数据中的上下文窗口。 */
    private int contextWindow;

    /** 记录模型元数据中的max输出。 */
    private int maxOutput;

    /** 是否启用supports工具。 */
    private boolean supportsTools;

    /** 是否启用supportsVision。 */
    private boolean supportsVision;

    /** 是否启用supports音频。 */
    private boolean supportsAudio;

    /** 是否启用supports附件。 */
    private boolean supportsAttachment;

    /** 是否启用supportsPdf。 */
    private boolean supportsPdf;

    /** 是否启用supportsMultimodal。 */
    private boolean supportsMultimodal;

    /** 是否启用supports推理。 */
    private boolean supportsReasoning;

    /** 是否启用supportsStructured输出。 */
    private boolean supportsStructuredOutput;

    /** 是否启用supportsOpenWeights。 */
    private boolean supportsOpenWeights;

    /** 是否启用supportsInterleaved。 */
    private boolean supportsInterleaved;

    /** 是否启用supports提示词缓存。 */
    private boolean supportsPromptCache;

    /** 是否启用supportsStreaming。 */
    private boolean supportsStreaming;

    /** 记录模型元数据中的apiURL。 */
    private String apiUrl;

    /** 记录模型元数据中的模型列表URL。 */
    private String modelListUrl;

    /** 记录模型元数据中的来源。 */
    private String source;

    /** 记录模型元数据中的来源追踪。 */
    private String provenance;

    /** 是否启用默认模型。 */
    private boolean defaultModel;

    /** 是否启用supported。 */
    private boolean supported;
}
