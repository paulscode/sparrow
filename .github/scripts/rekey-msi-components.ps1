# Gives every component in the .msi a GUID of this fork's own.
#
# jpackage derives each component's GUID from the file's path inside the app, and this app holds largely the same files
# as upstream Sparrow at the same paths: the runtime, the libraries. 250 of the two installers' component GUIDs were
# identical. Windows Installer takes a shared component GUID to mean one shared component, so with both installed,
# uninstalling either one left its files behind as still in use by the other, and repairs could cross between them.
#
# Each GUID is replaced by one derived from it and a key naming this fork. Derived rather than random, so the same file
# keeps the same GUID from one release of this fork to the next, which upgrades between them rely on.
#
# Usage: rekey-msi-components.ps1 <path to .msi>
param([Parameter(Mandatory = $true)][string]$Msi)
$ErrorActionPreference = 'Stop'

$path = (Resolve-Path $Msi).Path
$sha = [System.Security.Cryptography.SHA256]::Create()

function Get-ForkGuid([string]$old) {
    $bytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes("com.paulscode.sparrowblake2b:component:" + $old.ToUpperInvariant()))
    $guidBytes = [byte[]]$bytes[0..15]
    #Marked as a name-based GUID, version 5 and the RFC 4122 variant, so it cannot be mistaken for a random one
    $guidBytes[7] = ($guidBytes[7] -band 0x0F) -bor 0x50
    $guidBytes[8] = ($guidBytes[8] -band 0x3F) -bor 0x80
    return '{' + ([Guid]::new($guidBytes)).ToString().ToUpperInvariant() + '}'
}

function Invoke-Com($object, [string]$name, [string]$kind, [object[]]$arguments) {
    $flags = [System.Reflection.BindingFlags]::$kind
    return $object.GetType().InvokeMember($name, $flags, $null, $object, $arguments)
}

$installer = New-Object -ComObject WindowsInstaller.Installer
#1 is msiOpenDatabaseModeTransact: changes are written on Commit
$database = Invoke-Com $installer 'OpenDatabase' 'InvokeMethod' @($path, 1)
$view = Invoke-Com $database 'OpenView' 'InvokeMethod' @('SELECT `Component`, `ComponentId` FROM `Component`')
Invoke-Com $view 'Execute' 'InvokeMethod' $null | Out-Null

$changed = 0
$seen = @{}
while($true) {
    $record = Invoke-Com $view 'Fetch' 'InvokeMethod' $null
    if($null -eq $record) { break }
    $old = Invoke-Com $record 'StringData' 'GetProperty' @(2)
    if([string]::IsNullOrEmpty($old)) { continue }
    $new = Get-ForkGuid $old
    if($seen.ContainsKey($new)) { throw "Derived GUID $new collides for $old and $($seen[$new])" }
    $seen[$new] = $old
    Invoke-Com $record 'StringData' 'SetProperty' @(2, $new) | Out-Null
    #2 is msiViewModifyUpdate
    Invoke-Com $view 'Modify' 'InvokeMethod' @(2, $record) | Out-Null
    $changed++
}
Invoke-Com $view 'Close' 'InvokeMethod' $null | Out-Null
Invoke-Com $database 'Commit' 'InvokeMethod' $null | Out-Null
[System.Runtime.InteropServices.Marshal]::ReleaseComObject($view) | Out-Null
[System.Runtime.InteropServices.Marshal]::ReleaseComObject($database) | Out-Null

if($changed -eq 0) { throw "No components were rekeyed in $path" }
Write-Host "Rekeyed $changed components in $path"
