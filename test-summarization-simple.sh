#!/bin/bash

# 简化的记忆摘要测试脚本

SESSION_ID="test-sum-$(date +%s)"
echo "会话 ID: $SESSION_ID"
echo ""

# 发送多条消息以触发摘要
for i in {1..15}; do
    echo "[$i] 发送消息..."

    case $i in
        1) msg="你好，我叫张三，是一名软件工程师" ;;
        2) msg="我的邮箱是 zhangsan@example.com" ;;
        3) msg="我的项目地址是 github.com/example/project" ;;
        4) msg="我喜欢使用 Java 和 Python 开发" ;;
        5) msg="我正在学习 Solon AI 框架" ;;
        6) msg="我想开发一个 AI Agent 应用" ;;
        7) msg="需要实现对话记忆功能" ;;
        8) msg="还要支持工具调用" ;;
        9) msg="最后要添加摘要功能" ;;
        10) msg="摘要可以帮助减少 Token 消耗" ;;
        11) msg="同时保留关键信息" ;;
        12) msg="这是第 12 条消息，可能触发摘要" ;;
        13) msg="这是第 13 条消息" ;;
        14) msg="你记得我叫什么名字吗？" ;;
        15) msg="我的邮箱地址是什么？项目地址呢？" ;;
    esac

    curl -s -X POST "http://localhost:12345/api/chat" \
        -H "Content-Type: application/json" \
        -d "{\"message\": \"$msg\", \"sessionId\": \"$SESSION_ID\"}" > /dev/null

    echo "  ✓ 已发送: $msg"
    sleep 1
done

echo ""
echo "测试完成！请查看日志文件："
echo "  tail -f workspace-dev/logs/solonclaw.log"
echo ""
echo "搜索摘要相关日志："
echo "  grep -i 'summary\\|summarize\\|摘要' workspace-dev/logs/solonclaw.log"
