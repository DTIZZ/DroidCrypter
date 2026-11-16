# Changelog

## [1.11.0] — 2026-11-16

### Security
- **Keys are wiped from memory on lock**: AES, Serpent and Twofish now run on modified copies of the Bouncy Castle 1.85 engines (`engine/.../vc/bc`, MIT licence) that zero their key schedules when a volume is locked. Previously the platform AES cipher and Bouncy Castle's Serpent/Twofish kept key copies that could never be wiped. PBKDF2 uses an HMAC built on Bouncy Castle digests, and its key state is wiped after each use. No `javax.crypto` remains in the engine.
- **Auto-lock on Android 15+**: the 6-hour `dataSync` limit calls `onTimeout(startId, fgsType)`, which wasn't implemented, so the system crashed the app instead of letting it lock. Both foreground services now handle it.
- Biometric blob paths accept only hex container IDs, and media URIs validate volume IDs the same way document IDs do.

### Changed
- AES is now a software implementation (no hardware acceleration), so large copies and container creation on AES volumes are slower. This is the cost of being able to wipe the key.
- Internal cleanup: shared foreground-service helpers, provider document-resolution helpers, a typed low-storage exception, and a single Keystore loader.

## [1.10.0] — 2026-11-12

### Added
- **Full screen for photos and videos**: the photo viewer and the media player have a full-screen button in the top bar, available in portrait and landscape. Full screen hides the app's top and bottom bars and the system status/navigation bars, and the image or video grows to fill the screen without changing its aspect ratio (it is never cropped or stretched).
- **Previous / Next stay available in full screen**: a translucent strip at the top shows the file name, the position in the folder, Previous and Next, and a button to leave full screen. In the photo viewer a tap on the image shows or hides the strip (swiping between images still works); in the media player it appears and disappears together with the playback controls. Back leaves full screen before it closes the viewer.

## [1.9.0] — 2026-11-12

### Added
- **New folder**: the browser's "+ Add" button now opens a menu; "New folder" creates an empty folder in the folder you are looking at. Names that are already taken (by a file or a folder, in any case), invalid on FAT/exFAT, or reserved by DroidCrypter are refused with the reason shown in the dialog.
- **Add a folder**: "Add a folder" picks a folder from Android storage and copies it into the vault with its whole structure — subfolders, including empty ones, and every file. The confirmation shows the file and folder counts and the total size, and warns when it is more than the vault has free. If the folder name is taken it becomes "name (2)"; characters FAT/exFAT cannot store are replaced with "_". "Delete the originals afterwards" works as for files, and removes the source folder itself only when everything was added. Bounded to 50,000 items and 48 levels; anything left out is listed in the result.
- **Engine**: `MountedVolume.createDirectory` for FAT12/16/32 (a zeroed cluster holding "." and "..", allocated in the FAT before the directory entry is written) and exFAT (a zeroed, contiguous one-cluster directory with DataLength = ValidDataLength). NTFS stays read-only.

### Changed
- "+ Add files" is now "+ Add" → "Add files". Results of an import with problems show a summary and the first few failures instead of every line.

## [1.8.1] — 2026-11-11

### Added
- **Return to where you left off**: closing the photo viewer, media player or video editor scrolls the file list back to the file that was on screen when you left (including one you paged or skipped to) and marks it with a tinted row and an accent bar. It is placed about a third of the way down so neighbouring files are visible, works for search results as well as folders, and is announced as "Last viewed" by screen readers.

### Fixed
- A folder could occasionally open as an empty list: applying the saved sort order or star ratings at startup copied back a stale snapshot of the browser state and could overwrite a folder listing that had finished loading in the meantime. Sort and rating updates now only replace the rows on top of the latest state.

## [1.8.0] — 2026-11-11

