<#
    Push changed files to GitHub without git.

    Usage (from the repo root, Windows PowerShell):
        $env:GITHUB_TOKEN = "github_pat_..."
        .\tools\push.ps1
        .\tools\push.ps1 -Message "arbitrary commit message"
        .\tools\push.ps1 -DryRun

    How it works:
      computes the git blob SHA-1 of every file locally, compares it with the
      remote tree, then uploads only what changed as ONE commit through the
      Git Data API (blobs -> tree -> commit -> ref).

    Token scopes (fine-grained, this repo only):
      Contents: Read and write
      Workflows: Read and write   (only needed when .github/workflows changes)
#>
param(
    [string]$Repo = 'spec2250/adblock',
    [string]$Branch = 'main',
    [string]$Message = '',
    [string]$Token = $env:GITHUB_TOKEN,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Token)) {
    Write-Error 'Set $env:GITHUB_TOKEN first (fine-grained token: Contents RW, + Workflows RW if needed).'
}
$root = Split-Path -Parent $PSScriptRoot
$headers = @{
    Authorization = "Bearer $Token"
    Accept = 'application/vnd.github+json'
    'User-Agent' = 'AdBlocker'
}
$api = "https://api.github.com/repos/$Repo"

function Get-BlobSha([byte[]]$bytes) {
    $header = [System.Text.Encoding]::ASCII.GetBytes("blob $($bytes.Length)`0")
    $all = New-Object byte[] ($header.Length + $bytes.Length)
    [System.Array]::Copy($header, 0, $all, 0, $header.Length)
    [System.Array]::Copy($bytes, 0, $all, $header.Length, $bytes.Length)
    $sha1 = [System.Security.Cryptography.SHA1]::Create()
    return (-join ($sha1.ComputeHash($all) | ForEach-Object { $_.ToString('x2') }))
}

function Invoke-GitHub($method, $uri, $obj) {
    $json = $obj | ConvertTo-Json -Depth 12 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    return Invoke-RestMethod -Method $method -Uri $uri -Headers $headers -Body $bytes `
        -ContentType 'application/json; charset=utf-8'
}

$ref = Invoke-RestMethod -Uri "$api/git/ref/heads/$Branch" -Headers $headers
$headSha = $ref.object.sha
$headCommit = Invoke-RestMethod -Uri "$api/git/commits/$headSha" -Headers $headers
$tree = Invoke-RestMethod -Uri "$api/git/trees/$($headCommit.tree.sha)?recursive=1" -Headers $headers

$remote = @{}
foreach ($e in $tree.tree) { if ($e.type -eq 'blob') { $remote[$e.path] = $e.sha } }

$files = Get-ChildItem -Path $root -Recurse -File | Where-Object {
    $_.FullName -notmatch '\\build\\' -and $_.FullName -notmatch '\\\.gradle\\' -and
    $_.FullName -notmatch '\\\.git\\'
}

$entries = @()
$textExt = @('.xml', '.kt', '.kts', '.yml', '.yaml', '.md', '.properties', '.txt', '.json', '.gradle', '.pro', '.ps1')
foreach ($f in $files) {
    $rel = $f.FullName.Substring($root.Length + 1).Replace('\', '/')
    $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
    # Never upload a UTF-8 BOM: AGP's resource parser rejects XML files that
    # start with one ("mismatched input '﻿'"). Fix the local file too.
    if (($textExt -contains $f.Extension.ToLower()) -and
        $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $clean = New-Object byte[] ($bytes.Length - 3)
        [System.Array]::Copy($bytes, 3, $clean, 0, $clean.Length)
        $bytes = $clean
        [System.IO.File]::WriteAllBytes($f.FullName, $bytes)
        Write-Warning "stripped UTF-8 BOM: $rel"
    }
    $sha = Get-BlobSha $bytes
    if ($remote.ContainsKey($rel) -and $remote[$rel] -eq $sha) { continue }
    if ($DryRun) { Write-Output "would upload $rel"; continue }

    $blob = Invoke-GitHub 'POST' "$api/git/blobs" @{
        content  = [Convert]::ToBase64String($bytes)
        encoding = 'base64'
    }
    $entries += @{ path = $rel; mode = '100644'; type = 'blob'; sha = $blob.sha }
    Write-Output "uploaded $rel"
}

if ($DryRun -or $entries.Count -eq 0) {
    Write-Output $(if ($entries.Count -eq 0 -and -not $DryRun) { 'Nothing to push.' } else { 'Dry run finished.' })
    return
}

if ([string]::IsNullOrWhiteSpace($Message)) {
    $Message = "Update " + (($entries | ForEach-Object { $_.path }) -join ', ')
    if ($Message.Length -gt 200) { $Message = $Message.Substring(0, 200) + '...' }
}

$newTree = Invoke-GitHub 'POST' "$api/git/trees" @{
    base_tree = $headCommit.tree.sha
    tree      = $entries
}
$newCommit = Invoke-GitHub 'POST' "$api/git/commits" @{
    message = $Message
    tree    = $newTree.sha
    parents = @($headSha)
}
Invoke-GitHub 'PATCH' "$api/git/refs/heads/$Branch" @{
    sha   = $newCommit.sha
    force = $false
} | Out-Null

Write-Output "pushed $($entries.Count) file(s) -> $($newCommit.sha)"
Write-Output "CI: https://github.com/$Repo/actions"
