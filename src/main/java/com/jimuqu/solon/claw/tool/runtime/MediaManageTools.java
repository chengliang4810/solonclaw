package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardMediaService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供媒体库查询和受控索引工具，复用 Dashboard 媒体服务。 */
public class MediaManageTools {
    /** Dashboard 媒体服务，用于复用媒体索引、路径保护和引用生成逻辑。 */
    private final DashboardMediaService dashboardMediaService;

    /**
     * 创建媒体管理工具。
     *
     * @param dashboardMediaService Dashboard 媒体服务。
     */
    public MediaManageTools(DashboardMediaService dashboardMediaService) {
        this.dashboardMediaService = dashboardMediaService;
    }

    /**
     * 查询或管理本地媒体缓存索引。
     *
     * @param action 操作名称。
     * @param mediaId 媒体标识。
     * @param platform 平台筛选。
     * @param limit 列表数量。
     * @param bodyJson index 动作请求体。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "media_manage",
            description =
                    "Inspect and manage dashboard media cache. Actions: list, detail, index, refresh, download, reference.")
    public String mediaManage(
            @Param(
                            name = "action",
                            description = "list, detail, index, refresh, download, reference")
                    String action,
            @Param(name = "media_id", required = false, description = "Media id")
                    String mediaId,
            @Param(name = "platform", required = false, description = "Platform filter")
                    String platform,
            @Param(
                            name = "limit",
                            required = false,
                            defaultValue = "50",
                            description = "Max rows for list")
                    Integer limit,
            @Param(
                            name = "body_json",
                            required = false,
                            description = "JSON body for action=index")
                    String bodyJson) {
        try {
            if (dashboardMediaService == null) {
                return ToolResultEnvelope.error("media service unavailable").toJson();
            }
            Map<String, Object> result = run(action, mediaId, platform, limit, bodyJson);
            return ToolResultEnvelope.ok("媒体管理完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行媒体管理动作。
     *
     * @param action 操作名称。
     * @param mediaId 媒体标识。
     * @param platform 平台筛选。
     * @param limit 列表数量。
     * @param bodyJson index 动作请求体。
     * @return 返回 Dashboard 媒体服务结果。
     */
    private Map<String, Object> run(
            String action, String mediaId, String platform, Integer limit, String bodyJson)
            throws Exception {
        String normalized =
                action == null ? "list" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if ("detail".equals(normalized)) {
            return dashboardMediaService.detail(mediaId);
        }
        if ("index".equals(normalized)) {
            return dashboardMediaService.indexLocal(body(bodyJson));
        }
        if ("refresh".equals(normalized)) {
            return dashboardMediaService.refresh(mediaId);
        }
        if ("download".equals(normalized)) {
            return dashboardMediaService.download(mediaId);
        }
        if ("reference".equals(normalized)) {
            return dashboardMediaService.reference(mediaId);
        }
        return dashboardMediaService.list(platform, limit == null ? 50 : limit.intValue());
    }

    /**
     * 解析媒体索引请求体 JSON。
     *
     * @param bodyJson 请求体 JSON。
     * @return 返回请求体 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> body(String bodyJson) {
        if (bodyJson == null || bodyJson.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        return ONode.deserialize(bodyJson, LinkedHashMap.class);
    }
}
