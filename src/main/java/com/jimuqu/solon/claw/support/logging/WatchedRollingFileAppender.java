package com.jimuqu.solon.claw.support.logging;

import ch.qos.logback.core.rolling.RollingFileAppender;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 支持外部日志轮转恢复的滚动文件 appender。 */
public class WatchedRollingFileAppender<E> extends RollingFileAppender<E> {
    private Object activeFileKey;

    @Override
    public void start() {
        super.start();
        activeFileKey = readFileKey(getFile());
    }

    @Override
    public void rollover() {
        super.rollover();
        activeFileKey = readFileKey(getFile());
    }

    @Override
    protected void subAppend(E event) {
        reopenIfExternallyRotated();
        super.subAppend(event);
    }

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
