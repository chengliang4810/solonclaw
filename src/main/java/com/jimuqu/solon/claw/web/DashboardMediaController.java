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

    @Mapping(value = "/api/jimuqu/media", method = MethodType.GET)
    public Map<String, Object> list(Context context) throws Exception {
        return DashboardResponse.ok(
                mediaService.list(context.param("platform"), context.paramAsInt("limit", 50)));
    }

    @Mapping(value = "/api/jimuqu/media/index", method = MethodType.POST)
    public Map<String, Object> index(Context context) throws Exception {
        return safeMedia(
                context,
                new MediaAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mediaService.indexLocal(
                                ONode.deserialize(
                                        ONode.ofJson(context.body()).toJson(), LinkedHashMap.class));
                    }
                });
    }

    @Mapping(value = "/api/jimuqu/media/{mediaId}", method = MethodType.GET)
    public Map<String, Object> detail(String mediaId) throws Exception {
        return DashboardResponse.ok(mediaService.detail(mediaId));
    }

    @Mapping(value = "/api/jimuqu/media/{mediaId}/refresh", method = MethodType.POST)
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

    @Mapping(value = "/api/jimuqu/media/{mediaId}/download", method = MethodType.POST)
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

    @Mapping(value = "/api/jimuqu/media/{mediaId}/reference", method = MethodType.POST)
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

    private interface MediaAction {
        Map<String, Object> run() throws Exception;
    }
}
