#!/bin/bash

# 使用工具调用的记忆摘要测试

SESSION_ID="test-tools-$(date +%s)"
echo "会话 ID: $SESSION_ID"
echo "========================================="
echo ""

# 发送包含工具调用的消息
messages=(
    "你好"
    "帮我查看当前目录的文件"
    "显示项目根目录的文件列表"
    "帮我创建一个测试文件 test.txt"
    "查看 test.txt 的内容"
    "列出当前环境变量"
    "查看 Java 版本"
    "查看当前工作目录"
    "显示系统时间"
    "查看内存使用情况"
    "列出进程信息"
    "查看网络连接"
    "测试网络连接到 baidu.com"
    "你记得我们第一条消息是什么吗？"
    "总结一下我们做了什么操作"
)

for i in "${!messages[@]}"; do
    msg="${messages[$i]}"
    echo "[$((i+1))] 发送: $msg"

    response=$(curl -s -X POST "http://localhost:12345/api/chat" \
        -H "Content-Type: application/json" \
        -d "{\"message\": \"$msg\", \"sessionId\": \"$SESSION_ID\"}")

    echo "  ✓ 完成"
    sleep 2
done

echo ""
echo "========================================="
echo "测试完成！"
echo ""
echo "检查摘要日志："
echo "  grep -i 'observation\\|summary' workspace/logs/solonclaw.log | tail -50"
echo ""
