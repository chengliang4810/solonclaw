#!/bin/bash
# SolonClaw 启动脚本
# 确保应用运行在配置的端口上

# 颜色定义
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  SolonClaw AI Agent 服务启动中...${NC}"
echo -e "${BLUE}========================================${NC}"

# 查找并结束占用 12345 端口的进程
PORT_PID=$(lsof -ti:12345)
if [ -n "$PORT_PID" ]; then
    echo -e "${GREEN}发现端口 12345 被占用，正在结束进程 $PORT_PID...${NC}"
    kill -9 $PORT_PID
    sleep 1
fi

# 启动应用
echo -e "${GREEN}启动 SolonClaw...${NC}"
java -jar target/solonclaw.jar --server.port=12345

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  SolonClaw 已停止${NC}"
echo -e "${BLUE}========================================${NC}"
