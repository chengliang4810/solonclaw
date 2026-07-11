package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** 验证复用线程上的跨 Profile 路径判断始终使用捕获的活动 Profile。 */
public class ToolCrossProfilePathSupportTest {
    @Test
    void shouldDistinguishOwnAndCrossProfilePathsOnPreheatedExecutor() throws Exception {
        Path root = Files.createTempDirectory("profile-cross-path");
        Path homeA = Files.createDirectories(root.resolve("profiles/a"));
        Path homeB = Files.createDirectories(root.resolve("profiles/b"));
        Path skillsA = Files.createDirectories(homeA.resolve("skills"));
        Path skillsB = Files.createDirectories(homeB.resolve("skills"));
        Path workRoot = Files.createDirectories(root.resolve("workspace"));
        String previous = System.getProperty("solonclaw.profile.root");
        System.setProperty("solonclaw.profile.root", root.toString());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThat(executor.submit(ProfileRuntimeScope::current).get()).isNull();

            PathObservation fromA;
            try (ProfileRuntimeScope.Scope ignored =
                    ProfileRuntimeScope.open(
                            "a", homeA, Collections.<String, String>emptyMap(), null)) {
                fromA =
                        executor.submit(
                                        ProfileRuntimeScope.capture(
                                                classify(
                                                        workRoot,
                                                        skillsA.resolve("own.md"),
                                                        skillsB.resolve("cross.md"))))
                                .get();
            }
            assertThat(fromA.own).isNull();
            assertThat(ToolCrossProfilePathSupport.warning(fromA.cross))
                    .contains("Profile 'b'")
                    .contains("当前 Profile 为 'a'");

            PathObservation fromB;
            try (ProfileRuntimeScope.Scope ignored =
                    ProfileRuntimeScope.open(
                            "b", homeB, Collections.<String, String>emptyMap(), null)) {
                fromB =
                        executor.submit(
                                        ProfileRuntimeScope.capture(
                                                classify(
                                                        workRoot,
                                                        skillsB.resolve("own.md"),
                                                        skillsA.resolve("cross.md"))))
                                .get();
            }
            assertThat(fromB.own).isNull();
            assertThat(ToolCrossProfilePathSupport.warning(fromB.cross))
                    .contains("Profile 'a'")
                    .contains("当前 Profile 为 'b'");
            assertThat(executor.submit(ProfileRuntimeScope::current).get()).isNull();
        } finally {
            executor.shutdownNow();
            restoreProperty("solonclaw.profile.root", previous);
        }
    }

    /** 创建同时检查自身路径和其他 Profile 路径的任务。 */
    private Callable<PathObservation> classify(Path workRoot, Path own, Path cross) {
        return new Callable<PathObservation>() {
            /** 返回当前 Profile 对两个目标的分类结果。 */
            @Override
            public PathObservation call() {
                return new PathObservation(
                        ToolCrossProfilePathSupport.classify(workRoot, own.toString()),
                        ToolCrossProfilePathSupport.classify(workRoot, cross.toString()));
            }
        };
    }

    /** 恢复测试前系统属性，避免影响同 JVM 的其他 Profile 用例。 */
    private void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    /** 保存自身和跨 Profile 两种路径分类。 */
    private static final class PathObservation {
        /** 自身路径不应触发跨 Profile 保护。 */
        private final ToolCrossProfilePathSupport.CrossProfileTarget own;

        /** 其他 Profile 路径应触发保护。 */
        private final ToolCrossProfilePathSupport.CrossProfileTarget cross;

        /** 创建路径分类观测值。 */
        private PathObservation(
                ToolCrossProfilePathSupport.CrossProfileTarget own,
                ToolCrossProfilePathSupport.CrossProfileTarget cross) {
            this.own = own;
            this.cross = cross;
        }
    }
}
