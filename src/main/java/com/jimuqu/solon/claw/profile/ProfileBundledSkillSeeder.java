package com.jimuqu.solon.claw.profile;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/** 将当前发行版随附技能同步到新 Profile；具体分发源由安装包或嵌入环境提供。 */
public interface ProfileBundledSkillSeeder {
    /**
     * 把内置技能同步到目标 Profile，必须尊重用户文件且不能覆盖已修改技能。
     *
     * @param profileHome 新 Profile 工作区。
     * @return 已复制或更新的技能规范名；无内置技能时返回空列表。
     * @throws Exception 技能同步失败。
     */
    List<String> seed(Path profileHome) throws Exception;

    /**
     * 创建生产环境同步器，按显式配置、发行目录、类路径的顺序定位正式内置技能。
     *
     * @return 当前发行版的内置技能同步器。
     */
    static ProfileBundledSkillSeeder discover() {
        return DefaultProfileBundledSkillSeeder.discover();
    }

    /**
     * 创建固定目录同步器，供发行打包和隔离测试显式指定正式技能源。
     *
     * @param bundledSkillsRoot 包含分类目录和技能目录的根路径。
     * @return 固定目录同步器。
     */
    static ProfileBundledSkillSeeder fromDirectory(Path bundledSkillsRoot) {
        return DefaultProfileBundledSkillSeeder.fromDirectory(bundledSkillsRoot);
    }

    /**
     * 创建类路径同步器，用于从普通 classpath 目录或运行 Jar 中读取正式技能资源。
     *
     * @param classLoader 提供正式技能资源的类加载器。
     * @return 类路径同步器。
     */
    static ProfileBundledSkillSeeder fromClasspath(ClassLoader classLoader) {
        return DefaultProfileBundledSkillSeeder.fromClasspath(classLoader);
    }

    /** 返回不提供内置技能的默认实现，适用于精简发行包。 */
    static ProfileBundledSkillSeeder none() {
        return new ProfileBundledSkillSeeder() {
            /** 精简发行包没有可同步技能。 */
            @Override
            public List<String> seed(Path profileHome) {
                return Collections.emptyList();
            }
        };
    }
}
