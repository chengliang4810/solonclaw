#!/usr/bin/env bash
# solonclaw 一键安装脚本（Linux / macOS）
# 用法: curl -fsSL https://raw.githubusercontent.com/chengliang4810/solon-claw/main/scripts/install.sh | bash
set -euo pipefail

# ─── 颜色 ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ─── 平台检测 ────────────────────────────────────────────────────────────────
OS="$(uname -s)"
case "$OS" in
    Linux*)  PLATFORM="linux"  ;;
    Darwin*) PLATFORM="darwin" ;;
    *)       error "不支持的平台: $OS（请使用 scripts/install.ps1 在 Windows 上安装）" ;;
esac
ARCH="$(uname -m)"
case "$ARCH" in
    x86_64|amd64) ARCH="x64"   ;;
    arm64|aarch64) ARCH="arm64" ;;
    *)            error "不支持的架构: $ARCH" ;;
esac
info "平台: $PLATFORM/$ARCH"

# ─── 安装目录 ────────────────────────────────────────────────────────────────
INSTALL_DIR="${SOLONCLAW_HOME:-$HOME/.solonclaw}"
WORKSPACE_DIR="$INSTALL_DIR/workspace"
mkdir -p "$INSTALL_DIR" "$WORKSPACE_DIR"

# ─── 检查 Java ──────────────────────────────────────────────────────────────
check_java() {
    if command -v java &>/dev/null; then
        JAVA_VER=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)(\.[0-9]+)*.*/\1/')
        if [ "${JAVA_VER:-0}" -ge 17 ]; then
            ok "Java $JAVA_VER 已安装"
            return 0
        fi
    fi
    return 1
}

if ! check_java; then
    warn "未检测到 Java 17+，正在尝试安装..."
    if [ "$PLATFORM" = "darwin" ]; then
        if command -v brew &>/dev/null; then
            info "通过 Homebrew 安装 OpenJDK 17..."
            brew install openjdk@17
            export PATH="$(brew --prefix openjdk@17)/bin:$PATH"
        else
            error "请先安装 Homebrew（https://brew.sh），然后运行: brew install openjdk@17"
        fi
    elif [ "$PLATFORM" = "linux" ]; then
        if command -v apt-get &>/dev/null; then
            info "通过 apt 安装 OpenJDK 17..."
            sudo apt-get update -qq && sudo apt-get install -y -qq openjdk-17-jre-headless
        elif command -v yum &>/dev/null; then
            info "通过 yum 安装 Java 17..."
            sudo yum install -y java-17-openjdk
        elif command -v dnf &>/dev/null; then
            info "通过 dnf 安装 Java 17..."
            sudo dnf install -y java-17-openjdk
        else
            error "无法自动安装 Java，请手动安装 JDK 17+（https://adoptium.net）"
        fi
    fi
    check_java || error "Java 安装后仍未检测到 java 命令，请手动安装 JDK 17+"
fi

# ─── 检查 Node.js ────────────────────────────────────────────────────────────
check_node() {
    if command -v node &>/dev/null; then
        NODE_VER=$(node -v 2>/dev/null | sed -E 's/v([0-9]+).*/\1/')
        if [ "${NODE_VER:-0}" -ge 20 ]; then
            ok "Node.js $NODE_VER 已安装"
            return 0
        fi
    fi
    return 1
}

if ! check_node; then
    warn "未检测到 Node.js 20+，正在尝试安装..."
    if [ "$PLATFORM" = "darwin" ]; then
        if command -v brew &>/dev/null; then
            info "通过 Homebrew 安装 Node.js..."
            brew install node
        else
            error "请先安装 Homebrew（https://brew.sh），然后运行: brew install node"
        fi
    elif [ "$PLATFORM" = "linux" ]; then
        info "通过 NodeSource 安装 Node.js 20..."
        curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash - 2>/dev/null || true
        if command -v apt-get &>/dev/null; then
            sudo apt-get install -y -qq nodejs
        elif command -v yum &>/dev/null; then
            sudo yum install -y nodejs
        elif command -v dnf &>/dev/null; then
            sudo dnf install -y nodejs
        fi
    fi
    check_node || error "Node.js 安装后仍未检测到 node 命令，请手动安装 Node.js 20+（https://nodejs.org）"
