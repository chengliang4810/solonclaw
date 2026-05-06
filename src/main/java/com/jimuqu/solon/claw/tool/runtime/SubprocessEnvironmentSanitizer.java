package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Hermes-style subprocess environment filtering for local tools. */
public final class SubprocessEnvironmentSanitizer {
    public static final String FORCE_PREFIX = "_JIMUQU_FORCE_";
    public static final String HERMES_FORCE_PREFIX = "_HERMES_FORCE_";

    private static final String[] SAFE_ENV_PREFIXES =
            new String[] {
                "PATH", "HOME", "USER", "USERNAME", "USERPROFILE", "LANG", "LC_", "TERM",
                "TMPDIR", "TMP", "TEMP", "SHELL", "LOGNAME", "XDG_", "PYTHONPATH",
                "VIRTUAL_ENV", "CONDA", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT"
            };
    private static final String[] SECRET_ENV_SUBSTRINGS =
            new String[] {"KEY", "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL", "PASSWD", "AUTH"};

    private SubprocessEnvironmentSanitizer() {}

    public static void sanitize(Map<String, String> env) {
        if (env == null) {
            return;
        }
        Map<String, String> raw = new LinkedHashMap<String, String>(env);
        env.clear();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String name = entry.getKey();
            String forcedName = forcedName(name);
            if (forcedName != null) {
                env.put(forcedName, entry.getValue());
                continue;
            }
            if (isSecretEnvName(name)) {
                continue;
            }
            if (isSafeEnvName(name)) {
                env.put(name, entry.getValue());
            }
        }
    }

    public static boolean isSafeEnvName(String name) {
        String value = StrUtil.nullToEmpty(name);
        for (String prefix : SAFE_ENV_PREFIXES) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSecretEnvName(String name) {
        String upper = StrUtil.nullToEmpty(name).toUpperCase(Locale.ROOT);
        for (String marker : SECRET_ENV_SUBSTRINGS) {
            if (upper.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String forcedName(String name) {
        String value = StrUtil.nullToEmpty(name);
        if (value.startsWith(FORCE_PREFIX) && value.length() > FORCE_PREFIX.length()) {
            return value.substring(FORCE_PREFIX.length());
        }
        if (value.startsWith(HERMES_FORCE_PREFIX) && value.length() > HERMES_FORCE_PREFIX.length()) {
            return value.substring(HERMES_FORCE_PREFIX.length());
        }
        return null;
    }
}
