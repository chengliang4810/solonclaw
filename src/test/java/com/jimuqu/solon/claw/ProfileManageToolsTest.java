package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.tool.runtime.ProfileManageTools;
import com.jimuqu.solon.claw.web.DashboardProfileService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.core.Props;

/** 验证 default 专属 Profile 管理工具的生命周期和保护边界。 */
class ProfileManageToolsTest {
    /** 临时 default Profile 根目录。 */
    @TempDir Path root;

    /** 覆盖五个工具、default 保护和命名 Profile 数量上限。 */
    @Test
    void shouldManageOnlyLimitedNamedProfiles() throws Exception {
        String previousRoot = System.getProperty("solonclaw.profile.root");
        try {
            System.setProperty("solonclaw.profile.root", root.toString());
            AppConfig config = new AppConfig();
            config.getRuntime().setHome(root.toString());
            config.getProfiles().setMaxNamedProfiles(1);
            ProfileManager manager = new ProfileManager(root, root.resolve("bin"), "solonclaw");
            DashboardProfileService service = new DashboardProfileService(manager);
            ProfileManageTools tools = new ProfileManageTools(config, manager, service);

            assertThat(tools.create("researcher", "研究资料", null, null)).contains("智能体已创建");
            assertThat(tools.list()).contains("researcher").contains("default");
            assertThat(tools.get("researcher")).contains("研究资料");
            assertThat(tools.update("researcher", null, "事实核验", null, null)).contains("事实核验");
            assertThat(tools.update("researcher", "writer", "不应写入", null, null))
                    .contains("每次只能修改名称、职责或模型中的一项");
            assertThat(tools.get("researcher")).contains("事实核验").doesNotContain("不应写入");
            assertThat(tools.create("writer", "写作", null, null)).contains("已达到上限 1");
            assertThat(tools.update("default", null, "禁止", null, null))
                    .contains("default 智能体不能被创建、修改或删除");
            assertThat(tools.delete("default")).contains("default 智能体不能被创建、修改或删除");
            assertThat(tools.delete("researcher")).contains("智能体已删除");
        } finally {
            if (previousRoot == null) {
                System.clearProperty("solonclaw.profile.root");
            } else {
                System.setProperty("solonclaw.profile.root", previousRoot);
            }
        }
    }

    /** 验证命名 Profile 上限由配置加载且默认值为十。 */
    @Test
    void shouldLoadNamedProfileLimit() {
        assertThat(new AppConfig().getProfiles().getMaxNamedProfiles()).isEqualTo(10);
        Props props = new Props();
        props.put("solonclaw.profiles.maxNamedProfiles", "3");
        assertThat(AppConfig.loadDetached(props).getProfiles().getMaxNamedProfiles()).isEqualTo(3);
    }

    /** 验证协作任务配置会复制到热刷新使用的稳定配置对象。 */
    @Test
    void shouldApplyProfileTaskConfig() {
        AppConfig current = new AppConfig();
        AppConfig latest = new AppConfig();
        latest.getTask().setProfileTaskMaxConcurrency(7);
        latest.getTask().setProfileTaskDefaultTimeoutMinutes(41);
        latest.getTask().setProfileTaskMaxTimeoutMinutes(180);
        latest.getTask().setProfileTaskMaxAttempts(4);

        current.applyFrom(latest);

        assertThat(current.getTask().getProfileTaskMaxConcurrency()).isEqualTo(7);
        assertThat(current.getTask().getProfileTaskDefaultTimeoutMinutes()).isEqualTo(41);
        assertThat(current.getTask().getProfileTaskMaxTimeoutMinutes()).isEqualTo(180);
        assertThat(current.getTask().getProfileTaskMaxAttempts()).isEqualTo(4);
    }
}
