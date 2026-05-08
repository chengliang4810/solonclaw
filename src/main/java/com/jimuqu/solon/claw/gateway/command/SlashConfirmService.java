package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.support.constants.AgentSettingConstants;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.noear.snack4.ONode;

/** Slash-command confirmation state. */
public class SlashConfirmService {
    public static final String CHOICE_ONCE = "once";
    public static final String CHOICE_ALWAYS = "always";
    public static final String CHOICE_CANCEL = "cancel";

    public static final long DEFAULT_TIMEOUT_MS = 300000L;

    private final GlobalSettingRepository globalSettingRepository;
    private final Map<String, PendingConfirm> pendingBySource =
            new LinkedHashMap<String, PendingConfirm>();

    public SlashConfirmService(GlobalSettingRepository globalSettingRepository) {
        this.globalSettingRepository = globalSettingRepository;
    }

    public synchronized PendingConfirm register(String sourceKey, String command, String prompt) {
        return register(sourceKey, command, prompt, true);
    }

    public synchronized PendingConfirm register(
            String sourceKey, String command, String prompt, boolean allowAlways) {
        PendingConfirm confirm = new PendingConfirm();
        confirm.setConfirmId(com.jimuqu.solon.claw.support.IdSupport.newId());
        confirm.setSourceKey(StrUtil.nullToEmpty(sourceKey));
        confirm.setCommand(StrUtil.nullToEmpty(command));
        confirm.setPrompt(StrUtil.nullToEmpty(prompt));
        confirm.setAllowAlways(allowAlways);
        confirm.setCreatedAt(System.currentTimeMillis());
        pendingBySource.put(confirm.getSourceKey(), confirm);
        return confirm;
    }

    public synchronized PendingConfirm getPending(String sourceKey) {
        PendingConfirm confirm = pendingBySource.get(StrUtil.nullToEmpty(sourceKey));
        if (confirm == null) {
            return null;
        }
        if (isExpired(confirm)) {
            pendingBySource.remove(StrUtil.nullToEmpty(sourceKey));
            return null;
        }
        return confirm.copy();
    }

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

    public synchronized PendingConfirm resolve(String sourceKey) {
        return resolve(sourceKey, null, false);
    }

    public synchronized PendingConfirm resolve(String sourceKey, String confirmId) {
        return resolve(sourceKey, confirmId, true);
    }

    private PendingConfirm resolve(String sourceKey, String confirmId, boolean requireConfirmId) {
        String key = StrUtil.nullToEmpty(sourceKey);
        PendingConfirm confirm = pendingBySource.get(key);
        if (confirm == null) {
            return null;
        }
        if (isExpired(confirm)) {
            pendingBySource.remove(key);
            return null;
        }
        if (requireConfirmId
                && !StrUtil.equals(
                        StrUtil.nullToEmpty(confirm.getConfirmId()),
                        StrUtil.nullToEmpty(confirmId))) {
            return null;
        }
        pendingBySource.remove(key);
        return confirm.copy();
    }

    public synchronized boolean clear(String sourceKey) {
        return pendingBySource.remove(StrUtil.nullToEmpty(sourceKey)) != null;
    }

    public synchronized boolean clearIfStale(String sourceKey, long timeoutMs) {
        String key = StrUtil.nullToEmpty(sourceKey);
        PendingConfirm confirm = pendingBySource.get(key);
        if (confirm == null || !isExpired(confirm, timeoutMs)) {
            return false;
        }
        pendingBySource.remove(key);
        return true;
    }

    public boolean isAlwaysConfirmed(String command) {
        return loadAlwaysCommands().contains(normalizeCommand(command));
    }

    public void addAlwaysConfirmed(String command) throws Exception {
        Set<String> commands = loadAlwaysCommands();
        commands.add(normalizeCommand(command));
        saveAlwaysCommands(commands);
    }

    private boolean isExpired(PendingConfirm confirm) {
        return isExpired(confirm, DEFAULT_TIMEOUT_MS);
    }

    private boolean isExpired(PendingConfirm confirm, long timeoutMs) {
        return System.currentTimeMillis() - confirm.getCreatedAt() > Math.max(0L, timeoutMs);
    }

    private Set<String> loadAlwaysCommands() {
        if (globalSettingRepository == null) {
            return new LinkedHashSet<String>();
        }
        try {
            String raw = globalSettingRepository.get(AgentSettingConstants.SLASH_CONFIRM_ALWAYS_COMMANDS);
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
        } catch (Exception ignored) {
            return new LinkedHashSet<String>();
        }
    }

    private void saveAlwaysCommands(Set<String> commands) throws Exception {
        if (globalSettingRepository == null) {
            return;
        }
        List<String> values = new ArrayList<String>(commands);
        Collections.sort(values);
        globalSettingRepository.set(
                AgentSettingConstants.SLASH_CONFIRM_ALWAYS_COMMANDS, ONode.serialize(values));
    }

    private String normalizeCommand(String command) {
        String value = StrUtil.nullToEmpty(command).trim().toLowerCase();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    public static class PendingConfirm {
        private String confirmId;
        private String sourceKey;
        private String command;
        private String prompt;
        private boolean allowAlways = true;
        private long createdAt;

        public String getConfirmId() {
            return confirmId;
        }

        public void setConfirmId(String confirmId) {
            this.confirmId = confirmId;
        }

        public String getSourceKey() {
            return sourceKey;
        }

        public void setSourceKey(String sourceKey) {
            this.sourceKey = sourceKey;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }

        public boolean isAllowAlways() {
            return allowAlways;
        }

        public void setAllowAlways(boolean allowAlways) {
            this.allowAlways = allowAlways;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }

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
