package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.web.DashboardCuratorService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class DashboardCuratorServiceTest {
    @Test
    void shouldRedactSecretsFromCuratorReportsAndImprovements() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        insertReport(env);
        insertImprovement(env);

        DashboardCuratorService service = new DashboardCuratorService(null, env.sqliteDatabase);
        String list = ONode.serialize(service.list(10));
        String detail = ONode.serialize(service.detail("report-secret"));
        String improvements = ONode.serialize(service.improvements(10));

        assertRedacted(list);
        assertRedacted(detail);
        assertRedacted(improvements);
        assertThat(detail).contains("curator://report").contains("skill://curator-skill");
    }

    private void insertReport(TestEnvironment env) throws Exception {
        Connection connection = env.sqliteDatabase.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert into curator_reports (report_id, status, summary, report_path, report_json, started_at, finished_at) values (?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, "report-secret");
            statement.setString(2, "ok");
            statement.setString(3, "summary Authorization: Bearer ghp_curatorsummary12345");
            statement.setString(4, "/tmp/token-report-ghp_curatorpath12345.json");
            statement.setString(
                    5,
                    "{\"stateFile\":\"/tmp/token-state-ghp_curatorstate12345.json\",\"items\":[{\"name\":\"curator-skill\",\"path\":\"/tmp/token-skill-ghp_curatorskill12345.md\",\"note\":\"api_key=sk-test-curatornote\"}]}");
            statement.setLong(6, 1L);
            statement.setLong(7, 2L);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    private void insertImprovement(TestEnvironment env) throws Exception {
        Connection connection = env.sqliteDatabase.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert into skill_improvements (improvement_id, session_id, run_id, skill_name, action, summary, changed_files_json, evidence_json, needs_review, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, "improvement-secret");
            statement.setString(2, "session-ghp_curatorsession12345");
            statement.setString(3, "run-ghp_curatorrun12345");
            statement.setString(4, "skill-ghp_curatorskillname12345");
            statement.setString(5, "action-ghp_curatoraction12345");
            statement.setString(6, "summary token=ghp_curatorimprovement12345");
            statement.setString(7, "[\"/tmp/token-file-ghp_curatorfile12345.md\"]");
            statement.setString(
                    8,
                    "{\"summary\":\"Authorization: Bearer ghp_curatorevidence12345\",\"url\":\"https://u:p@example.com/cb?token=curator-token\"}");
            statement.setInt(9, 1);
            statement.setLong(10, 3L);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    private static void assertRedacted(String value) {
        assertThat(value)
                .contains("Bearer ***")
                .doesNotContain("ghp_curatorsummary12345")
                .doesNotContain("ghp_curatorpath12345")
                .doesNotContain("ghp_curatorstate12345")
                .doesNotContain("ghp_curatorskill12345")
                .doesNotContain("sk-test-curatornote")
                .doesNotContain("ghp_curatorsession12345")
                .doesNotContain("ghp_curatorrun12345")
                .doesNotContain("ghp_curatorskillname12345")
                .doesNotContain("ghp_curatoraction12345")
                .doesNotContain("ghp_curatorimprovement12345")
                .doesNotContain("ghp_curatorfile12345")
                .doesNotContain("ghp_curatorevidence12345")
                .doesNotContain("curator-token")
                .doesNotContain("u:p@example.com");
    }
}
