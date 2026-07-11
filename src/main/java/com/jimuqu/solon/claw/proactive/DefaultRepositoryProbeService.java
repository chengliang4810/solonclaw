package com.jimuqu.solon.claw.proactive;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 默认仓库探测服务，封装本地 Git 和显式远程 URL 的只读探测命令。 */
public class DefaultRepositoryProbeService implements RepositoryProbeService {
    /** 仓库探测内部日志，只记录阶段和异常类型，避免泄露仓库地址或命令输出。 */
    private static final Logger log = LoggerFactory.getLogger(DefaultRepositoryProbeService.class);

    /** Git 只读命令超时时间，避免主动协作 tick 被网络或坏仓库长时间阻塞。 */
    private static final int COMMAND_TIMEOUT_SECONDS = 8;

    /** 子进程输出最多读取字节数，避免异常输出撑大内存。 */
    private static final int OUTPUT_LIMIT_BYTES = 16 * 1024;

    /** 探测本地或远程仓库状态。 */
    @Override
    public RepositoryState probe(RepositoryReferenceExtractor.RepositoryReference reference) {
        if (reference == null || StrUtil.isBlank(reference.getRef())) {
            return null;
        }
        String ref = reference.getRef();
        if (isLocalPath(ref)) {
            return probeLocalRepository(ref);
        }
        if (isHostedUrl(ref)) {
            return probeRemoteRepository(ref);
        }
        return null;
    }

    /**
     * 探测本地仓库当前 HEAD 和远程 URL。
     *
     * @param path 本地仓库路径。
     * @return 返回仓库状态，失败时返回 null。
     */
    private RepositoryState probeLocalRepository(String path) {
        File directory = new File(path);
        if (!directory.isDirectory()) {
            return null;
        }
        String commitHash = runGit(directory, Arrays.asList("git", "rev-parse", "HEAD"));
        if (StrUtil.isBlank(commitHash)) {
            return null;
        }
        String branch =
                runGit(directory, Arrays.asList("git", "rev-parse", "--abbrev-ref", "HEAD"));
        if ("head".equalsIgnoreCase(StrUtil.nullToEmpty(branch))) {
            branch = "HEAD";
        }
        String remote = runGit(directory, Arrays.asList("git", "remote", "get-url", "origin"));
        RepositoryState state = new RepositoryState();
        state.setRef(path);
        state.setDisplayName(displayName(path));
        state.setBranch(StrUtil.blankToDefault(branch, "HEAD"));
        state.setCommitHash(commitHash);
        state.setReleaseId("");
        state.setStateHash(stateHash(commitHash, remote));
        return state;
    }

    /**
     * 探测远程仓库 HEAD 状态，只使用 ls-remote，不写入本地对象库。
     *
     * @param url 显式远程仓库 URL。
     * @return 返回远程状态，失败时返回 null。
     */
    private RepositoryState probeRemoteRepository(String url) {
        String output = runGit(null, Arrays.asList("git", "ls-remote", "--heads", url));
        if (StrUtil.isBlank(output)) {
            output = runGit(null, Arrays.asList("git", "ls-remote", url, "HEAD"));
        }
        RemoteHead head = parseRemoteHead(output);
        if (head == null || StrUtil.isBlank(head.commitHash)) {
            return null;
        }
        RepositoryState state = new RepositoryState();
        state.setRef(url);
        state.setDisplayName(displayName(url));
        state.setBranch(StrUtil.blankToDefault(head.branch, "HEAD"));
        state.setCommitHash(head.commitHash);
        state.setReleaseId("");
        state.setStateHash(stateHash(head.commitHash, head.branch));
        return state;
    }

