package com.jimuqu.solon.claw.core.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Jimuqu 对齐的模型能力元数据。 */
@Getter
@Setter
@NoArgsConstructor
public class ModelMetadata {
    private String provider;
    private String model;
    private String dialect;
    private List<String> aliases = new ArrayList<String>();
    private List<String> inputModalities = new ArrayList<String>();
    private List<String> outputModalities = new ArrayList<String>();
    private int contextWindow;
    private int maxOutput;
    private boolean supportsTools;
    private boolean supportsVision;
    private boolean supportsAudio;
    private boolean supportsAttachment;
    private boolean supportsPdf;
    private boolean supportsMultimodal;
    private boolean supportsReasoning;
    private boolean supportsStructuredOutput;
    private boolean supportsOpenWeights;
    private boolean supportsInterleaved;
    private boolean supportsPromptCache;
    private boolean supportsStreaming;
    private String apiUrl;
    private String modelListUrl;
    private String source;
    private String provenance;
    private boolean defaultModel;
    private boolean supported;
}
