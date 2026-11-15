package app.droidcrypter.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.droidcrypter.create.ContainerCreationService
import app.droidcrypter.create.CreationJobRequest
import app.droidcrypter.create.CreationUiState
import app.droidcrypter.engine.Secrets
import app.droidcrypter.engine.VolumeSpace
import app.droidcrypter.engine.vc.CipherSuite
import app.droidcrypter.engine.vc.FormatFilesystem
import app.droidcrypter.engine.vc.Prf
import app.droidcrypter.security.BiometricKeyManager

enum class SizeUnit(val label: String, val multiplierBytes: Long) {
    MB("MB", 1024L * 1024L),
    GB("GB", 1024L * 1024L * 1024L),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreationWizardScreen(
    activity: FragmentActivity,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val biometricManager = remember(context) { BiometricKeyManager(context) }
    val creationState by ContainerCreationService.state.collectAsState()

    var step by remember { mutableIntStateOf(1) } // 1..4
    var targetUri by remember { mutableStateOf<Uri?>(null) }
    var sizeText by remember { mutableStateOf("64") }
    var sizeUnit by remember { mutableStateOf(SizeUnit.MB) }

    var cipher by remember { mutableStateOf(CipherSuite.AES) }
    var prf by remember { mutableStateOf(Prf.SHA512) }
    val pimController = rememberSecureInputController()

    // Defaults per section 1: filesystem exFAT, full (not quick) format
    var filesystem by remember { mutableStateOf(FormatFilesystem.EXFAT) }
    var volumeLabel by remember { mutableStateOf("") }
    var quickFormat by remember { mutableStateOf(false) }

    val pw1Controller = rememberSecureInputController()
    val pw2Controller = rememberSecureInputController()
    var enrollBiometric by remember { mutableStateOf(false) }

    var validationError by remember { mutableStateOf<String?>(null) }

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        if (uri != null) {
            targetUri = uri
            validationError = null
        }
    }

    // Handle completion and optional biometric enrollment
    LaunchedEffect(creationState) {
        val st = creationState
        if (st is CreationUiState.Succeeded) {
            val pwBytes = st.pendingBiometricPasswordBytes
            if (st.enrollBiometric && pwBytes != null && biometricManager.canAuthenticate()) {
                biometricManager.enroll(
                    activity = activity,
                    containerId = st.containerId,
                    passwordBytes = pwBytes,
                    pim = st.pendingBiometricPim,
                    onSuccess = {
                        st.wipePendingBiometricSecret()
                        ContainerCreationService.resetState()
                        onFinished()
                    },
                    onError = {
                        st.wipePendingBiometricSecret()
                        ContainerCreationService.resetState()
                        onFinished()
                    },
                )
            } else {
                st.wipePendingBiometricSecret()
                ContainerCreationService.resetState()
                onFinished()
            }
        }
    }

    BackHandler {
        when {
            creationState is CreationUiState.Running -> {
                // Keep service running or let user tap Cancel explicitly
            }
            step > 1 -> {
                validationError = null
                step -= 1
            }
            else -> {
                ContainerCreationService.resetState()
                onFinished()
            }
        }
    }

    val parsedSizeValue = sizeText.trim().toLongOrNull() ?: 0L
    val computedSizeBytes = if (parsedSizeValue > 0L && parsedSizeValue <= Long.MAX_VALUE / sizeUnit.multiplierBytes) {
        parsedSizeValue * sizeUnit.multiplierBytes
    } else {
        0L
    }

