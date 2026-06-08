package com.jimuqu.solon.claw.security;

import cn.hutool.core.util.StrUtil;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 校验Solon项目Docker根用户安全边界，阻止不符合约束的运行路径。 */
public final class SolonClawDockerRootGuard {
    /** 官方Docker镜像环境标记的统一常量值。 */
    public static final String OFFICIAL_IMAGE_ENV = "SOLONCLAW_OFFICIAL_DOCKER_IMAGE";

    /** 允许root启动的显式开关的统一常量值。 */
    public static final String ALLOW_ROOT_ENV = "SOLONCLAW_ALLOW_ROOT_GATEWAY";

    /** 容器内应用根目录的统一常量值。 */
    public static final String APP_HOME = "/app";

    /** 入口脚本的统一常量值。 */
    public static final String ENTRYPOINT = "/app/docker-entrypoint.sh";

    /** 创建Solon项目Docker根用户保护实例。 */
    private SolonClawDockerRootGuard() {}

    /** 校验服务进程是否允许启动。 */
    public static void requireServerMayStart() {
        requireServerMayStart(
                System.getenv(OFFICIAL_IMAGE_ENV),
                System.getenv(ALLOW_ROOT_ENV),
                currentUid(),
                System.getProperty("user.name"),
                new File(ENTRYPOINT));
    }

    /**
     * 校验服务进程是否允许启动。
     *
     * @param officialImage official图片参数。
     * @param allowRoot allowRoot开关值。
     * @param userName 用户名称参数。
     * @param entrypoint entrypoint 参数。
     */
    static void requireServerMayStart(
            String officialImage, String allowRoot, String userName, File entrypoint) {
        requireServerMayStart(officialImage, allowRoot, currentUid(), userName, entrypoint);
    }

    /**
     * 校验服务进程是否允许启动。
     *
     * @param officialImage official图片参数。
     * @param allowRoot allowRoot开关值。
     * @param uid 系统用户标识。
     * @param userName 用户名称参数。
     * @param entrypoint entrypoint 参数。
     */
    static void requireServerMayStart(
            String officialImage, String allowRoot, Integer uid, String userName, File entrypoint) {
        if (!isTruthy(officialImage)) {
            return;
        }
        if (isTruthy(allowRoot)) {
            return;
        }
        if (!isRootUser(uid, userName)) {
            return;
        }
        if (entrypoint == null || !entrypoint.isFile()) {
            return;
        }
        throw new IllegalStateException(rootRefusalMessage());
    }

    /**
     * 生成官方 Docker 镜像内拒绝 root 启动的错误提示。
     *
     * @return 返回根用户Refusal消息结果。
     */
    static String rootRefusalMessage() {
        return "Refusing to run the SolonClaw gateway as root inside the official Docker image. "
                + "The image entrypoint normally drops privileges to the 'solonclaw' user. "
                + "If you override entrypoint in Docker Compose, include "
                + ENTRYPOINT
                + " before the Java command. Running as root can leave root-owned files in "
                + APP_HOME
                + "/runtime and break later non-root dashboard/gateway starts. Set "
                + ALLOW_ROOT_ENV
                + "=1 only if you intentionally accept this risk.";
    }

    /**
     * 判断当前进程是否以 root 用户运行。
     *
     * @param uid 系统用户标识。
     * @param userName 用户名称参数。
     * @return 如果根用户用户满足条件则返回 true，否则返回 false。
     */
    private static boolean isRootUser(Integer uid, String userName) {
        if (uid != null) {
            return uid.intValue() == 0;
        }
        return "root".equals(StrUtil.nullToEmpty(userName).trim());
    }

    /**
     * 读取当前 Linux 进程的有效用户标识。
     *
     * @return 返回当前Uid结果。
     */
    private static Integer currentUid() {
        File status = new File("/proc/self/status");
        if (!status.isFile()) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(status.toPath(), StandardCharsets.UTF_8)) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.substring("Uid:".length()).trim().split("\\s+");
                    if (parts.length > 0) {
                        return Integer.valueOf(parts[0]);
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    /**
     * 判断配置值是否表达启用语义。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Truthy满足条件则返回 true，否则返回 false。
     */
    private static boolean isTruthy(String value) {
        String normalized = StrUtil.nullToEmpty(value).trim().toLowerCase();
        return "1".equals(normalized)
                || "true".equals(normalized)
                || "yes".equals(normalized)
                || "on".equals(normalized);
    }
}