fi

# ─── 检查 npm ────────────────────────────────────────────────────────────────
command -v npm &>/dev/null || error "npm 未找到，请重新安装 Node.js"

# ─── 安装 TUI（npm 全局包）──────────────────────────────────────────────────
info "安装 solonclaw TUI..."
npm install -g solonclaw --silent 2>/dev/null || npm install -g solonclaw
ok "TUI 已安装: $(command -v solonclaw)"

# ─── 下载后端 jar ───────────────────────────────────────────────────────────
GITHUB_REPO="chengliang4810/solon-claw"
JAR_NAME="solonclaw.jar"
JAR_PATH="$INSTALL_DIR/$JAR_NAME"

info "获取最新版本..."
LATEST_TAG=$(curl -fsSL "https://api.github.com/repos/$GITHUB_REPO/releases/latest" | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/')
if [ -z "$LATEST_TAG" ]; then
    warn "无法获取最新版本，使用 main 分支构建信息"
    LATEST_TAG="latest"
fi
info "最新版本: $LATEST_TAG"

DOWNLOAD_URL="https://github.com/$GITHUB_REPO/releases/download/$LATEST_TAG/$JAR_NAME"
info "下载 jar: $DOWNLOAD_URL"
curl -fSL --progress-bar -o "$JAR_PATH" "$DOWNLOAD_URL"
ok "jar 已下载: $JAR_PATH ($(du -h "$JAR_PATH" | cut -f1))"

# ─── 创建默认配置 ───────────────────────────────────────────────────────────
CONFIG_FILE="$WORKSPACE_DIR/config.yml"
if [ ! -f "$CONFIG_FILE" ]; then
    cat > "$CONFIG_FILE" << 'EOF'
# solonclaw 运行配置
# 完整配置参考: https://github.com/chengliang4810/solon-claw/blob/main/config.example.yml

server:
  port: 8080

providers:
  default:
    name: DefaultProvider
    baseUrl: https://api.openai.com
    apiKey: ""
    defaultModel: gpt-5.4
    dialect: openai

model:
  providerKey: default
  default: "gpt-5.4"

solonclaw:
  dashboard:
    accessToken: ""
EOF
    ok "默认配置已创建: $CONFIG_FILE"
    warn "请编辑 $CONFIG_FILE 填入你的 API Key"
else
    ok "配置文件已存在: $CONFIG_FILE"
fi

# ─── 创建后端启动脚本 ──────────────────────────────────────────────────────
LAUNCHER="$INSTALL_DIR/solonclaw-server"
cat > "$LAUNCHER" << LAUNCHER_EOF
#!/usr/bin/env bash
# solonclaw 后端启动脚本
SOLONCLAW_JAR="$JAR_PATH"
SOLONCLAW_WORKSPACE="$WORKSPACE_DIR"
exec java -Dfile.encoding=UTF-8 -Dsolonclaw.workspace="\$SOLONCLAW_WORKSPACE" -jar "\$SOLONCLAW_JAR" "\$@"
LAUNCHER_EOF
chmod +x "$LAUNCHER"
ok "启动脚本: $LAUNCHER"

# ─── 写入环境变量到 shell profile ───────────────────────────────────────────
SHELL_PROFILE=""
if [ -n "${BASH_VERSION:-}" ] || [ -f "$HOME/.bashrc" ]; then
    SHELL_PROFILE="$HOME/.bashrc"
elif [ -n "${ZSH_VERSION:-}" ] || [ -f "$HOME/.zshrc" ]; then
    SHELL_PROFILE="$HOME/.zshrc"
fi

if [ -n "$SHELL_PROFILE" ]; then
    MARKER="# solonclaw environment"
    if ! grep -qF "$MARKER" "$SHELL_PROFILE" 2>/dev/null; then
        cat >> "$SHELL_PROFILE" << ENV_EOF

$MARKER
export SOLONCLAW_HOME="$INSTALL_DIR"
export SOLONCLAW_JAR="$JAR_PATH"
export SOLONCLAW_WORKSPACE="$WORKSPACE_DIR"
export SOLONCLAW_SERVER_URL="http://127.0.0.1:8080"
ENV_EOF
        ok "环境变量已写入 $SHELL_PROFILE"
        warn "请运行 source $SHELL_PROFILE 或重新打开终端使环境变量生效"
    else
        ok "环境变量已存在于 $SHELL_PROFILE"
    fi
fi

# ─── 注册 systemd 服务（Linux）─────────────────────────────────────────────
if [ "$PLATFORM" = "linux" ] && command -v systemctl &>/dev/null; then
    read -rp "是否注册 systemd 服务（开机自启）？[y/N] " REGISTER_SERVICE
    if [[ "$REGISTER_SERVICE" =~ ^[Yy]$ ]]; then
        JAVA_PATH="$(command -v java)"
        sudo tee /etc/systemd/system/solonclaw.service > /dev/null << SERVICE_EOF
[Unit]
Description=solonclaw agent service
After=network-online.target
Wants=network-online.target

[Service]
WorkingDirectory=$INSTALL_DIR
Environment="SOLONCLAW_HOME=$INSTALL_DIR"
Environment="SOLONCLAW_JAR=$JAR_PATH"
Environment="SOLONCLAW_WORKSPACE=$WORKSPACE_DIR"
ExecStart=$JAVA_PATH -Dfile.encoding=UTF-8 -Dsolonclaw.workspace=$WORKSPACE_DIR -jar $JAR_PATH
Restart=on-failure
RestartSec=5
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
SERVICE_EOF
        sudo systemctl daemon-reload
        sudo systemctl enable solonclaw
        ok "systemd 服务已注册并启用"
        info "启动服务: sudo systemctl start solonclaw"
        info "查看状态: sudo systemctl status solonclaw"
    fi
fi

# ─── 注册 launchd 服务（macOS）─────────────────────────────────────────────
if [ "$PLATFORM" = "darwin" ]; then
    read -rp "是否注册 launchd 服务（开机自启）？[y/N] " REGISTER_SERVICE
    if [[ "$REGISTER_SERVICE" =~ ^[Yy]$ ]]; then
        JAVA_PATH="$(command -v java)"
        PLIST_PATH="$HOME/Library/LaunchAgents/com.solonclaw.agent.plist"
        cat > "$PLIST_PATH" << PLIST_EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.solonclaw.agent</string>
    <key>ProgramArguments</key>
    <array>
        <string>$JAVA_PATH</string>
        <string>-Dfile.encoding=UTF-8</string>
        <string>-Dsolonclaw.workspace=$WORKSPACE_DIR</string>
        <string>-jar</string>
        <string>$JAR_PATH</string>
    </array>
    <key>WorkingDirectory</key>
    <string>$INSTALL_DIR</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>SOLONCLAW_HOME</key>
        <string>$INSTALL_DIR</string>
        <key>SOLONCLAW_JAR</key>
        <string>$JAR_PATH</string>
        <key>SOLONCLAW_WORKSPACE</key>
        <string>$WORKSPACE_DIR</string>
    </dict>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <dict>
        <key>SuccessfulExit</key>
        <false/>
    </dict>
    <key>StandardOutPath</key>
    <string>$INSTALL_DIR/logs/launchd-stdout.log</string>
    <key>StandardErrorPath</key>
    <string>$INSTALL_DIR/logs/launchd-stderr.log</string>
</dict>
</plist>
PLIST_EOF
        launchctl load "$PLIST_PATH" 2>/dev/null || true
        ok "launchd 服务已注册: $PLIST_PATH"
        info "启动服务: launchctl start com.solonclaw.agent"
    fi
fi

# ─── 完成 ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  solonclaw 安装完成！${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
echo ""
echo "  安装目录:   $INSTALL_DIR"
echo "  后端 jar:   $JAR_PATH"
echo "  工作区:     $WORKSPACE_DIR"
echo "  配置文件:   $CONFIG_FILE"
echo ""
echo "  启动后端:   $LAUNCHER"
echo "  启动 TUI:   solonclaw"
echo "  远程连接:   SOLONCLAW_SERVER_URL=http://IP:8080 solonclaw"
echo ""
echo "  首次使用请编辑配置文件填入 API Key:"
echo "    vim $CONFIG_FILE"
echo ""
