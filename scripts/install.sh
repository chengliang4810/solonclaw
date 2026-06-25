#!/usr/bin/env bash
# solonclaw 一键安装脚本（Linux / macOS）
# 用法: curl -fsSL https://raw.githubusercontent.com/chengliang4810/solon-claw/main/scripts/install.sh | bash
# 国内用户: curl -fsSL https://ghfast.top/https://raw.githubusercontent.com/chengliang4810/solon-claw/main/scripts/install.sh | bash
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

# ─── GitHub 代理（国内用户自动检测）──────────────────────────────────────────
GITHUB_MIRRORS=(
    "https://ghfast.top/"
    "https://gh-proxy.com/"
    "https://mirror.ghproxy.com/"
    "https://ghproxy.net/"
)

detect_github_proxy() {
    if [ -n "${GITHUB_PROXY+x}" ]; then
        [ -n "$GITHUB_PROXY" ] && info "使用自定义代理: $GITHUB_PROXY"
        return
    fi
    if curl -fsSL --connect-timeout 3 --max-time 5 "https://api.github.com" >/dev/null 2>&1; then
        GITHUB_PROXY=""
        info "GitHub 直连可用"
        return
    fi
    info "GitHub 直连不可用，正在寻找代理..."
    for mirror in "${GITHUB_MIRRORS[@]}"; do
        if curl -fsSL --connect-timeout 3 --max-time 5 "${mirror}https://api.github.com" >/dev/null 2>&1; then
            GITHUB_PROXY="$mirror"
            ok "找到可用代理: $GITHUB_PROXY"
            return
        fi
    done
    GITHUB_PROXY=""
    warn "未找到可用代理，将直连 GitHub（可能较慢或失败）"
    warn "可手动设置: export GITHUB_PROXY=\"https://ghfast.top/\""
}

gh_url() {
    if [ -n "${GITHUB_PROXY:-}" ]; then echo "${GITHUB_PROXY}$1"; else echo "$1"; fi
}

gh_api() {
    curl -fsSL --connect-timeout 10 --max-time 30 "$(gh_url "https://api.github.com$1")"
}

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
DEFAULT_INSTALL_DIR="$HOME/.solonclaw"
echo ""
read -rp "  安装目录（默认 $DEFAULT_INSTALL_DIR）: " CUSTOM_DIR < /dev/tty
INSTALL_DIR="${CUSTOM_DIR:-$DEFAULT_INSTALL_DIR}"
WORKSPACE_DIR="$INSTALL_DIR/workspace"
mkdir -p "$INSTALL_DIR" "$WORKSPACE_DIR"
info "安装目录: $INSTALL_DIR"

# ─── Dashboard 访问令牌 ──────────────────────────────────────────────────────
echo ""
echo "  设置 Dashboard 登录密钥（留空则无法登录 Web 管理页面）"
read -rp "  请输入访问令牌: " DASHBOARD_TOKEN < /dev/tty
if [ -z "$DASHBOARD_TOKEN" ]; then
    warn "未设置访问令牌，Dashboard 将无法登录（后续可通过 config.yml 配置）"
fi

# ─── 选择部署方式 ────────────────────────────────────────────────────────────
echo ""
echo "  选择部署方式:"
echo "    1) 原生安装（Java + 系统服务）"
echo "    2) Docker 部署（docker compose）"
echo ""
read -rp "  请输入 [1/2]（默认 1）: " DEPLOY_CHOICE < /dev/tty
DEPLOY_CHOICE="${DEPLOY_CHOICE:-1}"

