# Builds the value of FORMS_SECURITY_USERS from a list you copied (for example from Word).
#
# 1. In your document, select the list and copy it (Ctrl+C). One person per line:
#        anna - her-password
#        marc - his-password
#    (Word's long dashes are fine too.)
# 2. With Docker Desktop running, from the repository folder:
#        powershell -ExecutionPolicy Bypass -File scripts\users-from-clipboard.ps1
# 3. The finished line replaces the list on the clipboard: paste it into the host's
#    FORMS_SECURITY_USERS field (Render: Environment).
#
# Passwords are scrambled (BCrypt) with the official httpd image's htpasswd, each result is checked,
# and the passwords are never printed, written to disk or kept on the clipboard.

# Not 'Stop': htpasswd reports success on stderr, which Windows PowerShell would turn into a fatal
# error. Every step below checks its own result instead.
$ErrorActionPreference = 'Continue'

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Docker is not running. Start Docker Desktop, wait until it is ready, and run this again.' -ForegroundColor Red
    exit 1
}

$text = Get-Clipboard -Raw
if ([string]::IsNullOrWhiteSpace($text)) {
    Write-Host 'The clipboard is empty. Copy your list first (Ctrl+C), then run this again.' -ForegroundColor Red
    exit 1
}

# One person per line: name, a dash (plain or Word's en/em dash), password.
# The dashes are built from their codes: this file must stay plain ASCII, because Windows
# PowerShell 5.1 reads a script without a byte-order mark in the old Windows code page.
$dashes = '-' + [char]0x2013 + [char]0x2014
$personPattern = '^(\S+)\s+[' + $dashes + ']\s+(.+)$'
$people = @()
$problems = @()
$lineNumber = 0
foreach ($raw in ($text -split "`r?`n")) {
    $lineNumber++
    $line = $raw.Replace([char]0x00A0, ' ').Trim()   # Word's non-breaking spaces
    if ($line -eq '') { continue }
    $match = [regex]::Match($line, $personPattern)
    if (-not $match.Success) {
        $problems += "line ${lineNumber}: not 'name - password'"
        continue
    }
    $name = $match.Groups[1].Value
    $password = $match.Groups[2].Value.Trim()
    if ($name -notmatch '^[A-Za-z0-9._-]{1,100}$') {
        $problems += "line ${lineNumber}: user name '$name' may only use letters, digits, dot, dash or underscore"
        continue
    }
    if ($people | Where-Object { $_.Name -eq $name }) {
        $problems += "line ${lineNumber}: '$name' appears twice"
        continue
    }
    $people += [pscustomobject]@{ Name = $name; Password = $password }
}

if ($problems.Count -gt 0) {
    Write-Host 'Please fix the list and copy it again:' -ForegroundColor Red
    $problems | ForEach-Object { Write-Host "  $_" }
    exit 1
}
if ($people.Count -eq 0) {
    Write-Host 'No "name - password" lines found on the clipboard.' -ForegroundColor Red
    exit 1
}

Write-Host "Found $($people.Count) person(s): $(($people | ForEach-Object Name) -join ', ')"
$short = @($people | Where-Object { $_.Password.Length -lt 10 } | ForEach-Object Name)
if ($short.Count -gt 0) {
    Write-Host "Note: short passwords (under 10 characters) for $($short -join ', ') - they work, but longer is safer on a public site." -ForegroundColor Yellow
}

$entries = @()
foreach ($person in $people) {
    # The password travels in an environment variable of this process only, read inside the
    # container - no quoting for PowerShell to mangle, and it never appears in a command line.
    $env:FORMS_PW = $person.Password
    try {
        $line = docker run --rm -e FORMS_PW httpd:2.4-alpine sh -c 'printenv FORMS_PW | htpasswd -niBC 10 $0' $person.Name 2>$null |
            Select-Object -First 1
        $env:FORMS_LINE = $line
        $check = docker run --rm -e FORMS_LINE -e FORMS_PW httpd:2.4-alpine sh -c 'printenv FORMS_LINE > /tmp/f; printenv FORMS_PW | htpasswd -vi /tmp/f $0' $person.Name 2>&1
    } finally {
        Remove-Item Env:FORMS_PW, Env:FORMS_LINE -ErrorAction SilentlyContinue
    }
    if ($line -notmatch "^$([regex]::Escape($person.Name)):\`$2y\`$10\`$" -or "$check" -notmatch 'correct') {
        Write-Host "Scrambling the password of $($person.Name) failed; nothing was copied. Run this again." -ForegroundColor Red
        exit 1
    }
    $entries += $line
    Write-Host "  $($person.Name) done"
}
$people = $null

$value = $entries -join ','
Set-Clipboard -Value $value
Write-Host ''
Write-Host "Done. FORMS_SECURITY_USERS for $($entries.Count) person(s) is on the clipboard (your list is no longer there):" -ForegroundColor Green
Write-Host ''
Write-Host $value
Write-Host ''
Write-Host 'Paste it as the value of FORMS_SECURITY_USERS on the host.'