    /**
     * 执行只读 Git 命令并返回首段输出。
     *
     * @param directory 命令工作目录，远程探测可为空。
     * @param command Git 命令参数。
     * @return 成功时返回输出文本，失败时返回空字符串。
     */
    private String runGit(File directory, List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (directory != null) {
                builder.directory(directory);
            }
            builder.redirectErrorStream(true);
            Process process = builder.start();
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader =
                    new Thread(
                            new Runnable() {
                                /** 后台读取 Git 输出，避免主线程在无输出的长时间命令上阻塞。 */
                                @Override
                                public void run() {
                                    try {
                                        readBounded(process.getInputStream(), output);
                                    } catch (Exception e) {
                                        logRecoverableProbeFailure("read_git_output", e);
                                    }
                                }
                            },
                            "proactive-repository-probe-reader");
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(1000L);
                return "";
            }
            reader.join(1000L);
            if (process.exitValue() != 0) {
                return "";
            }
            return SecretRedactor.redact(output.toString(StandardCharsets.UTF_8.name()), 4000)
                    .trim();
        } catch (Exception e) {
            logRecoverableProbeFailure("run_git_command", e);
            return "";
        }
    }

    /**
     * 有界读取子进程输出。
     *
     * @param input 子进程输出流。
     * @param output 输出缓冲区。
     */
    private void readBounded(InputStream input, ByteArrayOutputStream output) throws Exception {
        byte[] buffer = new byte[1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (total >= OUTPUT_LIMIT_BYTES) {
                break;
            }
            int allowed = Math.min(read, OUTPUT_LIMIT_BYTES - total);
            output.write(buffer, 0, allowed);
            total += allowed;
        }
    }

    /**
     * 解析 ls-remote 输出中的一个分支头。
     *
     * @param output ls-remote 输出。
     * @return 返回远程 HEAD 信息。
     */
    private RemoteHead parseRemoteHead(String output) {
        if (StrUtil.isBlank(output)) {
            return null;
        }
        RemoteHead fallback = null;
        for (String line : output.split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            RemoteHead head = new RemoteHead();
            head.commitHash = parts[0];
            head.branch = branchName(parts[1]);
            if ("main".equals(head.branch) || "master".equals(head.branch)) {
                return head;
            }
            if (fallback == null) {
                fallback = head;
            }
        }
        return fallback;
    }

    /**
     * 从 refs/heads/name 中提取分支名。
     *
     * @param ref Git 引用名。
     * @return 返回短分支名。
     */
    private String branchName(String ref) {
        String value = StrUtil.nullToEmpty(ref);
        String prefix = "refs/heads/";
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    /**
     * 判断引用是否为允许范围内的本地路径。
     *
     * @param ref 仓库引用。
     * @return 本地路径返回 true。
     */
    private boolean isLocalPath(String ref) {
        String value = StrUtil.nullToEmpty(ref).toLowerCase(Locale.ROOT);
        return value.contains("/code-projects/")
                || value.contains("/code-repositories/")
                || value.contains("\\code-projects\\")
                || value.contains("\\code-repositories\\");
    }

    /**
     * 判断引用是否为支持的显式托管仓库 URL。
     *
     * @param ref 仓库引用。
     * @return 支持的 URL 返回 true。
     */
    private boolean isHostedUrl(String ref) {
        String value = StrUtil.nullToEmpty(ref).toLowerCase(Locale.ROOT);
        return value.startsWith("https://github.com/")
                || value.startsWith("https://gitee.com/")
                || value.startsWith("https://gitlab.com/")
                || value.startsWith("http://github.com/")
                || value.startsWith("http://gitee.com/")
                || value.startsWith("http://gitlab.com/");
    }

    /**
     * 生成可读仓库名称。
     *
     * @param ref 仓库引用。
     * @return 返回短展示名。
     */
    private String displayName(String ref) {
        String value = StrUtil.nullToEmpty(ref).replace('\\', '/');
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        int index = value.lastIndexOf('/');
        return index >= 0 ? value.substring(index + 1) : value;
    }

    /**
     * 拼接稳定状态哈希。
     *
     * @param commitHash 提交哈希。
     * @param extra 额外状态，例如分支或远程 URL。
     * @return 返回状态哈希。
     */
    private String stateHash(String commitHash, String extra) {
        return StrUtil.nullToEmpty(commitHash) + ":" + StrUtil.nullToEmpty(extra);
    }

    /**
     * 记录仓库探测可恢复失败；只输出阶段和异常类型，避免日志携带仓库内容、URL 或密钥。
     *
     * @param stage 失败阶段。
     * @param error 原始异常。
     */
    private void logRecoverableProbeFailure(String stage, Exception error) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "proactive repository probe fallback: stage={}, errorType={}",
                    stage,
                    exceptionType(error));
        }
    }

    /**
     * 提取异常类型；检测到中断异常时恢复线程中断标记，保留原有降级语义。
     *
     * @param error 原始异常。
     * @return 异常类名。
     */
    private String exceptionType(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
            current = current.getCause();
        }
        return error == null ? "UnknownException" : error.getClass().getSimpleName();
    }

    /** 远程分支头解析结果。 */
    private static final class RemoteHead {
        /** 提交哈希。 */
        private String commitHash;

        /** 分支名称。 */
        private String branch;
    }
}