### Added
- **Search inside a vault**: the browser's 🔍 button opens a search field that finds files and folders by name across the whole container (every word must appear in the name, case-insensitive). Hits show the folder they live in, keep the current sort, star ratings and multi-select actions, and open in the photo/media viewers; tapping a folder hit opens that folder. Back closes search before leaving the folder, and the hits are restored when returning from a viewer. Nothing is indexed or cached: each search walks the directory tree in RAM (`VaultSearch`), bounded to 500 hits, 20,000 folders and 64 levels.
- **Layout tests for the bottom bars** (`BrowserLayoutTest`, Robolectric + Compose): lays out the real browser screen with simulated 3-button, gesture, landscape side-bar and keyboard insets and asserts in pixels that the last row always scrolls clear of the multi-select action bar, the navigation bar and the keyboard.

### Fixed
- In landscape with 3-button navigation, browser rows, the top bar and the "Lock" button extended underneath the side navigation bar / display cutout.
- The file list and multi-select action bar are now padded for the on-screen keyboard.
- The file list returns to the top when the folder or the search results change.

## [1.7.0] — 2026-11-11

### Added
- **Userspace DroidCrypter Engine (`:engine`)**:
  - Header parsing, creation, and unlock across primary (`0`), hidden (`65536`), backup (`size - 131072`), and hidden backup (`size - 65536`) offsets.
  - Lazy block-by-block PBKDF2-HMAC for `SHA-512`, `SHA-256`, `Whirlpool`, and `Streebog-512` (GOST R 34.11-2012), supporting standard and custom PIM iteration counts.
  - IEEE 1619 XTS mode (`GF(2^128)` polynomial `0x87`) for all 8 cipher suites: `AES`, `Serpent`, `Twofish`, `AES-Twofish`, `AES-Twofish-Serpent`, `Serpent-AES`, `Serpent-Twofish-AES`, and `Twofish-Serpent`.
  - Userspace filesystems: `FAT12` / `FAT16` / `FAT32` (read/write/format), `exFAT` (read/write/format with compressed up-case table and contiguous `NoFatChain` allocation), and `NTFS` (read-only including resident/non-resident `$DATA`, `$ATTRIBUTE_LIST`, and `$INDEX_ALLOCATION` INDX B-trees with USA fixups).
  - Byte-exact free/total space reporting verified against Windows `Win32_LogicalDisk`, and Windows desktop mount verification for app-created FAT and exFAT containers.
- **Native Security (`libsecurewipe`)**:
  - 64-bit (`arm64-v8a`, `x86_64`) C++20 JNI library built with 16 KB page alignment (`-Wl,-z,max-page-size=16384`) that pins `ByteArray` buffers via `GetPrimitiveArrayCritical` and zeroes them through a `volatile` pointer + compiler barrier.
- **Biometric / Device-PIN Unlock**:
  - Hardware-backed `AndroidKeyStore` (StrongBox preferred with TEE fallback) AES-256-GCM key per container (`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`), wrapping 4-byte PIM + UTF-8 password into `noBackupFilesDir/<containerId>.blob` with automatic `KeyPermanentlyInvalidatedException` recovery.
- **System Integration & Media**:
  - `VaultDocumentsProvider` (`app.droidcrypter.documents`) exposing unlocked volumes to the Android file picker and Samsung My Files via `StorageManager.openProxyFileDescriptor` without thumbnails or plaintext temp files.
  - Media3 `DroidCrypterDataSource` (`droidcrypter://<volumeId>/<path>`) for direct encrypted streaming in `ExoPlayer` with secure `SurfaceView` (`setSecure(true)`).
  - In-memory `PhotoViewerScreen` with downsampling, encoded byte wiping, and immediate mutable `Bitmap.eraseColor(0)` pixel zeroing on page exit.
- **In-Container Non-Destructive Video Editor**:
  - Timeline with 100 ms bucketed RAM-only LRU `ThumbnailCache`, `EditDecisionList` with overlap merging, `EdlHistory` (undo/redo), "Preview edited" playback skipping cut ranges, and both **Fast (stream copy)** (`MediaExtractor` -> `Mp4Muxer`) and **Precise (re-encode)** (Media3 `Transformer` -> `Mp4Muxer`) export engines backed by an unlinked XTS-AES-256 `EncryptedScratch` file and post-export `MediaExtractor` validation.
