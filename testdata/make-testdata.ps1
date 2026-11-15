<#
  Rebuilds the compatibility fixtures with VeraCrypt for Windows (NOT with the app).

  For every container in the manifest below it:
    1. creates it with "VeraCrypt Format.exe" (CLI, /silent),
    2. mounts it with VeraCrypt.exe,
    3. copies the reference payload (testdata/payload) into it with Windows' own file system driver,
    4. records the Size / FreeSpace that Windows reports (Win32_LogicalDisk) into windows-space.csv,
    5. dismounts it.

  The containers must not be modified afterwards: the host tests compare the app's figures with
  the numbers recorded here. Requires VeraCrypt 1.25+ installed in "C:\Program Files\VeraCrypt".
  Passwords are test-only and documented in README.md.
#>
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$vcFormat = 'C:\Program Files\VeraCrypt\VeraCrypt Format.exe'
$vc = 'C:\Program Files\VeraCrypt\VeraCrypt.exe'
$payload = Join-Path $here 'payload'

function New-Payload {
    if (Test-Path $payload) { Remove-Item -Recurse -Force $payload }
    New-Item -ItemType Directory -Force $payload | Out-Null
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText("$payload\small.txt", "Hello from VeraCrypt for Windows.`r`n", $utf8)
    [IO.File]::WriteAllBytes("$payload\empty.dat", [byte[]]@())
    $rnd = New-Object System.Random(20261110)
    $b = New-Object byte[] 300001; $rnd.NextBytes($b); [IO.File]::WriteAllBytes("$payload\random_300k.bin", $b)
    $b = New-Object byte[] 1048583; $rnd.NextBytes($b); [IO.File]::WriteAllBytes("$payload\random_1m.bin", $b)
    [IO.File]::WriteAllText("$payload\Ünïcødé 日本語 файл.txt", "unicode name`r`n", $utf8)
    [IO.File]::WriteAllText("$payload\This is a very long file name that goes far beyond the old eight dot three limit of FAT.txt", "long name`r`n", $utf8)
    New-Item -ItemType Directory -Force "$payload\nested\level1\level2\level3" | Out-Null
    [IO.File]::WriteAllText("$payload\nested\top.txt", "top`r`n", $utf8)
    [IO.File]::WriteAllText("$payload\nested\level1\level2\level3\deep.txt", "deep file`r`n", $utf8)
    New-Item -ItemType Directory -Force "$payload\nested\Empty Folder" | Out-Null
    # Large directory (forces NTFS $INDEX_ALLOCATION, multi-cluster FAT/exFAT directories). Copied into every container.
    New-Item -ItemType Directory -Force "$payload\many" | Out-Null
    for ($i = 0; $i -lt 600; $i++) { [IO.File]::WriteAllText(("$payload\many\file_{0:D4}.txt" -f $i), "file $i`r`n", $utf8) }
}

# name, encryption, hash, filesystem, size, pim, password
$manifest = @(
  @('aes_sha512_fat',                 'AES',                   'sha512',    'FAT',   '4M',  '0', 'test-aes-sha512'),
  @('aes_sha256_fat',                 'AES',                   'sha256',    'FAT',   '4M',  '0', 'test-aes-sha256'),
  @('aes_sha512_fat16_40m',           'AES',                   'sha512',    'FAT',   '40M', '0', 'test-aes-fat16'),
  @('aes_sha512_exfat',               'AES',                   'sha512',    'exFAT', '8M',  '0', 'test-aes-exfat'),
  @('aes_sha512_ntfs',                'AES',                   'sha512',    'NTFS',  '8M',  '0', 'test-aes-ntfs'),
  @('aes_sha512_pim5_fat',            'AES',                   'sha512',    'FAT',   '4M',  '5', 'test-aes-pim5-long-password'),
  @('aes_whirlpool_fat',              'AES',                   'whirlpool', 'FAT',   '4M',  '0', 'test-aes-whirlpool'),
  @('aes_streebog_fat',               'AES',                   'streebog',  'FAT',   '4M',  '0', 'test-aes-streebog'),
  @('serpent_sha512_fat',             'Serpent',               'sha512',    'FAT',   '4M',  '0', 'test-serpent'),
  @('twofish_sha512_fat',             'Twofish',               'sha512',    'FAT',   '4M',  '0', 'test-twofish'),
  @('aes-twofish_sha512_fat',         'AES(Twofish)',          'sha512',    'FAT',   '4M',  '0', 'test-aes-twofish'),
  @('aes-twofish-serpent_sha512_fat', 'AES(Twofish(Serpent))', 'sha512',    'FAT',   '4M',  '0', 'test-aes-twofish-serpent'),
  @('serpent-aes_sha512_fat',         'Serpent(AES)',          'sha512',    'FAT',   '4M',  '0', 'test-serpent-aes'),
  @('serpent-twofish-aes_sha512_fat', 'Serpent(Twofish(AES))', 'sha512',    'FAT',   '4M',  '0', 'test-serpent-twofish-aes'),
  @('twofish-serpent_sha512_fat',     'Twofish(Serpent)',      'sha512',    'FAT',   '4M',  '0', 'test-twofish-serpent')
)

function Run($exe, [string[]]$argv) {
    $p = Start-Process -FilePath $exe -ArgumentList $argv -PassThru -WindowStyle Hidden
    if (-not $p.WaitForExit(300000)) { $p.Kill(); throw "timeout: $exe $argv" }
    if ($p.ExitCode -ne 0) { throw "exit $($p.ExitCode): $exe $($argv -join ' ')" }
}

New-Payload
$rows = @('name,filesystem,label,size_bytes,free_bytes')
foreach ($m in $manifest) {
    $name, $enc, $hash, $fs, $size, $pim, $pw = $m
    $file = Join-Path $here "$name.hc"
    Write-Host "== $name"
    Run $vcFormat @('/create', $file, '/size', $size, '/password', $pw, '/encryption', $enc, '/hash', $hash,
                    '/filesystem', $fs, '/pim', $pim, '/silent', '/force')
    $used = (Get-PSDrive -PSProvider FileSystem).Name
    $L = ('V','W','X','Y','Q','R','S','T') | Where-Object { $used -notcontains $_ } | Select-Object -First 1
    Run $vc @('/volume', $file, '/letter', $L, '/password', $pw, '/hash', $hash, '/pim', $pim, '/quit', '/silent')
    try {
        Copy-Item -Recurse -Force "$payload\*" "$($L):\"
        Start-Sleep -Milliseconds 500
        $d = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='$($L):'"
        $rows += "$name,$($d.FileSystem),$($d.VolumeName),$($d.Size),$($d.FreeSpace)"
    } finally {
        Run $vc @('/dismount', $L, '/quit', '/silent', '/force')
    }
}
$rows | Set-Content -Encoding ASCII (Join-Path $here 'windows-space.csv')
Write-Host 'done'

