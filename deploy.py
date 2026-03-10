#!/usr/bin/env python3
"""
部署脚本 - 上传 JAR 文件到远程服务器
"""
import paramiko
import os

# 配置
HOST = "156.225.28.65"
PORT = 22
USERNAME = "root"
PASSWORD = "qrmwNIKZ7693"
LOCAL_JAR = r"D:\IdeaProjects\SolonClaw\target\solonclaw.jar"
REMOTE_PATH = "/root/solonclaw.jar"

def deploy():
    """上传 JAR 文件到远程服务器"""
    try:
        # 创建 SSH 客户端
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())

        print(f"连接到 {HOST}...")
        ssh.connect(HOST, PORT, USERNAME, PASSWORD)
        print("连接成功！")

        # 创建 SFTP 客户端
        sftp = ssh.open_sftp()

        # 上传文件
        print(f"上传文件: {LOCAL_JAR} -> {REMOTE_PATH}")
        sftp.put(LOCAL_JAR, REMOTE_PATH)
        print("文件上传成功！")

        # 关闭连接
        sftp.close()
        ssh.close()

        print("\n部署完成！")
        print(f"远程文件路径: {REMOTE_PATH}")
        print("\n你可以使用以下命令启动应用:")
        print(f"  java -jar {REMOTE_PATH}")

        return True

    except Exception as e:
        print(f"部署失败: {e}")
        return False

if __name__ == "__main__":
    deploy()
