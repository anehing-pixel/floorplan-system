<#
.SYNOPSIS
    库存融资核心系统 · PowerShell 日志查看工具

.DESCRIPTION
    把「怎么在 PowerShell 里看日志」这件事固化成一个脚本，避免每次现敲命令、
    也避免踩编码和文件空洞的坑。

    解决的三个实际踩过的坑：
      1. 编码：Windows 控制台默认代码页 936(GBK)，直接读 UTF-8 日志中文会乱码。
         本脚本强制按 UTF-8 读取，并自动切控制台代码页。
      2. 空洞：日志文件被外部程序截断后，写入方仍持旧句柄继续写，
         文件前半段会出现一大片 NUL 字节。本脚本自动剔除 NUL。
      3. 位置：日志目录随运行目录变化，本脚本自动定位。

.PARAMETER Tail
    只看末尾 N 行。默认 50。

.PARAMETER Follow
    持续实时输出新日志（Ctrl+C 退出）。等价于 tail -f。

.PARAMETER Filter
    按关键字/错误码/traceId 过滤，例如 DEALER_001、AUTH_002、某个 traceId。

.PARAMETER Level
    按级别过滤：ERROR / WARN / INFO / DEBUG（可组合，如 "ERROR,WARN"）。

.PARAMETER File
    指定日志文件。默认用 logs\floorplan.log。

.EXAMPLE
    .\view-log.ps1
    看末尾 50 行。

.EXAMPLE
    .\view-log.ps1 -Follow
    实时跟踪日志。

.EXAMPLE
    .\view-log.ps1 -Filter DEALER_001
    查所有 DEALER_001 相关日志。

.EXAMPLE
    .\view-log.ps1 -Filter "6acd3ad3-055a-4f5b-9fab-ae96b0fc2614"
    按 traceId 精确追踪一次请求。

.EXAMPLE
    .\view-log.ps1 -Level ERROR -Tail 100
    看最近 100 条 ERROR 以上级别的日志。

.NOTES
    退出码：0 正常；1 日志文件不存在。
#>
[CmdletBinding()]
param(
    [int]    $Tail   = 50,
    [switch] $Follow,
    [string] $Filter,
    [string] $Level,
    [string] $File
)

$ErrorActionPreference = 'Stop'

# ---- 1. 定位日志目录：脚本在 floorplan-system 下时用 logs\，否则回退到相对路径 ----
$base = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $File) {
    $candidate = Join-Path $base 'logs\floorplan.log'
    if (-not (Test-Path $candidate)) {
        $candidate = Join-Path (Get-Location) 'floorplan-system\logs\floorplan.log'
    }
    $File = $candidate
} else {
    # 用户传的相对路径一律相对脚本所在目录解析，
    # 否则 .\logs\console.log 会被理解成「当前工作目录」下的路径（常被解析到工作区根）。
    if (-not [System.IO.Path]::IsPathRooted($File)) {
        $File = Join-Path $base $File
    }
}
if (-not (Test-Path -LiteralPath $File)) {
    Write-Host "[ERROR] 找不到日志文件: $File" -ForegroundColor Red
    Write-Host "        请确认应用已启动，或用 -File 指定路径。" -ForegroundColor Yellow
    exit 1
}

# ---- 2. 修编码：控制台切 UTF-8(65001)，日志按 UTF-8 读 ----
# Windows PowerShell 5.1 的 Get-Content 默认按 ANSI(GBK) 读 UTF-8 文件，中文必乱码。
# PowerShell 7+ 默认已是 UTF-8，此处设为兼容处理。
try { chcp 65001 > $null 2>&1 } catch { }
if ($PSVersionTable.PSVersion.Major -lt 6) {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
}
$OutputEncoding = [System.Text.Encoding]::UTF8

# ---- 3. 读日志：剔除 NUL 空洞，兼容 UTF-8/GBK 两种历史编码 ----
function Read-LogLines {
    param([string]$Path)

    # 必须用 FileShare.ReadWrite 打开：日志文件正被 Java 进程持有写句柄，
    # 独占方式打开会抛 "being used by another process"。
    $fs = [System.IO.File]::Open($Path, 'Open', 'Read', 'ReadWrite')
    try {
        $bytes = New-Object byte[] $fs.Length
        $read = 0
        while ($read -lt $bytes.Length) {
            $n = $fs.Read($bytes, $read, $bytes.Length - $read)
            if ($n -le 0) { break }
            $read += $n
        }
    } finally {
        $fs.Close()
    }
    if ($read -eq 0) { return @() }

    # 先按 UTF-8 严格解码；失败则退回 GBK（早期 GBK 日志的兼容处理）
    $strictUtf8 = New-Object System.Text.UTF8Encoding($false, $true)
    try {
        $text = $strictUtf8.GetString($bytes, 0, $read)
    } catch {
        $text = [System.Text.Encoding]::GetEncoding(936).GetString($bytes, 0, $read)
    }

    # 剔除 NUL：日志被外部截断后，写入方仍持旧句柄继续写，
    # 文件前半段会残留一大片 NUL 字节，直接读会看到满屏空方块。
    $text = $text -replace "`0", ''

    return ($text -split "`r?`n") | Where-Object { $_.Trim() -ne '' }
}

$lines = Read-LogLines -Path $File

# ---- 4. 过滤 ----
$levels = @()
if ($Level) {
    $levels = $Level -split ',' | ForEach-Object { $_.Trim().ToUpper() } | Where-Object { $_ }
}
if ($levels.Count -gt 0) {
    $pat = '^\S+\s+\S+\s+(?:\[.*?\]\s*)?(' + (($levels | ForEach-Object { [regex]::Escape($_) }) -join '|') + ')\b'
    $lines = $lines | Where-Object { $_ -match $pat }
}
if ($Filter) {
    $lines = $lines | Where-Object { $_ -like "*$Filter*" }
}

# ---- 5. 输出 ----
Write-Host "日志文件: $File" -ForegroundColor DarkGray
Write-Host "有效行数: $($lines.Count)   显示: $(if ($Follow) { '实时跟随 (Ctrl+C 退出)' } else { "末尾 $Tail 行" })" -ForegroundColor DarkGray
Write-Host ('-' * 78)

if (-not $Follow) {
    $lines | Select-Object -Last $Tail | ForEach-Object { Write-Host $_ }
    exit 0
}

# 实时跟随：先打印已有的尾部，再持续等新内容
$lines | Select-Object -Last $Tail | ForEach-Object { Write-Host $_ }

$lastLen = (Get-Item -LiteralPath $File).Length
$pos = $lastLen
try {
    $fs = [System.IO.File]::Open($File, 'Open', 'Read', 'ReadWrite')
} catch {
    Write-Host "[WARN] 无法打开文件进行跟随: $($_.Exception.Message)" -ForegroundColor Yellow
    exit 0
}
$reader = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
try {
    while ($true) {
        $fs.Seek($pos, 'Begin') | Out-Null
        $buf = New-Object char[] 4096
        $n = $reader.Read($buf, 0, $buf.Length)
        if ($n -gt 0) {
            $chunk = (-join $buf[0..($n - 1)]) -replace "`0", ''
            $pos += $n
            foreach ($l in ($chunk -split "`r?`n")) {
                if ($l.Trim() -ne '') {
                    if (-not $Filter -or $l -like "*$Filter*") { Write-Host $l }
                }
            }
        } else {
            Start-Sleep -Milliseconds 300
        }
    }
} finally {
    $reader.Close(); $fs.Close()
}
