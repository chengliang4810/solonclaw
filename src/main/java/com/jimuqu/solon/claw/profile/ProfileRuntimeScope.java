package com.jimuqu.solon.claw.profile;

import cn.hutool.core.util.StrUtil;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import org.noear.solon.core.AppContext;

/** 保存一次 Profile 运行链的不可变作用域，供异步续轮和少量延迟解析入口继承。 */
public final class ProfileRuntimeScope {
    /** 当前线程的 Profile；跨线程传播必须显式使用 capture，避免复用线程遗留其他 Profile。 */
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<Context>();

    /** 工具类不保存实例状态。 */
    private ProfileRuntimeScope() {}

    /**
     * 进入指定 Profile 作用域，关闭返回值后恢复进入前上下文。
     *
     * @param profile Profile 名。
     * @param home Profile 工作区。
     * @param environment Profile 局部环境快照，不允许调用方后续修改。
     * @param appContext Profile 独立 Bean 容器。
     * @return 可用于 try-with-resources 的恢复句柄。
     */
    public static Scope open(
            String profile, Path home, Map<String, String> environment, AppContext appContext) {
        final Context previous = CURRENT.get();
        CURRENT.set(new Context(profile, home, environment, appContext));
        return new Scope(previous);
    }

    /** 返回当前作用域；未进入 multiplex 链时返回 null。 */
    public static Context current() {
        return CURRENT.get();
    }

    /**
     * 捕获当前 Profile 作用域，供预先创建或复用的工作线程执行任务时安装并在结束后恢复。
     *
     * @param task 需要继承当前 Profile 的任务。
     * @return 可安全提交到任意线程池的任务包装。
     */
    public static Runnable capture(Runnable task) {
        return capture(CURRENT.get(), task);
    }

