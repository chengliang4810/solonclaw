# solonclaw 一键安装脚本（Windows PowerShell）
# 用法: irm https://raw.githubusercontent.com/chengliang4810/solon-claw/main/scripts/install.ps1 | iex
# 或者: powershell -ExecutionPolicy Bypass -File scripts/install.ps1
$ErrorActionPreference = "Stop"

function Write-Info  { Write-Host "[INFO] $args" -ForegroundColor Cyan }
function Write-Ok    { Write-Host "[OK]   $args" -ForegroundColor Green }
function Write-Warn  { Write-Host "[WARN] $args" -ForegroundColor Yellow }
function Write-Err   { Write-Host "[ERROR] $args" -ForegroundColor Red; exit 1 }

# ─── 安装目录 ────────────────────────────────────────────────────────────────
$DefaultDir = if ($env:SOLONCLAW_HOME) { $env:SOLONCLAW_HOME } else { Join-Path $env:USERPROFILE ".solonclaw" }
$CustomDir = Read-Host "  安装目录（默认 $DefaultDir）"
$InstallDir = if ([string]::IsNullOrEmpty($CustomDir)) { $DefaultDir } else { $CustomDir }
$WorkspaceDir = Join-Path $InstallDir "workspace"
New-Item -ItemType Directory -Force -Path $InstallDir, $WorkspaceDir | Out-Null
Write-Info "安装目录: $InstallDir"

# ─── Dashboard 访问令牌 ──────────────────────────────────────────────────────
Write-Host ""
Write-Host "  设置 Dashboard 登录密钥（留空则无法登录 Web 管理页面）"
$DashboardToken = Read-Host "  请输入访问令牌"
if ([string]::IsNullOrEmpty($DashboardToken)) {
    Write-Warn "未设置访问令牌，Dashboard 将无法登录（后续可通过 config.yml 配置）"
}

# ─── 选择部署方式 ────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "  选择部署方式:"
Write-Host "    1) 原生安装（Java + Windows 服务）"
Write-Host "    2) Docker 部署（docker compose）"
Write-Host ""
$DeployChoice = Read-Host "  请输入 [1/2]（默认 1）"
if ([string]::IsNullOrEmpty($DeployChoice)) { $DeployChoice = "1" }

# ─── 获取最新版本 ────────────────────────────────────────────────────────────
$Repo = "chengliang4810/solon-claw"
$JarName = "solonclaw.jar"
$ChecksumName = "SHA256SUMS"
$JarPath = Join-Path $InstallDir $JarName
$ImageName = "ghcr.io/${Repo}:latest"

Write-Info "获取最新版本..."
try {
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -UseBasicParsing
    $tag = if ([string]::IsNullOrWhiteSpace([string]$release.tag_name)) { "latest" } else { [string]$release.tag_name }
} catch { $tag = "latest" }
Write-Info "最新版本: $tag"

# ─── 创建默认配置 ───────────────────────────────────────────────────────────
function New-DefaultConfig {
    $ConfigFile = Join-Path $WorkspaceDir "config.yml"
    if (-not (Test-Path $ConfigFile)) {
        $tokenValue = if ([string]::IsNullOrEmpty($DashboardToken)) { "" } else { $DashboardToken }
        @"
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
    accessToken: "$tokenValue"
"@ | Out-File -Encoding utf8 $ConfigFile
        Write-Ok "默认配置已创建: $ConfigFile"
    } else {
        Write-Ok "配置文件已存在: $ConfigFile"
    }
}

# ═════════════════════════════════════════════════════════════════════════════
#  Docker 部署
# ═════════════════════════════════════════════════════════════════════════════
function Install-Docker {
    # 检查 Docker
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Write-Err "未检测到 Docker，请先安装 Docker Desktop: https://docker.com/products/docker-desktop"
    }
    Write-Ok "Docker $(docker --version)"

    $Image = if ($env:SOLONCLAW_IMAGE) { $env:SOLONCLAW_IMAGE } else { $ImageName }
    $ComposeFile = Join-Path $InstallDir "docker-compose.yml"

    @"
services:
  solonclaw:
    image: ${Image}
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
      - ${WorkspaceDir}:/app/workspace
"@ | Out-File -Encoding utf8 $ComposeFile
    Write-Ok "docker-compose.yml 已创建: $ComposeFile"

    New-DefaultConfig

    Write-Info "拉取镜像: $Image"
    Push-Location $InstallDir
    docker compose pull
    if ($LASTEXITCODE -ne 0) { Write-Err "镜像拉取失败，请检查网络或设置 SOLONCLAW_IMAGE" }

    Write-Info "启动服务..."
    docker compose up -d
    Pop-Location
    Write-Ok "solonclaw 容器已启动"

    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Green
    Write-Host "  solonclaw Docker 部署完成！" -ForegroundColor Green
    Write-Host "============================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "  Compose 文件: $ComposeFile"
    Write-Host "  工作区:       $WorkspaceDir"
    Write-Host ""
    Write-Host "  常用命令:"
    Write-Host "    启动: docker compose -f $ComposeFile up -d"
    Write-Host "    停止: docker compose -f $ComposeFile down"
    Write-Host "    日志: docker compose -f $ComposeFile logs -f"
    Write-Host ""
    Write-Host "  TUI 交互:   solonclaw"
    Write-Host ""
    Write-Host "  模型配置（二选一）:"
    Write-Host "    1. TUI 命令:   启动 solonclaw 后输入 /setup model"
    Write-Host "    2. Web 管理:   打开 http://127.0.0.1:8080 登录后在「模型」页面配置"
    Write-Host ""
}

