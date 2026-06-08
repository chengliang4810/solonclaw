package com.jimuqu.solon.claw.support.logging;

import ch.qos.logback.core.rolling.RollingFileAppender;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 支持外部日志轮转恢复的滚动文件 appender。 */
public class WatchedRollingFileAppender<E> extends RollingFileAppender<E> {
    /** 记录WatchedRolling文件Appender中的active文件键。 */
    private Object activeFileKey;

    /** 启动当前组件并准备运行资源。 */
    @Override
    public void start() {
        super.start();
        activeFileKey = readFileKey(getFile());
    }

    /** 执行rollover相关逻辑。 */
    @Override
    public void rollover() {
        super.rollover();
        activeFileKey = readFileKey(getFile());
    }

    /**
     * 执行subAppend相关逻辑。
     *
     * @param event 事件参数。
     */
    @Override
    protected void subAppend(E event) {
        reopenIfExternallyRotated();
        super.subAppend(event);
    }

    /** 重新打开IfExternallyRotated。 */
    private void reopenIfExternallyRotated() {
        String fileName = getFile();
        if (fileName == null || getOutputStream() == null) {
            return;
        }
        Object currentFileKey = readFileKey(fileName);
        if (activeFileKey == null && currentFileKey != null) {
            activeFileKey = currentFileKey;
            return;
        }
        if (currentFileKey != null && currentFileKey.equals(activeFileKey)) {
            return;
        }
        streamWriteLock.lock();
        try {
            closeOutputStream();
            openFile(fileName);
            activeFileKey = readFileKey(fileName);
        } catch (IOException e) {
            addError("Failed to reopen externally rotated log file [" + fileName + "].", e);
        } finally {
            streamWriteLock.unlock();
        }
    }

    /**
     * 读取文件键。
     *
     * @param fileName 文件或目录路径参数。
     * @return 返回读取到的文件键。
     */
    private Object readFileKey(String fileName) {
        if (fileName == null) {
            return null;
        }
        try {
            Path path = new File(fileName).toPath();
            if (!Files.exists(path)) {
                return null;
            }
            Object fileKey = Files.readAttributes(path, "basic:fileKey").get("fileKey");
            if (fileKey != null) {
                return fileKey;
            }
            return path.toRealPath().toString();
        } catch (IOException e) {
            return null;
        }
    }
}
