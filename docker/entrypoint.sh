#!/usr/bin/env bash
set -e

WORKSPACE_HOME="/app/workspace"
RUNTIME_JAR="$WORKSPACE_HOME/solonclaw.jar"

# /app/workspace 承载 config.yml、SQLite、日志、缓存、技能和上下文文件。
# 官方镜像以非 root 用户运行，这里只创建目录，不尝试修改宿主机挂载权限。
mkdir -p "$WORKSPACE_HOME"
if [ ! -f "$RUNTIME_JAR" ]; then
    cp /app/solonclaw.jar "$RUNTIME_JAR"
fi

# 参数原样透传给后端，便于 docker compose 覆盖 server/console 等启动模式。
exec java -jar "$RUNTIME_JAR" "$@"
