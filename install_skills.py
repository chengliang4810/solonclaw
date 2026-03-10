#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
技能安装脚本 - 上传技能到远程服务器
"""
import paramiko
import os

# 配置
HOST = "156.225.28.65"
PORT = 22
USERNAME = "root"
PASSWORD = "qrmwNIKZ7693"
REMOTE_SKILLS_DIR = "/root/solonclaw/workspace/skills/"
LOCAL_SKILLS_DIR = "test-skills/"

def install_skills():
    """安装技能到远程服务器"""
    try:
        # 创建 SSH 客户端
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())

        print(f"连接到 {HOST}...")
        ssh.connect(HOST, PORT, USERNAME, PASSWORD)
        print("连接成功！\n")

        # 创建 SFTP 客户端
        sftp = ssh.open_sftp()

        # 确保远程目录存在
        print("创建远程技能目录...")
        ssh.exec_command(f"mkdir -p {REMOTE_SKILLS_DIR}")

        # 上传技能文件
        print("上传技能文件...")
        skills = os.listdir(LOCAL_SKILLS_DIR)

        for skill in skills:
            if skill.endswith('.md'):
                local_path = os.path.join(LOCAL_SKILLS_DIR, skill)
                remote_path = os.path.join(REMOTE_SKILLS_DIR, skill)

                print(f"  上传: {skill}")
                sftp.put(local_path, remote_path)

        sftp.close()

        # 列出已安装的技能
        print("\n已安装的技能：")
        stdin, stdout, stderr = ssh.exec_command(f"ls -la {REMOTE_SKILLS_DIR}")
        print(stdout.read().decode('utf-8', errors='replace'))

        ssh.close()

        print("\n✓ 技能安装完成！")
        print(f"远程路径: {REMOTE_SKILLS_DIR}")

    except Exception as e:
        print(f"安装失败: {e}")

if __name__ == "__main__":
    install_skills()
