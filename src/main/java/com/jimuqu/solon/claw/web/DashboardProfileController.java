package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.support.DashboardRequestBodies;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.DownloadedFile;
import org.noear.solon.core.handle.MethodType;
import org.noear.solon.core.handle.UploadedFile;

/** 暴露机器级 Profile 管理与独立网关状态 REST 接口。 */
@Controller
public class DashboardProfileController {
    /** 单个导入归档允许的最大压缩文件大小，解压上限由 Profile 归档层继续约束。 */
    private static final long MAX_ARCHIVE_BYTES = 8L * 1024L * 1024L * 1024L;

    /** Dashboard Profile 服务。 */
    private final DashboardProfileService profileService;

    /**
     * 创建 Profile 控制器。
     *
     * @param profileService Dashboard Profile 服务。
     */
    public DashboardProfileController(DashboardProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * 列出机器上的全部 Profile。
     *
     * @param context 当前请求上下文。
     * @return Profile 列表、sticky 活动项和当前 Dashboard 运行项。
     */
    @Mapping(value = "/api/profiles", method = MethodType.GET)
    public Map<String, Object> list(Context context) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return profileService.listProfiles();
                    }
                });
    }

    /**
     * 创建空白或克隆 Profile。
     *
     * @param context 当前请求上下文。
     * @return 新 Profile 视图。
     */
    @Mapping(value = "/api/profiles", method = MethodType.POST)
    public Map<String, Object> create(final Context context) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return profileService.createProfile(
                                DashboardRequestBodies.jsonObjectMap(context));
                    }
                });
    }

    /**
     * 返回 sticky 活动项和当前 Dashboard 运行项。
     *
     * @param context 当前请求上下文。
     * @return 活动 Profile 信息。
     */
    @Mapping(value = "/api/profiles/active", method = MethodType.GET)
    public Map<String, Object> active(Context context) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return profileService.activeProfile();
                    }
                });
    }

    /**
     * 设置未来 CLI 和网关启动使用的 sticky Profile。
     *
     * @param context 当前请求上下文。
     * @return 更新后的活动 Profile 信息。
     */
    @Mapping(value = "/api/profiles/active", method = MethodType.POST)
    public Map<String, Object> use(final Context context) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return profileService.useProfile(
                                DashboardRequestBodies.jsonObject(context).get("name").getString());
                    }
                });
    }

    /**
     * 导入用户上传的 tar.gz Profile 归档。
     *
     * @param context 当前请求上下文。
     * @param file multipart 文件字段。
     * @return 导入后的 Profile 视图。
     */
    @Mapping(value = "/api/profiles/import", method = MethodType.POST, multipart = true)
    public Map<String, Object> importArchive(final Context context, UploadedFile[] file) {
        UploadedFile archive = firstFile(file);
        if (archive == null) {
            return DashboardResponse.error(
                    context, 400, "PROFILE_BAD_REQUEST", "请选择 tar.gz Profile 归档。");
        }
        Path temporary = null;
        try {
            validateArchive(archive);
            temporary = Files.createTempFile("solonclaw-profile-upload-", ".tar.gz");
            archive.transferTo(temporary.toFile());
            return DashboardResponse.ok(
                    profileService.importProfile(temporary, context.param("name")));
        } catch (Exception e) {
            return profileError(context, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    temporary.toFile().deleteOnExit();
                }
            }
            try {
                archive.delete();
            } catch (IOException ignored) {
                // 上传临时文件由 Solon 在请求结束后继续回收。
            }
        }
    }

    /** 从本地目录或 Git 地址安装 Profile 分发。 */
    @Mapping(value = "/api/profiles/install", method = MethodType.POST)
    public Map<String, Object> installDistribution(final Context context) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return profileService.installDistribution(
                                DashboardRequestBodies.jsonObjectMap(context));
                    }
                });
    }

    /**
     * 返回指定 Profile 详情。
     *
     * @param context 当前请求上下文。
     * @param name Profile 名。
     * @return Profile 隔离路径和运行状态。
     */
    @Mapping(value = "/api/profiles/{name}", method = MethodType.GET)
    public Map<String, Object> show(Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return profileService.showProfile(name);
                    }
                });
    }

    /** 返回用于配置指定 Profile 的 CLI setup 命令。 */
    @Mapping(value = "/api/profiles/{name}/setup-command", method = MethodType.GET)
    public Map<String, Object> setupCommand(Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return profileService.setupCommand(name);
                    }
                });
    }

    /** 在本机终端中打开指定 Profile 的 setup 命令。 */
    @Mapping(value = "/api/profiles/{name}/open-terminal", method = MethodType.POST)
    public Map<String, Object> openTerminal(Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return profileService.openTerminal(name);
                    }
                });
    }

    /** 写入或清空指定 Profile 的人工职责说明。 */
    @Mapping(value = "/api/profiles/{name}/description", method = MethodType.PUT)
    public Map<String, Object> updateDescription(final Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
                        return profileService.updateDescription(
                                name,
                                body.get("description") == null
                                        ? ""
                                        : String.valueOf(body.get("description")));
                    }
                });
    }

    /** 使用目标 Profile 自身配置自动生成职责说明。 */
    @Mapping(value = "/api/profiles/{name}/describe-auto", method = MethodType.POST)
    public Map<String, Object> describeAutomatically(final Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
                        return profileService.describeAutomatically(
                                name, booleanValue(body.get("overwrite")));
                    }
                });
    }

    /** 读取指定 Profile 的 SOUL.md。 */
    @Mapping(value = "/api/profiles/{name}/soul", method = MethodType.GET)
    public Map<String, Object> soul(Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return profileService.readSoul(name);
                    }
                });
    }

    /** 原子替换指定 Profile 的 SOUL.md。 */
    @Mapping(value = "/api/profiles/{name}/soul", method = MethodType.PUT)
    public Map<String, Object> updateSoul(final Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
                        return profileService.updateSoul(
                                name,
                                body.get("content") == null
                                        ? ""
                                        : String.valueOf(body.get("content")));
                    }
                });
    }

    /** 更新指定 Profile 的默认模型。 */
    @Mapping(value = "/api/profiles/{name}/model", method = MethodType.PUT)
    public Map<String, Object> updateModel(final Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
                        return profileService.updateModel(
                                name,
                                body.get("provider") == null
                                        ? ""
                                        : String.valueOf(body.get("provider")),
                                body.get("model") == null ? "" : String.valueOf(body.get("model")));
                    }
                });
    }

    /** 创建或刷新指定 Profile 的快捷命令别名。 */
    @Mapping(value = "/api/profiles/{name}/alias", method = MethodType.PUT)
    public Map<String, Object> createAlias(final Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
                        return profileService.createAlias(
                                name,
                                body.get("alias") == null
                                        ? null
                                        : String.valueOf(body.get("alias")));
                    }
                });
    }

    /** 删除指定 Profile 的快捷命令别名。 */
    @Mapping(value = "/api/profiles/{name}/alias", method = MethodType.DELETE)
    public Map<String, Object> removeAlias(final Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
                        return profileService.removeAlias(
                                name,
                                body.get("alias") == null
                                        ? null
                                        : String.valueOf(body.get("alias")));
                    }
                });
    }

    /** 返回指定 Profile 的分发来源、版本和所有权信息。 */
    @Mapping(value = "/api/profiles/{name}/distribution", method = MethodType.GET)
    public Map<String, Object> distribution(Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return profileService.distributionInfo(name);
                    }
                });
    }

    /** 更新指定 Profile 的分发内容。 */
    @Mapping(value = "/api/profiles/{name}/distribution/update", method = MethodType.POST)
    public Map<String, Object> updateDistribution(final Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
                        return profileService.updateDistribution(
                                name, booleanValue(body.get("force_config")));
                    }
                });
    }

    /**
     * 重命名指定 Profile。
     *
     * @param context 当前请求上下文。
     * @param name 原 Profile 名。
     * @return 重命名后的 Profile 视图。
     */
    @Mapping(value = "/api/profiles/{name}", method = MethodType.PATCH)
    public Map<String, Object> rename(final Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return profileService.renameProfile(
                                name,
                                DashboardRequestBodies.jsonObject(context)
                                        .get("new_name")
                                        .getString());
                    }
                });
    }

    /**
     * 删除指定命名 Profile。
     *
     * @param context 当前请求上下文。
     * @param name Profile 名。
     * @return 删除前的 Profile 路径。
     */
    @Mapping(value = "/api/profiles/{name}", method = MethodType.DELETE)
    public Map<String, Object> delete(Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return profileService.deleteProfile(name);
                    }
                });
    }

    /**
     * 下载不含明文凭据的 Profile tar.gz。
     *
     * @param context 当前请求上下文。
     * @param name Profile 名。
     * @return 可流式下载并在关闭后删除的归档附件。
     */
    @Mapping(value = "/api/profiles/{name}/export", method = MethodType.GET)
    public Object export(Context context, String name) {
        Path archive = null;
        try {
            archive = profileService.exportProfile(name);
            InputStream input = new DeleteAfterCloseInputStream(archive);
            return new DownloadedFile(
                            "application/gzip", Files.size(archive), input, name + ".tar.gz")
                    .asAttachment(true);
        } catch (Exception e) {
            if (archive != null) {
                try {
                    Files.deleteIfExists(archive);
                } catch (IOException ignored) {
                    archive.toFile().deleteOnExit();
                }
            }
            return profileError(context, e);
        }
    }

    /**
     * 返回指定 Profile 独立网关的真实进程状态。
     *
     * @param context 当前请求上下文。
     * @param name Profile 名。
     * @return 网关 PID、状态文件和日志路径。
     */
    @Mapping(value = "/api/profiles/{name}/gateway", method = MethodType.GET)
    public Map<String, Object> gateway(Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return profileService.gatewayStatus(name);
                    }
                });
    }

    /** 启动指定 Profile 的独立网关。 */
    @Mapping(value = "/api/profiles/{name}/gateway/start", method = MethodType.POST)
    public Map<String, Object> startGateway(final Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return profileService.startGateway(
                                name, DashboardRequestBodies.jsonObjectMap(context));
                    }
                });
    }

    /** 停止指定 Profile 的独立网关。 */
    @Mapping(value = "/api/profiles/{name}/gateway/stop", method = MethodType.POST)
    public Map<String, Object> stopGateway(final Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return profileService.stopGateway(name);
                    }
                });
    }

    /** 重启指定 Profile 的独立网关。 */
    @Mapping(value = "/api/profiles/{name}/gateway/restart", method = MethodType.POST)
    public Map<String, Object> restartGateway(final Context context, final String name) {
        return execute(
                context,
                new ProfileAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return profileService.restartGateway(
                                name, DashboardRequestBodies.jsonObjectMap(context));
                    }
                });
    }

    /** 执行 Profile 操作并统一输出 Dashboard 响应。 */
    private Map<String, Object> execute(Context context, ProfileAction action) {
        try {
            return DashboardResponse.ok(action.run());
        } catch (Exception e) {
            return profileError(context, e);
        }
    }

    /** 将 Profile 异常映射为稳定 HTTP 状态与错误码。 */
    private Map<String, Object> profileError(Context context, Exception error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.contains("does not exist") || message.contains("not found")) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", error);
        }
        if (message.contains("already exists")) {
            return DashboardResponse.error(context, 409, "PROFILE_CONFLICT", error);
        }
        if (error instanceof IllegalArgumentException || error instanceof IOException) {
            return DashboardResponse.error(context, 400, "PROFILE_BAD_REQUEST", error);
        }
        return DashboardResponse.error(context, 500, "PROFILE_FAILED", error);
    }

    /** 将 Dashboard JSON 值转换为布尔开关。 */
    private static boolean booleanValue(Object value) {
        return value instanceof Boolean
                ? ((Boolean) value).booleanValue()
                : "true".equalsIgnoreCase(value == null ? "" : String.valueOf(value).trim());
    }

    /** 返回 multipart 参数中的首个非空文件。 */
    private UploadedFile firstFile(UploadedFile[] files) {
        if (files == null) {
            return null;
        }
        for (UploadedFile file : files) {
            if (file != null) {
                return file;
            }
        }
        return null;
    }

    /** 校验归档扩展名和压缩文件大小，归档成员安全由 ProfileArchive 继续检查。 */
    private void validateArchive(UploadedFile archive) throws IOException {
        String name = archive.getName() == null ? "" : archive.getName().toLowerCase();
        if (!name.endsWith(".tar.gz") && !name.endsWith(".tgz")) {
            throw new IllegalArgumentException("Profile 导入仅支持 .tar.gz 或 .tgz 归档。");
        }
        long size = archive.getContentSize();
        if (size <= 0L || size > MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("Profile 归档为空或超过八 GiB 限制。");
        }
    }

    /** 定义可能抛出文件或状态异常的 Profile 操作。 */
    private interface ProfileAction {
        /**
         * @return Profile 响应数据。
         */
        Map<String, Object> run() throws Exception;
    }

    /** 在 HTTP 响应关闭后删除一次性导出归档。 */
    private static final class DeleteAfterCloseInputStream extends FilterInputStream {
        /** 待删除归档。 */
        private final Path archive;

        /**
         * 打开归档输入流。
         *
         * @param archive 一次性导出归档。
         * @throws IOException 归档无法读取。
         */
        private DeleteAfterCloseInputStream(Path archive) throws IOException {
            super(Files.newInputStream(archive));
            this.archive = archive;
        }

        /** 关闭流并删除一次性归档。 */
        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                Files.deleteIfExists(archive);
            }
        }
    }
}
