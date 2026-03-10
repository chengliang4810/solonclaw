#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
功能测试脚本 - 测试远程 SolonClaw 应用的 API
"""
import requests
import json
import time
import sys
import io

# 修复 Windows 控制台编码问题
if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

# 配置
BASE_URL = "http://156.225.28.65:12345"
TIMEOUT = 30

def test_health_check():
    """测试健康检查接口"""
    print("测试 1: 健康检查")
    try:
        response = requests.get(f"{BASE_URL}/health", timeout=TIMEOUT)
        print(f"  状态码: {response.status_code}")
        if response.status_code == 200:
            data = response.json()
            print(f"  响应: {json.dumps(data, indent=2, ensure_ascii=False)}")
            print("  ✓ 健康检查通过\n")
            return True
        else:
            print(f"  ✗ 健康检查失败\n")
            return False
    except Exception as e:
        print(f"  ✗ 错误: {e}\n")
        return False

def test_chat_api():
    """测试对话 API"""
    print("测试 2: 对话 API")
    try:
        payload = {
            "message": "你好，请介绍一下你自己",
            "sessionId": "test-session-001"
        }
        response = requests.post(
            f"{BASE_URL}/api/chat",
            json=payload,
            timeout=TIMEOUT
        )
        print(f"  状态码: {response.status_code}")
        if response.status_code == 200:
            data = response.json()
            print(f"  响应: {json.dumps(data, indent=2, ensure_ascii=False)[:200]}...")
            print("  ✓ 对话 API 正常\n")
            return True
        else:
            print(f"  ✗ 对话 API 失败: {response.text}\n")
            return False
    except Exception as e:
        print(f"  ✗ 错误: {e}\n")
        return False

def test_tools_list():
    """测试工具列表接口"""
    print("测试 3: 工具列表")
    try:
        response = requests.get(f"{BASE_URL}/api/tools", timeout=TIMEOUT)
        print(f"  状态码: {response.status_code}")
        if response.status_code == 200:
            data = response.json()
            if data.get("code") == 200:
                tools = data.get("data", {})
                print(f"  可用工具数量: {len(tools)}")
                # 检查是否有 subagent 工具
                if "spawn_subagent" in tools:
                    print("  ✓ 发现子 Agent 工具 (spawn_subagent)")
                    print(f"    描述: {tools['spawn_subagent'].get('description', 'N/A')[:100]}...")
                print("  ✓ 工具列表获取成功\n")
                return True
            else:
                print(f"  ✗ 工具列表获取失败\n")
                return False
        else:
            print(f"  ✗ 工具列表获取失败\n")
            return False
    except Exception as e:
        print(f"  ✗ 错误: {e}\n")
        return False

def test_history_api():
    """测试历史记录接口"""
    print("测试 4: 历史记录")
    try:
        response = requests.get(
            f"{BASE_URL}/api/history?sessionId=test-session-001",
            timeout=TIMEOUT
        )
        print(f"  状态码: {response.status_code}")
        if response.status_code == 200:
            data = response.json()
            if data.get("code") == 200:
                history = data.get("data", [])
                print(f"  历史消息数: {len(history)}")
                print("  ✓ 历史记录获取成功\n")
                return True
            else:
                print(f"  ✗ 历史记录获取失败\n")
                return False
        else:
            print(f"  ✗ 历史记录获取失败\n")
            return False
    except Exception as e:
        print(f"  ✗ 错误: {e}\n")
        return False

def run_all_tests():
    """运行所有测试"""
    print("=" * 60)
    print("SolonClaw 功能测试")
    print(f"测试地址: {BASE_URL}")
    print("=" * 60)
    print()

    results = {
        "健康检查": test_health_check(),
        "对话 API": test_chat_api(),
        "工具列表": test_tools_list(),
        "历史记录": test_history_api()
    }

    # 总结
    print("=" * 60)
    print("测试结果汇总")
    print("=" * 60)
    passed = sum(1 for v in results.values() if v)
    total = len(results)
    for test, result in results.items():
        status = "✓ 通过" if result else "✗ 失败"
        print(f"  {test}: {status}")

    print(f"\n总计: {passed}/{total} 测试通过")

    if passed == total:
        print("\n🎉 所有测试通过！")
    else:
        print(f"\n⚠️  有 {total - passed} 个测试失败")

    return passed == total

if __name__ == "__main__":
    run_all_tests()
