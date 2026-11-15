package app.droidcrypter.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.droidcrypter.engine.ContainerVolume
import app.droidcrypter.engine.FdBlockDevice
import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.engine.Secrets
import app.droidcrypter.engine.VolumeRegistry
import app.droidcrypter.engine.VolumeSpace
import app.droidcrypter.engine.vc.Prf
import app.droidcrypter.engine.vc.DroidCrypter
import app.droidcrypter.security.BiometricKeyManager
import app.droidcrypter.service.MountService
import app.droidcrypter.settings.AppPreferences
import app.droidcrypter.settings.AppSettings
import app.droidcrypter.settings.SavedContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
fun VaultListScreen(
    activity: FragmentActivity,
    prefs: AppPreferences,
    settings: AppSettings,
    unlockedByContainerId: Map<String, MountedVolume>,
    onContainerUnlocked: (String, MountedVolume) -> Unit,
    onContainerLocked: (String) -> Unit,
    onLockAll: () -> Unit,
    onBrowseVolume: (MountedVolume) -> Unit,
    onOpenCreateWizard: () -> Unit,
) {
    val context = LocalContext.current
    val biometricManager = remember(context) { BiometricKeyManager(context) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var bannerMessage by remember { mutableStateOf<String?>(null) }
    var enrollmentRevision by remember { mutableIntStateOf(0) }

    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Throwable) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Throwable) {
                }
            }
            prefs.addContainerUri(uri.toString())
        }
    }

    val anyUnlocked = unlockedByContainerId.isNotEmpty() || VolumeRegistry.all().isNotEmpty()

    Scaffold(
        containerColor = VaultColors.Background,
        contentColor = VaultColors.TextPrimary,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VaultColors.Surface)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DroidCrypter",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = VaultColors.TextPrimary,
                        )
                        Text(
                            text = "Userspace DroidCrypter containers · Zero plaintext on disk",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColors.TextSecondary,
                        )
                    }

                    TextButton(
                        onClick = { showAboutDialog = true },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "About and security diagnostics" },
                    ) {
                        Text("About", color = VaultColors.Primary)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { openDocLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VaultColors.Primary,
                            contentColor = Color(0xFF0F141A),
                        ),
                    ) {
                        Text("Open vault", fontWeight = FontWeight.SemiBold, color = Color(0xFF0F141A))
                    }

                    OutlinedButton(
                        onClick = onOpenCreateWizard,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VaultColors.Primary),
                    ) {
                        Text("Create vault", color = VaultColors.Primary, fontWeight = FontWeight.SemiBold)
                    }

                    if (anyUnlocked) {
                        Button(
                            onClick = onLockAll,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .semantics { contentDescription = "Emergency Lock all unlocked vaults" },
                            colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Danger),
                        ) {
                            Text("Lock all", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            HorizontalDivider(color = VaultColors.Outline)

            if (bannerMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(VaultColors.SurfaceElevated)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = bannerMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.StarAmber,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { bannerMessage = null },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Text("Dismiss")
                    }
                }
                HorizontalDivider(color = VaultColors.Outline)
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
            if (settings.containers.isEmpty()) {
                item(key = "empty_state") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = VaultColors.Surface,
                            contentColor = VaultColors.TextPrimary,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, VaultColors.Outline, RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "No vaults added yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = VaultColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Tap \"Open vault\" to add an existing DroidCrypter (.hc / .tc) container, or \"Create vault\" to format a new encrypted container compatible with DroidCrypter for Windows.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = VaultColors.TextSecondary,
                            )
                        }
                    }
                }
            }

            items(
                items = settings.containers,
                key = { it.id },
            ) { container ->
                val unlockedVol = unlockedByContainerId[container.id]
                VaultContainerCard(
                    activity = activity,
                    container = container,
                    unlockedVolume = unlockedVol,
                    biometricManager = biometricManager,
                    enrollmentRevision = enrollmentRevision,
                    onEnrollmentChanged = { enrollmentRevision++ },
                    onUnlocked = { vol -> onContainerUnlocked(container.id, vol) },
                    onLocked = { onContainerLocked(container.id) },
                    onBrowse = { vol -> onBrowseVolume(vol) },
                    onRemove = {
                        onContainerLocked(container.id)
                        biometricManager.forget(container.id)
                        prefs.removeContainerUri(container.uriString)
                    },
                    onBanner = { msg -> bannerMessage = msg },
                )
            }

            item(key = "settings_section") {
                SettingsCard(
                    settings = settings,
                    onToggleLockScreenOff = { prefs.setLockOnScreenOff(it) },
                    onToggleShowStars = { prefs.setShowStars(it) },
                )
            }
        }
    }
}

    if (showAboutDialog) {
        AboutDiagnosticsDialog(onDismiss = { showAboutDialog = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VaultContainerCard(
    activity: FragmentActivity,
    container: SavedContainer,
    unlockedVolume: MountedVolume?,
    biometricManager: BiometricKeyManager,
    enrollmentRevision: Int,
    onEnrollmentChanged: () -> Unit,
    onUnlocked: (MountedVolume) -> Unit,
    onLocked: () -> Unit,
    onBrowse: (MountedVolume) -> Unit,
    onRemove: () -> Unit,
    onBanner: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val displayName = remember(container.uriString) {
        resolveUriDisplayName(context, Uri.parse(container.uriString))
    }

    val pwController = rememberSecureInputController()
    val pimController = rememberSecureInputController()
    var selectedPrf by remember { mutableStateOf<Prf?>(null) } // null = Auto
    var enrollBiometricNext by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val canBio = remember(enrollmentRevision) { biometricManager.canAuthenticate() }
    val isBioEnrolled = remember(container.id, enrollmentRevision) {
        biometricManager.isEnrolled(container.id)
    }

    // Compute live volume space off the main thread whenever unlockedVolume is present
    var liveSpace by remember(unlockedVolume) { mutableStateOf<VolumeSpace?>(null) }
    LaunchedEffect(unlockedVolume) {
        if (unlockedVolume != null) {
            liveSpace = withContext(Dispatchers.IO) {
                VolumeSpace.of(unlockedVolume)
            }
        } else {
            liveSpace = null
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = VaultColors.Surface,
            contentColor = VaultColors.TextPrimary,
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (unlockedVolume != null) VaultColors.Primary else VaultColors.Outline,
                shape = RoundedCornerShape(14.dp),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = VaultColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = container.uriString,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (unlockedVolume == null) {
                    TextButton(
                        onClick = onRemove,
                        enabled = !isBusy,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Remove $displayName from vault list" },
                    ) {
                        Text("Remove", color = VaultColors.Danger)
                    }
                }
            }

            if (unlockedVolume != null) {
                // ---- Unlocked Card ----
                val cv = unlockedVolume as? ContainerVolume
                val fsName = cv?.filesystemName ?: "Volume"
                val cipherName = cv?.cipher?.displayName ?: "XTS"
                val hiddenTag = if (cv?.isHidden == true) " · Hidden volume" else ""

                Text(
                    text = "Unlocked: ${unlockedVolume.label} · $fsName · $cipherName$hiddenTag",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.Primary,
                    fontWeight = FontWeight.SemiBold,
                )

                val roReason = unlockedVolume.writeSupport
                if (roReason != null) {
                    Text(
                        text = "Read-only: $roReason",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.StarAmber,
                    )
                }

                // Space summary (section 7.1 & 7.13)
                val space = liveSpace
                if (space != null) {
                    val summaryLabel = space.vaultSummaryText()
                    val barColor = if (space.isLow) VaultColors.Danger else VaultColors.Primary
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(VaultColors.SurfaceElevated)
                            .padding(10.dp)
                            .clearAndSetSemantics {
                                contentDescription = if (space.isLow) {
                                    "Low disk space warning: $summaryLabel"
                                } else {
                                    summaryLabel
                                }
                            },
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { space.usedFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = barColor,
                            trackColor = VaultColors.Outline,
                        )
                        Text(
                            text = summaryLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (space.isLow) VaultColors.Danger else VaultColors.TextSecondary,
                            fontWeight = if (space.isLow) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { onBrowse(unlockedVolume) },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VaultColors.Primary,
                            contentColor = Color(0xFF0F141A),
                        ),
                    ) {
                        Text("Browse", fontWeight = FontWeight.SemiBold, color = Color(0xFF0F141A))
                    }

                    OutlinedButton(
                        onClick = onLocked,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VaultColors.Danger),
                    ) {
                        Text("Lock", color = VaultColors.Danger, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                // ---- Locked Card ----
                SecurePasswordField(
                    controller = pwController,
                    hint = "Password (1–128 UTF-8 bytes)",
                    enabled = !isBusy,
                )

                SecurePimField(
                    controller = pimController,
                    hint = "PIM (optional, 0 = standard iterations)",
                    enabled = !isBusy,
                )

                // Hash selector: Auto + 4 PRFs
                Text(
                    text = "Header hash (PRF)",
                    style = MaterialTheme.typography.labelMedium,
                    color = VaultColors.TextSecondary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = selectedPrf == null,
                        onClick = { selectedPrf = null },
                        enabled = !isBusy,
                        label = { Text("Auto") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = VaultColors.SurfaceElevated,
                            labelColor = VaultColors.TextPrimary,
                            selectedContainerColor = VaultColors.Primary,
                            selectedLabelColor = Color(0xFF0F141A),
                        ),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    )
                    for (prf in Prf.entries) {
                        FilterChip(
                            selected = selectedPrf == prf,
                            onClick = { selectedPrf = prf },
                            enabled = !isBusy,
                            label = { Text(prf.displayName) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = VaultColors.SurfaceElevated,
                                labelColor = VaultColors.TextPrimary,
                                selectedContainerColor = VaultColors.Primary,
                                selectedLabelColor = Color(0xFF0F141A),
                            ),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        )
                    }
                }

                if (canBio && !isBioEnrolled) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(
                                enabled = !isBusy,
                                role = Role.Checkbox,
                            ) { enrollBiometricNext = !enrollBiometricNext }
                            .semantics {
                                stateDescription = if (enrollBiometricNext) "Checked" else "Unchecked"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = enrollBiometricNext,
                            onCheckedChange = null,
                            enabled = !isBusy,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Unlock with fingerprint next time",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VaultColors.TextPrimary,
                        )
                    }
                }

                if (errorText != null) {
                    Text(
                        text = errorText!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.Danger,
                    )
                }

                if (isBusy) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = VaultColors.Primary,
                        )
                        Text(
                            text = "Deriving keys (PBKDF2)...",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColors.TextSecondary,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            errorText = null
                            val pim = pimController.readPimInt()
                            if (pim < 0) {
                                errorText = "PIM must be a non-negative integer (at most 7 digits)."
                                return@Button
                            }
                            val pwChars = pwController.readChars()
                            val pwBytes = try {
                                Secrets.utf8Bytes(pwChars)
                            } finally {
                                Secrets.wipe(pwChars)
                                pwController.wipeAndClear()
                                pimController.wipeAndClear()
                            }
                            try {
                                Secrets.validatePasswordLength(pwBytes)
                            } catch (e: IllegalArgumentException) {
                                Secrets.wipe(pwBytes)
                                errorText = e.message
                                return@Button
                            }

                            isBusy = true
                            val wantBio = enrollBiometricNext && canBio
                            val prfChoice = selectedPrf
                            scope.launch {
                                var enrollCopy: ByteArray? = null
                                try {
                                    if (wantBio) {
                                        enrollCopy = pwBytes.copyOf()
                                    }
                                    val vol = withContext(Dispatchers.Default) {
                                        openContainerUri(
                                            context = context,
                                            uri = Uri.parse(container.uriString),
                                            passwordBytes = pwBytes,
                                            pim = pim,
                                            prf = prfChoice,
                                        )
                                    }
                                    VolumeRegistry.mount(vol)
                                    MountService.syncWithRegistry(context)
                                    onUnlocked(vol)

                                    if (wantBio && enrollCopy != null) {
                                        val copyToWrap = enrollCopy
                                        enrollCopy = null
                                        biometricManager.enroll(
                                            activity = activity,
                                            containerId = container.id,
                                            passwordBytes = copyToWrap,
                                            pim = pim,
                                            onSuccess = {
                                                Secrets.wipe(copyToWrap)
                                                enrollBiometricNext = false
                                                onEnrollmentChanged()
                                            },
                                            onError = { msg ->
                                                Secrets.wipe(copyToWrap)
                                                onBanner("Vault unlocked, but fingerprint setup was skipped: $msg")
                                            },
                                        )
                                    }
                                } catch (t: Throwable) {
                                    errorText = t.message ?: "Failed to unlock container."
                                } finally {
                                    Secrets.wipe(pwBytes)
                                    if (enrollCopy != null) Secrets.wipe(enrollCopy!!)
                                    isBusy = false
                                }
                            }
                        },
                        enabled = !isBusy && pwController.length > 0,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Primary),
                    ) {
                        Text("Unlock", fontWeight = FontWeight.SemiBold)
                    }

                    if (isBioEnrolled) {
                        OutlinedButton(
                            onClick = {
                                errorText = null
                                val prfChoice = selectedPrf
                                biometricManager.unlock(
                                    activity = activity,
                                    containerId = container.id,
                                    onSuccess = { creds ->
                                        isBusy = true
                                        scope.launch {
                                            try {
                                                val vol = withContext(Dispatchers.Default) {
                                                    openContainerUri(
                                                        context = context,
                                                        uri = Uri.parse(container.uriString),
                                                        passwordBytes = creds.passwordBytes,
                                                        pim = creds.pim,
                                                        prf = prfChoice,
                                                    )
                                                }
                                                VolumeRegistry.mount(vol)
                                                MountService.syncWithRegistry(context)
                                                onUnlocked(vol)
                                            } catch (t: Throwable) {
                                                errorText = t.message ?: "Failed to unlock container."
                                            } finally {
                                                creds.wipe()
                                                isBusy = false
                                            }
                                        }
                                    },
                                    onInvalidated = { inv ->
                                        onEnrollmentChanged()
                                        errorText = inv.message
                                    },
                                    onError = { msg ->
                                        errorText = msg
                                    },
                                )
                            },
                            enabled = !isBusy,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .semantics { contentDescription = "Unlock $displayName with fingerprint" },
                        ) {
                            Text("Fingerprint", color = VaultColors.Primary)
                        }

                        TextButton(
                            onClick = {
                                biometricManager.forget(container.id)
                                onEnrollmentChanged()
                                onBanner("Cleared saved fingerprint key for $displayName.")
                            },
                            enabled = !isBusy,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .semantics { contentDescription = "Forget fingerprint for $displayName" },
                        ) {
                            Text("Forget FP", color = VaultColors.TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    settings: AppSettings,
    onToggleLockScreenOff: (Boolean) -> Unit,
    onToggleShowStars: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = VaultColors.Surface,
            contentColor = VaultColors.TextPrimary,
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, VaultColors.Outline, RoundedCornerShape(14.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Security & Browser Preferences",
                style = MaterialTheme.typography.titleSmall,
                color = VaultColors.Primary,
                fontWeight = FontWeight.SemiBold,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.Switch) {
                        onToggleLockScreenOff(!settings.lockOnScreenOff)
                    }
                    .semantics {
                        stateDescription = if (settings.lockOnScreenOff) "On" else "Off"
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Lock when screen turns off",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColors.TextPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Automatically unmounts all vaults and wipes master keys on ACTION_SCREEN_OFF",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.TextSecondary,
                    )
                }
                Switch(
                    checked = settings.lockOnScreenOff,
                    onCheckedChange = null,
                )
            }

            HorizontalDivider(color = VaultColors.Outline)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.Switch) {
                        onToggleShowStars(!settings.showStars)
                    }
                    .semantics {
                        stateDescription = if (settings.showStars) "On" else "Off"
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show star ratings",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColors.TextPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Displays 3-star ratings stored inside /.veradroid_meta.json within each vault",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.TextSecondary,
                    )
                }
                Switch(
                    checked = settings.showStars,
                    onCheckedChange = null,
                )
            }
        }
    }
}

