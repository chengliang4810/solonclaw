#!/usr/bin/env python3
"""
记忆摘要功能测试脚本
用于验证 SolonClaw 的"永不失忆"功能
"""

import requests
import time
import json

BASE_URL = "http://localhost:12345"
SESSION_ID = f"test-memory-{int(time.time())}"

def chat(message):
    """发送聊天消息"""
    response = requests.post(
        f"{BASE_URL}/api/chat",
        json={"message": message, "sessionId": SESSION_ID}
    )
    return response.json()

def main():
    print("=" * 60)
    print("记忆摘要功能测试")
    print("=" * 60)
    print(f"会话 ID: {SESSION_ID}\n")

    # 测试消息列表
    messages = [
        # 第一阶段：基本信息（5条）
        ("你好，我叫张三", "用户介绍"),
        ("我是一名软件工程师", "职业信息"),
        ("我的邮箱是 zhangsan@example.com", "联系方式"),
        ("我的项目在 github.com/zhangsan/project", "项目信息"),
        ("我喜欢使用 Java 和 Python", "技术偏好"),

        # 第二阶段：技术讨论（5条）
        ("Java 的并发编程很重要", "技术话题1"),
        ("Python 的数据科学库很强大", "技术话题2"),
        ("Spring Boot 是个好框架", "技术话题3"),
        ("Django 也很不错", "技术话题4"),
        ("微服务架构是趋势", "技术话题5"),

        # 第三阶段：触发摘要阈值（超过12条）
        ("容器化部署很流行", "技术话题6"),
        ("Kubernetes 是标准", "技术话题7 - 第12条"),
        ("云原生架构很重要", "技术话题8 - 第13条，应触发摘要"),
        ("DevOps 实践很关键", "技术话题9 - 第14条"),
        ("CI/CD 自动化必不可少", "技术话题10 - 第15条"),

        # 第四阶段：验证记忆保留
        ("你还记得我的名字吗？", "验证记忆1"),
        ("我的邮箱地址是什么？", "验证记忆2"),
        ("我的项目地址在哪里？", "验证记忆3"),
        ("我喜欢什么编程语言？", "验证记忆4"),
        ("我们聊了哪些技术话题？", "验证记忆5"),
    ]

    print(f"总共将发送 {len(messages)} 条消息\n")
    print("=" * 60)
    print("开始测试...")
    print("=" * 60 + "\n")

    for i, (msg, desc) in enumerate(messages, 1):
        print(f"[{i}] {desc}")
        print(f"    消息: {msg}")

        try:
            result = chat(msg)
            if result.get("code") == 200:
                content = result.get("data", {}).get("response", "")
                # 只显示前100个字符
                preview = content[:100] + "..." if len(content) > 100 else content
                print(f"    响应: {preview}")
            else:
                print(f"    错误: {result}")
        except Exception as e:
            print(f"    异常: {e}")

        print()
        time.sleep(1.5)

    print("=" * 60)
    print("测试完成！")
    print("=" * 60)
    print("\n请检查应用日志，查找摘要相关日志：")
    print("  grep -i 'summary\\|summarize\\|摘要' workspace/logs/solonclaw.log")
    print("\n或者查看完整日志：")
    print("  tail -f workspace/logs/solonclaw.log")

if __name__ == "__main__":
    main()
