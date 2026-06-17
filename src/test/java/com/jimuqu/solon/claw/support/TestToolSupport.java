package com.jimuqu.solon.claw.support;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SolonClawFileReadWriteSkill;
import com.jimuqu.solon.claw.tool.runtime.SolonClawPatchTools;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;

/** 汇总工具类测试里反复出现的临时目录、UTF-8 文件和工具实例准备逻辑。 */
public final class TestToolSupport {
    /** 测试临时目录统一前缀，避免夹具名称泄漏历史实现命名。 */
    private static final String TEMP_PREFIX = "solonclaw-";

    private TestToolSupport() {}

    /** 创建带项目统一前缀的临时目录，用于隔离工具读写和进程执行状态。 */
    public static Path tempDir(String purpose) throws IOException {
        return Files.createTempDirectory(TEMP_PREFIX + purpose);
    }

    /** 创建临时目录并返回字符串路径，便于直接传给现有工具构造器。 */
    public static String tempDirString(String purpose) throws IOException {
        return tempDir(purpose).toString();
    }

    /** 使用 UTF-8 写入测试夹具文件，保持跨平台断言稳定。 */
    public static void writeUtf8(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 使用 UTF-8 读取测试夹具文件，避免每个测试重复手写编码转换。 */
    public static String readUtf8(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** 构造默认 patch 工具实例，适合普通沙箱内文件修改测试。 */
    public static SolonClawPatchTools patchTools(Path root) {
        return new SolonClawPatchTools(root.toString());
    }

    /** 构造启用安全策略的 patch 工具实例，适合凭据路径和敏感文件拦截测试。 */
    public static SolonClawPatchTools guardedPatchTools(Path root) {
        AppConfig config = new AppConfig();
        return new SolonClawPatchTools(root.toString(), new SecurityPolicyService(config));
    }

    /** 构造启用安全策略的文件读写工具实例，适合验证 runtime 内敏感路径拦截。 */
    public static SolonClawFileReadWriteSkill guardedFileSkill(Path root) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(root.toString());
        return new SolonClawFileReadWriteSkill(
                root.toString(), new SecurityPolicyService(config));
    }

    /** 将工具返回的 JSON 解析为 Map，保持测试统一使用 snack4。 */
    public static Map<?, ?> parseJsonMap(String json) {
        return ONode.deserialize(json, LinkedHashMap.class);
    }
}
