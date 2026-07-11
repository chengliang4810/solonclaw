package com.jimuqu.solon.claw.profile;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 只读取指定 Profile 的 .env，并把当前项目或协议明确支持的凭据应用到独立配置快照。 */
public final class ProfileEnvironmentLoader {
    /** Profile 环境文件最大读取体积，避免异常文件拖垮网关启动。 */
    private static final long MAX_ENV_BYTES = 1024L * 1024L;

    /** 合法环境变量名。 */
    private static final Pattern ENV_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** 工具类不保存实例状态。 */
    private ProfileEnvironmentLoader() {}

    /**
     * 读取 Profile 局部环境变量；不会修改进程级 System.getenv 或系统属性。
     *
     * @param home Profile 工作区。
     * @return 保持文件顺序的不可变环境快照。
     */
    public static Map<String, String> load(Path home) {
        if (home == null) {
            return Collections.emptyMap();
        }
        Path file = home.toAbsolutePath().normalize().resolve(".env");
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_ENV_BYTES) {
                return Collections.emptyMap();
            }
            LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
            for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                parseLine(values, rawLine);
            }
            return Collections.unmodifiableMap(values);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read Profile environment file.", e);
        }
    }

    /**
     * 把 Profile 环境中的凭据应用到已隔离的 AppConfig；配置文件非空值始终优先。
     *
     * @param config Profile 独立配置快照。
     * @param environment Profile 局部环境快照。
     */
    public static void apply(AppConfig config, Map<String, String> environment) {
        if (config == null || environment == null || environment.isEmpty()) {
            return;
        }
        applyProviderCredentials(config, environment);
        applySpeechCredentials(config, environment);
        applyChannelCredentials(config, environment);
    }

    /** 解析一行 dotenv 文本，拒绝非法键并保留值内等号。 */
    private static void parseLine(Map<String, String> values, String rawLine) {
        String line = StrUtil.nullToEmpty(rawLine).trim();
        if (line.length() == 0 || line.startsWith("#")) {
            return;
        }
        if (line.startsWith("export ")) {
            line = line.substring("export ".length()).trim();
        }
        int separator = line.indexOf('=');
        if (separator <= 0) {
            return;
        }
        String name = line.substring(0, separator).trim();
        if (!ENV_NAME.matcher(name).matches()) {
            return;
        }
        values.put(name, unquote(line.substring(separator + 1).trim()));
    }

    /** 去掉一层标准引号；双引号值支持常用换行和反斜杠转义。 */
    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1)
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** 为每个 provider 读取当前项目专属键，并为限定协议提供标准协议键回退。 */
    private static void applyProviderCredentials(
            AppConfig config, Map<String, String> environment) {
        String active = StrUtil.nullToEmpty(config.getModel().getProviderKey()).trim();
        for (Map.Entry<String, AppConfig.ProviderConfig> entry : config.getProviders().entrySet()) {
            AppConfig.ProviderConfig provider = entry.getValue();
            if (provider == null || StrUtil.isNotBlank(provider.getApiKey())) {
                continue;
            }
            String providerKey = normalizedProviderEnvKey(entry.getKey());
            String value = environment.get("SOLONCLAW_PROVIDER_" + providerKey + "_API_KEY");
            if (StrUtil.isBlank(value) && entry.getKey().equals(active)) {
                value = protocolCredential(environment, provider.getDialect());
            }
            if (StrUtil.isNotBlank(value)) {
                provider.setApiKey(value.trim());
            }
        }
    }

    /** 复用 OpenAI 协议密钥补齐独立 TTS/STT，仍以各自显式配置为最高优先级。 */
    private static void applySpeechCredentials(AppConfig config, Map<String, String> environment) {
        String openAi = first(environment, "SOLONCLAW_SPEECH_API_KEY", "OPENAI_API_KEY");
        if (config.getSpeech() != null && StrUtil.isNotBlank(openAi)) {
            if (config.getSpeech().getTts() != null
                    && StrUtil.isBlank(config.getSpeech().getTts().getApiKey())) {
                config.getSpeech().getTts().setApiKey(openAi);
            }
            if (config.getSpeech().getStt() != null
                    && StrUtil.isBlank(config.getSpeech().getStt().getApiKey())) {
                config.getSpeech().getStt().setApiKey(openAi);
            }
        }
    }

    /** 应用六个国内渠道的 Profile 局部凭据。 */
    private static void applyChannelCredentials(AppConfig config, Map<String, String> environment) {
        AppConfig.ChannelsConfig channels = config.getChannels();
        fill(
                channels.getFeishu(),
                first(environment, "SOLONCLAW_CHANNEL_FEISHU_APP_ID"),
                first(environment, "SOLONCLAW_CHANNEL_FEISHU_APP_SECRET"),
                CredentialKind.APP);
        fill(
                channels.getDingtalk(),
                first(environment, "SOLONCLAW_CHANNEL_DINGTALK_CLIENT_ID"),
                first(environment, "SOLONCLAW_CHANNEL_DINGTALK_CLIENT_SECRET"),
                CredentialKind.CLIENT);
        if (StrUtil.isBlank(channels.getDingtalk().getRobotCode())) {
            channels.getDingtalk()
                    .setRobotCode(first(environment, "SOLONCLAW_CHANNEL_DINGTALK_ROBOT_CODE"));
        }
        fill(
                channels.getWecom(),
                first(environment, "SOLONCLAW_CHANNEL_WECOM_BOT_ID"),
                first(environment, "SOLONCLAW_CHANNEL_WECOM_SECRET"),
                CredentialKind.BOT);
        fill(
                channels.getWeixin(),
                first(environment, "SOLONCLAW_CHANNEL_WEIXIN_ACCOUNT_ID"),
                first(environment, "SOLONCLAW_CHANNEL_WEIXIN_TOKEN"),
                CredentialKind.ACCOUNT_TOKEN);
        fill(
                channels.getQqbot(),
                first(environment, "SOLONCLAW_CHANNEL_QQBOT_APP_ID"),
                first(environment, "SOLONCLAW_CHANNEL_QQBOT_CLIENT_SECRET"),
                CredentialKind.APP_CLIENT);
        fill(
                channels.getYuanbao(),
                first(environment, "SOLONCLAW_CHANNEL_YUANBAO_APP_ID"),
                first(environment, "SOLONCLAW_CHANNEL_YUANBAO_APP_SECRET"),
                CredentialKind.APP);
        if (StrUtil.isBlank(channels.getYuanbao().getBotId())) {
            channels.getYuanbao().setBotId(first(environment, "SOLONCLAW_CHANNEL_YUANBAO_BOT_ID"));
        }
    }

    /** 按渠道字段形态只补空白凭据。 */
    private static void fill(
            AppConfig.ChannelConfig config, String identity, String secret, CredentialKind kind) {
        if (config == null) {
            return;
        }
        if (kind == CredentialKind.CLIENT) {
            if (StrUtil.isBlank(config.getClientId())) {
                config.setClientId(identity);
            }
            if (StrUtil.isBlank(config.getClientSecret())) {
                config.setClientSecret(secret);
            }
        } else if (kind == CredentialKind.BOT) {
            if (StrUtil.isBlank(config.getBotId())) {
                config.setBotId(identity);
            }
            if (StrUtil.isBlank(config.getSecret())) {
                config.setSecret(secret);
            }
        } else if (kind == CredentialKind.ACCOUNT_TOKEN) {
            if (StrUtil.isBlank(config.getAccountId())) {
                config.setAccountId(identity);
            }
            if (StrUtil.isBlank(config.getToken())) {
                config.setToken(secret);
            }
        } else if (kind == CredentialKind.APP_CLIENT) {
            if (StrUtil.isBlank(config.getAppId())) {
                config.setAppId(identity);
            }
            if (StrUtil.isBlank(config.getClientSecret())) {
                config.setClientSecret(secret);
            }
        } else {
            if (StrUtil.isBlank(config.getAppId())) {
                config.setAppId(identity);
            }
            if (StrUtil.isBlank(config.getAppSecret())) {
                config.setAppSecret(secret);
            }
        }
    }

    /** 根据限定协议读取标准凭据键。 */
    private static String protocolCredential(Map<String, String> environment, String dialect) {
        String normalized = StrUtil.nullToEmpty(dialect).trim().toLowerCase(Locale.ROOT);
        if ("openai".equals(normalized) || "openai-responses".equals(normalized)) {
            return first(environment, "OPENAI_API_KEY");
        }
        if ("gemini".equals(normalized)) {
            return first(environment, "GEMINI_API_KEY", "GOOGLE_API_KEY");
        }
        if ("anthropic".equals(normalized)) {
            return first(environment, "ANTHROPIC_API_KEY");
        }
        return "";
    }

    /** 把 provider key 转为当前项目环境变量片段。 */
    private static String normalizedProviderEnvKey(String providerKey) {
        return StrUtil.nullToEmpty(providerKey)
                .trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_");
    }

    /** 返回第一个非空环境值。 */
    private static String first(Map<String, String> environment, String... names) {
        for (String name : names) {
            String value = environment.get(name);
            if (StrUtil.isNotBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    /** 渠道凭据字段组合类型。 */
    private enum CredentialKind {
        APP,
        CLIENT,
        BOT,
        ACCOUNT_TOKEN,
        APP_CLIENT
    }
}