# ═════════════════════════════════════════════════════════════════════════════
#  原生安装（Java + Windows 服务）
# ═════════════════════════════════════════════════════════════════════════════
function Install-Native {
    # ─── 检查 Java ───────────────────────────────────────────────────────
    $javaOk = $false
    try {
        $ver = & java -version 2>&1 | Select-Object -First 1
        if ($ver -match '"(\d+)') {
            $major = [int]$Matches[1]
            if ($major -ge 8) { Write-Ok "Java $major 已安装"; $javaOk = $true }
        }
    } catch {}

    if (-not $javaOk) {
        Write-Warn "未检测到 Java 8+"
        $winget = Read-Host "是否通过 winget 自动安装？[y/N]"
        if ($winget -match '^[Yy]$') {
            winget install EclipseAdoptium.Temurin.17.JRE --accept-source-agreements --accept-package-agreements
            $env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path", "User")
        }
        if (-not $javaOk) {
            try {
                $ver = & java -version 2>&1 | Select-Object -First 1
                if ($ver -match '"(\d+)') { if ([int]$Matches[1] -ge 8) { $javaOk = $true } }
            } catch {}
        }
        if (-not $javaOk) { Write-Err "Java 安装失败，请手动安装 JDK 8+" }
    }

    # ─── 检查 Node.js ───────────────────────────────────────────────────
    $nodeOk = $false
    try {
        $ver = & node -v 2>$null
        if ($ver -match 'v(\d+)') { if ([int]$Matches[1] -ge 20) { Write-Ok "Node.js $([int]$Matches[1]) 已安装"; $nodeOk = $true } }
    } catch {}

    if (-not $nodeOk) {
        Write-Warn "未检测到 Node.js 20+"
        $winget = Read-Host "是否通过 winget 自动安装？[y/N]"
        if ($winget -match '^[Yy]$') {
            winget install OpenJS.NodeJS.LTS --accept-source-agreements --accept-package-agreements
            $env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path", "User")
        }
        try {
            $ver = & node -v 2>$null
            if ($ver -match 'v(\d+)') { if ([int]$Matches[1] -ge 20) { $nodeOk = $true } }
        } catch {}
        if (-not $nodeOk) { Write-Err "Node.js 安装失败，请手动安装 Node.js 20+" }
    }

    # ─── 安装 TUI ───────────────────────────────────────────────────────
    Write-Info "安装 solonclaw TUI..."
    & npm install -g solonclaw 2>$null
    if ($LASTEXITCODE -ne 0) { & npm install -g solonclaw }
    $tuiPath = (Get-Command solonclaw -ErrorAction SilentlyContinue).Source
    if ($tuiPath) { Write-Ok "TUI 已安装: $tuiPath" } else { Write-Err "TUI 安装失败" }

    # ─── 下载后端 jar ───────────────────────────────────────────────────
    if ($tag -eq "latest") {
        $downloadUrl = "https://github.com/$Repo/releases/latest/download/$JarName"
        $checksumUrl = "https://github.com/$Repo/releases/latest/download/$ChecksumName"
    } else {
        $downloadUrl = "https://github.com/$Repo/releases/download/$tag/$JarName"
        $checksumUrl = "https://github.com/$Repo/releases/download/$tag/$ChecksumName"
    }
    Write-Info "下载 jar: $downloadUrl"
    $tempJar = Join-Path $InstallDir ".${JarName}.$([Guid]::NewGuid().ToString('N')).download"
    $checksumPath = Join-Path ([System.IO.Path]::GetTempPath()) "solonclaw-SHA256SUMS-$([Guid]::NewGuid().ToString('N'))"
    try {
        Invoke-WebRequest -Uri $downloadUrl -OutFile $tempJar -UseBasicParsing -ErrorAction Stop
        Write-Info "校验 jar SHA-256..."
        Invoke-WebRequest -Uri $checksumUrl -OutFile $checksumPath -UseBasicParsing -ErrorAction Stop
        if ((Get-Item -LiteralPath $checksumPath).Length -gt 1MB) {
            throw "SHA256SUMS 文件超过 1MB 限制"
        }
        $checksumEntries = @(
            Get-Content -LiteralPath $checksumPath | Where-Object {
                $_ -match '^(?<hash>[0-9A-Fa-f]{64})\s+\*?solonclaw\.jar\s*$'
            }
        )
        if ($checksumEntries.Count -ne 1) {
            throw "SHA256SUMS 中缺少或重复 $JarName 的校验记录"
        }
        $expectedHash = (($checksumEntries[0] -split '\s+')[0]).ToLowerInvariant()
        $actualHash = (Get-FileHash -LiteralPath $tempJar -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($expectedHash -ne $actualHash) {
            throw "jar 的 SHA-256 校验失败"
        }
        Move-Item -LiteralPath $tempJar -Destination $JarPath -Force
    } catch {
        Remove-Item -LiteralPath $tempJar -Force -ErrorAction SilentlyContinue
        Write-Err "后端 jar 下载或校验失败：$($_.Exception.Message)"
    } finally {
        Remove-Item -LiteralPath $checksumPath -Force -ErrorAction SilentlyContinue
    }
    $size = [math]::Round((Get-Item $JarPath).Length / 1MB, 1)
    Write-Ok "jar 已下载: $JarPath (${size}MB)"

    # ─── 创建默认配置 ───────────────────────────────────────────────────
    New-DefaultConfig

    # ─── 设置用户环境变量 ───────────────────────────────────────────────
    $envVars = @{
        "SOLONCLAW_HOME"       = $InstallDir
        "SOLONCLAW_JAR"        = $JarPath
        "SOLONCLAW_WORKSPACE"  = $WorkspaceDir
        "SOLONCLAW_SERVER_URL" = "http://127.0.0.1:8080"
    }
    foreach ($kv in $envVars.GetEnumerator()) {
        [System.Environment]::SetEnvironmentVariable($kv.Key, $kv.Value, "User")
        Write-Ok "环境变量 $($kv.Key)=$($kv.Value)"
    }

    # ─── 注册 Windows 服务 ──────────────────────────────────────────────
    $javaPath = (Get-Command java).Source
    $nssm = Join-Path $InstallDir "nssm.exe"
    if (-not (Test-Path $nssm)) {
        Write-Info "下载 NSSM 服务管理工具..."
        $nssmUrl = "https://nssm.cc/release/nssm-2.24.zip"
        $nssmZip = Join-Path $env:TEMP "nssm.zip"
        $nssmExtract = Join-Path $env:TEMP "nssm"
        Invoke-WebRequest -Uri $nssmUrl -OutFile $nssmZip -UseBasicParsing
        Expand-Archive -Path $nssmZip -DestinationPath $nssmExtract -Force
        Copy-Item "$nssmExtract\nssm-2.24\win64\nssm.exe" $nssm -Force
    }
    & $nssm install solonclaw $javaPath "-Dfile.encoding=UTF-8" "-Dsolonclaw.workspace=$WorkspaceDir" "-jar" $JarPath
    & $nssm set solonclaw AppDirectory $InstallDir
    & $nssm set solonclaw DisplayName "solonclaw agent service"
    & $nssm set solonclaw Description "solonclaw AI agent service"
    & $nssm set solonclaw Start SERVICE_AUTO_START
    & $nssm start solonclaw
    Write-Ok "Windows 服务已注册并启动"

    # ─── 验证 ───────────────────────────────────────────────────────────
    Start-Sleep -Seconds 3
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:8080/health" -UseBasicParsing -TimeoutSec 5
        if ($health.ok) { Write-Ok "后端服务运行正常" }
    } catch {
        Write-Warn "后端服务可能还在启动中，请稍后检查"
    }

    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Green
    Write-Host "  solonclaw 原生安装完成！" -ForegroundColor Green
    Write-Host "============================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "  安装目录:   $InstallDir"
    Write-Host "  后端 jar:   $JarPath"
    Write-Host "  工作区:     $WorkspaceDir"
    Write-Host "  配置文件:   $WorkspaceDir\config.yml"
    Write-Host ""
    Write-Host "  服务管理:"
    Write-Host "    状态: nssm status solonclaw"
    Write-Host "    停止: nssm stop solonclaw"
    Write-Host "    启动: nssm start solonclaw"
    Write-Host ""
    Write-Host "  TUI 交互:   solonclaw"
    Write-Host "  远程连接:   `$env:SOLONCLAW_SERVER_URL='http://IP:8080'; solonclaw"
    Write-Host ""
    Write-Host "  模型配置（二选一）:"
    Write-Host "    1. TUI 命令:   启动 solonclaw 后输入 /setup model"
    Write-Host "    2. Web 管理:   打开 http://127.0.0.1:8080 登录后在「模型」页面配置"
    Write-Host ""
}

# ═════════════════════════════════════════════════════════════════════════════
#  主流程
# ═════════════════════════════════════════════════════════════════════════════
switch ($DeployChoice) {
    "2" { Install-Docker }
    default { Install-Native }
}
