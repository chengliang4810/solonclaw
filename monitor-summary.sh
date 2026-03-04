#!/bin/bash

echo "========================================="
echo "监控摘要触发情况"
echo "========================================="
echo ""
echo "当前会话消息数量："
grep "历史消息数" workspace/logs/solonclaw.log | tail -5
echo ""
echo "工具执行次数："
grep "Agent 执行工具" workspace/logs/solonclaw.log | wc -l
echo ""
echo "最近的工具调用："
grep "Agent 执行工具" workspace/logs/solonclaw.log | tail -5
echo ""
echo "检查摘要相关日志："
echo "-------------------------------------------"
grep -i "summary\|summarize\|摘要\|expired" workspace/logs/solonclaw.log | tail -20
echo ""
echo "========================================="
echo ""
echo "如果没有看到摘要日志，可能是因为："
echo "1. 消息数还未达到阈值（maxMessages=12）"
echo "2. 摘要拦截器未正确触发"
echo "3. 需要更多轮次的对话"
