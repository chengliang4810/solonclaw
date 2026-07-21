package com.jimuqu.solon.claw.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IORuntimeException;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ContextFileConstants;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** workspace/ 根目录下人格工作区文件的统一访问服务。 */
public class PersonaWorkspaceService {
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(PersonaWorkspaceService.class);

    /** TEMPLATE根用户的统一常量值。 */
    private static final String TEMPLATE_ROOT = "persona-templates/";

    /** 记录Persona工作区中的工作区目录。 */
    private final File workspaceDir;

    /** 记录Persona工作区中的记忆目录。 */
    private final File memoryDir;

    /**
     * 创建Persona工作区服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public PersonaWorkspaceService(AppConfig appConfig) {
        this.workspaceDir = FileUtil.file(appConfig.getWorkspace().getDir());
        this.memoryDir = FileUtil.file(this.workspaceDir, ContextFileConstants.MEMORY_DIR);
        mkdirIfPossible(this.workspaceDir, "persona workspace");
        mkdirIfPossible(this.memoryDir, "persona memory");
        ensureSeeded();
    }

    /** 返回受控文件 key 顺序。 */
    public List<String> orderedKeys() {
        return ContextFileConstants.orderedKeys();
    }

    /** 解析 key 对应文件名。 */
    public String fileName(String key) {
        return ContextFileConstants.fileName(key);
    }

    /** 获取 key 对应文件。 */
    public File file(String key) {
        return FileUtil.file(workspaceDir, fileName(key));
    }

    /** 获取 key 对应文件绝对路径。 */
    public String absolutePath(String key) {
        return file(key).getAbsolutePath();
    }

    /** 判断文件是否存在。 */
    public boolean exists(String key) {
        return file(key).exists();
    }

    /** 读取文件内容；不存在时返回模板默认内容。 */
    public String read(String key) {
        if (isTodayMemoryKey(key)) {
            return readExistingTodayMemory();
        }
        File target = file(key);
        if (!target.exists()) {
            return loadTemplate(key);
        }
        try {
            return FileUtil.readUtf8String(target);
        } catch (IORuntimeException e) {
            log.warn(
                    "Unable to read persona workspace file {}; falling back to bundled template: {}",
                    safePathRef(target),
                    failureMessage(e));
            return loadTemplate(key);
        } catch (SecurityException e) {
            log.warn(
                    "Unable to read persona workspace file {}; falling back to bundled template: {}",
                    safePathRef(target),
                    failureMessage(e));
            return loadTemplate(key);
        }
    }

    /** 读取供系统提示词使用的正文内容。 */
    public String readPromptBody(String key) {
        return read(key);
    }

    /** 写入文件内容，不存在时自动创建。 */
    public void write(String key, String content) {
        writeContent(file(key), content);
    }

    /** 读取 key 对应模板内容。 */
    public String readTemplate(String key) {
        return loadTemplate(key);
    }

    /** 将文件恢复为模板默认内容。 */
    public void restoreTemplate(String key) {
        write(key, readTemplate(key));
    }

    /** 返回所有日记文件；活动日记优先，归档原文和派生摘要随后各自倒序排列。 */
    public List<String> listDiaryRelativePaths() {
        if (!memoryDir.exists()) {
            return Collections.emptyList();
        }

        List<File> files =
                FileUtil.loopFiles(
                        memoryDir, file -> file.isFile() && file.getName().endsWith(".md"));
        files.sort(
                (left, right) -> {
                    String leftPath = relativeDiaryPath(left);
                    String rightPath = relativeDiaryPath(right);
                    int categoryCompare =
                            Integer.compare(diaryCategory(leftPath), diaryCategory(rightPath));
                    return categoryCompare != 0 ? categoryCompare : rightPath.compareTo(leftPath);
                });

        List<String> result = new ArrayList<String>();
        for (File file : files) {
            result.add(ContextFileConstants.MEMORY_DIR + "/" + relativeDiaryPath(file));
        }
        return result;
    }

    /** 将日记文件转换为保留子目录的正斜杠相对路径。 */
    private String relativeDiaryPath(File file) {
        return memoryDir
                .toPath()
                .relativize(file.toPath())
                .toString()
                .replace(File.separatorChar, '/');
    }

    /** 把活动日记、不可变归档原文和派生摘要分为稳定展示顺序。 */
    private int diaryCategory(String relativePath) {
        if (!relativePath.startsWith("archive/")) {
            return 0;
        }
        return relativePath.endsWith(".summary.md") ? 2 : 1;
    }

    /**
     * 读取Diary。
     *
     * @param relativePath 文件或目录路径参数。
     * @return 返回读取到的Diary。
     */
    public String readDiary(String relativePath) {
        File target = diaryFile(relativePath);
        if (!target.exists()) {
            return "";
        }
        return FileUtil.readUtf8String(target);
    }

    /**
     * 执行absoluteDiary路径相关逻辑。
     *
     * @param relativePath 文件或目录路径参数。
     * @return 返回absolute Diary路径。
     */
    public String absoluteDiaryPath(String relativePath) {
        return diaryFile(relativePath).getAbsolutePath();
    }

