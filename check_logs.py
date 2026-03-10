#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
日志检查脚本 - 查看远程服务器日志
"""
import paramiko

# 配置
HOST = "156.225.28.65"
PORT = 22
USERNAME = "root"
PASSWORD = "qrmwNIKZ7693"
LOG_PATH = "/root/solonclaw.log"

def check_logs():
    """检查远程日志"""
    try:
        # 创建 SSH 客户端
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())

        print(f"连接到 {HOST}...")
        ssh.connect(HOST, PORT, USERNAME, PASSWORD)
        print("连接成功！\n")

        # 查看日志最后 50 行
        print("=== 日志最后 50 行 ===")
        stdin, stdout, stderr = ssh.exec_command(f"tail -n 50 {LOG_PATH}")
        print(stdout.read().decode('utf-8', errors='replace'))

        # 检查是否有错误
        print("\n=== 检查错误日志 ===")
        stdin, stdout, stderr = ssh.exec_command(f"grep -i 'error\\|exception\\|failed' {LOG_PATH} | tail -n 20")
        errors = stdout.read().decode('utf-8', errors='replace')
        if errors:
            print(errors)
        else:
            print("未发现错误日志")

        ssh.close()

    except Exception as e:
        print(f"检查日志失败: {e}")

if __name__ == "__main__":
    check_logs()
