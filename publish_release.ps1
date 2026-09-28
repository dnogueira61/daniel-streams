param(
    [string]$TagName = "v1.5",
    [string]$ReleaseTitle = "v1.5 - Melhorias TV Remote, Rotação Tablet e Performance",
    [string]$Notes = "Novidades da v1.5:`n- Suporte total para comando de TV / Mi TV Stick (navegação D-Pad em grelha e player)`n- Auto-rotação no tablet sem bloqueios e layout adaptativo em grelha`n- Transição instantânea sem lag entre os separadores Portugal, Jogos em Direto e Todos`n- Overlay e zapping melhorados com teclas Up/Down/Left/Right/OK no comando"
)

$ErrorActionPreference = "Stop"

Write-Host "==> 1. A compilar APK de Release assinado..." -ForegroundColor Cyan
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
& .\gradlew.bat assembleRelease
if ($LASTEXITCODE -ne 0) {
    Write-Error "Falha ao compilar APK"
    exit 1
}

$apkPath = "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apkPath)) {
    Write-Error "Ficheiro APK não encontrado em $apkPath"
    exit 1
}

Write-Host "==> 2. A obter token do Git Credential Manager..." -ForegroundColor Cyan
$token = (@"
protocol=https
host=github.com
"@ | & "C:\Program Files\Git\mingw64\bin\git-credential-manager.exe" get | Where-Object { $_ -match "^password=" }) -replace "^password=",""

if (-not $token) {
    Write-Error "Não foi possível obter o token do GitHub através do Git Credential Manager."
    exit 1
}

Write-Host "==> 3. Git commit e push para o repositório..." -ForegroundColor Cyan
git add .
git commit -m "Release ${TagName}: TV remote support, tablet rotation and tab optimization"
git push origin main

Write-Host "==> 4. A criar Release no GitHub ($TagName)..." -ForegroundColor Cyan
$headers = @{
    "Authorization" = "Bearer $token"
    "Accept" = "application/vnd.github.v3+json"
    "User-Agent" = "DanielStreams-Publisher"
}

$body = @{
    tag_name = $TagName
    target_commitish = "main"
    name = $ReleaseTitle
    body = $Notes
    draft = $false
    prerelease = $false
} | ConvertTo-Json

# Check if release exists; if so, delete or reuse
try {
    $existing = Invoke-RestMethod -Uri "https://api.github.com/repos/dnogueira61/daniel-streams/releases/tags/$TagName" -Headers $headers -Method Get -ErrorAction SilentlyContinue
    if ($existing -and $existing.id) {
        Write-Host "Release $TagName já existia. A remover release antiga..." -ForegroundColor Yellow
        Invoke-RestMethod -Uri "https://api.github.com/repos/dnogueira61/daniel-streams/releases/$($existing.id)" -Headers $headers -Method Delete
    }
} catch {
    # Ignore 404
}

$release = Invoke-RestMethod -Uri "https://api.github.com/repos/dnogueira61/daniel-streams/releases" -Headers $headers -Method Post -Body $body -ContentType "application/json"
$releaseId = $release.id
Write-Host "Release criada com ID: $releaseId" -ForegroundColor Green

Write-Host "==> 5. A carregar ficheiro app-release.apk para os assets do GitHub..." -ForegroundColor Cyan
$uploadUrl = "https://uploads.github.com/repos/dnogueira61/daniel-streams/releases/$releaseId/assets?name=app-release.apk"
$apkBytes = [System.IO.File]::ReadAllBytes((Resolve-Path $apkPath))

$uploadHeaders = @{
    "Authorization" = "Bearer $token"
    "Accept" = "application/vnd.github.v3+json"
    "User-Agent" = "DanielStreams-Publisher"
    "Content-Type" = "application/vnd.android.package-archive"
}

$assetResponse = Invoke-RestMethod -Uri $uploadUrl -Headers $uploadHeaders -Method Post -Body $apkBytes
Write-Host "==> SUCESSO! Release $TagName publicada com sucesso no GitHub!" -ForegroundColor Green
Write-Host "Download URL: $($assetResponse.browser_download_url)" -ForegroundColor Yellow