internal fun openContainerUri(
    context: Context,
    uri: Uri,
    passwordBytes: ByteArray,
    pim: Int,
    prf: Prf?,
): ContainerVolume {
    val cr = context.contentResolver
    // Try read-write ("rw") first via Os.pread/Os.pwrite FdBlockDevice; fall back to read-only ("r")
    var writable = true
    val pfd = try {
        cr.openFileDescriptor(uri, "rw")
    } catch (_: Throwable) {
        writable = false
        cr.openFileDescriptor(uri, "r")
    } ?: throw IOException("Could not open container file descriptor.")

    val device = FdBlockDevice.fromParcelFileDescriptor(
        pfd = pfd,
        writable = writable,
    )
    return try {
        DroidCrypter.open(
            host = device,
            password = passwordBytes.copyOf(),
            pim = pim,
            prf = prf,
        )
    } catch (t: Throwable) {
        try { device.close() } catch (_: Throwable) {}
        throw t
    }
}

internal fun resolveUriDisplayName(context: Context, uri: Uri): String {
    try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && !c.isNull(idx)) {
                    val name = c.getString(idx)?.trim()
                    if (!name.isNullOrEmpty()) return name
                }
            }
        }
    } catch (_: Throwable) {
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "DroidCrypter Vault"
}

