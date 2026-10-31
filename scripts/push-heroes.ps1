# Sube al TV las imagenes destacadas: <paquete de la app>.jpg, .png o .webp, de 1920x1080 o similar.
# Una app sin imagen muestra su logo. El Home las toma la proxima vez que vuelvas a el.
# Uso:
#   powershell -ExecutionPolicy Bypass -File scripts\push-heroes.ps1 -TvHost <ip del tv> [-Folder <carpeta>]
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$Folder
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if (-not $Folder) { $Folder = Join-Path $repo 'hero' }
$py = Join-Path $repo 'tools\venv\Scripts\python.exe'
$env:TV_HOST = $TvHost
$dir = '/sdcard/Android/data/com.gathin.tvhome/files/hero'
& $py -I (Join-Path $repo 'tools\tv.py') "mkdir -p $dir"
foreach ($file in Get-ChildItem $Folder -File | Where-Object { $_.Extension -match '^\.(jpg|png|webp)$' }) {
    $tmp = "/data/local/tmp/$($file.Name)"
    & $py -I (Join-Path $repo 'tools\b64push.py') $file.FullName $tmp
    if ($LASTEXITCODE -ne 0) { throw "no se pudo subir $($file.Name)" }
    & $py -I (Join-Path $repo 'tools\tv.py') "cp $tmp $dir/$($file.Name) && rm -f $tmp && ls -la $dir/$($file.Name)"
}
