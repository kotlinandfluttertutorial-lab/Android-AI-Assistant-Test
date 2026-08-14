# Start backend containers and cloudflared tunnel
Write-Host "Starting containers..." -ForegroundColor Cyan
docker compose up -d

Write-Host "Waiting for backend to be healthy..." -ForegroundColor Cyan
$maxWait = 60
$elapsed = 0
do {
    Start-Sleep -Seconds 2
    $elapsed += 2
    $status = docker inspect --format "{{.State.Health.Status}}" testgithub-backend-1 2>$null
} while ($status -ne "healthy" -and $elapsed -lt $maxWait)

if ($status -eq "healthy") {
    Write-Host "Backend is healthy. Starting cloudflared tunnel..." -ForegroundColor Green
} else {
    Write-Host "Backend did not become healthy in time, starting tunnel anyway..." -ForegroundColor Yellow
}

cloudflared tunnel --config "C:\Users\admin\.cloudflared\config.yml" run mybackend
