#!/usr/bin/env python3
"""
远程启动脚本 - 在远程服务器上启动 SolonClaw 应用
"""
import paramiko
import time

# 配置
HOST = "156.225.28.65"
PORT = 22
USERNAME = "root"
PASSWORD = "qrmwNIKZ7693"
JAR_PATH = "/root/solonclaw.jar"

def execute_command(ssh, command):
    """执行远程命令"""
    stdin, stdout, stderr = ssh.exec_command(command)
    output = stdout.read().decode('utf-8')
    error = stderr.read().decode('utf-8')
    return output, error

def start_application():
    """启动远程应用"""
    try:
        # 创建 SSH 客户端
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())

        print(f"连接到 {HOST}...")
        ssh.connect(HOST, PORT, USERNAME, PASSWORD)
        print("连接成功！")

        # 检查文件是否存在
        print("\n检查 JAR 文件...")
        output, error = execute_command(ssh, f"ls -lh {JAR_PATH}")
        if error:
            print(f"错误: {error}")
            return False
        print(f"JAR 文件信息:\n{output}")

        # 停止旧进程（如果存在）
        print("\n停止旧进程...")
        execute_command(ssh, "pkill -f solonclaw.jar")
        time.sleep(2)

        # 启动应用
        print("\n启动应用...")
        command = f"nohup java -jar {JAR_PATH} --solon.env=prod > /root/solonclaw.log 2>&1 &"
        execute_command(ssh, command)
        print("应用已启动！")

        # 等待应用启动
        print("\n等待应用启动...")
        time.sleep(5)

        # 检查进程
        print("检查应用状态...")
        output, error = execute_command(ssh, "ps aux | grep solonclaw.jar | grep -v grep")
        if output:
            print(f"应用正在运行:\n{output}")
        else:
            print("警告: 未检测到应用进程")

        # 检查日志
        print("\n应用日志:")
        output, error = execute_command(ssh, "tail -n 20 /root/solonclaw.log")
        print(output)

        # 检查端口
        print("\n检查端口 12345...")
        output, error = execute_command(ssh, "netstat -tlnp | grep 12345")
        if output:
            print(f"端口监听状态:\n{output}")
        else:
            print("警告: 端口 12345 未监听")

        # 关闭连接
        ssh.close()

        print("\n========== 部署完成 ==========")
        print(f"应用地址: http://{HOST}:12345")
        print(f"日志文件: /root/solonclaw.log")
        print("================================")

        return True

    except Exception as e:
        print(f"启动应用失败: {e}")
        return False

if __name__ == "__main__":
    start_application()
