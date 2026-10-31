# Vuelve a habilitar todo lo deshabilitado con las listas de device\onn-4k y devuelve Home al launcher original.
# Uso:  powershell -ExecutionPolicy Bypass -File scripts\restore.ps1 -TvHost <ip del tv>
# Requiere que la depuracion USB/ADB este encendida en el TV.
param([string]$TvHost = $env:TV_HOST)
$ErrorActionPreference = 'Stop'
if (-not $TvHost) { throw 'pasa -TvHost <ip del tv>' }
$repo = Split-Path -Parent $PSScriptRoot
foreach ($list in Get-ChildItem (Join-Path $repo 'device\onn-4k\batch-*.txt') | Sort-Object Name -Descending) {
    & (Join-Path $repo 'tools\restore.ps1') -TvHost $TvHost -Stock 'com.google.android.apps.tv.launcherx' $list.FullName
}
$env:TV_HOST = $TvHost
& (Join-Path $repo 'tools\venv\Scripts\python.exe') -I (Join-Path $repo 'tools\tv.py') 'pm enable com.google.android.apps.tv.launcherx'
