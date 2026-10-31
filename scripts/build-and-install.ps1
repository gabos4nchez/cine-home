# Compila el Home en Docker (sin JDK ni Android SDK en este PC), lo instala en el TV y le aplica un tema.
# Hay que tener Docker Desktop encendido y, para instalar, la depuracion USB activa en el TV.
# Uso:
#   powershell -ExecutionPolicy Bypass -File scripts\build-and-install.ps1 -TvHost <ip del tv> [-Theme <carpeta>]
#   powershell -ExecutionPolicy Bypass -File scripts\build-and-install.ps1 -SoloCompilar
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$Theme,
    [switch]$SoloCompilar
)
$repo = Split-Path -Parent $PSScriptRoot
if (-not $Theme) { $Theme = Join-Path $repo 'theme' }
if (-not $SoloCompilar -and -not $TvHost) { throw 'pasa -TvHost <ip del tv>' }
Set-Location $repo

docker build -q -t cleanyourtv-android-build tools/docker
if ($LASTEXITCODE -ne 0) { throw 'fallo docker build' }
docker run --rm -v "${repo}:/repo" -v cleanyourtv-gradle:/gradle -w /repo/launcher `
    -e ANDROID_USER_HOME=/gradle/.android cleanyourtv-android-build `
    sh ./gradlew --no-daemon --console=plain assembleRelease
if ($LASTEXITCODE -ne 0) { throw 'fallo la compilacion' }
if ($SoloCompilar) { exit 0 }

# launcher\build-and-install.ps1 instala el APK ya compilado y mantiene el control de Home de cleanyourtv.
$env:SKIP_BUILD = '1'
& (Join-Path $repo 'launcher\build-and-install.ps1') -TvHost $TvHost
Remove-Item env:SKIP_BUILD
& (Join-Path $repo 'tools\theme.ps1') -TvHost $TvHost $Theme
