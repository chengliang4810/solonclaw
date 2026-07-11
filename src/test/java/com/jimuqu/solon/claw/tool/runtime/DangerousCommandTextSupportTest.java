package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 验证危险命令文本解析能定位受管进程真正接收 stdin 的可执行程序。 */
class DangerousCommandTextSupportTest {
    /** 包装器、环境变量和绝对路径不得遮蔽真实解释器。 */
    @Test
    void shouldResolveWrappedExecutableName() {
        String[][] cases = {
            {"sh", "sh"},
            {"TOKEN=1 /usr/bin/python3 -i", "python3"},
            {"/usr/bin/env -i -u TOKEN FOO=bar node", "node"},
            {"sudo -S -p '' python3", "python3"},
            {"sudo --user root -- bash", "bash"},
            {"doas python3", "python3"},
            {"pkexec node", "node"},
            {"runas /user:Administrator powershell", "powershell"},
            {"command -p sh", "sh"},
            {"exec /bin/bash", "bash"},
            {"nohup node", "node"},
            {"cat", "cat"}
        };

        for (String[] testCase : cases) {
            assertThat(DangerousCommandTextSupport.firstExecutableName(testCase[0]))
                    .as(testCase[0])
                    .isEqualTo(testCase[1]);
        }
    }
}
