# Sube al TV las imagenes destacadas, .jpg, .png o .webp de 1920x1080 o similar:
#   <paquete de la app>.jpg       una imagen fija para esa app
#   <paquete de la app>\*.jpg     varias, que rotan; la carpeta del TV se vacia antes, asi refleja la tuya
# Una app sin imagen propia muestra las de Play Store o su logo. El Home las toma la proxima vez que vuelvas a el.
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

function Send-Picture($file, $target) {
    $tmp = "/data/local/tmp/$($file.Name)"
    & $py -I (Join-Path $repo 'tools\b64push.py') $file.FullName $tmp
    if ($LASTEXITCODE -ne 0) { throw "no se pudo subir $($file.Name)" }
    & $py -I (Join-Path $repo 'tools\tv.py') "cp $tmp $target/$($file.Name) && rm -f $tmp && ls -la $target/$($file.Name)"
}

$images = { $_.Extension -match '^\.(jpg|jpeg|png|webp)$' }
& $py -I (Join-Path $repo 'tools\tv.py') "mkdir -p $dir"
foreach ($file in Get-ChildItem $Folder -File | Where-Object $images) { Send-Picture $file $dir }
foreach ($app in Get-ChildItem $Folder -Directory) {
    # Package names are letters, digits, dots and underscores; anything else is not an app's folder.
    if ($app.Name -notmatch '^[A-Za-z0-9_.]+$') { continue }
    $target = "$dir/$($app.Name)"
    & $py -I (Join-Path $repo 'tools\tv.py') "mkdir -p $target && rm -f $target/*"
    foreach ($file in Get-ChildItem $app.FullName -File | Where-Object $images) { Send-Picture $file $target }
}
