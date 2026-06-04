package com.jimuqu.solon.claw.security;

import cn.hutool.core.util.StrUtil;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Startup guard for the official Docker image privilege-drop boundary. */
public final class SolonClawDockerRootGuard {
    public static final String OFFICIAL_IMAGE_ENV = "SOLONCLAW_OFFICIAL_DOCKER_IMAGE";
    public static final String ALLOW_ROOT_ENV = "SOLONCLAW_ALLOW_ROOT_GATEWAY";
    public static final String APP_HOME = "/app";
    public static final String ENTRYPOINT = "/app/docker-entrypoint.sh";

    private SolonClawDockerRootGuard() {}

    public static void requireServerMayStart() {
        requireServerMayStart(
                System.getenv(OFFICIAL_IMAGE_ENV),
                System.getenv(ALLOW_ROOT_ENV),
                currentUid(),
                System.getProperty("user.name"),
                new File(ENTRYPOINT));
    }

    static void requireServerMayStart(
            String officialImage, String allowRoot, String userName, File entrypoint) {
        requireServerMayStart(officialImage, allowRoot, currentUid(), userName, entrypoint);
    }

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

    private static boolean isRootUser(Integer uid, String userName) {
        if (uid != null) {
            return uid.intValue() == 0;
        }
        return "root".equals(StrUtil.nullToEmpty(userName).trim());
    }

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

    private static boolean isTruthy(String value) {
        String normalized = StrUtil.nullToEmpty(value).trim().toLowerCase();
        return "1".equals(normalized)
                || "true".equals(normalized)
                || "yes".equals(normalized)
                || "on".equals(normalized);
    }
}
