package app.droidcrypter.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hides the status and navigation bars while [fullscreen] is true (a swipe from the edge brings
 * them back briefly) and restores them when it turns false or the screen is left.
 */
@Composable
internal fun FullscreenSystemBars(fullscreen: Boolean) {
    val view = LocalView.current
    DisposableEffect(fullscreen, view) {
        val controller = view.context.findActivity()?.window?.let {
            WindowCompat.getInsetsController(it, view)
        }
        if (fullscreen && controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Four corner brackets: pointing outwards to enter full screen, inwards to leave it. */
@Composable
internal fun FullscreenGlyph(exit: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        val half = size.minDimension / 2f - stroke / 2f
        val arm = half * 0.55f
        val centre = Offset(size.width / 2f, size.height / 2f)
        for (sx in listOf(-1f, 1f)) {
            for (sy in listOf(-1f, 1f)) {
                // Enter: vertex in the corner, arms towards the centre.
                // Exit: vertex near the centre, arms towards the edges.
                val reach = if (exit) half - arm else half
                val dir = if (exit) 1f else -1f
                val vertex = Offset(centre.x + sx * reach, centre.y + sy * reach)
                drawLine(
                    color = color,
                    start = vertex,
                    end = Offset(vertex.x + sx * dir * arm, vertex.y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = vertex,
                    end = Offset(vertex.x, vertex.y + sy * dir * arm),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** Top-bar button that switches a viewer to full screen. */
@Composable
internal fun EnterFullscreenButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(44.dp)
            .semantics { contentDescription = "Enter full screen" },
    ) {
        FullscreenGlyph(exit = false, color = VaultColors.Primary)
    }
}

/**
 * Translucent strip drawn over full-screen content: leave full screen, the file name, and
 * Previous / Next, so moving through the folder never requires leaving full screen. Kept clear of
 * display cutouts and any system bar that is showing.
 */
@Composable
internal fun FullscreenOverlayBar(
    title: String,
    position: String,
    hasPrevious: Boolean,
    hasNext: Boolean,
    previousDescription: String,
    nextDescription: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onExitFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = onExitFullscreen,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Exit full screen" },
        ) {
            FullscreenGlyph(exit = true, color = Color.White)
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        )

        IconButton(
            onClick = onPrevious,
            enabled = hasPrevious,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = previousDescription },
        ) {
            Text(
                text = "◀",
                color = if (hasPrevious) Color.White else Color.White.copy(alpha = 0.35f),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Text(
            text = position,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )

        IconButton(
            onClick = onNext,
            enabled = hasNext,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = nextDescription },
        ) {
            Text(
                text = "▶",
                color = if (hasNext) Color.White else Color.White.copy(alpha = 0.35f),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
