package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.media.VisionAnalysisService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 独立图片理解工具。 */
public class VisionAnalyzeTools {
    /** 图片理解服务。 */
    private final VisionAnalysisService visionAnalysisService;

    /**
     * 创建图片理解工具。
     *
     * @param visionAnalysisService 图片理解服务。
     */
    public VisionAnalyzeTools(VisionAnalysisService visionAnalysisService) {
        this.visionAnalysisService = visionAnalysisService;
    }

    /**
     * 分析一张图片并回答指定问题。
     *
     * @param imageUrl 图片 URL、data URL、media:// 引用或缓存内路径。
     * @param question 针对图片的问题。
     * @return JSON 结果，成功时包含 answer、provider、model 和 usage。
     */
    @ToolMapping(name = "vision_analyze", description = "Analyze an image and answer a question.")
    public String analyze(
            @Param(
                            name = "image_url",
                            description =
                                    "Image URL, data URL, media:// reference, or runtime-cache"
                                            + " path")
                    String imageUrl,
            @Param(name = "question", description = "Question about the image") String question) {
        VisionAnalysisService.VisionAnalysisOutcome outcome =
                visionAnalysisService.analyze(imageUrl, question);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", outcome.isSuccess() ? "success" : "error");
        if (outcome.isSuccess()) {
            result.put("answer", outcome.getAnswer());
            result.put("provider", outcome.getProvider());
            result.put("model", outcome.getModel());
            result.put("usage", outcome.getUsage());
        } else {
            result.put("error", outcome.getError());
        }
        return ONode.serialize(result);
    }
}
