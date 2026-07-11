package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.AgentSettingConstants;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提供Slash Confirm相关业务能力，封装调用方不需要感知的运行细节。 */
public class SlashConfirmService {
    /** 记录确认状态读取失败等低敏诊断信息，不输出命令正文。 */
    private static final Logger log = LoggerFactory.getLogger(SlashConfirmService.class);

    /** CHOICEONCE的统一常量值。 */
    public static final String CHOICE_ONCE = "once";

    /** CHOICEALWAYS的统一常量值。 */
    public static final String CHOICE_ALWAYS = "always";

    /** CHOICECANCEL的统一常量值。 */
    public static final String CHOICE_CANCEL = "cancel";

    /** 默认TIMEOUTMS的统一常量值。 */
    public static final long DEFAULT_TIMEOUT_MS = 300000L;

    /** 保存global设置仓储集合，维持调用顺序或去重语义。 */
    private final GlobalSettingRepository globalSettingRepository;

    /** 保存待恢复根据来源映射，便于按键快速查询。 */
    private final Map<String, PendingConfirm> pendingBySource =
            new LinkedHashMap<String, PendingConfirm>();

    /**
     * 创建Slash Confirm服务实例，并注入运行所需依赖。
     *
     * @param globalSettingRepository globalSetting仓储依赖。
     */
    public SlashConfirmService(GlobalSettingRepository globalSettingRepository) {
        this.globalSettingRepository = globalSettingRepository;
    }

    /**
     * 执行register相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param command 待执行或解析的命令文本。
     * @param prompt 提示词参数。
     * @return 返回register结果。
     */
    public synchronized PendingConfirm register(String sourceKey, String command, String prompt) {
        return register(sourceKey, command, prompt, true);
    }

    /**
     * 执行register相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param command 待执行或解析的命令文本。
     * @param prompt 提示词参数。
     * @param allowAlways allowAlways开关值。
     * @return 返回register结果。
     */
    public synchronized PendingConfirm register(
            String sourceKey, String command, String prompt, boolean allowAlways) {
        PendingConfirm confirm = new PendingConfirm();
        confirm.setConfirmId(com.jimuqu.solon.claw.support.IdSupport.newId());
        confirm.setSourceKey(cleanDisplay(sourceKey));
        confirm.setCommand(safeDisplay(command));
        confirm.setPrompt(safeDisplay(prompt));
        confirm.setAllowAlways(allowAlways);
        confirm.setCreatedAt(System.currentTimeMillis());
        pendingBySource.put(confirm.getSourceKey(), confirm);
        return confirm;
    }

    /**
     * 读取Pending。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回读取到的Pending。
     */
    public synchronized PendingConfirm getPending(String sourceKey) {
        String key = cleanDisplay(sourceKey);
        PendingConfirm confirm = pendingBySource.get(key);
        if (confirm == null) {
            return null;
        }
        if (isExpired(confirm)) {
            pendingBySource.remove(key);
            return null;
        }
        return confirm.copy();
    }

    /**
     * 列出Pending。
     *
     * @return 返回Pending列表。
     */
    public synchronized List<PendingConfirm> listPending() {
        List<String> expired = new ArrayList<String>();
        List<PendingConfirm> values = new ArrayList<PendingConfirm>();
        for (Map.Entry<String, PendingConfirm> entry : pendingBySource.entrySet()) {
            PendingConfirm confirm = entry.getValue();
            if (isExpired(confirm)) {
                expired.add(entry.getKey());
                continue;
            }
            values.add(confirm.copy());
        }
        for (String key : expired) {
            pendingBySource.remove(key);
        }
        return values;
    }

    /**
     * 解析运行时需要的目标对象。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回resolve结果。
     */
    public synchronized PendingConfirm resolve(String sourceKey) {
        return resolve(sourceKey, null, false);
    }

    /**
     * 解析运行时需要的目标对象。
     *
     * @param sourceKey 渠道来源键。
     * @param confirmId confirm标识。
     * @return 返回resolve结果。
     */
    public synchronized PendingConfirm resolve(String sourceKey, String confirmId) {
        return resolve(sourceKey, confirmId, true);
    }

    /**
     * 解析运行时需要的目标对象。
     *
     * @param sourceKey 渠道来源键。
     * @param confirmId confirm标识。
     * @param requireConfirmId requireConfirm标识。
     * @return 返回resolve结果。
     */
    private PendingConfirm resolve(String sourceKey, String confirmId, boolean requireConfirmId) {
        String key = cleanDisplay(sourceKey);
        PendingConfirm confirm = pendingBySource.get(key);
        if (confirm == null) {
            return null;
        }
        if (isExpired(confirm)) {
            pendingBySource.remove(key);
            return null;
        }
        if (requireConfirmId
                && !StrUtil.equals(cleanDisplay(confirm.getConfirmId()), cleanDisplay(confirmId))) {
            return null;
        }
        pendingBySource.remove(key);
        return confirm.copy();
    }