    /**
     * 使用已保存的 Profile 上下文包装任务，适用于构造时捕获、稍后由其他线程触发的生命周期回调。
     *
     * @param context 明确要安装的 Profile 上下文；null 表示执行期间清空 Profile 作用域。
     * @param task 需要在指定 Profile 中执行的任务。
     * @return 可安全提交到任意线程池的任务包装。
     */
    public static Runnable capture(Context context, Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("Profile scoped task is required.");
        }
        final Context captured = context;
        return new Runnable() {
            /** 在捕获的 Profile 作用域中执行，并清理复用线程上的临时状态。 */
            @Override
            public void run() {
                try (Scope ignored = install(captured)) {
                    task.run();
                }
            }
        };
    }

    /**
     * 捕获当前 Profile 作用域，供预先创建或复用的工作线程执行有返回值任务时安装并恢复。
     *
     * @param task 需要继承当前 Profile 的有返回值任务。
     * @param <T> 任务返回值类型。
     * @return 可安全提交到任意线程池的任务包装。
     */
    public static <T> Callable<T> capture(Callable<T> task) {
        return capture(CURRENT.get(), task);
    }

    /**
     * 使用已保存的 Profile 上下文包装有返回值任务，适用于构造时捕获、稍后由其他线程触发的回调。
     *
     * @param context 明确要安装的 Profile 上下文；null 表示执行期间清空 Profile 作用域。
     * @param task 需要在指定 Profile 中执行的有返回值任务。
     * @param <T> 任务返回值类型。
     * @return 可安全提交到任意线程池的任务包装。
     */
    public static <T> Callable<T> capture(Context context, Callable<T> task) {
        if (task == null) {
            throw new IllegalArgumentException("Profile scoped task is required.");
        }
        final Context captured = context;
        return new Callable<T>() {
            /** 在捕获的 Profile 作用域中执行，并清理复用线程上的临时状态。 */
            @Override
            public T call() throws Exception {
                try (Scope ignored = install(captured)) {
                    return task.call();
                }
            }
        };
    }

    /**
     * 读取当前 Profile 的环境值；Profile .env 显式声明的空值也会遮蔽进程级同名凭据。
     *
     * @param name 环境变量名。
     * @return 当前 Profile 优先的值；作用域内仅 PATH、HOME、代理等操作系统运行变量允许回退进程环境。
     */
    public static String environmentValue(String name) {
        if (name == null || name.trim().length() == 0) {
            return null;
        }
        Context current = CURRENT.get();
        if (current != null) {
            if ("SOLONCLAW_PROFILE".equals(name)) {
                return current.profile;
            }
            if ("SOLONCLAW_HOME".equals(name)) {
                return current.home == null ? null : current.home.toString();
            }
            if (current.environment.containsKey(name)) {
                return current.environment.get(name);
            }
            return isProcessFallbackAllowed(name) ? System.getenv(name) : null;
        }
        return System.getenv(name);
    }

    /**
     * 返回适合传给子进程的环境快照；命名 Profile 只继承明确的操作系统运行变量，再叠加自己的 .env。
     *
     * @return 与调用方修改隔离的环境 Map。
     */
    public static Map<String, String> environmentSnapshot() {
        Context current = CURRENT.get();
        if (current == null) {
            return new LinkedHashMap<String, String>(System.getenv());
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (isProcessFallbackAllowed(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        result.putAll(current.environment);
        result.put("SOLONCLAW_PROFILE", current.profile);
        if (current.home != null) {
            result.put("SOLONCLAW_HOME", current.home.toString());
        }
        return result;
    }

    /**
     * 用当前 Profile 可见环境完整替换 ProcessBuilder 的初始进程环境，避免 configured passthrough 重新放行其他 Profile 遗留的变量。
     *
     * @param target ProcessBuilder.environment() 返回的可变 Map。
     */
    public static void replaceProcessEnvironment(Map<String, String> target) {
        if (target == null) {
            return;
        }
        Map<String, String> snapshot = environmentSnapshot();
        target.clear();
        target.putAll(snapshot);
    }

    /** 判断命名 Profile 可安全继承的进程级操作系统或部署环境变量。 */
    static boolean isProcessFallbackAllowed(String name) {
        String normalized = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        return "PATH".equals(normalized)
                || "HOME".equals(normalized)
                || "USER".equals(normalized)
                || "LOGNAME".equals(normalized)
                || "SHELL".equals(normalized)
                || "TMPDIR".equals(normalized)
                || "TMP".equals(normalized)
                || "TEMP".equals(normalized)
                || "TZ".equals(normalized)
                || "TERM".equals(normalized)
                || "COLORTERM".equals(normalized)
                || "JAVA_HOME".equals(normalized)
                || "MAVEN_HOME".equals(normalized)
                || "VIRTUAL_ENV".equals(normalized)
                || "PYTHONPATH".equals(normalized)
                || "SSL_CERT_FILE".equals(normalized)
                || "PATHEXT".equals(normalized)
                || "SYSTEMROOT".equals(normalized)
                || "SYSTEMDRIVE".equals(normalized)
                || "WINDIR".equals(normalized)
                || "COMSPEC".equals(normalized)
                || "OS".equals(normalized)
                || "PROCESSOR_ARCHITECTURE".equals(normalized)
                || "NUMBER_OF_PROCESSORS".equals(normalized)
                || "PUBLIC".equals(normalized)
                || "ALLUSERSPROFILE".equals(normalized)
                || "PROGRAMDATA".equals(normalized)
                || "PROGRAMFILES".equals(normalized)
                || "PROGRAMFILES(X86)".equals(normalized)
                || "PROGRAMW6432".equals(normalized)
                || "APPDATA".equals(normalized)
                || "LOCALAPPDATA".equals(normalized)
                || "USERPROFILE".equals(normalized)
                || "USERDOMAIN".equals(normalized)
                || "USERNAME".equals(normalized)
                || "HOMEDRIVE".equals(normalized)
                || "HOMEPATH".equals(normalized)
                || "COMPUTERNAME".equals(normalized)
                || "HTTP_PROXY".equals(normalized)
                || "HTTPS_PROXY".equals(normalized)
                || "ALL_PROXY".equals(normalized)
                || "NO_PROXY".equals(normalized)
                || normalized.startsWith("LC_")
                || normalized.startsWith("XDG_")
                || normalized.startsWith("CONDA")
                || "LANG".equals(normalized);
    }

    /** 安装已捕获上下文；null 表示任务必须按未进入 Profile 的语义执行。 */
    private static Scope install(Context context) {
        Context previous = CURRENT.get();
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
        return new Scope(previous);
    }

    /** 表示不可变 Profile 运行上下文。 */
    public static final class Context {
        /** Profile 名。 */
        private final String profile;

        /** Profile 工作区。 */
        private final Path home;

        /** Profile 局部环境变量快照。 */
        private final Map<String, String> environment;

        /** Profile 独立 Bean 容器。 */
        private final AppContext appContext;

        /** 创建不可变 Profile 运行上下文。 */
        private Context(
                String profile, Path home, Map<String, String> environment, AppContext appContext) {
            this.profile = StrUtil.blankToDefault(profile, "default").trim();
            this.home = home == null ? null : home.toAbsolutePath().normalize();
            this.environment =
                    environment == null
                            ? Collections.<String, String>emptyMap()
                            : Collections.unmodifiableMap(
                                    new LinkedHashMap<String, String>(environment));
            this.appContext = appContext;
        }

        /**
         * @return 当前 Profile 名。
         */
        public String getProfile() {
            return profile;
        }

        /**
         * @return 当前 Profile 工作区。
         */
        public Path getHome() {
            return home;
        }

        /**
         * @return 当前 Profile 局部环境变量只读快照。
         */
        public Map<String, String> getEnvironment() {
            return environment;
        }

        /**
         * @return 当前 Profile 独立 Bean 容器。
         */
        public AppContext getAppContext() {
            return appContext;
        }
    }

    /** 关闭时恢复父作用域的句柄。 */
    public static final class Scope implements AutoCloseable {
        /** 进入当前作用域前的上下文。 */
        private final Context previous;

        /** 是否已经恢复，确保重复关闭无副作用。 */
        private boolean closed;

        /** 创建作用域恢复句柄。 */
        private Scope(Context previous) {
            this.previous = previous;
        }

        /** 恢复进入前作用域。 */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
