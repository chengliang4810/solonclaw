#!/usr/bin/env bash
set -e

RUNTIME_HOME="/app/runtime"

# /app/runtime 承载 config.yml、SQLite、日志、缓存、技能和上下文文件。
# 官方镜像默认以 root 运行，这里只创建目录，不修改宿主机挂载权限。
mkdir -p "$RUNTIME_HOME"

# 参数原样透传给后端，便于 docker compose 覆盖 server/console 等启动模式。
exec java -jar /app/solon-claw.jar "$@"
