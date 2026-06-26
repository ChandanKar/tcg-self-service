# VM Self-Service Platform - Frontend Build Script
# Derives the file list from index.html, matching the Gradle buildFrontend task.

$staticDir = "src\main\resources\static"
$distDir = "$staticDir\dist"
$indexHtml = "$staticDir\index.html"

if (-not (Test-Path $indexHtml)) {
    Write-Error "index.html not found at $indexHtml"
    exit 1
}

if (-not (Test-Path $distDir)) {
    New-Item -ItemType Directory -Path $distDir -Force | Out-Null
}

$html = Get-Content $indexHtml -Raw
$dq = [char]34
$utf8NoBom = New-Object System.Text.UTF8Encoding $false

Write-Host "Building frontend assets..." -ForegroundColor Cyan

Write-Host "Bundling CSS..." -ForegroundColor Yellow
$cssPattern = '<link rel=' + $dq + 'stylesheet' + $dq + ' href=' + $dq + '(.+?)' + $dq
$cssMatches = [regex]::Matches($html, $cssPattern)
$cssContent = ""
$cssCount = 0
foreach ($m in $cssMatches) {
    $href = $m.Groups[1].Value
    if ($href -match "^https?://") { continue }
    if ($href -match "^vendor/") { continue }

    $file = Join-Path $staticDir ($href -replace "/", "\")
    if (Test-Path $file) {
        $cssContent += "/* === $(Split-Path $file -Leaf) === */`n"
        $cssContent += Get-Content $file -Raw
        $cssContent += "`n`n"
        $cssCount++
    } else {
        Write-Host "  Warning: $file not found" -ForegroundColor Red
    }
}
[System.IO.File]::WriteAllText((Join-Path $PWD "$distDir\app.css"), $cssContent, $utf8NoBom)
$cssSize = [math]::Round((Get-Item "$distDir\app.css").Length / 1KB, 2)
Write-Host "  dist\app.css  - $cssCount files, $cssSize KB" -ForegroundColor Green

Write-Host "Bundling JS..." -ForegroundColor Yellow
$jsPattern = '<script src=' + $dq + '(.+?)' + $dq
$jsMatches = [regex]::Matches($html, $jsPattern)
$jsContent = ""
$jsCount = 0
foreach ($m in $jsMatches) {
    $src = $m.Groups[1].Value
    if ($src -match "^https?://") { continue }
    if ($src -match "^vendor/") { continue }

    $file = Join-Path $staticDir ($src -replace "/", "\")
    if (Test-Path $file) {
        $jsContent += "/* === $(Split-Path $file -Leaf) === */`n"
        $jsContent += Get-Content $file -Raw
        $jsContent += "`n`n"
        $jsCount++
    } else {
        Write-Host "  Warning: $file not found" -ForegroundColor Red
    }
}
[System.IO.File]::WriteAllText((Join-Path $PWD "$distDir\app.js"), $jsContent, $utf8NoBom)
$jsSize = [math]::Round((Get-Item "$distDir\app.js").Length / 1KB, 2)
Write-Host "  dist\app.js   - $jsCount files, $jsSize KB" -ForegroundColor Green

Write-Host ""
Write-Host "Done. Run './gradlew deploy' to build the production JAR." -ForegroundColor Cyan
