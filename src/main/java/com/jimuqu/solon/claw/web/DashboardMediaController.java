package com.jimuqu.solon.claw.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard media cache endpoints. */
@Controller
public class DashboardMediaController {
    private final DashboardMediaService mediaService;

    public DashboardMediaController(DashboardMediaService mediaService) {
        this.mediaService = mediaService;
    }

    @Mapping(value = "/api/media", method = MethodType.GET)
    public Map<String, Object> list(Context context) throws Exception {
        return DashboardResponse.ok(
                mediaService.list(context.param("platform"), context.paramAsInt("limit", 50)));
    }

    @Mapping(value = "/api/media/index", method = MethodType.POST)
    public Map<String, Object> index(Context context) throws Exception {
        return safeMedia(
                context,
                new MediaAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mediaService.indexLocal(body(context));
                    }
                });
    }

    @Mapping(value = "/api/media/{mediaId}", method = MethodType.GET)
    public Map<String, Object> detail(String mediaId) throws Exception {
        return DashboardResponse.ok(mediaService.detail(mediaId));
    }

    @Mapping(value = "/api/media/{mediaId}/refresh", method = MethodType.POST)
    public Map<String, Object> refresh(String mediaId) throws Exception {
        return safeMedia(
                Context.current(),
                new MediaAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mediaService.refresh(mediaId);
                    }
                });
    }

    @Mapping(value = "/api/media/{mediaId}/download", method = MethodType.POST)
    public Map<String, Object> download(String mediaId) throws Exception {
        return safeMedia(
                Context.current(),
                new MediaAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mediaService.download(mediaId);
                    }
                });
    }

    @Mapping(value = "/api/media/{mediaId}/reference", method = MethodType.POST)
    public Map<String, Object> reference(String mediaId) throws Exception {
        return safeMedia(
                Context.current(),
                new MediaAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mediaService.reference(mediaId);
                    }
                });
    }

    private Map<String, Object> safeMedia(Context context, MediaAction action) throws Exception {
        try {
            return DashboardResponse.ok(action.run());
        } catch (IllegalArgumentException e) {
            if (context != null) {
                context.status(400);
            }
            return DashboardResponse.error("MEDIA_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            if (context != null) {
                context.status(400);
            }
            return DashboardResponse.error("MEDIA_BAD_REQUEST", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(Context context) {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体读取失败 / Request body read failed");
        }
        if (raw == null || raw.trim().length() == 0) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            ONode node = ONode.ofJson(raw);
            if (node.toData() instanceof Map) {
                return ONode.deserialize(node.toJson(), LinkedHashMap.class);
            }
            throw new IllegalArgumentException(
                    "请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }

    private interface MediaAction {
        Map<String, Object> run() throws Exception;
    }
}