    Scaffold(
        containerColor = VaultColors.Background,
        contentColor = VaultColors.TextPrimary,
        topBar = {
            Surface(
                color = VaultColors.Surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            if (step > 1 && creationState !is CreationUiState.Running) {
                                validationError = null
                                step -= 1
                            } else if (creationState !is CreationUiState.Running) {
                                ContainerCreationService.resetState()
                                onFinished()
                            }
                        },
                        enabled = creationState !is CreationUiState.Running,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Back" },
                    ) {
                        Text(if (step > 1) "← Previous" else "✕ Cancel", color = VaultColors.Primary)
                    }

                    Text(
                        text = "Create Vault · Step $step of 4",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.width(48.dp))
                }
            }
        },
        bottomBar = {
            Surface(
                color = VaultColors.Surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding(),
                ) {
                    HorizontalDivider(color = VaultColors.Outline)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (validationError != null) {
                            Text(
                                text = validationError!!,
                                color = VaultColors.Danger,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        if (step < 4) {
                            Button(
                                onClick = {
                                    validationError = null
                                    if (step == 1) {
                                        val uri = targetUri
                                        if (uri == null) {
                                            validationError = "Please select a destination file for the container."
                                            return@Button
                                        }
                                        if (computedSizeBytes < ContainerCreationService.MIN_CONTAINER_SIZE_BYTES) {
                                            validationError = "Container size must be at least 2 MB."
                                            return@Button
                                        }
                                        try {
                                            ContainerCreationService.checkEnoughHostFreeSpace(
                                                context.contentResolver,
                                                uri,
                                                computedSizeBytes,
                                            )
                                        } catch (t: Throwable) {
                                            validationError = t.message
                                            return@Button
                                        }
                                    } else if (step == 2) {
                                        val pim = pimController.readPimInt()
                                        if (pim < 0) {
                                            validationError = "PIM must be between 0 and 9,999,999."
                                            return@Button
                                        }
                                    }
                                    step += 1
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Primary),
                            ) {
                                Text("Next →", fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Button(
                                onClick = {
                                    validationError = null
                                    val uri = targetUri ?: return@Button
                                    val pim = pimController.readPimInt().coerceAtLeast(0)
                                    val c1 = pw1Controller.readChars()
                                    val c2 = pw2Controller.readChars()
                                    try {
                                        if (!charsEqualConstantTime(c1, c2)) {
                                            Secrets.wipe(c1)
                                            validationError = "Passwords do not match."
                                            return@Button
                                        }
                                        val utf8Check = Secrets.utf8Bytes(c1)
                                        try {
                                            Secrets.validatePasswordLength(utf8Check)
                                        } catch (e: IllegalArgumentException) {
                                            Secrets.wipe(c1)
                                            validationError = e.message
                                            return@Button
                                        } finally {
                                            Secrets.wipe(utf8Check)
                                        }

                                        pw1Controller.wipeAndClear()
                                        pw2Controller.wipeAndClear()
                                        pimController.wipeAndClear()

                                        val req = CreationJobRequest(
                                            targetUri = uri,
                                            sizeBytes = computedSizeBytes,
                                            cipher = cipher,
                                            prf = prf,
                                            pim = pim,
                                            filesystem = filesystem,
                                            label = volumeLabel.trim(),
                                            quickFormat = quickFormat,
                                            enrollBiometric = enrollBiometric,
                                            passwordChars = c1,
                                        )
                                        ContainerCreationService.startCreation(context, req)
                                    } finally {
                                        Secrets.wipe(c2)
                                    }
                                },
                                enabled = pw1Controller.length > 0 && pw2Controller.length > 0 && creationState !is CreationUiState.Running,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Primary),
                            ) {
                                Text("Create Vault", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding(),
                ),
        ) {
            HorizontalDivider(color = VaultColors.Outline)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
            val runningState = creationState as? CreationUiState.Running
            if (runningState != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = VaultColors.Surface,
                        contentColor = VaultColors.TextPrimary,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, VaultColors.Primary, RoundedCornerShape(14.dp)),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Formatting DroidCrypter Container",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = runningState.statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = VaultColors.Primary,
                        )
                        LinearProgressIndicator(
                            progress = { runningState.fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = VaultColors.Primary,
                            trackColor = VaultColors.Outline,
                        )
                        Text(
                            text = "Runs in a foreground service — you can switch apps safely while formatting completes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColors.TextSecondary,
                        )
                        OutlinedButton(
                            onClick = { ContainerCreationService.cancelActiveCreation() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text("Cancel & delete partial file", color = VaultColors.Danger)
                        }
                    }
                }
                return@Column
            }

            if (creationState is CreationUiState.Failed) {
                val err = (creationState as CreationUiState.Failed).errorMessage
                Text(
                    text = err,
                    color = VaultColors.Danger,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (creationState is CreationUiState.Cancelled) {
                Text(
                    text = "Container creation was cancelled and the partial file was deleted.",
                    color = VaultColors.StarAmber,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            when (step) {
                1 -> {
                    // ---- Step 1: Location & Size ----
                    Text(
                        text = "1. Container File & Size",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Choose where to save the new container file (.hc) and how much capacity to allocate (minimum 2 MB).",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.TextSecondary,
                    )

                    Button(
                        onClick = { createDocLauncher.launch("vault.hc") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Primary),
                    ) {
                        Text(if (targetUri == null) "Choose file location..." else "Change file location...")
                    }

                    if (targetUri != null) {
                        Text(
                            text = "Target: ${resolveUriDisplayName(context, targetUri!!)}\n$targetUri",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColors.Primary,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = sizeText,
                            onValueChange = {
                                sizeText = it.filter { ch -> ch.isDigit() }.take(7)
                                validationError = null
                            },
                            label = { Text("Container size") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = VaultColors.TextPrimary,
                                unfocusedTextColor = VaultColors.TextPrimary,
                                focusedLabelColor = VaultColors.Primary,
                                unfocusedLabelColor = VaultColors.TextSecondary,
                                focusedBorderColor = VaultColors.Primary,
                                unfocusedBorderColor = VaultColors.Outline,
                                cursorColor = VaultColors.Primary,
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )

                        for (u in SizeUnit.entries) {
                            FilterChip(
                                selected = sizeUnit == u,
                                onClick = { sizeUnit = u },
                                label = { Text(u.label) },
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

                    Text(
                        text = "Allocated size: ${VolumeSpace.formatBytes(computedSizeBytes)} (requires +64 MB free host storage margin)",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.TextSecondary,
                    )
                }

                2 -> {
                    // ---- Step 2: Cipher, PRF & PIM ----
                    Text(
                        text = "2. Encryption & Key Derivation",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    Text(
                        text = "Encryption Algorithm (XTS mode)",
                        style = MaterialTheme.typography.labelLarge,
                        color = VaultColors.Primary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (c in CipherSuite.entries) {
                            FilterChip(
                                selected = cipher == c,
                                onClick = { cipher = c },
                                label = { Text(c.displayName) },
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

                    Text(
                        text = "Header Hash Algorithm (PBKDF2-HMAC)",
                        style = MaterialTheme.typography.labelLarge,
                        color = VaultColors.Primary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (h in Prf.entries) {
                            FilterChip(
                                selected = prf == h,
                                onClick = { prf = h },
                                label = { Text(h.displayName) },
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

                    Text(
                        text = "Personal Iterations Multiplier (PIM, optional)",
                        style = MaterialTheme.typography.labelLarge,
                        color = VaultColors.Primary,
                    )
                    SecurePimField(
                        controller = pimController,
                        hint = "PIM (leave empty or 0 for standard 500,000 iterations)",
                    )
                }

                3 -> {
                    // ---- Step 3: Filesystem, Label & Quick Format ----
                    Text(
                        text = "3. Filesystem & Format Mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    Text(
                        text = "Filesystem (Default: exFAT)",
                        style = MaterialTheme.typography.labelLarge,
                        color = VaultColors.Primary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (fs in FormatFilesystem.entries) {
                            FilterChip(
                                selected = filesystem == fs,
                                onClick = { filesystem = fs },
                                label = { Text(fs.displayName) },
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

                    OutlinedTextField(
                        value = volumeLabel,
                        onValueChange = {
                            volumeLabel = it.filter { ch -> ch.code in 0x20..0x7E }.take(11)
                        },
                        label = { Text("Volume label (optional, max 11 ASCII chars)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = VaultColors.TextPrimary,
                            unfocusedTextColor = VaultColors.TextPrimary,
                            focusedLabelColor = VaultColors.Primary,
                            unfocusedLabelColor = VaultColors.TextSecondary,
                            focusedBorderColor = VaultColors.Primary,
                            unfocusedBorderColor = VaultColors.Outline,
                            cursorColor = VaultColors.Primary,
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = VaultColors.Surface,
                            contentColor = VaultColors.TextPrimary,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, VaultColors.Outline, RoundedCornerShape(12.dp)),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clickable(role = Role.Switch) { quickFormat = !quickFormat }
                                    .semantics {
                                        stateDescription = if (quickFormat) "On" else "Off"
                                    },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Quick format",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Switch(
                                    checked = quickFormat,
                                    onCheckedChange = null,
                                )
                            }

                            Text(
                                text = if (!quickFormat) {
                                    "Full format (recommended default): Fills the entire container data area with XTS-encrypted random data so free space is indistinguishable from encrypted files."
                                } else {
                                    "Security trade-off: Quick format writes only the headers and filesystem metadata. It finishes much faster on large containers, but leaves unused space unwritten, which can reveal how much of the container has been filled."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (quickFormat) VaultColors.StarAmber else VaultColors.TextSecondary,
                            )
                        }
                    }
                }

                4 -> {
                    // ---- Step 4: Password, Biometric Option & Summary ----
                    Text(
                        text = "4. Master Password & Review",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    Text(
                        text = "Recommendation: DroidCrypter recommends a strong passphrase of at least 20 characters (maximum 128 UTF-8 bytes).",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.Primary,
                    )

                    SecurePasswordField(
                        controller = pw1Controller,
                        hint = "Enter vault password",
                    )

                    SecurePasswordField(
                        controller = pw2Controller,
                        hint = "Confirm vault password",
                    )

                    if (biometricManager.canAuthenticate()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .clickable(role = Role.Checkbox) {
                                    enrollBiometric = !enrollBiometric
                                }
                                .semantics {
                                    stateDescription = if (enrollBiometric) "Checked" else "Unchecked"
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = enrollBiometric,
                                onCheckedChange = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Unlock with fingerprint next time")
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = VaultColors.Surface,
                            contentColor = VaultColors.TextPrimary,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, VaultColors.Outline, RoundedCornerShape(12.dp)),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Summary",
                                style = MaterialTheme.typography.titleSmall,
                                color = VaultColors.Primary,
                                fontWeight = FontWeight.Bold,
                            )
                            Text("Size: ${VolumeSpace.formatBytes(computedSizeBytes)}", style = MaterialTheme.typography.bodySmall)
                            Text("Cipher: ${cipher.displayName} (XTS)", style = MaterialTheme.typography.bodySmall)
                            Text("Header PRF: ${prf.displayName}", style = MaterialTheme.typography.bodySmall)
                            Text("Filesystem: ${filesystem.displayName}", style = MaterialTheme.typography.bodySmall)
                            Text("Format mode: ${if (quickFormat) "Quick format" else "Full format (encrypted fill)"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
}

private fun charsEqualConstantTime(a: CharArray, b: CharArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) {
        diff = diff or (a[i].code xor b[i].code)
    }
    return diff == 0
}

