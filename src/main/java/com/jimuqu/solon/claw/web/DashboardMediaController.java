package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.support.DashboardRequestBodies;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** 执行控制台媒体相关HTTP入口，负责请求参数转换与响应输出相关逻辑。 */
@Controller
public class DashboardMediaController {
    /** 注入媒体服务，用于调用对应业务能力。 */
    private final DashboardMediaService mediaService;

    /**
     * 创建控制台媒体控制器实例，并注入运行所需依赖。
     *
     * @param mediaService 媒体服务依赖。
     */
    public DashboardMediaController(DashboardMediaService mediaService) {
        this.mediaService = mediaService;
    }

    /**
     * 执行列表相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回list结果。
     */
    @Mapping(value = "/api/media", method = MethodType.GET)
    public Map<String, Object> list(Context context) throws Exception {
        return DashboardResponse.ok(
                mediaService.list(context.param("platform"), context.paramAsInt("limit", 50)));
    }

    /**
     * 执行索引相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回index结果。
     */
    @Mapping(value = "/api/media/index", method = MethodType.POST)
    public Map<String, Object> index(Context context) throws Exception {
        return safeMedia(
                context,
                new MediaAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mediaService.indexLocal(DashboardRequestBodies.jsonObjectMap(context));
                    }
                });
    }

    /**
     * 执行详情相关逻辑。
     *
     * @param mediaId 媒体标识。
     * @return 返回detail结果。
     */
    @Mapping(value = "/api/media/{mediaId}", method = MethodType.GET)
    public Map<String, Object> detail(String mediaId) throws Exception {
        return DashboardResponse.ok(mediaService.detail(mediaId));
    }

    /**
     * 执行刷新相关逻辑。
     *
     * @param mediaId 媒体标识。
     * @return 返回刷新结果。
     */
    @Mapping(value = "/api/media/{mediaId}/refresh", method = MethodType.POST)
    public Map<String, Object> refresh(String mediaId) throws Exception {
        return safeMedia(
                Context.current(),
                new MediaAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mediaService.refresh(mediaId);
                    }
                });
    }

    /**
     * 执行download相关逻辑。
     *
     * @param mediaId 媒体标识。
     * @return 返回download结果。
     */
    @Mapping(value = "/api/media/{mediaId}/download", method = MethodType.POST)
    public Map<String, Object> download(String mediaId) throws Exception {
        return safeMedia(
                Context.current(),
                new MediaAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mediaService.download(mediaId);
                    }
                });
    }

    /**
     * 执行引用相关逻辑。
     *
     * @param mediaId 媒体标识。
     * @return 返回reference结果。
     */
    @Mapping(value = "/api/media/{mediaId}/reference", method = MethodType.POST)
    public Map<String, Object> reference(String mediaId) throws Exception {
        return safeMedia(
                Context.current(),
                new MediaAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mediaService.reference(mediaId);
                    }
                });
    }

    /**
     * 生成安全展示用的媒体。
     *
     * @param context 当前请求或运行上下文。
     * @param action 操作参数。
     * @return 返回safe媒体结果。
     */
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

    /** 定义媒体Action的抽象契约，供不同运行时实现保持一致行为。 */
    private interface MediaAction {
        /**
         * 执行异步任务主体。
         *
         * @return 返回运行结果。
         */
        Map<String, Object> run() throws Exception;
    }
}
