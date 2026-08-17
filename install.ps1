<#
.SYNOPSIS
    Installs orkx for the current user, and puts it on PATH.

.DESCRIPTION
    Copies the native binary to %LOCALAPPDATA%\Programs\orkx and adds that directory to
    your user PATH, so `orkx` works from any shell. Builds the binary first if it is not
    there yet.

    Per-user throughout: nothing here needs administrator rights, and the machine-wide
    PATH is never touched.

    The PATH entry is written with [Environment]::SetEnvironmentVariable rather than
    `setx`, which truncates PATH at 1024 characters and has eaten a good many of them.

.PARAMETER Destination
    Where to put the binary. Defaults to %LOCALAPPDATA%\Programs\orkx.

.PARAMETER Uninstall
    Remove the binary and take the directory back out of PATH.

.PARAMETER NoPathChange
    Install the binary but leave PATH alone.

.EXAMPLE
    .\install.cmd

.EXAMPLE
    .\install.cmd -Uninstall
#>
[CmdletBinding()]
param(
    [string] $Destination = "$env:LOCALAPPDATA\Programs\orkx",
    [switch] $Uninstall,
    [switch] $NoPathChange
)

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$binary = Join-Path $root 'target\orkx.exe'
$installed = Join-Path $Destination 'orkx.exe'

# A PATH entry differing only in case or a trailing slash is the same entry.
function Normalize([string] $path) {
    return $path.TrimEnd('\', '/').ToLowerInvariant()
}

# Everything below works on the raw string and keeps empty entries, because a PATH is
# the user's and this script has business changing exactly one entry of it. Dropping the
# empties looks like tidying up and is not: an empty entry means "the current directory",
# so removing one silently changes what their shell resolves.
function Get-UserPath {
    $current = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($null -eq $current) { return '' }
    return $current
}

function Test-OnUserPath([string] $directory) {
    $wanted = Normalize $directory
    return [bool] ((Get-UserPath) -split ';' | Where-Object { (Normalize $_) -eq $wanted })
}

if ($Uninstall) {
    if (Test-Path $installed) {
        Remove-Item $installed -Force
        # Only if we emptied it - a directory the user put other things in is theirs.
        if (-not (Get-ChildItem $Destination -Force)) { Remove-Item $Destination -Force }
        Write-Host "Removed $installed"
    } else {
        Write-Host "Nothing installed at $installed"
    }

    $entries = (Get-UserPath) -split ';'
    $wanted = Normalize $Destination
    $kept = @($entries | Where-Object { (Normalize $_) -ne $wanted })
    if ($kept.Count -ne $entries.Count) {
        [Environment]::SetEnvironmentVariable('Path', ($kept -join ';'), 'User')
        Write-Host "Took $Destination back out of your user PATH."
    }
    Write-Host 'Open a new terminal for that to take effect.'
    exit 0
}

if (-not (Test-Path $binary)) {
    Write-Host "No binary at $binary yet - building it."
    & (Join-Path $root 'native.cmd')
    if ($LASTEXITCODE -ne 0) { throw "The native build failed; nothing installed." }
}

New-Item -ItemType Directory -Path $Destination -Force | Out-Null
Copy-Item $binary $installed -Force
Write-Host "Installed $installed"
Write-Host (& $installed --version)

# Said whether or not this run is what added the PATH entry, because either way the
# shell that started this script cannot see it, and "already on your user PATH" followed
# by a command-not-found is how an installer earns a bug report.
function Write-NextSteps {
    Write-Host ''
    Write-Host 'A PATH change does not reach a shell that is already open - nor a new one'
    Write-Host 'started from it, which inherits this environment. Open a terminal from the'
    Write-Host 'Start menu, or paste this into the shell you are in:'
    Write-Host ''
    Write-Host ('    PowerShell   $env:PATH += ";' + $Destination + '"')
    Write-Host ('    cmd.exe      set PATH=%PATH%;' + $Destination)
    Write-Host ''
    Write-Host 'Then: orkx login'
}

if ($NoPathChange) {
    Write-Host "PATH left alone, as asked. Add $Destination to it yourself to type 'orkx'."
    exit 0
}

if (Test-OnUserPath $Destination) {
    Write-Host "$Destination is already on your user PATH."
    Write-NextSteps
} else {
    # Appended to the string as it stands, separator only where one is missing, so every
    # other entry survives exactly as the user left it.
    $current = Get-UserPath
    $updated = if ($current -eq '') { $Destination }
               elseif ($current.EndsWith(';')) { "$current$Destination" }
               else { "$current;$Destination" }
    [Environment]::SetEnvironmentVariable('Path', $updated, 'User')
    Write-Host "Added $Destination to your user PATH."
    Write-NextSteps
}
