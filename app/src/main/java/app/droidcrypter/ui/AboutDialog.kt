package app.droidcrypter.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.droidcrypter.BuildConfig
import app.droidcrypter.engine.vc.CipherSuite
import app.droidcrypter.engine.vc.Prf
import app.droidcrypter.security.BiometricKeyManager
import app.droidcrypter.security.NativeWipe

data class DiagnosticsSummary(
    val versionName: String,
    val nativeWipeLoaded: Boolean,
    val strongBoxAvailable: Boolean,
    val biometricReady: Boolean,
    val hasInternetPermission: Boolean,
)

fun collectDiagnostics(context: Context): DiagnosticsSummary {
    val bio = BiometricKeyManager(context)
    val hasInternet = try {
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        pkgInfo.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
    } catch (_: Throwable) {
        false
    }
    return DiagnosticsSummary(
        versionName = BuildConfig.VERSION_NAME,
        nativeWipeLoaded = NativeWipe.isHardwareBacked(),
        strongBoxAvailable = bio.isStrongBoxSupported(),
        biometricReady = bio.canAuthenticate(),
        hasInternetPermission = hasInternet,
    )
}

@Composable
fun AboutDiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val diag = remember(context) { collectDiagnostics(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "DroidCrypter ${diag.versionName} — Diagnostics",
                color = VaultColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Userspace DroidCrypter container engine (byte-compatible with Windows-created containers). Decrypts on demand in RAM with zero plaintext temp files or caches.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColors.TextSecondary,
                )

                HorizontalDivider(color = VaultColors.Outline)

                Text(
                    text = "Security Posture",
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColors.Primary,
                    fontWeight = FontWeight.SemiBold,
                )
                DiagRow(
                    label = "Native JNI memory wipe",
                    value = if (diag.nativeWipeLoaded) "Active (C++20 volatile + barrier)" else "JVM fallback",
                )
                DiagRow(
                    label = "Hardware StrongBox Keystore",
                    value = if (diag.strongBoxAvailable) "Supported" else "TEE Keystore",
                )
                DiagRow(
                    label = "Class 3 Biometric / PIN",
                    value = if (diag.biometricReady) "Enrolled & ready" else "Not configured",
                )
                DiagRow(
                    label = "Window protection",
                    value = "FLAG_SECURE + touch-obscured filter",
                )
                DiagRow(
                    label = "Network permissions",
                    value = if (!diag.hasInternetPermission) "None (100% offline)" else "WARNING: INTERNET present",
                )
                DiagRow(
                    label = "Editor export scratch",
                    value = "XTS-AES-256 RAM-key + immediate unlink",
                )

                HorizontalDivider(color = VaultColors.Outline)

                Text(
                    text = "Supported Ciphers (XTS)",
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColors.Primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = CipherSuite.entries.joinToString(", ") { it.displayName },
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Supported Header PRFs (PBKDF2-HMAC)",
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColors.Primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = Prf.entries.joinToString(", ") { it.displayName },
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Filesystems",
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColors.Primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "FAT12 / FAT16 / FAT32 (read/write/format), exFAT (read/write/format), NTFS (read-only)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        containerColor = VaultColors.Surface,
        titleContentColor = VaultColors.TextPrimary,
        textContentColor = VaultColors.TextPrimary,
    )
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = VaultColors.TextSecondary,
            modifier = Modifier.weight(0.48f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = VaultColors.TextPrimary,
            modifier = Modifier.weight(0.52f),
        )
    }
}

