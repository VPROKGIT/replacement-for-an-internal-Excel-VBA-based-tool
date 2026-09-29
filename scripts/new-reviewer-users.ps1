# Builds the value of FORMS_SECURITY_USERS (who may sign in, FORMS-12 / FORMS-21).
#
# Asks for each person's user name and password, scrambles (BCrypt-hashes) each password, checks
# the result, and prints one line to paste into the host (Render: Environment > FORMS_SECURITY_USERS).
# The line is also copied to the clipboard.
#
# Needs Docker Desktop running (it uses the official httpd image's htpasswd tool).
# Passwords are typed hidden, never shown, never written to disk.
#
# Run from the repository folder:
#   powershell -ExecutionPolicy Bypass -File scripts\new-reviewer-users.ps1

# Not 'Stop': htpasswd reports success on stderr, which Windows PowerShell would turn into a fatal
# error. Every step below checks its own result instead.
$ErrorActionPreference = 'Continue'

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Docker is not running. Start Docker Desktop, wait until it is ready, and run this again.' -ForegroundColor Red
    exit 1
}

$entries = @()
Write-Host ''
Write-Host 'Enter one person at a time. Leave the user name empty when you are done.'
while ($true) {
    Write-Host ''
    $name = (Read-Host 'User name').Trim()
    if ($name -eq '') { break }
    if ($name -notmatch '^[A-Za-z0-9._-]{1,100}$') {
        Write-Host 'Use only letters, digits, dot, dash or underscore (no spaces).' -ForegroundColor Yellow
        continue
    }
    if ($entries | Where-Object { $_.StartsWith("${name}:") }) {
        Write-Host "$name is already in the list." -ForegroundColor Yellow
        continue
    }

    $first = Read-Host "Password for $name" -AsSecureString
    $again = Read-Host 'Same password again' -AsSecureString
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($first))
    $plainAgain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($again))
    if ($plain -ne $plainAgain) {
        Write-Host 'The two passwords differ. Try this person again.' -ForegroundColor Yellow
        continue
    }
    if ($plain.Length -lt 10) {
        Write-Host 'Use at least 10 characters. Try this person again.' -ForegroundColor Yellow
        continue
    }

    # The password travels in an environment variable of this process only, read inside the
    # container - no quoting for PowerShell to mangle, and it never appears in a command line.
    $env:FORMS_PW = $plain
    try {
        $line = docker run --rm -e FORMS_PW httpd:2.4-alpine sh -c 'printenv FORMS_PW | htpasswd -niBC 10 $0' $name 2>$null |
            Select-Object -First 1
        $env:FORMS_LINE = $line
        $check = docker run --rm -e FORMS_LINE -e FORMS_PW httpd:2.4-alpine sh -c 'printenv FORMS_LINE > /tmp/f; printenv FORMS_PW | htpasswd -vi /tmp/f $0' $name 2>&1
    } finally {
        Remove-Item Env:FORMS_PW, Env:FORMS_LINE -ErrorAction SilentlyContinue
        $plain = $null
        $plainAgain = $null
    }
    if ($line -notmatch "^$([regex]::Escape($name)):\`$2y\`$10\`$" -or "$check" -notmatch 'correct') {
        Write-Host 'Scrambling the password failed. Nothing was added for this person.' -ForegroundColor Red
        continue
    }
    $entries += $line
    Write-Host "$name added." -ForegroundColor Green
}

if ($entries.Count -eq 0) {
    Write-Host 'No one was added.'
    exit 0
}

$value = $entries -join ','
Set-Clipboard -Value $value
Write-Host ''
Write-Host "FORMS_SECURITY_USERS for $($entries.Count) person(s) - copied to the clipboard:" -ForegroundColor Green
Write-Host ''
Write-Host $value
Write-Host ''
Write-Host 'Paste it as the value of FORMS_SECURITY_USERS on the host. Send each person their own'
Write-Host 'password privately; the line above does not contain the passwords themselves.'
