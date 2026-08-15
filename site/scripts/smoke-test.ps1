<#
.SYNOPSIS
  End-to-end dev-loop smoke test: log in via auth.war (real OS auth), create a container,
  poll until provisioned, and mint a session URL — all against the fakes started by
  tools/scripts/start-dev-stack.ps1.

.PARAMETER Username
  A real local OS account on this machine to log in as (PAM/LogonUserW authenticates for real).

.PARAMETER Password
  That account's password. Prompted securely if omitted.
#>
param(
    [Parameter(Mandatory = $true)][string]$Username,
    [System.Security.SecureString]$Password,
    [string]$NspawnmgrUrl = "http://localhost:8080/nspawnmgr",
    [string]$AuthUrl = "http://localhost:8080/auth",
    [string]$ContainerName = "smoke-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
)

$ErrorActionPreference = "Stop"

if (-not $Password) {
    $Password = Read-Host -Prompt "Password for $Username" -AsSecureString
}
$plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password))

$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

Write-Host "1. Logging in as $Username via auth.war..."
Invoke-WebRequest -Method Post -Uri "$AuthUrl/login" -WebSession $session -UseBasicParsing `
    -Body @{ username = $Username; password = $plainPassword } | Out-Null

Write-Host "2. Fetching templates from nspawnmgr..."
$templates = Invoke-RestMethod -Uri "$NspawnmgrUrl/api/templates" -WebSession $session
if (-not $templates -or $templates.Count -eq 0) {
    # A fresh install starts with zero templates (no longer Flyway-seeded - see
    # docs/administrator-guide.md §2 "Container templates"), so create the dev placeholder here
    # instead of assuming a migration already did it. Requires $Username to be ADMIN - true for
    # the first-ever login against a fresh dev DB (app-managed role mode's default), which is
    # exactly the case this branch actually runs in.
    Write-Host "   None found - creating 'debian-minimal' via the admin API (matches site/templates/nspawn/debian-minimal)..."
    Invoke-RestMethod -Method Post -Uri "$NspawnmgrUrl/api/admin/templates" -WebSession $session `
        -ContentType "application/json" `
        -Body (@{ name = "debian-minimal"; description = "Minimal Debian/Ubuntu base image (apt)"; sourcePath = "debian-minimal"; backend = "SYSTEMD_NSPAWN"; packageManager = "APT"; rdpCapable = $true; active = $true } | ConvertTo-Json) | Out-Null
    $templates = Invoke-RestMethod -Uri "$NspawnmgrUrl/api/templates" -WebSession $session
}
if (-not $templates -or $templates.Count -eq 0) {
    throw "Failed to create or find a template via the admin API"
}
$template = $templates[0]
Write-Host "   Using template '$($template.name)' (id=$($template.id))"

Write-Host "3. Creating container '$ContainerName'..."
$created = Invoke-RestMethod -Method Post -Uri "$NspawnmgrUrl/api/containers" -WebSession $session `
    -ContentType "application/json" `
    -Body (@{ name = $ContainerName; templateId = $template.id; rdpEnabled = $false } | ConvertTo-Json)
$containerId = $created.id
Write-Host "   Container id=$containerId, polling for provisioning to finish..."

for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 1
    $status = Invoke-RestMethod -Uri "$NspawnmgrUrl/api/containers/$containerId/status" -WebSession $session
    Write-Host "   state=$($status.state)"
    if ($status.state -eq "RUNNING" -or $status.state -eq "ERROR") { break }
}
if ($status.state -ne "RUNNING") {
    throw "Container did not reach RUNNING: $($status | ConvertTo-Json)"
}

Write-Host "4. Minting an SSH session URL..."
$sshSession = Invoke-RestMethod -Method Post -Uri "$NspawnmgrUrl/api/containers/$containerId/session/ssh" -WebSession $session
Write-Host "   $($sshSession.url)"

Write-Host ""
Write-Host "Smoke test passed. Open the URL above in a browser (with the same cookie) to see the fake session page."
Write-Host "Run tools/scripts/reset-fake-state.ps1 to clear fake-guacamole-server state between runs."
