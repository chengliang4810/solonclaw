package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.tool.runtime.SessionManageTools;
import com.jimuqu.solon.claw.web.DashboardSessionService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

/** 验证会话管理工具的检查点页面动作别名。 */
public class SessionManageToolsTest {
    /** rollback_checkpoint、checkpoint_rollback 和 rollback 应复用检查点回滚能力。 */
    @Test
    void shouldRouteCheckpointRollbackAliasesToRollbackCheckpoint() {
        RecordingSessionService service = new RecordingSessionService();
        SessionManageTools tools = new SessionManageTools(service);

        for (String action :
                new String[] {"rollback_checkpoint", "checkpoint_rollback", "rollback"}) {
            String json =
                    tools.sessionManage(
                            action, null, "checkpoint-a", null, Boolean.FALSE, 20, 0, 20);

            Map<?, ?> result = result(json);
            assertThat(result.get("checkpoint_id")).isEqualTo("checkpoint-a");
        }
        assertThat(service.rollbackCalls).isEqualTo(3);
    }

    /** 记录会话服务调用，避免测试触碰真实 checkpoint 文件。 */
    private static class RecordingSessionService extends DashboardSessionService {
        /** 检查点回滚调用次数。 */
        private int rollbackCalls;

        /** 创建记录型会话服务。 */
        RecordingSessionService() {
            super(null, null);
        }

        /** 返回固定检查点回滚结果。 */
        @Override
        public Map<String, Object> rollbackCheckpoint(String checkpointId) {
            rollbackCalls++;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("checkpoint_id", checkpointId);
            return result;
        }

        /** 返回固定列表，便于识别未知 action 落回列表分支。 */
        @Override
        public Map<String, Object> getSessions(int limit, int offset) {
            return Collections.<String, Object>singletonMap("sessions", Collections.emptyList());
        }
    }

    /** 读取工具结果里的 result 数据。 */
    @SuppressWarnings("unchecked")
    private Map<?, ?> result(String json) {
        Map<?, ?> root = ONode.deserialize(json, LinkedHashMap.class);
        assertThat(root.get("status")).isEqualTo("success");
        return (Map<?, ?>) root.get("result");
    }
}
