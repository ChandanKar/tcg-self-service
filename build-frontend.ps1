# VM Self-Service Platform - Frontend Build Script
# Concatenates CSS and JS files for production

param(
    [switch]$Minify = $false
)

$staticDir = "src\main\resources\static"
$distDir = "$staticDir\dist"

# Create dist directory
if (-not (Test-Path $distDir)) {
    New-Item -ItemType Directory -Path $distDir -Force | Out-Null
}

Write-Host "Building frontend assets..." -ForegroundColor Cyan

# Concatenate CSS files
Write-Host "Concatenating CSS files..." -ForegroundColor Yellow
$cssFiles = @(
    "$staticDir\css\main.css",
    "$staticDir\css\layout\topnav.css",
    "$staticDir\css\layout\sidebar.css",
    "$staticDir\css\layout\content.css",
    "$staticDir\css\components\cards.css",
    "$staticDir\css\components\tables.css",
    "$staticDir\css\components\badges.css",
    "$staticDir\css\components\buttons.css",
    "$staticDir\css\components\slideout.css",
    "$staticDir\css\components\modals.css"
)

$cssContent = ""
foreach ($file in $cssFiles) {
    if (Test-Path $file) {
        $cssContent += "/* === $(Split-Path $file -Leaf) === */`n"
        $cssContent += Get-Content $file -Raw
        $cssContent += "`n`n"
    } else {
        Write-Host "Warning: CSS file not found: $file" -ForegroundColor Red
    }
}
$cssContent | Out-File -FilePath "$distDir\app.css" -Encoding utf8
Write-Host "  Created: dist\app.css" -ForegroundColor Green

# Concatenate JS files
Write-Host "Concatenating JS files..." -ForegroundColor Yellow
$jsFiles = @(
    "$staticDir\js\config.js",
    "$staticDir\js\core\api-client.js",
    "$staticDir\js\core\template-loader.js",
    "$staticDir\js\core\utils.js",
    "$staticDir\js\core\router.js",
    "$staticDir\js\ui\sidebar.js",
    "$staticDir\js\ui\slideout.js",
    "$staticDir\js\ui\notifications.js",
    "$staticDir\js\features\dashboard.js",
    "$staticDir\js\features\environments.js",
    "$staticDir\js\features\features.js",
    "$staticDir\js\app.js"
)

$jsContent = ""
foreach ($file in $jsFiles) {
    if (Test-Path $file) {
        $jsContent += "/* === $(Split-Path $file -Leaf) === */`n"
        $jsContent += Get-Content $file -Raw
        $jsContent += "`n`n"
    } else {
        Write-Host "Warning: JS file not found: $file" -ForegroundColor Red
    }
}
$jsContent | Out-File -FilePath "$distDir\app.js" -Encoding utf8
Write-Host "  Created: dist\app.js" -ForegroundColor Green

# Summary
$cssSize = (Get-Item "$distDir\app.css").Length / 1KB
$jsSize = (Get-Item "$distDir\app.js").Length / 1KB

Write-Host ""
Write-Host "Build complete!" -ForegroundColor Green
Write-Host "  CSS: $([math]::Round($cssSize, 2)) KB"
Write-Host "  JS:  $([math]::Round($jsSize, 2)) KB"
Write-Host ""
Write-Host "To use bundled files in production, update index.html to load:" -ForegroundColor Cyan
Write-Host "  <link rel='stylesheet' href='dist/app.css'>"
Write-Host "  <script src='dist/app.js'></script>"

