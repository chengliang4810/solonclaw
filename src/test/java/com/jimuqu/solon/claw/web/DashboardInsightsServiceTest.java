package com.jimuqu.solon.claw.web;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.context.SkillUsageTracker;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.io.File;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证控制台洞察会展示已安装技能，即使该技能尚未产生使用记录。 */
public class DashboardInsightsServiceTest {
    /** 已安装但零使用的技能应返回默认活动状态与零计数。 */
    @Test
    void shouldIncludeInstalledSkillWithoutUsage() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileUtil.writeUtf8String(
                "---\nname: unused-skill\ndescription: test\n---\n\n# Test\n",
                new File(env.appConfig.getRuntime().getSkillsDir(), "unused-skill/SKILL.md"));

        DashboardInsightsService service =
                new DashboardInsightsService(
                        env.appConfig,
                        new SkillUsageTracker(env.appConfig),
                        env.sessionRepository,
                        env.localSkillService);

        Map<String, Object> entry = (Map<String, Object>) service.skillUsage().get("unused-skill");
        assertThat(entry).containsEntry("state", "active").containsEntry("count", Long.valueOf(0L));
        assertThat((Map<String, Object>) service.overview().get("skills"))
                .containsEntry("tracked", Integer.valueOf(1))
                .containsEntry("active", Integer.valueOf(1));
    }
}
