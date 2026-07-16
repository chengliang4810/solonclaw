FROM node:24-bookworm-slim AS frontend

# 前端阶段只负责编译 Dashboard 静态资源，避免最终镜像携带前端构建缓存。
WORKDIR /workspace/web

COPY web/package*.json /workspace/web/
RUN npm ci

COPY web /workspace/web
RUN npm run build

FROM node:24-bookworm-slim AS terminal-ui

WORKDIR /workspace/terminal-ui

COPY terminal-ui/package*.json /workspace/terminal-ui/
COPY terminal-ui/packages/solonclaw-ink/package.json /workspace/terminal-ui/packages/solonclaw-ink/package.json
RUN npm ci

COPY terminal-ui /workspace/terminal-ui
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-17 AS builder

# 后端阶段复用 Maven 镜像打包 fat jar，并接收上一阶段产出的 Dashboard dist。
WORKDIR /workspace

COPY pom.xml /workspace/pom.xml
COPY config.example.yml /workspace/config.example.yml
COPY src /workspace/src
COPY --from=frontend /workspace/web/dist /workspace/web/dist

RUN mvn -DskipTests -Dskip.web.build=true package \
    && cp "$(find target -maxdepth 1 -type f -name 'solonclaw-*.jar' ! -name 'original-*' | head -n 1)" /tmp/solonclaw.jar

FROM eclipse-temurin:17-jre

# 运行阶段保留常用诊断工具，服务仍按单实例 java -jar 方式启动。
WORKDIR /app

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=Asia/Shanghai \
    PYTHONIOENCODING=UTF-8 \
    PIP_DISABLE_PIP_VERSION_CHECK=1
# 标记官方 Docker 运行环境，供启动保护和诊断逻辑识别。
ENV SOLONCLAW_OFFICIAL_DOCKER_IMAGE=1

# 安装国内渠道接入、调试、文件处理、字体渲染和脚本工具所需的最小运行依赖。
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        git \
        curl \
        wget \
        jq \
        less \
        nano \
        vim-tiny \
        procps \
        psmisc \
        net-tools \
        iputils-ping \
        dnsutils \
        file \
        tree \
        unzip \
        zip \
        ca-certificates \
        tzdata \
        locales \
        openssh-client \
        tini \
        fontconfig \
        python3 \
        python3-pip \
        python3-venv \
        fonts-arphic-gbsn00lp \
        fonts-noto-cjk \
    && locale-gen C.UTF-8 \
    && fc-cache -f \
    && update-ca-certificates \
    && ln -sf /usr/bin/python3 /usr/local/bin/python \
    && ln -sf /usr/bin/pip3 /usr/local/bin/pip \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# 官方镜像使用固定 UID 的非 root 用户运行，避免 Agent 工具获得容器 root 权限。
RUN groupadd --system --gid 10001 solonclaw \
    && useradd --system --uid 10001 --gid solonclaw --home-dir /app --shell /usr/sbin/nologin solonclaw

COPY --from=builder /tmp/solonclaw.jar /app/solonclaw.jar
COPY --from=terminal-ui /usr/local /usr/local
COPY --from=terminal-ui /workspace/terminal-ui/dist/entry.js /app/terminal-ui/entry.js
COPY docker/entrypoint.sh /app/docker-entrypoint.sh
COPY docker/solonclaw /usr/local/bin/solonclaw

# workspace 是唯一持久化目录；入口脚本只确保目录存在，不改写用户配置。
RUN mkdir -p /app/workspace \
    && sed -i 's/\r$//' /app/docker-entrypoint.sh \
    && chmod 755 /app/docker-entrypoint.sh /usr/local/bin/solonclaw /app/terminal-ui/entry.js \
    && chown -R solonclaw:solonclaw /app \
    && chmod -R u+rwX,go-rwx /app

EXPOSE 8080

# tini 负责转发信号和回收子进程，入口脚本再启动 solonclaw。
USER solonclaw
ENTRYPOINT ["/usr/bin/tini", "-g", "--", "/app/docker-entrypoint.sh"]
CMD ["--solonclaw.workspace=/app/workspace"]
