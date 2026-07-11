package com.jimuqu.solon.claw.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 复用网关启动时使用的轻量 Profile 枚举入口，不读取配置、会话或进程状态。 */
public final class ProfileMultiplexProfiles {
    /** 合法命名 Profile 的安全标识格式。 */
    private static final Pattern PROFILE_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    /** 工具类不保存实例状态。 */
    private ProfileMultiplexProfiles() {}

    /**
     * 返回 default 和全部合法命名 Profile，命名项按文件名稳定排序。
     *
     * @param root default Profile 根目录。
     * @return 复用网关应承载的 Profile 名。
     * @throws IOException Profile 目录无法读取。
     */
    public static List<String> names(Path root) throws IOException {
        if (root == null) {
            throw new IllegalArgumentException("Profile root is required.");
        }
        List<String> result = new ArrayList<String>();
        result.add("default");
        Path profiles = root.toAbsolutePath().normalize().resolve("profiles");
        if (!Files.isDirectory(profiles)) {
            return result;
        }
        List<String> named = new ArrayList<String>();
        try (Stream<Path> stream = Files.list(profiles)) {
            stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !"default".equals(name))
                    .filter(name -> PROFILE_NAME.matcher(name).matches())
                    .forEach(named::add);
        }
        Collections.sort(named);
        result.addAll(named);
        return result;
    }
}
