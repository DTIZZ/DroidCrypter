package app.droidcrypter.ui

import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.droidcrypter.engine.Secrets

/**
 * Controller for an `EditText`-backed secure password or PIM field (section 4.2).
 *
 * - Never converts the password into an immutable `String`;
 * - Reads characters directly from the underlying `Editable` into a `CharArray`;
 * - Overwrites the `Editable`'s gap buffer in place with `\u0000` before calling `clear()`.
 */
class SecureInputController {
    internal var editText: EditText? = null
    var length by mutableIntStateOf(0)
        internal set

    /**
     * Copies the current characters into a fresh [CharArray].
     * Caller MUST pass the returned array to [Secrets.wipe] when finished.
     */
    fun readChars(): CharArray {
        val et = editText ?: return CharArray(0)
        val editable = et.text ?: return CharArray(0)
        val len = editable.length
        if (len <= 0) return CharArray(0)
        val out = CharArray(len) { idx -> editable[idx] }
        return out
    }

    /**
     * Parses a digits-only PIM field into an [Int] (`0` if empty or invalid) without allocating a `String`.
     */
    fun readPimInt(): Int {
        val chars = readChars()
        try {
            if (chars.isEmpty()) return 0
            var acc = 0L
            for (c in chars) {
                if (c !in '0'..'9') return -1
                acc = acc * 10L + (c - '0')
                if (acc > 9_999_999L) return -1
            }
            return acc.toInt()
        } finally {
            Secrets.wipe(chars)
        }
    }

    /**
     * Overwrites the internal [Editable] character buffer in-place with NUL characters before `clear()`.
     */
    fun wipeAndClear() {
        val et = editText ?: return
        wipeEditableInPlace(et.text)
        length = 0
    }

    companion object {
        fun wipeEditableInPlace(editable: Editable?) {
            if (editable == null) return
            val len = editable.length
            if (len > 0) {
                val zeros = CharArray(len) { '\u0000' }
                editable.replace(0, len, java.nio.CharBuffer.wrap(zeros), 0, len)
            }
            editable.clear()
        }
    }
}

@Composable
fun rememberSecureInputController(): SecureInputController =
    remember { SecureInputController() }

/**
 * Custom `EditText`-based password field wrapped for Compose (section 4.2):
 * - Sets single-line mode FIRST, then password input type + [PasswordTransformationMethod]
 *   so the password mask is never stripped by `setSingleLine(true)`;
 * - Disables autofill (`IMPORTANT_FOR_AUTOFILL_NO`) and sets `IME_FLAG_NO_PERSONALIZED_LEARNING`;
 * - Wipes the internal `Editable` buffer in-place when disposed.
 */
@Composable
fun SecurePasswordField(
    controller: SecureInputController,
    hint: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDone: (() -> Unit)? = null,
) {
    var passwordVisible by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        onDispose {
            controller.wipeAndClear()
            controller.editText = null
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .background(VaultColors.SurfaceElevated, RoundedCornerShape(10.dp))
            .border(1.dp, VaultColors.Outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AndroidView(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 48.dp),
            factory = { ctx ->
                EditText(ctx).apply {
                    background = null
                    setTextColor(VaultColors.TextPrimary.toArgb())
                    setHintTextColor(VaultColors.TextSecondary.toArgb())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    gravity = Gravity.CENTER_VERTICAL
                    // CRITICAL: setSingleLine(true) MUST come BEFORE setting password inputType + transformationMethod
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    transformationMethod = PasswordTransformationMethod.getInstance()
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                    imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    filters = arrayOf(InputFilter.LengthFilter(Secrets.MAX_PASSWORD_BYTES))
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            controller.length = s?.length ?: 0
                        }
                    })
                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE && onDone != null) {
                            onDone()
                            true
                        } else {
                            false
                        }
                    }
                    controller.editText = this
                }
            },
            update = { et ->
                et.hint = hint
                et.isEnabled = enabled
                val desiredTransform = if (passwordVisible) null else PasswordTransformationMethod.getInstance()
                if (et.transformationMethod !== desiredTransform) {
                    val selStart = et.selectionStart.coerceAtLeast(0)
                    val selEnd = et.selectionEnd.coerceAtLeast(0)
                    et.transformationMethod = desiredTransform
                    if (selStart <= et.length() && selEnd <= et.length()) {
                        et.setSelection(selStart, selEnd)
                    }
                }
            },
        )

        TextButton(
            onClick = { passwordVisible = !passwordVisible },
            enabled = enabled,
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .semantics {
                    contentDescription = if (passwordVisible) "Hide password" else "Show password"
                    stateDescription = if (passwordVisible) "Password visible" else "Password hidden"
                },
        ) {
            Text(
                text = if (passwordVisible) "Hide" else "Show",
                color = VaultColors.Primary,
            )
        }
    }
}

/**
 * Masked digits-only PIM field (max 7 digits, section 4.2 & 6.2).
 */
@Composable
fun SecurePimField(
    controller: SecureInputController,
    hint: String = "PIM (optional, default 0)",
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    DisposableEffect(controller) {
        onDispose {
            controller.wipeAndClear()
            controller.editText = null
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 50.dp)
            .background(VaultColors.SurfaceElevated, RoundedCornerShape(10.dp))
            .border(1.dp, VaultColors.Outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
            factory = { ctx ->
                EditText(ctx).apply {
                    background = null
                    setTextColor(VaultColors.TextPrimary.toArgb())
                    setHintTextColor(VaultColors.TextSecondary.toArgb())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER_VERTICAL
                    // Set single-line FIRST, then masked number password transformation
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    keyListener = DigitsKeyListener.getInstance("0123456789")
                    transformationMethod = PasswordTransformationMethod.getInstance()
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                    imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    filters = arrayOf(InputFilter.LengthFilter(7))
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            controller.length = s?.length ?: 0
                        }
                    })
                    controller.editText = this
                }
            },
            update = { et ->
                et.hint = hint
                et.isEnabled = enabled
                if (et.transformationMethod == null) {
                    et.transformationMethod = PasswordTransformationMethod.getInstance()
                }
            },
        )
    }
}

