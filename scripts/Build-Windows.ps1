$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$project = Join-Path $root 'src\ClassroomAudio.Host\ClassroomAudio.Host.csproj'
$publish = Join-Path $root 'publish'
$dist = Join-Path $root 'dist'

if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    throw 'Le SDK .NET 10 est requis. Installez-le puis relancez ce script.'
}

New-Item -ItemType Directory -Force -Path $publish, $dist | Out-Null

dotnet publish $project `
    --configuration Release `
    --runtime win-x64 `
    --self-contained true `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:EnableCompressionInSingleFile=true `
    --output $publish
if ($LASTEXITCODE -ne 0) { throw "dotnet publish a échoué (code $LASTEXITCODE)." }

$compiler = @(
    'C:\Program Files (x86)\Inno Setup 6\ISCC.exe',
    'C:\Program Files\Inno Setup 6\ISCC.exe'
) | Where-Object { Test-Path $_ } | Select-Object -First 1

if ($compiler) {
    & $compiler (Join-Path $root 'installer\ClasseAudio.iss')
    if ($LASTEXITCODE -ne 0) { throw "Inno Setup a échoué (code $LASTEXITCODE)." }
    Write-Host "Installateur créé dans $dist"
} else {
    Write-Warning 'Inno Setup 6 non trouvé : l’application publiée est dans publish\, mais aucun installateur n’a été créé.'
}