    /**
     * 执行diary文件相关逻辑。
     *
     * @param relativePath 文件或目录路径参数。
     * @return 返回diary文件结果。
     */
    private File diaryFile(String relativePath) {
        if (StrUtil.isBlank(relativePath) || relativePath.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid diary path");
        }
        if (!listDiaryRelativePaths().contains(relativePath)) {
            throw new IllegalArgumentException("Diary file is not available: " + relativePath);
        }
        try {
            File target = FileUtil.file(workspaceDir, relativePath).getCanonicalFile();
            File root = memoryDir.getCanonicalFile();
            String targetPath = target.getAbsolutePath();
            String rootPath = root.getAbsolutePath();
            if (!targetPath.startsWith(rootPath + File.separator)) {
                throw new IllegalArgumentException("Diary file is outside memory directory");
            }
            return target;
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) e;
            }
            throw new IllegalArgumentException("Invalid diary path", e);
        }
    }

    /** 启动时补齐缺失的人格工作区文件。 */
    private void ensureSeeded() {
        boolean newWorkspace =
                !file(ContextFileConstants.KEY_AGENTS).exists()
                        && !file(ContextFileConstants.KEY_IDENTITY).exists()
                        && !file(ContextFileConstants.KEY_USER).exists();
        for (String key : orderedKeys()) {
            if (isTodayMemoryKey(key)) {
                continue;
            }
            if (ContextFileConstants.KEY_BOOTSTRAP.equals(key) && !newWorkspace) {
                continue;
            }
            File target = file(key);
            if (target.exists()) {
                continue;
            }
            try {
                writeContent(target, loadTemplate(key));
            } catch (IORuntimeException e) {
                log.warn(
                        "Unable to seed persona workspace file {}: {}. Startup continues; fix workspace directory permissions before editing workspace files.",
                        safePathRef(target),
                        failureMessage(e));
            } catch (SecurityException e) {
                log.warn(
                        "Unable to seed persona workspace file {}: {}. Startup continues; fix workspace directory permissions before editing workspace files.",
                        safePathRef(target),
                        failureMessage(e));
            }
        }
    }

    /**
     * 执行mkdirIfPossible相关逻辑。
     *
     * @param dir 文件或目录路径参数。
     * @param label label 参数。
     */
    private void mkdirIfPossible(File dir, String label) {
        try {
            FileUtil.mkdir(dir);
        } catch (IORuntimeException e) {
            log.warn(
                    "Unable to create {} directory {}: {}. Startup continues; file edits under this directory may fail.",
                    label,
                    safePathRef(dir),
                    failureMessage(e));
        } catch (SecurityException e) {
            log.warn(
                    "Unable to create {} directory {}: {}. Startup continues; file edits under this directory may fail.",
                    label,
                    safePathRef(dir),
                    failureMessage(e));
        }
    }

    /**
     * 写入Content。
     *
     * @param target target 参数。
     * @param content 待处理内容。
     */
    private void writeContent(File target, String content) {
        File parent = target.getParentFile();
        if (parent != null) {
            FileUtil.mkdir(parent);
        }
        FileUtil.writeUtf8String(StrUtil.nullToEmpty(content), target);
    }

    /**
     * 执行failure消息相关逻辑。
     *
     * @param e 捕获到的异常。
     * @return 返回failure消息结果。
     */
    private static String failureMessage(Throwable e) {
        if (e.getMessage() == null) {
            return e.getClass().getSimpleName();
        }
        return e.getClass().getSimpleName() + ": " + SecretRedactor.redact(e.getMessage(), 1000);
    }

    /**
     * 生成安全展示用的路径Ref。
     *
     * @param file 文件或目录路径参数。
     * @return 返回safe路径Ref结果。
     */
    private static String safePathRef(File file) {
        if (file == null) {
            return "path://unknown";
        }
        String name = file.getName();
        if (StrUtil.isBlank(name)) {
            name = "path";
        }
        return "path://" + SecretRedactor.redact(name, 200);
    }

    /** 从类路径加载原始模板。 */
    private String loadTemplate(String key) {
        String normalized = ContextFileConstants.normalizeKey(key);
        if (ContextFileConstants.KEY_MEMORY_TODAY.equals(normalized)) {
            return "";
        }
        String resource = TEMPLATE_ROOT + fileName(key);
        InputStream stream = getClass().getClassLoader().getResourceAsStream(resource);
        if (stream == null) {
            if (ContextFileConstants.KEY_MEMORY.equals(normalized)
                    || ContextFileConstants.KEY_SOUL.equals(normalized)) {
                return "";
            }
            throw new IllegalStateException("Missing persona template resource: " + resource);
        }
        try {
            return IoUtil.read(stream, StandardCharsets.UTF_8);
        } finally {
            IoUtil.close(stream);
        }
    }

    /** 判断 key 是否为当天记忆文件。 */
    private boolean isTodayMemoryKey(String key) {
        return ContextFileConstants.KEY_MEMORY_TODAY.equals(ContextFileConstants.normalizeKey(key));
    }

    /** 读取已存在的当天记忆文件；缺失时保持空内容，避免启动或读取时隐式生成流水账文件。 */
    private String readExistingTodayMemory() {
        File target = file(ContextFileConstants.KEY_MEMORY_TODAY);
        if (!target.exists()) {
            return "";
        }
        try {
            return FileUtil.readUtf8String(target);
        } catch (IORuntimeException e) {
            log.warn(
                    "Unable to read persona workspace file {}; falling back to empty today memory: {}",
                    safePathRef(target),
                    failureMessage(e));
            return "";
        } catch (SecurityException e) {
            log.warn(
                    "Unable to read persona workspace file {}; falling back to empty today memory: {}",
                    safePathRef(target),
                    failureMessage(e));
            return "";
        }
    }
}
