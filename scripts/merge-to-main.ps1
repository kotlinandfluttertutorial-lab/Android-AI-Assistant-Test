Set-Location "j:\Android\AndroidStudioProjects\Kiro\TestBranch\Develop_Feature\Android-AI-Assistant-Test"
git checkout main
git pull origin main
git merge set_up_gcp --no-ff -m "fix: set real TLS certificate pins for Cloud Run domain"
git push origin main
git checkout set_up_gcp
Write-Host "✅ Done — main updated and pushed"
