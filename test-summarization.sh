#!/bin/bash

# 记忆摘要系统测试脚本
# 用于测试 SolonClaw 的"永不失忆"功能

BASE_URL="http://localhost:12345"
SESSION_ID="test-summarization-$(date +%s)"

echo "========================================="
echo "记忆摘要系统测试"
echo "========================================="
echo "会话 ID: $SESSION_ID"
echo ""

# 测试函数
send_message() {
    local message="$1"
    echo "发送消息: $message"

    response=$(curl -s -X POST "$BASE_URL/api/chat" \
        -H "Content-Type: application/json" \
        -d "{
            \"message\": \"$message\",
            \"sessionId\": \"$SESSION_ID\"
        }")

    echo "$response" | jq -r '.data.content' 2>/dev/null || echo "$response"
    echo ""
    sleep 2
}

# 发送 15 条消息来触发摘要（阈值为 12）
echo "第一阶段：发送前 10 条消息"
echo "----------------------------------------"

send_message "你好，我叫张三"
send_message "我是一名软件工程师"
send_message "我喜欢使用 Java 和 Python 开发"
send_message "我的项目地址是 github.com/example/project"
send_message "我正在学习 Solon AI 框架"
send_message "我的邮箱是 zhangsan@example.com"
send_message "我想开发一个 AI Agent 应用"
send_message "需要实现对话记忆功能"
send_message "还要支持工具调用"
send_message "最后要添加摘要功能"

echo ""
echo "第二阶段：继续发送 5 条消息（预期触发摘要）"
echo "----------------------------------------"

send_message "现在我需要测试摘要功能"
send_message "这是第 12 条消息"
send_message "这是第 13 条消息，应该会触发摘要了"
send_message "你记得我叫什么名字吗？"
send_message "我的邮箱地址是什么？项目地址呢？"

echo ""
echo "========================================="
echo "测试完成"
echo "========================================="
echo ""
echo "请检查应用日志，查找以下内容："
echo "1. 摘要触发日志"
echo "2. 关键信息提取日志"
echo "3. 层级摘要生成日志"
echo ""
echo "查看日志命令："
echo "  tail -f workspace-dev/logs/solonclaw.log | grep -i summary"
echo ""
