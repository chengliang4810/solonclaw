# solonclaw 一键安装脚本（Windows PowerShell）
# 用法: irm https://raw.githubusercontent.com/chengliang4810/solon-claw/main/scripts/install.ps1 | iex
# 或者: powershell -ExecutionPolicy Bypass -File scripts/install.ps1
$ErrorActionPreference = "Stop"

function Write-Info  { Write-Host "[INFO] $args" -ForegroundColor Cyan }
function Write-Ok    { Write-Host "[OK]   $args" -ForegroundColor Green }
function Write-Warn  { Write-Host "[WARN] $args" -ForegroundColor Yellow }
function Write-Err   { Write-Host "[ERROR] $args" -ForegroundColor Red; exit 1 }

# ─── 安装目录 ────────────────────────────────────────────────────────────────
$InstallDir = if ($env:SOLONCLAW_HOME) { $env:SOLONCLAW_HOME } else { Join-Path $env:USERPROFILE ".solonclaw" }
$WorkspaceDir = Join-Path $InstallDir "workspace"
New-Item -ItemType Directory -Force -Path $InstallDir, $WorkspaceDir | Out-Null
Write-Info "安装目录: $InstallDir"

# ─── 检查 Java ──────────────────────────────────────────────────────────────
function Test-Java {
    try {
        $ver = & java -version 2>&1 | Select-Object -First 1
        if ($ver -match '"(\d+)') {
            $major = [int]$Matches[1]
            if ($major -ge 17) {
                Write-Ok "Java $major 已安装"
                return $true
            }
        }
    } catch {}
    return $false
}

if (-not (Test-Java)) {
    Write-Warn "未检测到 Java 17+"
    Write-Host ""
    Write-Host "请手动安装 Java 17+:"
    Write-Host "  推荐: winget install EclipseAdoptium.Temurin.17.JRE"
    Write-Host "  或者: https://adoptium.net"
    Write-Host ""
    $winget = Read-Host "是否通过 winget 自动安装？[y/N]"
    if ($winget -match '^[Yy]$') {
        winget install EclipseAdoptium.Temurin.17.JRE --accept-source-agreements --accept-package-agreements
        $env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path", "User")
    }
    if (-not (Test-Java)) { Write-Err "Java 安装失败，请手动安装 JDK 17+" }
}

# ─── 检查 Node.js ────────────────────────────────────────────────────────────
function Test-Node {
    try {
        $ver = & node -v 2>$null
        if ($ver -match 'v(\d+)') {
            $major = [int]$Matches[1]
            if ($major -ge 20) {
                Write-Ok "Node.js $major 已安装"
                return $true
            }
        }
    } catch {}
    return $false
}

if (-not (Test-Node)) {
    Write-Warn "未检测到 Node.js 20+"
    Write-Host ""
    Write-Host "请手动安装 Node.js 20+:"
    Write-Host "  推荐: winget install OpenJS.NodeJS.LTS"
    Write-Host "  或者: https://nodejs.org"
    Write-Host ""
    $winget = Read-Host "是否通过 winget 自动安装？[y/N]"
    if ($winget -match '^[Yy]$') {
        winget install OpenJS.NodeJS.LTS --accept-source-agreements --accept-package-agreements
        $env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path", "User")
    }
    if (-not (Test-Node)) { Write-Err "Node.js 安装失败，请手动安装 Node.js 20+" }
}

# ─── 安装 TUI ───────────────────────────────────────────────────────────────
Write-Info "安装 solonclaw TUI..."
& npm install -g solonclaw 2>$null
if ($LASTEXITCODE -ne 0) { & npm install -g solonclaw }
$tuiPath = (Get-Command solonclaw -ErrorAction SilentlyContinue).Source
if ($tuiPath) { Write-Ok "TUI 已安装: $tuiPath" } else { Write-Err "TUI 安装失败" }

# ─── 下载后端 jar ───────────────────────────────────────────────────────────
$Repo = "chengliang4810/solon-claw"
$JarName = "solonclaw.jar"
$JarPath = Join-Path $InstallDir $JarName

Write-Info "获取最新版本..."
try {
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -UseBasicParsing
    $tag = $release.tag_name
} catch {
    $tag = "latest"
}
Write-Info "最新版本: $tag"

$downloadUrl = "https://github.com/$Repo/releases/download/$tag/$JarName"
Write-Info "下载 jar: $downloadUrl"
Invoke-WebRequest -Uri $downloadUrl -OutFile $JarPath -UseBasicParsing
$size = [math]::Round((Get-Item $JarPath).Length / 1MB, 1)
Write-Ok "jar 已下载: $JarPath (${size}MB)"

# ─── 创建默认配置 ───────────────────────────────────────────────────────────
$ConfigFile = Join-Path $WorkspaceDir "config.yml"
if (-not (Test-Path $ConfigFile)) {
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
    accessToken: ""
"@ | Out-File -Encoding utf8 $ConfigFile
    Write-Ok "默认配置已创建: $ConfigFile"
    Write-Warn "请编辑 $ConfigFile 填入你的 API Key"
} else {
    Write-Ok "配置文件已存在: $ConfigFile"
}

# ─── 创建后端启动脚本 ──────────────────────────────────────────────────────
$Launcher = Join-Path $InstallDir "solonclaw-server.bat"
@"
@echo off
java -Dfile.encoding=UTF-8 -Dsolonclaw.workspace="$WorkspaceDir" -jar "$JarPath" %*
"@ | Out-File -Encoding ascii $Launcher
Write-Ok "启动脚本: $Launcher"

# ─── 设置用户环境变量 ──────────────────────────────────────────────────────
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

# ─── 注册 Windows 服务（可选）─────────────────────────────────────────────
Write-Host ""
$registerService = Read-Host "是否注册为 Windows 服务（开机自启）？[y/N]"
if ($registerService -match '^[Yy]$') {
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
    Write-Ok "Windows 服务已注册"
    Write-Info "启动服务: nssm start solonclaw"
    Write-Info "查看状态: nssm status solonclaw"
}

# ─── 完成 ───────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "  solonclaw 安装完成！" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
Write-Host ""
Write-Host "  安装目录:   $InstallDir"
Write-Host "  后端 jar:   $JarPath"
Write-Host "  工作区:     $WorkspaceDir"
Write-Host "  配置文件:   $ConfigFile"
Write-Host ""
Write-Host "  启动后端:   $Launcher"
Write-Host "  启动 TUI:   solonclaw"
Write-Host "  远程连接:   `$env:SOLONCLAW_SERVER_URL='http://IP:8080'; solonclaw"
Write-Host ""
Write-Host "  首次使用请编辑配置文件填入 API Key:"
Write-Host "    notepad $ConfigFile"
Write-Host ""
