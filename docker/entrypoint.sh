#!/usr/bin/env bash
set -e

RUNTIME_HOME="/app/runtime"

# 官方镜像默认以 root 运行，确保运行态目录存在后直接启动 SolonClaw。
mkdir -p "$RUNTIME_HOME"

exec java -jar /app/solon-claw.jar "$@"
