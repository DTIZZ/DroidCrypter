# Architecture & Security Decisions (`DECISIONS.md`)

This document records design choices made where the specification allowed discretion, always selecting the safest behaviour (zero plaintext on disk, zero data loss, and minimal lifetime of key material in RAM).

## 1. Visual Design & Navigation
- **Dark-First Cryptographic Palette (`VaultColors`)**: Uses an obsidian dark background (`#0F141A`), slate cards (`#171F28` / `#202B38`), crisp outlines (`#303E4F`), emerald-teal primary accents (`#00D1B2`), cobalt secondary accents (`#3B82F6`), crimson danger actions (`#F43F5E`), and warm amber stars (`#F59E0B`).
- **Single-Activity State Machine (`MainActivity`)**: Uses a single `FragmentActivity` with `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode"` so that rotating the device, folding/unfolding, or entering split-screen never recreates the activity, never re-runs PBKDF2, and never loses un-exported cuts in the video editor.
- **Immediate Return on Lock**: `MainActivity` registers a `VolumeRegistry` change listener that immediately returns any open volume screen (`Browser`, `PhotoViewer`, `MediaPlayer`, `VideoEditor`) to `VaultList` if that volume is unmounted (by user action, `ACTION_SCREEN_OFF`, task removal, or `dataSync` foreground service timeout).

## 2. Password, PIM, and Key Material Hygiene
- **Zero-String Password Pipeline**: `SecurePasswordField` and `SecurePimField` wrap an Android `EditText` via `AndroidView`. They call `setSingleLine(true)` *before* setting the password `inputType` and `PasswordTransformationMethod.getInstance()` so the mask is never stripped. Characters are read into a `CharArray` via `Editable.getChars`, converted directly to UTF-8 via `Secrets.utf8Bytes(CharArray)` without creating a `String`, and the `Editable`'s internal gap buffer is overwritten in-place with `\u0000` characters before calling `editable.clear()`.
- **In-Process Creation Job Handoff**: `ContainerCreationService` receives its `CreationJobRequest` through a synchronized in-RAM handoff slot rather than Android `Intent` extras, preventing the password from ever crossing Binder IPC or being logged by the OS activity manager.

## 3. Encrypted Video Scratch & Media3 `Mp4Muxer` Integration
- **FileChannel Adapter over `EncryptedScratch`**: Media3 `Mp4Muxer.Builder` accepts a `FileOutputStream` and obtains its `FileChannel` (`outputStream.getChannel()`) for random-access MP4 box updates (`moov` / `mdat`). `EncryptedScratch` provides `asChannel(): FileChannel` and `asFileOutputStream(): FileOutputStream` backed by XTS-AES-256 over 512-byte units with an in-RAM random key and a `BitSet` tracking written units so unwritten gap units always read/materialize as encrypted zeros.
- **Immediate Unlink + Fallback Cleanup**: `EncryptedScratch` unlinks its backing file immediately after opening on POSIX (Android/Linux) so the path disappears from the directory tree while the file descriptor remains open, and also truncates to 0 and deletes on `close()` for host JVM tests on Windows where open files cannot be unlinked while open.

## 4. Shared `RatingsStore` Registry
- **`RatingsRegistry`**: Each unlocked `MountedVolume` is associated with a single `RatingsStore` instance in `RatingsRegistry`, ensuring that renames or deletes performed through `VaultDocumentsProvider` (e.g., from Samsung My Files or the system picker) and those performed inside the in-app browser update the exact same in-RAM ratings map and flush via the same `VolumeRegistry` before-close hook.

## 5. Hostile / Corrupted Filesystem Bounds
- All FAT, exFAT, and NTFS parsers cap cluster chains to `totalClusters + 2`, cap directory listings to `100,000` entries (`64 MiB` max directory stream on exFAT), cap NTFS `$ATTRIBUTE_LIST` and `$INDEX_ALLOCATION` INDX B-tree recursion, and cap in-RAM image decoding to `64 MiB` encoded size downsampled to `min(2 * maxScreenSide, 4096)` pixels.
