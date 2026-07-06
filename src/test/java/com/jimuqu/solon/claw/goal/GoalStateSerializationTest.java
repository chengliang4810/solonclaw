// src/test/java/com/jimuqu/solon/claw/goal/GoalStateSerializationTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoalStateSerializationTest {
    @Test
    void oldNineFieldJsonLoadsCleanly() {
        // 旧版只有 9 字段的 JSON 必须能加载，新字段取默认值
        String oldJson =
                "{\"goal\":\"g\",\"status\":\"active\",\"turns_used\":1,\"max_turns\":5,"
                        + "\"created_at\":100,\"last_turn_at\":200,\"last_verdict\":\"continue\","
                        + "\"last_reason\":\"r\",\"paused_reason\":null}";
        GoalState s = GoalState.fromJson(oldJson);
        assertThat(s.getGoal()).isEqualTo("g");
        assertThat(s.getSubgoals()).isEmpty();
        assertThat(s.getConsecutiveParseFailures()).isZero();
        assertThat(s.getWaitingOnPid()).isNull();
        assertThat(s.getContract().isEmpty()).isTrue();
    }

    @Test
    void newFieldsRoundTrip() {
        GoalState s = new GoalState();
        s.setGoal("完成测试");
        s.addSubgoal("覆盖 goal 包");
        s.addSubgoal("覆盖工具");
        s.setConsecutiveParseFailures(2);
        s.setWaitingOnPid(1234);
        s.setWaitingReason("等编译");
        GoalContract c = new GoalContract();
        c.setOutcome("测试通过");
        s.setContract(c);
        String json = s.toJson();

        GoalState back = GoalState.fromJson(json);
        assertThat(back.getSubgoals()).containsExactly("覆盖 goal 包", "覆盖工具");
        assertThat(back.getConsecutiveParseFailures()).isEqualTo(2);
        assertThat(back.getWaitingOnPid()).isEqualTo(1234);
        assertThat(back.getWaitingReason()).isEqualTo("等编译");
        assertThat(back.getContract().getOutcome()).isEqualTo("测试通过");
    }

    @Test
    void subgoalAddRemoveClear() {
        GoalState s = new GoalState();
        s.addSubgoal("a");
        s.addSubgoal("b");
        s.addSubgoal("c");
        assertThat(s.removeSubgoal(2)).isTrue(); // 删 b
        assertThat(s.getSubgoals()).containsExactly("a", "c");
        assertThat(s.removeSubgoal(99)).isFalse(); // 越界
        s.clearSubgoals();
        assertThat(s.getSubgoals()).isEmpty();
    }

    @Test
    void isWaitingPidBarrierClearsWhenPidDead() {
        GoalState s = new GoalState();
        s.setWaitingOnPid(Integer.MAX_VALUE); // 几乎不可能存活的 pid
        assertThat(s.isWaiting()).isFalse(); // pid 不存活 → 不在等待
        s.clearWaitBarrier();
        assertThat(s.getWaitingOnPid()).isNull();
    }

    @Test
    void isWaitingUntilDeadline() {
        GoalState s = new GoalState();
        s.setWaitingUntil(System.currentTimeMillis() + 60_000);
        assertThat(s.isWaiting()).isTrue();
        s.setWaitingUntil(System.currentTimeMillis() - 1);
        assertThat(s.isWaiting()).isFalse();
    }

    @Test
    void isActiveReflectsStatus() {
        GoalState s = new GoalState();
        assertThat(s.isActive()).isTrue(); // 默认 active
        s.setStatus(GoalState.STATUS_PAUSED);
        assertThat(s.isActive()).isFalse();
    }
}
