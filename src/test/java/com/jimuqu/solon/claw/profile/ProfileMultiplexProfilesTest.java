package com.jimuqu.solon.claw.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证复用网关只枚举合法 Profile 目录。 */
class ProfileMultiplexProfilesTest {
    /** 临时 default Profile 根目录。 */
    @TempDir Path root;

    /** default 固定在首位，合法命名项排序，普通文件和非法目录忽略。 */
    @Test
    void enumeratesOnlyValidProfileDirectories() throws Exception {
        Files.createDirectories(root.resolve("profiles/zeta"));
        Files.createDirectories(root.resolve("profiles/alpha"));
        Files.createDirectories(root.resolve("profiles/../profiles/BadName"));
        Files.write(root.resolve("profiles/file-only"), new byte[] {1});

        assertThat(ProfileMultiplexProfiles.names(root))
                .isEqualTo(Arrays.asList("default", "alpha", "zeta"));
    }
}
