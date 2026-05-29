import com.jimuqu.solon.claw.core.service.MemoryProvider;
import com.jimuqu.solon.claw.plugin.AgentPlugin;
import com.jimuqu.solon.claw.plugin.AgentPluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class HolographicPlugin implements AgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(HolographicPlugin.class);

    @Override
    public void register(AgentPluginContext ctx) {
        ctx.registerMemoryProvider(new HolographicProvider());
    }

    private static class HolographicProvider implements MemoryProvider {
        private static final String DB_PATH;

        static {
            String home = System.getProperty("user.home");
            String dirPath = home + File.separator + ".jimuqu" + File.separator + "memory";
            File dir = new File(dirPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            DB_PATH = dirPath + File.separator + "holographic.db";
            initDatabase();
        }

        private static void initDatabase() {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                log.warn("SQLite JDBC driver not found: {}", e.getMessage());
                return;
            }
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS facts ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "source_key TEXT NOT NULL, "
                        + "fact TEXT NOT NULL, "
                        + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_facts_source_key ON facts(source_key)");
            } catch (Exception e) {
                log.warn("Failed to initialize holographic database: {}", e.getMessage());
            }
        }

        private static Connection getConnection() throws Exception {
            return DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
        }

        @Override
        public String name() {
            return "holographic";
        }

        @Override
        public String systemPromptBlock(String sourceKey) throws Exception {
            return "Long-term memory is available via the holographic engine.";
        }

        @Override
        public String prefetch(String sourceKey, String userMessage) throws Exception {
            List<String> facts = new ArrayList<String>();
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT fact FROM facts WHERE source_key = ? ORDER BY created_at DESC LIMIT 20")) {
                ps.setString(1, sourceKey);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        facts.add(rs.getString("fact"));
                    }
                }
            } catch (Exception e) {
                log.debug("Holographic prefetch error: {}", e.getMessage());
                return "";
            }

            if (facts.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder("[Recalled facts]\n");
            for (String fact : facts) {
                sb.append("- ").append(fact).append("\n");
            }
            return sb.toString().trim();
        }

        @Override
        public void syncTurn(String sourceKey, String userMessage, String assistantMessage) throws Exception {
            // No-op: facts are stored via the memory tool, not auto-extracted from turns
        }
    }
}
