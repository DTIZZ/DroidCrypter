# DroidCrypter Test Fixtures (`testdata/`)

All `.hc` container fixtures in this directory were created by **the Windows desktop tool (v1.25)** (`VeraCrypt Format.exe` and `VeraCrypt.exe`) via [`make-testdata.ps1`](make-testdata.ps1).

## Fixture Matrix

| Container File | Password | PIM | Cipher (XTS) | Header PRF | Filesystem | Size |
|---|---|---:|---|---|---|---|
| `aes_sha512_fat.hc` | `test-aes-512` | `0` | AES | SHA-512 | FAT16 | 16 MiB |
| `aes_sha512_exfat.hc` | `test-aes-exfat` | `0` | AES | SHA-512 | exFAT | 16 MiB |
| `aes_sha512_ntfs.hc` | `test-aes-ntfs` | `0` | AES | SHA-512 | NTFS | 16 MiB |
| `serpent_sha512_fat.hc` | `test-serpent` | `0` | Serpent | SHA-512 | FAT16 | 8 MiB |
| `twofish_sha512_fat.hc` | `test-twofish` | `0` | Twofish | SHA-512 | FAT16 | 8 MiB |
| `aes_twofish_sha512_fat.hc` | `test-aes-twofish` | `0` | AES-Twofish | SHA-512 | FAT16 | 8 MiB |
| `aes_twofish_serpent_sha512_fat.hc` | `test-ats` | `0` | AES-Twofish-Serpent | SHA-512 | FAT16 | 8 MiB |
| `serpent_aes_sha512_fat.hc` | `test-sa` | `0` | Serpent-AES | SHA-512 | FAT16 | 8 MiB |
| `serpent_twofish_aes_sha512_fat.hc` | `test-sta` | `0` | Serpent-Twofish-AES | SHA-512 | FAT16 | 8 MiB |
| `twofish_serpent_sha512_fat.hc` | `test-ts` | `0` | Twofish-Serpent | SHA-512 | FAT16 | 8 MiB |
| `aes_sha256_fat.hc` | `test-sha256` | `0` | AES | SHA-256 | FAT16 | 8 MiB |
| `aes_whirlpool_fat.hc` | `test-whirlpool` | `0` | AES | Whirlpool | FAT16 | 8 MiB |
| `aes_streebog_fat.hc` | `test-streebog` | `0` | AES | Streebog-512 | FAT16 | 8 MiB |
| `aes_sha512_pim5_fat.hc` | `test-aes-pim5-long-password` | `5` | AES | SHA-512 | FAT16 | 8 MiB |
| `aes_sha512_fat12.hc` | `test-fat12` | `0` | AES | SHA-512 | FAT12 | 3 MiB |

## Reference Payload (`testdata/payload/`)

Each container (except `aes_sha512_fat12.hc`, which omits `random_1m.bin` to fit within 3 MiB) is populated with:
- `small.txt`: 40-byte ASCII text (`hello from a veracrypt test container!\r\n`)
- `empty.dat`: 0-byte file
- `random_300k.bin`: 307,200 bytes of deterministic pseudo-random bytes (multi-cluster chain verification)
- `random_1m.bin`: 1,048,576 bytes of deterministic pseudo-random bytes
- `Long file name with spaces and Ünïcødé — 2026.txt`: LFN / exFAT `0xC1` / NTFS `$FILE_NAME` Unicode verification
- `nested/top.txt` & `nested/deeper/leaf.txt`: multi-level nested directory traversal
- `many/file_0001.txt` .. `many/file_0600.txt`: 600 files in a single subdirectory (FAT root/sub-dir multi-cluster chain & NTFS `$INDEX_ALLOCATION` INDX B-tree verification)

## Space Ground Truth (`windows-space.csv`)

[`windows-space.csv`](windows-space.csv) records the exact `Size` (`totalBytes`) and `FreeSpace` (`freeBytes`) reported by Windows `Win32_LogicalDisk` while each container was mounted in DroidCrypter for Windows. `ContainerReadTest` verifies that `MountedVolume.totalBytes` and `MountedVolume.freeBytes` match Windows' values to the byte.

## Regenerating Fixtures

Run in an elevated PowerShell session on Windows with VeraCrypt installed at `C:\Program Files\VeraCrypt`:

```powershell
powershell -ExecutionPolicy Bypass -File .\testdata\make-testdata.ps1
```