# ─── sdkman / jabba 等 Java 版本管理器自动加载 ───────────────────────────────
# sdkman-init.sh 内部引用 ZSH_VERSION 等未定义变量，需临时关闭 set -u 避免退出
if [ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
    set +u; source "$HOME/.sdkman/bin/sdkman-init.sh" 2>/dev/null; set -u
fi
if [ -s "$HOME/.jabba/jabba.sh" ]; then
    set +u; source "$HOME/.jabba/jabba.sh" 2>/dev/null; set -u
fi

# ─── GitHub 代理检测 ─────────────────────────────────────────────────────────
detect_github_proxy

# ─── npm 镜像（国内加速）───────────────────────────────────────────────────
if [ -n "$GITHUB_PROXY" ]; then
    NPM_REGISTRY="https://registry.npmmirror.com"
    npm config set registry "$NPM_REGISTRY" 2>/dev/null || true
    info "npm 镜像已设置为 $NPM_REGISTRY"
fi

# ─── 获取最新版本号 ─────────────────────────────────────────────────────────
GITHUB_REPO="chengliang4810/solon-claw"
JAR_NAME="solonclaw.jar"
JAR_PATH="$INSTALL_DIR/$JAR_NAME"
IMAGE_NAME="ghcr.io/${GITHUB_REPO}:latest"

info "获取最新版本..."
LATEST_TAG=$(gh_api "/repos/$GITHUB_REPO/releases/latest" | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/') || true
[ -z "$LATEST_TAG" ] && LATEST_TAG="latest"
info "最新版本: $LATEST_TAG"

# ═══════════════════════════════════════════════════════════════════════════════
#  Docker 部署
# ═══════════════════════════════════════════════════════════════════════════════
deploy_docker() {
    # 检查 Docker
    if ! command -v docker &>/dev/null; then
        warn "未检测到 Docker，正在安装..."
        if [ "$PLATFORM" = "darwin" ]; then
            error "请先安装 Docker Desktop: https://docker.com/products/docker-desktop"
        fi
        curl -fsSL https://get.docker.com | sh
        sudo usermod -aG docker "$USER" 2>/dev/null || true
        ok "Docker 已安装"
    fi

    # 检查 docker compose
    if ! docker compose version &>/dev/null 2>&1; then
        error "需要 docker compose v2，请升级 Docker: https://docs.docker.com/compose/install/"
    fi
    ok "Docker $(docker --version | sed 's/.*version //' | sed 's/,.*//')"

    # 创建 docker-compose.yml
    COMPOSE_FILE="$INSTALL_DIR/docker-compose.yml"
    if [ -n "$GITHUB_PROXY" ]; then
        # 国内环境：使用 GitHub 代理拉取 ghcr.io 镜像
        PROXY_IMAGE="ghcr.io/chengliang4810/solonclaw"
        info "国内环境：将通过代理拉取镜像"
        info "如需使用自定义镜像地址，请设置 SOLONCLAW_IMAGE 环境变量"
    fi
    IMAGE="${SOLONCLAW_IMAGE:-$IMAGE_NAME}"

    cat > "$COMPOSE_FILE" << COMPOSE_EOF
services:
  solonclaw:
    image: ${IMAGE}
    container_name: solonclaw
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      SOLONCLAW_OFFICIAL_DOCKER_IMAGE: "1"
    security_opt:
      - no-new-privileges:true
    cap_drop:
      - ALL
    read_only: true
    tmpfs:
      - /tmp
    volumes:
      - ${WORKSPACE_DIR}:/app/workspace
COMPOSE_EOF
    ok "docker-compose.yml 已创建: $COMPOSE_FILE"

    # 创建默认配置
    create_config

    # 拉取镜像并启动
    info "拉取镜像: $IMAGE"
    cd "$INSTALL_DIR"
    docker compose pull 2>&1 || {
        warn "镜像拉取失败，尝试通过代理..."
        if [ -n "$GITHUB_PROXY" ]; then
            error "请手动拉取镜像后重试:
  docker pull $IMAGE
  或设置 SOLONCLAW_IMAGE 指向可用镜像源"
        fi
        error "镜像拉取失败，请检查网络或设置 SOLONCLAW_IMAGE 环境变量"
    }

    info "启动服务..."
    docker compose up -d
    sleep 3

    if docker compose ps | grep -q "Up"; then
        ok "solonclaw 容器已启动"
    else
        warn "容器可能未正常启动，请检查: docker compose -f $COMPOSE_FILE logs"
    fi

    echo ""
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}  solonclaw Docker 部署完成！${NC}"
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo ""
    echo "  安装目录:     $INSTALL_DIR"
    echo "  工作区:       $WORKSPACE_DIR"
    echo "  配置文件:     $WORKSPACE_DIR/config.yml"
    echo "  Compose 文件: $COMPOSE_FILE"
    echo ""
    echo "  常用命令:"
    echo "    启动:   docker compose -f $COMPOSE_FILE up -d"
    echo "    停止:   docker compose -f $COMPOSE_FILE down"
    echo "    日志:   docker compose -f $COMPOSE_FILE logs -f"
    echo "    重启:   docker compose -f $COMPOSE_FILE restart"
    echo ""
    echo "  TUI 交互:    solonclaw"
    echo ""
    echo "  模型配置（二选一）:"
    echo "    1. TUI 命令:    启动 solonclaw 后输入 /setup model"
    echo "    2. Web 管理:    打开 http://127.0.0.1:8080 登录后在「模型」页面配置"
    echo ""
}

# ═══════════════════════════════════════════════════════════════════════════════
#  原生安装（Java + 系统服务）
# ═══════════════════════════════════════════════════════════════════════════════
deploy_native() {
    # ─── 检查 Java ────────────────────────────────────────────────────────
    check_java() {
        if command -v java &>/dev/null; then
            JAVA_VER=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)(\.[0-9]+)*.*/\1/')
            if [ "${JAVA_VER:-0}" -ge 8 ]; then
                ok "Java $JAVA_VER 已安装"
                return 0
            fi
        fi
        return 1
    }

    if ! check_java; then
        warn "未检测到 Java 8+，正在尝试安装..."
        if [ "$PLATFORM" = "darwin" ]; then
            if command -v brew &>/dev/null; then
                brew install openjdk
                export PATH="$(brew --prefix openjdk)/bin:$PATH"
            else
                error "请先安装 Homebrew（https://brew.sh），然后运行: brew install openjdk"
            fi
        elif [ "$PLATFORM" = "linux" ]; then
            if command -v apt-get &>/dev/null; then
                sudo apt-get update -qq && sudo apt-get install -y -qq default-jre-headless
            elif command -v yum &>/dev/null; then
                sudo yum install -y java-1.8.0-openjdk
            elif command -v dnf &>/dev/null; then
                sudo dnf install -y java-1.8.0-openjdk
            else
                error "无法自动安装 Java，请手动安装 JDK 8+（https://adoptium.net）"
            fi
            # apt/yum 安装后刷新 shell 命令哈希表
            hash -r 2>/dev/null || true
            # 如果 command -v 仍找不到，尝试常见路径
            if ! command -v java &>/dev/null; then
                for jbin in /usr/bin/java /usr/lib/jvm/default-java/bin/java /etc/alternatives/java; do
                    if [ -x "$jbin" ]; then
                        export PATH="$(dirname "$jbin"):$PATH"
                        break
                    fi
                done
            fi
        fi
        check_java || error "Java 安装后仍未检测到 java 命令，请手动安装 JDK 8+"
    fi

    # ─── 检查 Node.js（TUI 需要）────────────────────────────────────────
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
        NODE_INSTALLED=0
        if [ "$PLATFORM" = "darwin" ]; then
            if command -v brew &>/dev/null; then
                brew install node && NODE_INSTALLED=1
            else
                error "请先安装 Homebrew（https://brew.sh），然后运行: brew install node"
            fi
        elif [ "$PLATFORM" = "linux" ]; then
            # 方式 1: NodeSource（海外优先）
            if [ -z "$GITHUB_PROXY" ]; then
                info "通过 NodeSource 安装 Node.js 20..."
                curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash - 2>/dev/null && \
                (command -v apt-get &>/dev/null && sudo apt-get install -y -qq nodejs || \
                 command -v yum &>/dev/null && sudo yum install -y nodejs || \
                 command -v dnf &>/dev/null && sudo dnf install -y nodejs) && NODE_INSTALLED=1 || true
            fi
            # 方式 2: 二进制包直接安装（国内环境，从 npmmirror 下载）
            if [ "$NODE_INSTALLED" -eq 0 ]; then
                info "通过二进制包安装 Node.js 20..."
                NODE_VERSION="v20.19.2"
                NODE_ARCH="x64"
                [ "$ARCH" = "arm64" ] && NODE_ARCH="arm64"
                NODE_DISTRO="linux-${NODE_ARCH}"
                NODE_TAR="node-${NODE_VERSION}-${NODE_DISTRO}.tar.xz"
                # 优先用 npmmirror，回退官方
                NODE_MIRRORS=(
                    "https://npmmirror.com/mirrors/node/${NODE_VERSION}/${NODE_TAR}"
                    "https://nodejs.org/dist/${NODE_VERSION}/${NODE_TAR}"
                )
                for url in "${NODE_MIRRORS[@]}"; do
                    info "下载: $url"
                    if curl -fSL --connect-timeout 10 --max-time 120 -o "/tmp/$NODE_TAR" "$url" 2>/dev/null; then
                        sudo tar -xJf "/tmp/$NODE_TAR" -C /usr/local --strip-components=1
                        rm -f "/tmp/$NODE_TAR"
                        NODE_INSTALLED=1
                        break
                    fi
                done
            fi
            # 方式 3: apt 默认源（版本可能较低，但能用）
            if [ "$NODE_INSTALLED" -eq 0 ] && command -v apt-get &>/dev/null; then
                info "通过 apt 默认源安装 Node.js..."
                sudo apt-get update -qq && sudo apt-get install -y -qq nodejs npm && NODE_INSTALLED=1 || true
            fi
        fi
        check_node || error "Node.js 安装失败，请手动安装 Node.js 20+（https://nodejs.org 或 https://npmmirror.com）"
    fi

    command -v npm &>/dev/null || error "npm 未找到，请重新安装 Node.js"

    # ─── 安装 TUI ───────────────────────────────────────────────────────
    info "安装 solonclaw TUI..."
    npm install -g solonclaw --silent 2>/dev/null || npm install -g solonclaw
    ok "TUI 已安装: $(command -v solonclaw)"

    # ─── 下载后端 jar ───────────────────────────────────────────────────
    if [ "$LATEST_TAG" = "latest" ]; then
        DOWNLOAD_URL="https://github.com/$GITHUB_REPO/releases/latest/download/$JAR_NAME"
    else
        DOWNLOAD_URL="https://github.com/$GITHUB_REPO/releases/download/$LATEST_TAG/$JAR_NAME"
    fi
    info "下载 jar: $(gh_url "$DOWNLOAD_URL")"
    curl -fSL --progress-bar -o "$JAR_PATH" "$(gh_url "$DOWNLOAD_URL")"
    ok "jar 已下载: $JAR_PATH ($(du -h "$JAR_PATH" | cut -f1))"

    # ─── 创建默认配置 ───────────────────────────────────────────────────
    create_config

    # ─── 注册系统服务 ───────────────────────────────────────────────────
    JAVA_PATH="$(command -v java)"

    if [ "$PLATFORM" = "linux" ] && command -v systemctl &>/dev/null; then
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
        sudo systemctl start solonclaw
        ok "systemd 服务已注册、启用并启动"
    elif [ "$PLATFORM" = "darwin" ]; then
        PLIST_PATH="$HOME/Library/LaunchAgents/com.solonclaw.agent.plist"
        mkdir -p "$(dirname "$PLIST_PATH")"
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
        mkdir -p "$INSTALL_DIR/logs"
        launchctl load "$PLIST_PATH" 2>/dev/null || true
        launchctl start com.solonclaw.agent 2>/dev/null || true
        ok "launchd 服务已注册并启动"
    fi

    # ─── 写入环境变量 ───────────────────────────────────────────────────
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
        fi
    fi

    # ─── 验证服务 ───────────────────────────────────────────────────────
    info "等待服务启动..."
    sleep 3
    if curl -sf http://127.0.0.1:8080/health >/dev/null 2>&1; then
        ok "后端服务运行正常"
    else
        warn "后端服务可能还在启动中，请稍后检查: curl http://127.0.0.1:8080/health"
    fi

    echo ""
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}  solonclaw 原生安装完成！${NC}"
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo ""
    echo "  安装目录:   $INSTALL_DIR"
    echo "  后端 jar:   $JAR_PATH"
    echo "  工作区:     $WORKSPACE_DIR"
    echo "  配置文件:   $WORKSPACE_DIR/config.yml"
    echo ""
    echo "  服务管理:"
    if [ "$PLATFORM" = "linux" ]; then
        echo "    状态: sudo systemctl status solonclaw"
        echo "    停止: sudo systemctl stop solonclaw"
        echo "    启动: sudo systemctl start solonclaw"
        echo "    日志: sudo journalctl -u solonclaw -f"
    else
        echo "    状态: launchctl list com.solonclaw.agent"
        echo "    停止: launchctl stop com.solonclaw.agent"
        echo "    启动: launchctl start com.solonclaw.agent"
    fi
    echo ""
    echo "  TUI 交互:   solonclaw"
    echo "  远程连接:   SOLONCLAW_SERVER_URL=http://IP:8080 solonclaw"
    echo ""
    echo "  模型配置（二选一）:"
    echo "    1. TUI 命令:   启动 solonclaw 后输入 /setup model"
    echo "    2. Web 管理:   打开 http://127.0.0.1:8080 登录后在「模型」页面配置"
    echo ""
}

# ═══════════════════════════════════════════════════════════════════════════════
#  通用：创建默认配置
# ═══════════════════════════════════════════════════════════════════════════════
create_config() {
    CONFIG_FILE="$WORKSPACE_DIR/config.yml"
    if [ ! -f "$CONFIG_FILE" ]; then
        cat > "$CONFIG_FILE" << EOF
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
    accessToken: "${DASHBOARD_TOKEN}"
EOF
        ok "默认配置已创建: $CONFIG_FILE"
    else
        ok "配置文件已存在: $CONFIG_FILE"
    fi
}

# ═══════════════════════════════════════════════════════════════════════════════
#  主流程
# ═══════════════════════════════════════════════════════════════════════════════
case "$DEPLOY_CHOICE" in
    2) deploy_docker ;;
    *) deploy_native ;;
esac