    /**
     * 执行clear相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回clear结果。
     */
    public synchronized boolean clear(String sourceKey) {
        return pendingBySource.remove(cleanDisplay(sourceKey)) != null;
    }

    /**
     * 清理If Stale。
     *
     * @param sourceKey 渠道来源键。
     * @param timeoutMs timeoutMs 参数。
     * @return 返回If Stale结果。
     */
    public synchronized boolean clearIfStale(String sourceKey, long timeoutMs) {
        String key = cleanDisplay(sourceKey);
        PendingConfirm confirm = pendingBySource.get(key);
        if (confirm == null || !isExpired(confirm, timeoutMs)) {
            return false;
        }
        pendingBySource.remove(key);
        return true;
    }

    /**
     * 判断是否Always Confirmed。
     *
     * @param command 待执行或解析的命令文本。
     * @return 如果Always Confirmed满足条件则返回 true，否则返回 false。
     */
    public boolean isAlwaysConfirmed(String command) {
        return loadAlwaysCommands().contains(normalizeCommand(command));
    }

    /**
     * 追加AlwaysConfirmed。
     *
     * @param command 待执行或解析的命令文本。
     */
    public void addAlwaysConfirmed(String command) throws Exception {
        Set<String> commands = loadAlwaysCommands();
        commands.add(normalizeCommand(command));
        saveAlwaysCommands(commands);
    }

    /**
     * 判断是否Expired。
     *
     * @param confirm confirm 参数。
     * @return 如果Expired满足条件则返回 true，否则返回 false。
     */
    private boolean isExpired(PendingConfirm confirm) {
        return isExpired(confirm, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 判断是否Expired。
     *
     * @param confirm confirm 参数。
     * @param timeoutMs timeoutMs 参数。
     * @return 如果Expired满足条件则返回 true，否则返回 false。
     */
    private boolean isExpired(PendingConfirm confirm, long timeoutMs) {
        return confirm.expiredAt(System.currentTimeMillis(), timeoutMs);
    }

    /**
     * 加载Always Commands。
     *
     * @return 返回Always Commands结果。
     */
    private Set<String> loadAlwaysCommands() {
        if (globalSettingRepository == null) {
            return new LinkedHashSet<String>();
        }
        try {
            String raw =
                    globalSettingRepository.get(
                            AgentSettingConstants.SLASH_CONFIRM_ALWAYS_COMMANDS);
            if (StrUtil.isBlank(raw)) {
                return new LinkedHashSet<String>();
            }
            Object parsed = ONode.deserialize(raw, Object.class);
            if (!(parsed instanceof List)) {
                return new LinkedHashSet<String>();
            }
            Set<String> commands = new LinkedHashSet<String>();
            for (Object item : (List<?>) parsed) {
                String command = normalizeCommand(String.valueOf(item));
                if (StrUtil.isNotBlank(command)) {
                    commands.add(command);
                }
            }
            return commands;
        } catch (Exception e) {
            log.debug(
                    "Slash confirm always-command state parse failed; using empty fallback: {}",
                    exceptionSummary(e));
            return new LinkedHashSet<String>();
        }
    }

    /**
     * 生成低敏异常摘要，避免日志中出现仓储内容或命令文本。
     *
     * @param e 异常对象。
     * @return 仅包含异常类型的摘要文本。
     */
    private static String exceptionSummary(Exception e) {
        return e == null ? "unknown" : e.getClass().getSimpleName();
    }

    /**
     * 保存Always Commands。
     *
     * @param commands commands 参数。
     */
    private void saveAlwaysCommands(Set<String> commands) throws Exception {
        if (globalSettingRepository == null) {
            return;
        }
        List<String> values = new ArrayList<String>(commands);
        Collections.sort(values);
        globalSettingRepository.set(
                AgentSettingConstants.SLASH_CONFIRM_ALWAYS_COMMANDS, ONode.serialize(values));
    }

    /**
     * 规范化命令。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回命令结果。
     */
    private String normalizeCommand(String command) {
        String value = cleanDisplay(command).trim().toLowerCase();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return SecretRedactor.redact(value, 2000);
    }

    /**
     * 清理展示。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回clean展示结果。
     */
    private String cleanDisplay(String value) {
        return SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(value));
    }

    /**
     * 生成安全展示用的展示。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe展示结果。
     */
    private String safeDisplay(String value) {
        return SecretRedactor.redact(cleanDisplay(value), 2000);
    }

    /** 承载待恢复Confirm相关状态和辅助逻辑。 */
    public static class PendingConfirm {
        /** 记录待恢复Confirm中的confirm标识。 */
        private String confirmId;

        /** 记录待恢复Confirm中的来源键。 */
        private String sourceKey;

        /** 记录待恢复Confirm中的命令。 */
        private String command;

        /** 记录待恢复Confirm中的提示词。 */
        private String prompt;

        /** 标记是否允许Always。 */
        private boolean allowAlways = true;

        /** 记录待恢复Confirm中的创建时间。 */
        private long createdAt;

        /**
         * 读取Confirm标识。
         *
         * @return 返回读取到的Confirm标识。
         */
        public String getConfirmId() {
            return confirmId;
        }

        /**
         * 写入Confirm标识。
         *
         * @param confirmId confirm标识。
         */
        public void setConfirmId(String confirmId) {
            this.confirmId = confirmId;
        }

        /**
         * 读取来源键。
         *
         * @return 返回读取到的来源键。
         */
        public String getSourceKey() {
            return sourceKey;
        }

        /**
         * 写入来源键。
         *
         * @param sourceKey 渠道来源键。
         */
        public void setSourceKey(String sourceKey) {
            this.sourceKey = sourceKey;
        }

        /**
         * 读取命令。
         *
         * @return 返回读取到的命令。
         */
        public String getCommand() {
            return command;
        }

        /**
         * 写入命令。
         *
         * @param command 待执行或解析的命令文本。
         */
        public void setCommand(String command) {
            this.command = command;
        }

        /**
         * 读取提示词。
         *
         * @return 返回读取到的提示词。
         */
        public String getPrompt() {
            return prompt;
        }

        /**
         * 写入提示词。
         *
         * @param prompt 提示词参数。
         */
        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }

        /**
         * 判断是否Allow Always。
         *
         * @return 如果Allow Always满足条件则返回 true，否则返回 false。
         */
        public boolean isAllowAlways() {
            return allowAlways;
        }

        /**
         * 写入Allow Always。
         *
         * @param allowAlways allowAlways开关值。
         */
        public void setAllowAlways(boolean allowAlways) {
            this.allowAlways = allowAlways;
        }

        /**
         * 读取创建时间。
         *
         * @return 返回读取到的创建时间。
         */
        public long getCreatedAt() {
            return createdAt;
        }

        /**
         * 写入创建时间。
         *
         * @param createdAt createdAt 参数。
         */
        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }

        /** 读取默认过期时间，供诊断输出与过期判断复用同一口径。 */
        public long expiresAt() {
            return expiresAt(DEFAULT_TIMEOUT_MS);
        }

        /**
         * 按指定超时时长读取过期时间。
         *
         * @param timeoutMs 超时时长毫秒数。
         * @return 过期时间毫秒时间戳。
         */
        long expiresAt(long timeoutMs) {
            return createdAt + Math.max(0L, timeoutMs);
        }

        /**
         * 按默认超时时长判断指定时间点是否已过期。
         *
         * @param nowMillis 当前毫秒时间戳。
         * @return 已过期返回 true。
         */
        public boolean expiredAt(long nowMillis) {
            return expiredAt(nowMillis, DEFAULT_TIMEOUT_MS);
        }

        /**
         * 按指定超时时长判断指定时间点是否已过期。
         *
         * @param nowMillis 当前毫秒时间戳。
         * @param timeoutMs 超时时长毫秒数。
         * @return 已过期返回 true。
         */
        boolean expiredAt(long nowMillis, long timeoutMs) {
            return expiresAt(timeoutMs) <= nowMillis;
        }

        /**
         * 按默认超时时长计算指定时间点剩余秒数。
         *
         * @param nowMillis 当前毫秒时间戳。
         * @return 剩余秒数，已过期返回 0。
         */
        public long expiresInSecondsAt(long nowMillis) {
            return Math.max(0L, (expiresAt() - nowMillis) / 1000L);
        }

        /**
         * 执行copy相关逻辑。
         *
         * @return 返回copy结果。
         */
        private PendingConfirm copy() {
            PendingConfirm copy = new PendingConfirm();
            copy.setConfirmId(confirmId);
            copy.setSourceKey(sourceKey);
            copy.setCommand(command);
            copy.setPrompt(prompt);
            copy.setAllowAlways(allowAlways);
            copy.setCreatedAt(createdAt);
            return copy;
        }
    }
}
