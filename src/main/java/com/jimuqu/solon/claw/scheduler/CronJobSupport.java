package com.jimuqu.solon.claw.scheduler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.support.ErrorTextSupport;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 定时任务调度包内共享的工具方法集合，收敛历史复制粘贴。
 *
 * <p>这里只收录在多个调度类中逐字复制、且行为完全一致的私有辅助方法；存在日志级别或异常处理差异的副本仍保留在原类中。
 */
public final class CronJobSupport {

    private CronJobSupport() {}

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * <p>统一返回 "unknown" 兜底（与 SkillCuratorScheduler / HeartbeatScheduler 历史行为一致）。
     *
     * @param error 捕获到的异常。
     * @return 返回safe Error结果。
     */
    public static String safeError(Throwable error) {
        return ErrorTextSupport.safeError(error);
    }

    /**
     * 规范化字符串：null 返回 null，纯空白返回 null，其余返回去除首尾空白后的值。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回Blank结果。
     */
    public static String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.length() == 0 ? null : text;
    }

    /**
     * 按候选键顺序从映射中读取第一个非空且非空白的字符串值。
     *
     * @param map 待读取的映射对象。
     * @param keys 候选键列表。
     * @return 返回first String结果。
     */
    public static String firstString(Map<?, ?> map, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            Object value = map.get(keys[i]);
            if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /**
     * 判断目标文件是否严格位于指定目录之下（不含目录自身）。
     *
     * @param root root 参数。
     * @param target target 参数。
     * @return 如果Under Directory满足条件则返回 true，否则返回 false。
     * @throws IOException 当解析规范路径失败时抛出。
     */
    public static boolean isUnderDirectory(File root, File target) throws IOException {
        Path rootPath = root.getCanonicalFile().toPath().toAbsolutePath().normalize();
        Path targetPath = target.getCanonicalFile().toPath().toAbsolutePath().normalize();
        if (targetPath.equals(rootPath)) {
            return false;
        }
        return targetPath.startsWith(rootPath);
    }

    /**
     * 生成日志用任务标识，避免空值或异常值破坏结构化日志。
     *
     * @param jobId 定时任务标识。
     * @return 可用于日志的任务标识。
     */
    public static String safeLogJobId(String jobId) {
        String value = StrUtil.nullToEmpty(jobId).trim();
        return StrUtil.isBlank(value) ? "unknown" : value;
    }

    /**
     * 生成异常类型摘要，避免把异常消息中的 prompt、脚本或密钥写入日志。
     *
     * @param error 捕获到的异常。
     * @return 异常类型名称。
     */
    public static String exceptionType(Throwable error) {
        if (error == null) {
            return "Exception";
        }
        if (error instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return error.getClass().getSimpleName();
    }

    /**
     * 计算脚本版本指纹，用于区分同一 Cron 任务的已批准脚本版本。
     *
     * @param scriptContent 脚本文本。
     * @return 返回脚本指纹。
     */
    public static String approvalFingerprint(String scriptContent) {
        String value = StrUtil.nullToEmpty(scriptContent);
        return SecureUtil.sha256(value).substring(0, 16);
    }

    /**
     * 计算绑定脚本内容和执行上下文的授权指纹，防止切换工作目录后复用旧授权。
     *
     * @param job Cron 任务。
     * @param scriptContent 脚本文本。
     * @return 返回脚本执行版本指纹。
     */
    public static String approvalFingerprint(CronJobRecord job, String scriptContent) {
        String context =
                StrUtil.nullToEmpty(job == null ? null : job.getScript())
                        + "\n"
                        + StrUtil.nullToEmpty(job == null ? null : job.getWorkdir())
                        + "\n"
                        + (job != null && job.isNoAgent())
                        + "\n"
                        + StrUtil.nullToEmpty(scriptContent);
        return SecureUtil.sha256(context).substring(0, 16);
    }
}
