# DroidCrypter

**DroidCrypter** is a native Android application that opens, browses, plays, edits, and creates **DroidCrypter containers entirely in userspace without root**, maintaining full byte-compatibility with Windows-created containers and strict zero-plaintext-on-disk guarantees.

---

## Features

- **Full DroidCrypter Cryptographic Compatibility**:
  - All 8 XTS cipher suites: `AES`, `Serpent`, `Twofish`, `AES-Twofish`, `AES-Twofish-Serpent`, `Serpent-AES`, `Serpent-Twofish-AES`, `Twofish-Serpent`.
  - All 4 PBKDF2-HMAC header PRFs: `SHA-512`, `SHA-256`, `Whirlpool`, `Streebog-512` (GOST R 34.11-2012), plus `Auto` detection and custom `PIM`.
  - Supports standard, hidden, and backup volume headers.
- **Userspace Filesystems**:
  - **FAT12 / FAT16 / FAT32**: Full read, write, recursive overwrite-on-delete, rename, move, and formatter.
  - **exFAT**: Full read, write (contiguous `NoFatChain` + FAT-chained growth), overwrite-on-delete, rename, move, and formatter.
  - **NTFS**: Read-only support (resident and non-resident `$DATA` runlists, `$ATTRIBUTE_LIST`, `$INDEX_ROOT` and `$INDEX_ALLOCATION` INDX B-trees with Update Sequence Array fixups).
- **Zero Plaintext on Storage**:
  - Decrypts on demand in RAM. No temp files, thumbnails, or filenames are ever cached on device storage.
  - 3-star file ratings are stored **inside** the container at `/.veradroid_meta.json` (with crash-safe `.new` atomic replacement and `VolumeRegistry` before-close hook flushing).
  - Video editor exports write to an **immediately unlinked** scratch file encrypted with **XTS-AES-256** under a random in-RAM key (`EncryptedScratch`), validate the MP4 with `MediaExtractor`, and stream it directly into the container as `<name>_edited.mp4`.
- **Key & Memory Hygiene**:
  - Custom `EditText`-backed `SecurePasswordField` and `SecurePimField` that read directly into a `CharArray` and overwrite the `Editable`'s gap buffer in-place with `\u0000` before clearing.
  - Native C++20 JNI library (`libsecurewipe.so`, 16 KB page-aligned for `arm64-v8a` and `x86_64`) that zeroes byte arrays via a `volatile` pointer and compiler barrier.
  - `FLAG_SECURE` enabled before `super.onCreate`, `filterTouchesWhenObscured = true`, `SurfaceView.setSecure(true)` on video surfaces, and **no `INTERNET` permission**.

---

## Project Structure

- [`:engine`](engine): Pure Kotlin/JVM library containing the DroidCrypter volume engine (`engine/vc`), FAT/exFAT/NTFS filesystems and formatters (`engine/fs`), `VolumeRegistry`, `VolumeSpace`, `BrowserSort`, `RatingsStore`, `BrowserStateHolder`, `VolumeUris`, `EncryptedScratch`, and `EditDecisionList`.
- [`:app`](app): Android application module containing `libsecurewipe` (`src/main/cpp`), `FdBlockDevice`, `BiometricKeyManager`, `MountService`, `ContainerCreationService`, `VaultDocumentsProvider`, `DroidCrypterDataSource`, `ThumbnailCache`, `VideoExporter`, and the Jetpack Compose UI.
- [`testdata/`](testdata): 15 real Windows-created `.hc` containers and space ground-truth (`windows-space.csv`).

---

## Building & Running Tests

Set `JAVA_HOME` to a JDK 17+ installation (such as Android Studio's JBR) and run:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'

# Run all host JVM unit, container integration and browser layout (Robolectric) tests
.\gradlew.bat :engine:test testDebugUnitTest

# Build debug APK
.\gradlew.bat assembleDebug

# Build R8-minified, resource-shrunk release APK
.\gradlew.bat assembleRelease
```

### Release Signing Configuration

Release signing credentials are read **exclusively** from `~/.gradle/gradle.properties` (never committed to the repository):

```properties
DROIDCRYPTER_STORE_FILE=/absolute/path/to/release.keystore
DROIDCRYPTER_STORE_PASSWORD=your_store_password
DROIDCRYPTER_KEY_ALIAS=your_key_alias
DROIDCRYPTER_KEY_PASSWORD=your_key_password
```

- If `DROIDCRYPTER_STORE_FILE` is set to a path that does not exist, Gradle fails configuration immediately with a clear error message.
- If `DROIDCRYPTER_STORE_FILE` is unset, `assembleRelease` emits a warning and produces an unsigned release APK.
